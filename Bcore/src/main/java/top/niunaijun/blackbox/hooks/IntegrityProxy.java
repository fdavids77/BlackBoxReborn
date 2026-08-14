package top.niunaijun.blackbox.hooks;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.IServiceConnection;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.Slog;

/**
 * IntegrityProxy — BlackBox side of the Phase 2 Play Integrity token bridge.
 *
 * Instead of blocking the ExpressIntegrityService bind (Fix 10), we now let
 * the bind happen but wrap the IServiceConnection. When WhatsApp's Play Core
 * SDK calls requestIntegrityToken(nonce) via the connected GMS service, we
 * intercept, route the nonce to WaEnhancer (real WhatsApp), wait for the real
 * com.whatsapp token, and return it to WhatsApp's listener.
 *
 * Simplified Phase 2 approach: since nonce interception from the Binder IPC
 * level is complex, we use a pre-cached token strategy:
 * 1. WaEnhancer pre-fetches a com.whatsapp token at startup
 * 2. BlackBox requests this token via broadcast when integrity is needed
 * 3. The token is returned and used in the registration request
 */
public final class IntegrityProxy {

    private static final String TAG = "IntegrityProxy";

    public static final String ACTION_REQUEST  = "com.blackbox.integrity.REQUEST";
    public static final String ACTION_RESPONSE = "com.blackbox.integrity.RESPONSE";
    public static final String EXTRA_NONCE     = "nonce";
    public static final String EXTRA_REQUESTOR = "requestor";
    public static final String EXTRA_TOKEN     = "token";
    public static final String EXTRA_ERROR     = "error";
    public static final String EXTRA_CLOUD_PROJECT = "cloud_project";

    /**
     * WhatsApp's Google Cloud project number, captured from the virtual WhatsApp's
     * Express Integrity prepare/warm-up transaction. Forwarded to WaEnhancer so it
     * never has to hardcode the value. 0 = not captured yet.
     */
    private static volatile long sCloudProject = 0L;

    private static final String REAL_WA_PKG      = "com.whatsapp";
    private static final long   BRIDGE_TIMEOUT_MS = 12_000;

    private static volatile boolean sInstalled = false;

    /** Called from BlackBoxCore when starting a virtual WhatsApp process. */
    public static void install(ClassLoader loader, String virtualPackage) {
        if (sInstalled) return;
        if (!REAL_WA_PKG.equals(virtualPackage)) return;
        sInstalled = true;
        Slog.d(TAG, "IntegrityProxy installed for " + virtualPackage);
    }

    /**
     * Create a proxy IServiceConnection that, when the ExpressIntegrityService
     * connects, wraps the returned IBinder so that requestIntegrityToken() calls
     * are routed through WaEnhancer to get a real com.whatsapp token.
     *
     * Called from BindServiceCommon when an integrity service bind is detected.
     */
    public static IServiceConnection createConnectionProxy(
            IServiceConnection original, Intent intent) {

        // We use a dynamic proxy to implement IServiceConnection without
        // needing to know all API 37 method signatures at compile time.
        ClassLoader loader = IServiceConnection.class.getClassLoader();

        return (IServiceConnection) Proxy.newProxyInstance(
                loader,
                new Class[]{IServiceConnection.class},
                (proxy, method, args) -> {
                    String mName = method.getName();

                    // connected(ComponentName, IBinder, boolean) — service is live
                    if ("connected".equals(mName) && args != null && args.length >= 2) {
                        android.os.IBinder realBinder = (android.os.IBinder) args[1];
                        boolean dead = args.length >= 3 && Boolean.TRUE.equals(args[2]);

                        if (dead || realBinder == null) {
                            // Service disconnected — pass through
                            return method.invoke(original, args);
                        }

                        Slog.d(TAG, "createConnectionProxy: ExpressIntegrityService connected, wrapping IBinder");

                        // Wrap the real GMS integrity service IBinder with our proxy
                        // that intercepts requestIntegrityToken() calls
                        android.os.IBinder proxyBinder = createIntegrityBinder(realBinder);
                        args[1] = proxyBinder;
                        return method.invoke(original, args);
                    }

                    // All other IServiceConnection methods pass through
                    return method.invoke(original, args);
                });
    }

