package top.niunaijun.blackbox.core;


import android.os.Process;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.File;
import java.util.List;

import dalvik.system.DexFile;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.compat.DexFileCompat;

import top.niunaijun.blackbox.core.system.JarManager;
import top.niunaijun.blackbox.utils.Slog;


public class NativeCore {
    public static final String TAG = "NativeCore";

    /** Real system UID of the currently-running virtual app, cached before PM hooks fire. */
    private static volatile int sCachedRealUid = -1;
    private static volatile String sCachedRealUidPkg = null;
    // Re-entrancy guard for getCallingUid — prevents StackOverflowError on API 37
    // where PropertyInvalidatedCache calls Binder.getCallingUid() inside cache queries.
    private static final ThreadLocal<Boolean> sReentrantGuard = new ThreadLocal<>();

    /**
     * Called from BlackBoxLoader.beforeCreateApplication() BEFORE any PM proxy hooks fire.
     * At that point BlackBoxCore.getPackageManager() still returns the raw system PM.
     */
    public static void cacheRealUid(String packageName) {
        // Strategy 1: parse /data/system/packages.list via su — bypasses all Java PM hooks
        try {
            java.lang.Process suProcess = Runtime.getRuntime().exec(
                    new String[]{"su", "-c",
                            "grep '^" + packageName + " ' /data/system/packages.list"});
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(suProcess.getInputStream()));
            String line = reader.readLine();
            reader.close();
            suProcess.waitFor();
            if (line != null && !line.isEmpty()) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    int uid = Integer.parseInt(parts[1]);
                    if (uid >= Process.FIRST_APPLICATION_UID
                            && uid <= Process.LAST_APPLICATION_UID) {
                        sCachedRealUid = uid;
                        sCachedRealUidPkg = packageName;
                        Log.d(TAG, "cacheRealUid: " + packageName + "=" + uid + " (su)");
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cacheRealUid su failed for " + packageName + ": " + e.getMessage());
        }
        // Strategy 2: raw PM fallback (may be hooked inside virtual process)
        try {
            android.content.pm.PackageManager pm = BlackBoxCore.getPackageManager();
            int uid = pm.getApplicationInfo(packageName, 0).uid;
            if (uid >= Process.FIRST_APPLICATION_UID && uid <= Process.LAST_APPLICATION_UID) {
                sCachedRealUid = uid;
                sCachedRealUidPkg = packageName;
                Log.d(TAG, "cacheRealUid: " + packageName + "=" + uid + " (PM)");
            } else {
                Log.w(TAG, "cacheRealUid: PM uid=" + uid + " out of range for " + packageName);
            }
        } catch (Exception e) {
            Log.w(TAG, "cacheRealUid PM failed for " + packageName + ": " + e.getMessage());
        }
    }

    static {

        System.loadLibrary("blackbox");
    }

    public static native void init(int apiLevel);

    public static native void enableIO();

    public static native void addIORule(String targetPath, String relocatePath);

    public static native void hideXposed();

    public static native boolean disableHiddenApi();

    public static native boolean disableResourceLoading();

    // ART Offset Verifier tools — see tools/art-offset-verifier/
    public static native long getArtMethodPtr(java.lang.reflect.Method method);
    public static native int readNativeInt(long address);


