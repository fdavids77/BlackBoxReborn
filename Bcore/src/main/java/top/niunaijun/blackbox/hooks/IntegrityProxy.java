package top.niunaijun.blackbox.hooks;

import android.app.IServiceConnection;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import black.android.app.BRIServiceConnectionO;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;

/**
 * IntegrityProxy — Phase 2 Play Integrity token bridge (Binder-proxy approach).
 *
 * When the virtualised WhatsApp binds to ExpressIntegrityService, GMS generates
 * a token for "top.niunaijun.blackbox". WhatsApp's server rejects it → parole.
 *
 * This class:
 * 1. Wraps the IServiceConnection in BindServiceCommon for integrity binds
 * 2. When GMS calls connected(), wraps the service IBinder with IntegrityBinderProxy
 * 3. Play Core calls requestExpressIntegrityToken on our proxy
 * 4. We intercept the callback IBinder, wrapping it with IntegrityCallbackProxy
 * 5. When GMS delivers the (wrong-package) token to our callback, we:
 *    a. Extract the nonce from the JWT payload
 *    b. Broadcast the nonce to WaEnhancer (IntegrityBridge in real com.whatsapp)
 *    c. Wait up to 15s for a real com.whatsapp token
 *    d. Replace the forwarded Parcel with the real token before calling the real callback
 *
 * This gives WhatsApp a valid com.whatsapp Play Integrity token → server accepts → ✅
 */
public final class IntegrityProxy {

    private static final String TAG = "IntegrityProxy";

    public static final String ACTION_REQUEST  = "com.blackbox.integrity.REQUEST";
    public static final String ACTION_RESPONSE = "com.blackbox.integrity.RESPONSE";
    public static final String EXTRA_NONCE     = "nonce";
    public static final String EXTRA_REQUESTOR = "requestor";
    public static final String EXTRA_TOKEN     = "token";
    public static final String EXTRA_ERROR     = "error";

    private static final String REAL_WA_PKG      = "com.whatsapp";
    private static final long   BRIDGE_TIMEOUT_MS = 15_000;

    // ── Connection proxy ──────────────────────────────────────────────────────

    public static IServiceConnection createConnectionProxy(
            IServiceConnection real, Intent intent) {
        return new IntegrityConnectionProxy(real);
    }

    private static class IntegrityConnectionProxy extends IServiceConnection.Stub {
        private final IServiceConnection mReal;
        IntegrityConnectionProxy(IServiceConnection real) { this.mReal = real; }