    /**
     * Wrap the real ExpressIntegrityService IBinder so that when WhatsApp's
     * Play Core SDK calls requestIntegrityToken(nonce), we intercept the nonce,
     * request a real com.whatsapp token from WaEnhancer, and return it.
     */
    private static android.os.IBinder createIntegrityBinder(android.os.IBinder realBinder) {
        return new android.os.Binder() {
            @Override
            public String getInterfaceDescriptor() {
                try { return realBinder.getInterfaceDescriptor(); }
                catch (Exception e) { return null; }
            }

            @Override
            protected boolean onTransact(int code, android.os.Parcel data,
                    android.os.Parcel reply, int flags)
                    throws android.os.RemoteException {

                // Diagnostic: the Express Integrity service is callback-based, so the
                // token is delivered on a separate callback binder, not in `reply`.
                // Log every transaction to map the real protocol from logcat before
                // relying on the synchronous reply path below.
                try {
                    Slog.d(TAG, "IntegrityService onTransact code=" + code
                            + " dataSize=" + data.dataSize() + " flags=" + flags
                            + " iface=" + realBinder.getInterfaceDescriptor());
                } catch (Throwable ignored) {}

                // Capture WhatsApp's cloud project number from the prepare/warm-up
                // transaction so we can forward it to WaEnhancer (avoids hardcoding).
                try { captureCloudProject(code, data); } catch (Throwable ignored) {}

                // Play Core sends FIRST_CALL_TRANSACTION (1) for requestIntegrityToken.
                // We intercept it, extract the nonce, bridge to WaEnhancer.
                // For all other transactions, pass through to the real service.
                if (code == android.os.IBinder.FIRST_CALL_TRANSACTION) {
                    try {
                        // Extract nonce from the request parcel
                        // The parcel starts with the interface descriptor (String16), then the nonce.
                        int savedPos = data.dataPosition();
                        data.setDataPosition(0);
                        String nonce = extractNonceFromParcel(data);
                        data.setDataPosition(savedPos);

                        Slog.d(TAG, "Intercepted requestIntegrityToken nonce="
                                + (nonce != null ? nonce.substring(0, Math.min(16, nonce.length())) + "..." : "null"));

                        String realToken = null;
                        if (nonce != null) {
                            realToken = fetchTokenFromBridge(nonce);
                        }

                        if (realToken != null) {
                            // Write the real token into the reply parcel
                            Slog.d(TAG, "Bridge returned real token, len=" + realToken.length());
                            reply.writeNoException();
                            reply.writeString(realToken);
                            return true;
                        }
                        // Fall through to real service if bridge failed
                        Slog.w(TAG, "Bridge failed, forwarding to real GMS service");
                    } catch (Throwable t) {
                        Slog.e(TAG, "onTransact intercept failed: " + t);
                    }
                }

                // Pass through to real GMS service
                return realBinder.transact(code, data, reply, flags);
            }
        };
    }

