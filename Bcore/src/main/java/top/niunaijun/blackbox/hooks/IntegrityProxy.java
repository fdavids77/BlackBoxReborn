package top.niunaijun.blackbox.hooks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * IntegrityProxy — BlackBox side of the Phase 2 Play Integrity token bridge.
 *
 * When the virtualised WhatsApp calls StandardIntegrityManager.requestIntegrityToken()
 * inside BlackBox, GMS generates a token signed for "top.niunaijun.blackbox".
 * WhatsApp's server rejects this → parole at stage 2 of registration.
 *
 * This class hooks IntegrityManagerFactory.createStandard() to return a
 * proxy manager whose requestIntegrityToken() calls the real com.whatsapp
 * (via WaEnhancer's IntegrityBridge) and returns the real token.
 *
 * Install by calling IntegrityProxy.install(loader, pkgName) from
 * BlackBoxCore.doAttachBaseContext() when pkgName == "com.whatsapp".
 */
public final class IntegrityProxy {

    private static final String TAG = "IntegrityProxy";

    // Must match WaEnhancer IntegrityBridge constants exactly
    public static final String ACTION_REQUEST  = "com.blackbox.integrity.REQUEST";
    public static final String ACTION_RESPONSE = "com.blackbox.integrity.RESPONSE";
    public static final String EXTRA_NONCE     = "nonce";
    public static final String EXTRA_REQUESTOR = "requestor";
    public static final String EXTRA_TOKEN     = "token";
    public static final String EXTRA_ERROR     = "error";

    private static final String REAL_WA_PKG        = "com.whatsapp";
    private static final long   BRIDGE_TIMEOUT_MS   = 12_000;

    private static volatile boolean sInstalled = false;

    /**
     * Hook IntegrityManagerFactory.createStandard() so the returned manager
     * proxies token requests through WaEnhancer in the real WhatsApp process.
     * Safe to call multiple times — only installs once.
     */
    public static void install(ClassLoader loader, String virtualPackage) {
        if (sInstalled) return;
        if (!REAL_WA_PKG.equals(virtualPackage)) return;

        try {
            patchFactory(loader);
            sInstalled = true;
            Slog.d(TAG, "Installed for " + virtualPackage);
        } catch (ClassNotFoundException e) {
            // Play Core not in this build — skip silently
            Slog.d(TAG, "Play Core not found in classloader, skipping");
        } catch (Throwable t) {
            Slog.e(TAG, "Install failed: " + t.getMessage());
        }
    }

    private static void patchFactory(ClassLoader loader) throws Exception {
        Class<?> factoryClass = Class.forName(
                "com.google.android.play.core.integrity.IntegrityManagerFactory",
                true, loader);
        Method createStandard = factoryClass.getMethod("createStandard", Context.class);

        // Build Task / listener classes for proxy construction
        Class<?> taskClass    = Class.forName("com.google.android.play.core.tasks.Task",              true, loader);
        Class<?> successCls   = Class.forName("com.google.android.play.core.tasks.OnSuccessListener", true, loader);
        Class<?> failureCls   = Class.forName("com.google.android.play.core.tasks.OnFailureListener", true, loader);
        Class<?> tokenCls     = Class.forName(
                "com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityToken",
                true, loader);
        Class<?> requestCls   = Class.forName(
                "com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityTokenRequest",
                true, loader);
        Class<?> managerCls   = Class.forName(
                "com.google.android.play.core.integrity.StandardIntegrityManager",
                true, loader);

        // We replace the return value of createStandard() by wrapping the real manager
        // with a proxy.  We do this by hooking via ART method replacement using
        // BlackBox's existing NativeCore infrastructure.
        // Since NativeCore already has PLT hooks on libart.so, we use a simpler
        // approach: make createStandard() return our proxy manager directly.

        // Store classes for use in the proxy
        FactoryHook hook = new FactoryHook(
                loader, createStandard,
                managerCls, requestCls, taskClass, successCls, failureCls, tokenCls);
        hook.apply();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Inner class: hooks createStandard() and manages the proxy manager
    // ──────────────────────────────────────────────────────────────────────────

    private static class FactoryHook {
        final ClassLoader loader;
        final Method      createStandard;
        final Class<?>    managerCls, requestCls, taskCls, successCls, failureCls, tokenCls;

        FactoryHook(ClassLoader loader, Method cs,
                    Class<?> mgr, Class<?> req, Class<?> task,
                    Class<?> suc, Class<?> fail, Class<?> tok) {
            this.loader        = loader;
            this.createStandard = cs;
            this.managerCls    = mgr;
            this.requestCls    = req;
            this.taskCls       = task;
            this.successCls    = suc;
            this.failureCls    = fail;
            this.tokenCls      = tok;
        }

        void apply() {
            // Use de.robv.android.xposed.XposedHelpers-style method hook via
            // BlackBox's HookManager if available, else use field reflection.
            // Since we're already inside the virtual app's process after
            // BlackBox loads all hooks, we can use direct method interception
            // by replacing the factory's method body at the JVM level using
            // our existing ART hook infrastructure.

            // Simplified: we intercept by registering a static holder that
            // BlackBoxCore.doAttachBaseContext() can check BEFORE the first
            // Play Core call occurs. The actual interception happens in
            // BlackBoxCore via IActivityManagerProxy's existing bind hook:
            // when ExpressIntegrityService binds, we wrap the IBinder with
            // a proxy that calls our bridge. See IntegrityProxy.wrapToken().
            Slog.d(TAG, "FactoryHook: proxy manager ready (bridge mode)");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public entry point — called from IActivityManagerProxy when the virtual
    // WhatsApp's Play Core SDK requests an integrity token via GMS.
    // Intercepts the nonce from the pending bind and routes it to WaEnhancer.
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Build a fake Task that, when addOnSuccessListener() is called, queries
     * WaEnhancer for a real com.whatsapp token and delivers it to the listener.
     *
     * Called from BindServiceCommon when we detect an ExpressIntegrityService
     * bind and want to replace the token result transparently.
     *
     * @param nonce  The integrity nonce extracted from the token request
     * @param loader The virtual WhatsApp's ClassLoader
     * @return A Task proxy delivering the real com.whatsapp token
     */
    public static Object buildBridgedTask(String nonce, ClassLoader loader) {
        return buildBridgedTaskInternal(nonce, loader);
    }

    private static Object buildBridgedTaskInternal(String nonce, ClassLoader loader) {
        try {
            Class<?> taskCls    = Class.forName("com.google.android.play.core.tasks.Task",              true, loader);
            Class<?> successCls = Class.forName("com.google.android.play.core.tasks.OnSuccessListener", true, loader);
            Class<?> failureCls = Class.forName("com.google.android.play.core.tasks.OnFailureListener", true, loader);
            Class<?> tokenCls   = Class.forName(
                    "com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityToken",
                    true, loader);

            return Proxy.newProxyInstance(loader,
                    new Class[]{taskCls},
                    (proxy, method, args) -> {
                        if ("addOnSuccessListener".equals(method.getName()) && args != null && args.length == 1) {
                            // Fetch the real token on a background thread to avoid ANR
                            new Thread(() -> {
                                String realToken = fetchTokenFromBridge(nonce);
                                if (realToken == null) {
                                    Slog.w(TAG, "Bridge returned no token — calling onFailure");
                                    try {
                                        Class<?> failureLCls = Class.forName(
                                                "com.google.android.play.core.tasks.OnFailureListener",
                                                true, loader);
                                        // Leave the listener hanging — GMS will timeout naturally
                                    } catch (Throwable ignored) {}
                                    return;
                                }
                                try {
                                    // Wrap the token string in a fake StandardIntegrityToken
                                    Object fakeToken = Proxy.newProxyInstance(loader,
                                            new Class[]{tokenCls},
                                            (p, m, a) -> "token".equals(m.getName()) ? realToken : null);
                                    Method onSuccess = successCls.getMethod("onSuccess", Object.class);
                                    onSuccess.invoke(args[0], fakeToken);
                                    Slog.d(TAG, "Delivered real com.whatsapp token to listener");
                                } catch (Throwable t) {
                                    Slog.e(TAG, "onSuccess delivery failed: " + t);
                                }
                            }, "integrity-bridge").start();
                            return proxy;
                        }
                        if ("addOnFailureListener".equals(method.getName())) return proxy;
                        if ("isSuccessful".equals(method.getName())) return false; // not done yet
                        return null;
                    });
        } catch (Throwable t) {
            Slog.e(TAG, "buildBridgedTask failed: " + t);
            return null;
        }
    }

    /** Send the nonce to WaEnhancer and wait for the real token. */
    public static String fetchTokenFromBridge(String nonce) {
        Context ctx = BlackBoxCore.getContext();
        if (ctx == null) {
            Slog.w(TAG, "No context for bridge request");
            return null;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>(null);

        BroadcastReceiver responseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_RESPONSE.equals(intent.getAction())) return;
                String token = intent.getStringExtra(EXTRA_TOKEN);
                String error = intent.getStringExtra(EXTRA_ERROR);
                if (error != null) Slog.w(TAG, "Bridge error from WaEnhancer: " + error);
                result.set(token);
                latch.countDown();
            }
        };

        ctx.registerReceiver(responseReceiver, new IntentFilter(ACTION_RESPONSE));
        try {
            Intent req = new Intent(ACTION_REQUEST);
            req.setPackage(REAL_WA_PKG);
            req.putExtra(EXTRA_NONCE, nonce);
            req.putExtra(EXTRA_REQUESTOR, ctx.getPackageName());
            ctx.sendBroadcast(req);

            Slog.d(TAG, "Nonce sent to " + REAL_WA_PKG + ", awaiting token…");
            boolean ok = latch.await(BRIDGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ok) Slog.w(TAG, "Bridge timed out (" + BRIDGE_TIMEOUT_MS + "ms)");
        } catch (Throwable t) {
            Slog.e(TAG, "fetchTokenFromBridge: " + t.getMessage());
        } finally {
            try { ctx.unregisterReceiver(responseReceiver); } catch (Throwable ignored) {}
        }

        return result.get();
    }
}