        @Override public void connected(ComponentName name, IBinder service) throws RemoteException {
            connected(name, service, false);
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (BuildCompat.isQ() /* API 37 = V */ && Build.VERSION.SDK_INT >= 37
                    && code == android.os.IBinder.FIRST_CALL_TRANSACTION) {
                data.enforceInterface(DESCRIPTOR);
                ComponentName name = (data.readInt() != 0)
                        ? ComponentName.CREATOR.createFromParcel(data) : null;
                IBinder service = data.readStrongBinder();
                if (data.dataAvail() > 4) data.readStrongBinder(); // IBinderSession (API 37)
                boolean dead = data.readBoolean();
                connected(name, service, dead);
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        public void connected(ComponentName name, IBinder service, boolean dead)
                throws RemoteException {
            Slog.d(TAG, "IntegrityConnectionProxy.connected: " + name);
            IBinder proxied = service != null ? new IntegrityBinderProxy(service) : null;
            if (BuildCompat.isOreo()) {
                try { BRIServiceConnectionO.get(mReal).connected(name, proxied, dead); return; }
                catch (Throwable ignored) {}
            }
            mReal.connected(name, proxied);
        }
    }

    // ── Service Binder proxy ──────────────────────────────────────────────────

    /**
     * Wraps ExpressIntegrityService IBinder.
     * Transaction 2 = requestExpressIntegrityToken(request, callback) — we wrap the callback.
     * All other transactions are forwarded transparently.
     */
    private static class IntegrityBinderProxy extends android.os.Binder {
        private final IBinder mReal;
        private static final int TXN_REQUEST = 2;

        IntegrityBinderProxy(IBinder real) { mReal = real; }

        @Override public String getInterfaceDescriptor() {
            try { return mReal.getInterfaceDescriptor(); } catch (Throwable t) { return null; }
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == TXN_REQUEST) {
                return handleRequestToken(code, data, reply, flags);
            }
            return mReal.transact(code, data, reply, flags);
        }

        private boolean handleRequestToken(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            // Marshal the original parcel bytes
            data.setDataPosition(0);
            byte[] raw = data.marshall();

            // Find the callback Binder position: it's the LAST IBinder in the Parcel.
            // We reconstruct a Parcel, skip everything until only the callback remains.
            // Simpler: forward the call, but wrap the callback IBinder by parsing the data.
            Parcel scan = Parcel.obtain();
            scan.unmarshall(raw, 0, raw.length);
            scan.setDataPosition(0);

            IBinder callbackBinder = null;
            try {
                // Skip interface descriptor
                String desc = scan.readString();
                // Skip request bytes (written as a proto byte array or an int+bytes)
                int marker = scan.readInt();
                if (marker > 0) {
                    // Positive int = length prefix for byte array
                    scan.setDataPosition(scan.dataPosition() + marker);
                } else if (marker == -1) {
                    // -1 = null byte array (no request data)
                } else if (marker == 0) {
                    // No data or presence flag = 0 (no object)
                }
                callbackBinder = scan.readStrongBinder();
            } catch (Throwable t) {
                Slog.w(TAG, "handleRequestToken: parse failed: " + t);
            } finally {
                scan.recycle();
            }

            if (callbackBinder != null) {
                // Replace the callback with our proxy in the forwarded Parcel
                Parcel modified = Parcel.obtain();
                modified.unmarshall(raw, 0, raw.length);
                modified.setDataPosition(0);
                try {
                    modified.readString(); // skip descriptor
                    int m = modified.readInt();
                    if (m > 0) modified.setDataPosition(modified.dataPosition() + m);
                    // Now at the callback position — write our proxy binder
                    IBinder wrappedCallback = new IntegrityCallbackProxy(callbackBinder);
                    modified.writeStrongBinder(wrappedCallback);
                    modified.setDataPosition(0);
                    boolean result = mReal.transact(code, modified, reply, flags);
                    modified.recycle();
                    return result;
                } catch (Throwable t) {
                    Slog.w(TAG, "handleRequestToken: rebuild failed: " + t);
                    modified.recycle();
                }
            }

            // Fallback: forward unchanged
            data.setDataPosition(0);
            return mReal.transact(code, data, reply, flags);
        }
    }

    // ── Callback proxy ────────────────────────────────────────────────────────

    /**
     * Wraps Play Core's IRequestExpressIntegrityTokenCallback.
     * GMS calls onExpressIntegrityToken (code 1) with the token response.
     * We intercept, extract the nonce, get a real com.whatsapp token from
     * WaEnhancer, rebuild the response Parcel, and forward to the real callback.
     */
    private static class IntegrityCallbackProxy extends android.os.Binder {
        private final IBinder mReal;
        private static final int TXN_ON_TOKEN = 1;

        IntegrityCallbackProxy(IBinder real) { mReal = real; }

        @Override public String getInterfaceDescriptor() {
            try { return mReal.getInterfaceDescriptor(); } catch (Throwable t) { return null; }
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == TXN_ON_TOKEN) {
                Slog.d(TAG, "IntegrityCallbackProxy: onExpressIntegrityToken intercepted");
                return handleTokenDelivery(data, reply, flags);
            }
            return mReal.transact(code, data, reply, flags);
        }

        private boolean handleTokenDelivery(Parcel data, Parcel reply, int flags)
                throws RemoteException {
            data.setDataPosition(0);
            byte[] raw = data.marshall();

            // Extract the token JWT from the response Parcel (best-effort)
            String wrongToken = extractTokenFromParcel(raw);
            String nonce = wrongToken != null ? extractNonceFromJwt(wrongToken) : "";

            Slog.d(TAG, "Wrong-package token nonce: "
                    + nonce.substring(0, Math.min(20, nonce.length())) + "…");

            // Bridge to WaEnhancer
            String realToken = fetchTokenFromBridge(nonce);

            if (realToken != null) {
                Slog.d(TAG, "Real com.whatsapp token obtained ✅ — rebuilding response Parcel");
                byte[] rebuilt = rebuildResponseParcel(raw, realToken);
                if (rebuilt != null) {
                    Parcel newData = Parcel.obtain();
                    newData.unmarshall(rebuilt, 0, rebuilt.length);
                    newData.setDataPosition(0);
                    boolean result = mReal.transact(TXN_ON_TOKEN, newData, reply, flags);
                    newData.recycle();
                    return result;
                }
            }

            Slog.w(TAG, "Bridge failed — forwarding original wrong-package token");
            data.setDataPosition(0);
            return mReal.transact(TXN_ON_TOKEN, data, reply, flags);
        }

