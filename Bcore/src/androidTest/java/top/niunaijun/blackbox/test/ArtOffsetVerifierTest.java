package top.niunaijun.blackbox.test;

import android.os.Build;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.NativeCore;

/**
 * ART Offset Verifier for BlackBox Reborn.
 *
 * Measures the real byte offsets of key ArtMethod fields on the current device.
 * Run this on any new device/Android version and add the output to COMPAT.md.
 *
 * Usage:
 *   Windows: tools\run_verifier.bat
 *   Linux/Mac: bash tools/run_verifier.sh
 *
 * Or manually:
 *   adb shell am instrument -w -e class top.niunaijun.blackbox.test.ArtOffsetVerifierTest \
 *     top.niunaijun.blackbox.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4.class)
public class ArtOffsetVerifierTest {

    private static final String TAG = "ArtOffsetVerifier";

    // Target method with known, stable access_flags:
    // ACC_PRIVATE (0x0002) | ACC_STATIC (0x0008) = 0x000A
    // The low 16 bits of the access_flags field will always be 0x000A for this method.
    private static void verifierTarget() {
        // Body is intentionally non-empty so dex_code_item_offset != 0
        int x = 1 + 1;
        if (x == 2) { x = 3; }
    }

    @Test
    public void measureArtMethodOffsets() {
        int apiLevel = Build.VERSION.SDK_INT;
        String device = Build.MODEL + " (" + Build.DEVICE + ", " + Build.SUPPORTED_ABIS[0] + ")";

        Log.i(TAG, "");
        Log.i(TAG, "╔══════════════════════════════════════════════╗");
        Log.i(TAG, "║   BlackBox Reborn — ART Offset Verifier      ║");
        Log.i(TAG, "╚══════════════════════════════════════════════╝");
        Log.i(TAG, "Device  : " + device);
        Log.i(TAG, "Android : " + Build.VERSION.RELEASE + " (API " + apiLevel + ")");
        Log.i(TAG, "ABI     : " + Build.SUPPORTED_ABIS[0]);

        // Ensure BlackBox native library is loaded
        BlackBoxCore.get();

        try {
            Method method = ArtOffsetVerifierTest.class.getDeclaredMethod("verifierTarget");
            long artMethodPtr = NativeCore.getArtMethodPtr(method);

            if (artMethodPtr == 0) {
                Log.e(TAG, "FAILED: getArtMethodPtr returned null — NativeCore not loaded?");
                return;
            }

            Log.i(TAG, "ArtMethod*: 0x" + Long.toHexString(artMethodPtr));
            Log.i(TAG, "");

            // ── access_flags scan ────────────────────────────────────────────
            // Known value: low 16 bits == 0x000A (ACC_PRIVATE | ACC_STATIC)
            // Upper 16 bits may contain ART-internal runtime flags — we mask them off.
            final int ACCESS_FLAGS_MASK     = 0x0000FFFF;
            final int EXPECTED_ACCESS_FLAGS = 0x0000000A;

            int accessFlagsOffset = -1;
            Log.i(TAG, "── access_flags scan (looking for 0x" +
                Integer.toHexString(EXPECTED_ACCESS_FLAGS) + " in low 16 bits) ──");

            for (int offset = 0; offset <= 64; offset += 4) {
                int raw = NativeCore.readNativeInt(artMethodPtr + offset);
                int masked = raw & ACCESS_FLAGS_MASK;
                Log.d(TAG, String.format("  [%2d] raw=0x%08X  masked=0x%04X%s",
                    offset, raw, masked,
                    masked == EXPECTED_ACCESS_FLAGS ? "  ← MATCH" : ""));
                if (masked == EXPECTED_ACCESS_FLAGS && accessFlagsOffset == -1) {
                    accessFlagsOffset = offset;
                }
            }

            // ── dex_code_item_offset_ scan ───────────────────────────────────
            // This field is a uint32 byte offset into the DEX/compact-DEX code section.
            // Compact DEX (Android 10+) does NOT require 4-byte alignment for code items,
            // so we only require: positive, plausibly small (< 0x100000), non-pointer.
            // We scan up to offset 128 to catch struct layout shifts across API levels.
            int dexCodeOffset = -1;
            Log.i(TAG, "");
            Log.i(TAG, "── dex_code_item_offset scan (non-zero, < 0x100000, non-pointer) ──");

            for (int offset = 0; offset <= 128; offset += 4) {
                if (offset == accessFlagsOffset) continue;
                int raw = NativeCore.readNativeInt(artMethodPtr + offset);
                // Plausible code item offset: small positive value that isn't a pointer
                // (pointers on arm64 have high bits set; raw DEX offsets are small)
                boolean isPointer = (raw & 0xFF000000) != 0 || raw < 0;
                boolean plausible  = raw > 0 && raw < 0x100000 && !isPointer;
                if (plausible) {
                    Log.d(TAG, String.format("  [%3d] 0x%08X  ← plausible (value=%d)", offset, raw, raw));
                    if (dexCodeOffset == -1) dexCodeOffset = offset;
                } else {
                    Log.d(TAG, String.format("  [%3d] 0x%08X", offset, raw));
                }
            }

            // ── Results ─────────────────────────────────────────────────────
            Log.i(TAG, "");
            Log.i(TAG, "╔══════════════════════════════════════════════╗");
            Log.i(TAG, "║   RESULTS                                    ║");
            Log.i(TAG, "╚══════════════════════════════════════════════╝");
            Log.i(TAG, "access_flags offset      : " + accessFlagsOffset +
                (accessFlagsOffset == -1 ? " ← NOT FOUND (new ART layout?)" : ""));
            Log.i(TAG, "dex_code_item offset     : " + dexCodeOffset +
                (dexCodeOffset == -1 ? " ← NOT FOUND" : " (best candidate)"));
            Log.i(TAG, "");
            Log.i(TAG, "── Paste into COMPAT.md Fix 2 table ────────────────");
            Log.i(TAG, String.format("| %s | %d | access_flags=%d | dex_code_item=%d |",
                device, apiLevel, accessFlagsOffset, dexCodeOffset));
            Log.i(TAG, "────────────────────────────────────────────────────");
            Log.i(TAG, "");

            // Validate against known-good values
            validateAgainstKnown(apiLevel, accessFlagsOffset, dexCodeOffset);

        } catch (NoSuchMethodException e) {
            Log.e(TAG, "FAILED: Could not find verifierTarget method", e);
        } catch (Exception e) {
            Log.e(TAG, "FAILED: Unexpected error", e);
        }
    }

    private void validateAgainstKnown(int api, int accessFlags, int dexCode) {
        // Known-good offsets from devices tested during development.
        // A mismatch here may indicate a new ART layout or OEM customisation.
        int knownAccessFlags = -1;
        int knownDexCode     = -1;

        if      (api >= 36) { knownAccessFlags = 4;  knownDexCode = 20; }
        else if (api >= 35) { knownAccessFlags = 4;  knownDexCode = 16; }
        else if (api >= 34) { knownAccessFlags = 4;  knownDexCode = 12; }
        else if (api >= 31) { knownAccessFlags = 4;  knownDexCode = 12; }

        if (knownAccessFlags == -1) {
            Log.i(TAG, "VALIDATION: No known baseline for API " + api + " — add one to COMPAT.md");
            return;
        }

        boolean ok = (accessFlags == knownAccessFlags) && (dexCode == knownDexCode);
        if (ok) {
            Log.i(TAG, "VALIDATION: ✅ Offsets match known baseline for API " + api);
        } else {
            Log.w(TAG, "VALIDATION: ⚠️ MISMATCH from known baseline for API " + api);
            Log.w(TAG, "  Expected access_flags=" + knownAccessFlags +
                " got=" + accessFlags);
            Log.w(TAG, "  Expected dex_code=" + knownDexCode +
                " got=" + dexCode);
            Log.w(TAG, "  Update NativeCore.cpp offset tables and submit a PR.");
        }
    }
}