    /**
     * Best-effort scan of an Express Integrity transaction parcel for WhatsApp's
     * cloud project number. GCP project numbers are ~10–13 digit values; we walk
     * the raw 32-bit words and assemble little-endian longs, taking the first value
     * in a plausible range. Reading is non-destructive (position is restored).
     *
     * This is heuristic — the exact match is confirmed from the logged candidate.
     * Once known, the value is stable and can be hardcoded in WaEnhancer.
     */
    private static void captureCloudProject(int code, android.os.Parcel data) {
        if (sCloudProject > 0) return;
        int saved = data.dataPosition();
        try {
            int words = data.dataSize() / 4;
            if (words < 2) return;
            data.setDataPosition(0);
            int[] w = new int[words];
            for (int i = 0; i < words; i++) w[i] = data.readInt();
            for (int i = 0; i + 1 < words; i++) {
                long lo  = ((long) w[i])     & 0xFFFFFFFFL;
                long hi  = ((long) w[i + 1]) & 0xFFFFFFFFL;
                long val = (hi << 32) | lo;                 // little-endian
                // GCP project numbers: ~10 to 13 digits.
                if (val >= 1_000_000_000L && val <= 9_999_999_999_999L) {
                    sCloudProject = val;
                    Slog.d(TAG, "Captured candidate cloudProjectNumber=" + val
                            + " (txn code=" + code + ")");
                    return;
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "captureCloudProject: " + t);
        } finally {
            try { data.setDataPosition(saved); } catch (Throwable ignored) {}
        }
    }

    /**
     * Try to extract the nonce string from a Play Core request Parcel.
     * Layout: [interface descriptor String16] [nonce String] [cloud project number long?]
     * We skip the descriptor and read the nonce.
     */
    private static String extractNonceFromParcel(android.os.Parcel data) {
        try {
            // Skip interface descriptor — it's a String16
            // In Android Parcel: String16 is written as int (length) then UTF-16 chars
            // readString16() handles this
            data.setDataPosition(0);
            data.readString(); // interface descriptor (use readString which works on API 37)
            String nonce = data.readString(); // nonce / requestHash
            return nonce;
        } catch (Throwable t) {
            try {
                // Fallback: try readString (String8)
                data.setDataPosition(0);
                data.readString(); // descriptor
                return data.readString();
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /**
     * Send nonce to WaEnhancer via broadcast and wait for the real com.whatsapp token.
     */
    public static String fetchTokenFromBridge(String nonce) {
        Context ctx = BlackBoxCore.getContext();
        if (ctx == null) {
            Slog.w(TAG, "No context for bridge request");
            return null;
        }

        CountDownLatch latch   = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>(null);

        BroadcastReceiver responseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_RESPONSE.equals(intent.getAction())) return;
                result.set(intent.getStringExtra(EXTRA_TOKEN));
                String error = intent.getStringExtra(EXTRA_ERROR);
                if (error != null) Slog.w(TAG, "Bridge error: " + error);
                latch.countDown();
            }
        };

        ctx.registerReceiver(responseReceiver, new IntentFilter(ACTION_RESPONSE));
        try {
            Intent req = new Intent(ACTION_REQUEST);
            req.setPackage(REAL_WA_PKG);
            req.putExtra(EXTRA_NONCE, nonce);
            // Requestor MUST be the BlackBox host package. WaEnhancer replies with
            // setPackage(requestor) through the real AMS; using the virtual package
            // ("com.whatsapp") would route the token to the REAL WhatsApp app instead
            // of back to this host-owned virtual process.
            req.putExtra(EXTRA_REQUESTOR, BlackBoxCore.getHostPkg());
            if (sCloudProject > 0) {
                req.putExtra(EXTRA_CLOUD_PROJECT, sCloudProject);
                Slog.d(TAG, "Forwarding cloudProjectNumber=" + sCloudProject + " to WaEnhancer");
            }
            ctx.sendBroadcast(req);

            Slog.d(TAG, "Nonce broadcast sent to " + REAL_WA_PKG + ", awaiting token...");
            boolean ok = latch.await(BRIDGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ok) Slog.w(TAG, "Bridge timed out after " + BRIDGE_TIMEOUT_MS + "ms");
        } catch (Throwable t) {
            Slog.e(TAG, "fetchTokenFromBridge: " + t.getMessage());
        } finally {
            try { ctx.unregisterReceiver(responseReceiver); } catch (Throwable ignored) {}
        }

        return result.get();
    }

    /**
     * Build a fake Task that, when addOnSuccessListener() is called, queries
     * WaEnhancer for a real com.whatsapp token and delivers it.
     * Used as a fallback if the Binder-level interception doesn't work.
     */
    public static Object buildBridgedTask(String nonce, ClassLoader loader) {
        try {
            Class<?> taskCls    = Class.forName("com.google.android.gms.tasks.Task",              true, loader);
            Class<?> successCls = Class.forName("com.google.android.gms.tasks.OnSuccessListener", true, loader);

            return Proxy.newProxyInstance(loader, new Class[]{taskCls},
                (proxy, method, args) -> {
                    if ("addOnSuccessListener".equals(method.getName()) && args != null && args.length == 1) {
                        new Thread(() -> {
                            String realToken = fetchTokenFromBridge(nonce);
                            if (realToken == null) return;
                            try {
                                // Wrap token in a fake StandardIntegrityToken proxy
                                Class<?> tokenCls = Class.forName(
                                    "com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityToken",
                                    true, loader);
                                Object fakeToken = Proxy.newProxyInstance(loader, new Class[]{tokenCls},
                                    (tp, tm, ta) -> "token".equals(tm.getName()) ? realToken : null);
                                Method onSuccess = successCls.getMethod("onSuccess", Object.class);
                                onSuccess.invoke(args[0], fakeToken);
                            } catch (Throwable t) { Slog.e(TAG, "buildBridgedTask delivery: " + t); }
                        }, "integrity-bridge").start();
                        return proxy;
                    }
                    if ("addOnFailureListener".equals(method.getName())) return proxy;
                    if ("isSuccessful".equals(method.getName())) return false;
                    return null;
                });
        } catch (Throwable t) {
            Slog.e(TAG, "buildBridgedTask: " + t);
            return null;
        }
    }
}