        private String extractTokenFromParcel(byte[] raw) {
            String s = new String(raw);
            int idx = s.indexOf("eyJ"); // JWT header prefix (base64 for '{"')
            if (idx < 0) return null;
            int end = idx;
            while (end < s.length() && isJwtChar(s.charAt(end))) end++;
            String jwt = s.substring(idx, end);
            return jwt.contains(".") ? jwt : null; // must have at least one '.'
        }

        private boolean isJwtChar(char c) {
            return Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == '=';
        }

        private String extractNonceFromJwt(String jwt) {
            try {
                String[] parts = jwt.split("\\.");
                if (parts.length < 2) return "";
                byte[] payloadBytes = android.util.Base64.decode(
                        parts[1], android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING);
                String payload = new String(payloadBytes);
                for (String key : new String[]{"\"nonce\":", "\"requestHash\":", "\"rhs\":"}) {
                    int i = payload.indexOf(key);
                    if (i >= 0) {
                        i += key.length();
                        if (i < payload.length() && payload.charAt(i) == '"') i++;
                        int end = payload.indexOf('"', i);
                        if (end > i) return payload.substring(i, end);
                    }
                }
            } catch (Throwable t) {
                Slog.w(TAG, "extractNonceFromJwt: " + t);
            }
            return "";
        }

        private byte[] rebuildResponseParcel(byte[] raw, String realToken) {
            try {
                String s = new String(raw);
                String wrongJwt = extractTokenFromParcel(raw);
                if (wrongJwt == null) return null;
                // Replace the first occurrence of the wrong JWT with the real one
                String replaced = s.replaceFirst(
                        java.util.regex.Pattern.quote(wrongJwt), realToken);
                return replaced.getBytes();
            } catch (Throwable t) {
                Slog.w(TAG, "rebuildResponseParcel: " + t);
                return null;
            }
        }
    }

    // ── Bridge ────────────────────────────────────────────────────────────────

    public static String fetchTokenFromBridge(String nonce) {
        Context ctx = BlackBoxCore.getContext();
        if (ctx == null) { Slog.w(TAG, "fetchTokenFromBridge: no context"); return null; }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>(null);

        BroadcastReceiver resp = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!ACTION_RESPONSE.equals(i.getAction())) return;
                String token = i.getStringExtra(EXTRA_TOKEN);
                String error = i.getStringExtra(EXTRA_ERROR);
                if (error != null) Slog.w(TAG, "Bridge error: " + error);
                result.set(token);
                latch.countDown();
            }
        };

        ctx.registerReceiver(resp, new IntentFilter(ACTION_RESPONSE));
        try {
            Intent req = new Intent(ACTION_REQUEST);
            req.setPackage(REAL_WA_PKG);
            req.putExtra(EXTRA_NONCE, nonce);
            req.putExtra(EXTRA_REQUESTOR, ctx.getPackageName());
            ctx.sendBroadcast(req);
            Slog.d(TAG, "Nonce sent to " + REAL_WA_PKG + " — awaiting token…");
            if (!latch.await(BRIDGE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                Slog.w(TAG, "Bridge timed out after " + BRIDGE_TIMEOUT_MS + "ms");
        } catch (Throwable t) {
            Slog.e(TAG, "fetchTokenFromBridge: " + t);
        } finally {
            try { ctx.unregisterReceiver(resp); } catch (Throwable ignored) {}
        }
        return result.get();
    }

    // Legacy entry points
    public static void install(ClassLoader loader, String virtualPackage) {
        Slog.d(TAG, "IntegrityProxy ready for " + virtualPackage + " (connection-proxy mode)");
    }
    public static Object buildBridgedTask(String nonce, ClassLoader loader) { return null; }
}