    @Keep
    public static int getCallingUid(int origCallingUid) {
        // Fix 6 (API 37): Android 17 changed PropertyInvalidatedCache to call
        // Binder.getCallingUid() from inside cache queries. Since we hook
        // getCallingUid() and call back into getPackageInfo() (which uses the
        // cache), this creates an infinite recursion → StackOverflowError.
        // Guard with a ThreadLocal re-entrancy flag to break the cycle.
        if (Boolean.TRUE.equals(sReentrantGuard.get())) {
            return origCallingUid;
        }
        sReentrantGuard.set(true);
        try {
            
            if (origCallingUid > 0 && origCallingUid < Process.FIRST_APPLICATION_UID)
                return origCallingUid;
            
            if (origCallingUid > Process.LAST_APPLICATION_UID)
                return origCallingUid;

            if (origCallingUid == BlackBoxCore.getHostUid()) {
                // Return the real system UID of the virtual app so Telecom/JobScheduler accept it
                String appPackageName = BlackBoxCore.getAppPackageName();
                if (appPackageName != null) {
                    // GMS and WebView need their own process UID
                    if (appPackageName.equals("com.google.android.gms") ||
                        appPackageName.equals("com.google.android.webview")) {
                        return Process.myUid();
                    }
                    // All other virtual apps: return real system UID.
                    // Use the cached real UID set before PM proxy hooks fire.
                    if (sCachedRealUid > 0 && sCachedRealUidPkg != null
                            && sCachedRealUidPkg.equals(appPackageName)) {
                        Log.d(TAG, "getCallingUid: cached uid=" + sCachedRealUid
                                + " spoofing " + origCallingUid + " for " + appPackageName);
                        return sCachedRealUid;
                    }
                    // Fallback: callingBUid
                    try {
                        int callingBUid = BlackBoxCore.getCallingBUid();
                        if (callingBUid > 0 && callingBUid < Process.LAST_APPLICATION_UID) {
                            return callingBUid;
                        }
                    } catch (Exception ignored) {}
                }
                return BlackBoxCore.getHostUid();
            }
            return origCallingUid;
        } catch (Exception e) {
            Log.e(TAG, "Error in getCallingUid: " + e.getMessage());
            return Process.myUid();
        } finally {
            sReentrantGuard.set(false);
        }
    }

    @Keep
    public static String redirectPath(String path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static long[] loadEmptyDex() {
        try {
            File emptyJar = JarManager.getInstance().getEmptyJar();
            if (emptyJar == null) {
                Log.w(TAG, "Empty JAR not available, attempting sync initialization");
                JarManager.getInstance().initializeSync();
                emptyJar = JarManager.getInstance().getEmptyJar();
            }
            
            if (emptyJar == null || !emptyJar.exists()) {
                Log.e(TAG, "Empty JAR file not found or invalid");
                return new long[]{};
            }
            
            DexFile dexFile = new DexFile(emptyJar);
            List<Long> cookies = DexFileCompat.getCookies(dexFile);
            long[] longs = new long[cookies.size()];
            for (int i = 0; i < cookies.size(); i++) {
                longs[i] = cookies.get(i);
            }
            Log.d(TAG, "Successfully loaded empty DEX with " + cookies.size() + " cookies");
            return longs;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load empty DEX", e);
        }
        return new long[]{};
    }
    
    
    private static long[] createFallbackEmptyDex() {
        try {
            Slog.d(TAG, "Creating fallback empty DEX");
            
            
            
            byte[] emptyDexBytes = createMinimalDexBytes();
            
            
            File tempDexFile = File.createTempFile("fallback_empty", ".dex");
            tempDexFile.deleteOnExit();
            
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tempDexFile);
            fos.write(emptyDexBytes);
            fos.close();
            
            
            DexFile dexFile = new DexFile(tempDexFile);
            List<Long> cookies = DexFileCompat.getCookies(dexFile);
            
            if (cookies != null && !cookies.isEmpty()) {
                long[] longs = new long[cookies.size()];
                for (int i = 0; i < cookies.size(); i++) {
                    longs[i] = cookies.get(i);
                }
                
                Slog.d(TAG, "Successfully created fallback empty DEX with " + cookies.size() + " cookies");
                return longs;
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Error creating fallback empty DEX: " + e.getMessage());
        }
        
        
        Slog.w(TAG, "Returning empty DEX array as last resort");
        return new long[]{};
    }
    
    
    private static byte[] createMinimalDexBytes() {
        
        
        
        
        
        byte[] dexHeader = {
            'd', 'e', 'x', '\n',  
            0x30, 0x33, 0x35, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x70, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00   
        };
        
        return dexHeader;
    }
}
