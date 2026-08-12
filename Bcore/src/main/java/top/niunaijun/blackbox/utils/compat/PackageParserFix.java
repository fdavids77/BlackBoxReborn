package top.niunaijun.blackbox.utils.compat;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;
import java.io.File;

/**
 * PackageParserFix — replaces android.content.pm.PackageParser
 * removed in Android 14 (API 34). Uses public PM APIs only.
 */
public class PackageParserFix {
    private static final String TAG = "PackageParserFix";

    @SuppressWarnings("deprecation")
    private static int getFullFlags() {
        int flags = PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PROVIDERS
                | PackageManager.GET_PERMISSIONS
                | PackageManager.GET_META_DATA;
        if (Build.VERSION.SDK_INT >= 28) {
            flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        } else {
            flags |= PackageManager.GET_SIGNATURES;
        }
        return flags;
    }

    public static PackageInfo parseApk(Context context, String apkPath) {
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageArchiveInfo(apkPath, getFullFlags());
            if (pi != null && pi.applicationInfo != null) {
                pi.applicationInfo.sourceDir = apkPath;
                pi.applicationInfo.publicSourceDir = apkPath;
            }
            return pi;
        } catch (Exception e) {
            Log.e(TAG, "parseApk failed: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    public static Signature[] getSignatures(PackageInfo pi) {
        if (pi == null) return new Signature[0];
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
            Signature[] certs = pi.signingInfo.getApkContentsSigners();
            return certs != null ? certs : new Signature[0];
        }
        return pi.signatures != null ? pi.signatures : new Signature[0];
    }

    /** True on API 33+ where PackageParser is deprecated/removed */
    public static boolean needsFix() {
        return Build.VERSION.SDK_INT >= 33;
    }
}
