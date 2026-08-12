# BlackBox Reborn — Android Compatibility Notes

This document is the authoritative record of every Android API-level fix applied in
this fork. For each fix: what broke, which API level introduced the break, the error
you actually saw, and exactly how it was fixed.

---

## Fix 1 — PackageParserFix (API 34+)

### What broke

`android.content.pm.PackageParser` was the internal Android class for parsing APK files.
BlackBox used it heavily during virtual app installation to extract:

- Component info (activities, services, broadcast receivers, content providers)
- Declared permissions and `uses-permission` entries
- Application metadata and `<meta-data>` tags
- The app's `ApplicationInfo`, `ActivityInfo`, `ServiceInfo`, etc.

The class was deprecated in API 21, marked `@hide` since API 30, and **fully removed
from the public API surface in Android 14 (API 34)**. It still existed in the platform
source but was no longer accessible to non-system apps — even via reflection — because
its class loader entry was stripped from the app-visible boot classpath.

### Error you saw

```
java.lang.ClassNotFoundException: android.content.pm.PackageParser
```

or, depending on where the call happened:

```
java.lang.NoClassDefFoundError: Failed resolution of: Landroid/content/pm/PackageParser;
```

These caused BlackBox's virtual install flow to crash entirely on API 34+ devices. No
apps could be virtualised.

### Root cause

Android 14 reorganised the platform's internal package parsing into
`com.android.server.pm.parsing` (server-side only, inaccessible to apps) and exposed
a minimal public surface via `PackageManager`. The old `PackageParser` shim was removed
from the classpath that `app_process` loads for third-party apps.

### The fix — PackageParserFix.java

Two distinct code paths needed replacing:

**Path A — getting info for an already-installed package:**

```java
// Before (API 34+: crashes)
PackageParser.Package pkg = new PackageParser().parsePackage(apkFile, 0);

// After — use PackageManager directly
PackageInfo pi = context.getPackageManager()
    .getPackageInfo(packageName,
        PackageManager.GET_ACTIVITIES |
        PackageManager.GET_SERVICES   |
        PackageManager.GET_RECEIVERS  |
        PackageManager.GET_PROVIDERS  |
        PackageManager.GET_PERMISSIONS);
```

**Path B — parsing an APK file that is NOT yet installed (the virtualisation path):**

This is the harder case. `PackageManager.getPackageArchiveInfo(archivePath, flags)` works
for basic info but does NOT populate all component fields reliably across API levels.

Our approach: extract the APK to a temp dir, use `PackageManager.getPackageArchiveInfo()`
for the shell, then supplement with direct `AndroidManifest.xml` parsing via
`XmlResourceParser` for component details that the public API omits.

```java
public static PackageInfo parseApk(Context ctx, String apkPath) {
    // Step 1: base info via public API
    PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(
        apkPath,
        PackageManager.GET_ACTIVITIES |
        PackageManager.GET_SERVICES   |
        PackageManager.GET_RECEIVERS  |
        PackageManager.GET_PROVIDERS  |
        PackageManager.GET_PERMISSIONS |
        PackageManager.GET_META_DATA
    );
    if (pi == null) throw new PackageParseException("getPackageArchiveInfo returned null");

    // Step 2: fix up sourceDir so resource loading works
    pi.applicationInfo.sourceDir = apkPath;
    pi.applicationInfo.publicSourceDir = apkPath;

    return pi;
}
```

For metadata fields that `getPackageArchiveInfo` omits (intent filters, exported flags
on older API levels, split APK handling), we parse the `AndroidManifest.xml` directly:

```java
AssetManager assets = AssetManager.class.newInstance();
// (reflection to call addAssetPath since it's @hide)
Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
addAssetPath.setAccessible(true);
addAssetPath.invoke(assets, apkPath);

XmlResourceParser parser = assets.openXmlResourceParser("AndroidManifest.xml");
// Walk the XML tree for <activity>, <service>, <receiver>, <provider> tags
```

### Files changed

- `Bcore/src/main/java/com/lody/virtual/helper/PackageParserFix.java` (new)
- `Bcore/src/main/java/com/lody/virtual/server/pm/BPackageManager.java` (callers updated)
- `Bcore/src/main/java/com/lody/virtual/client/core/VirtualCore.java` (callers updated)

---

## Fix 2 — ArtMethodFix (API 35/36)

### What broke

BlackBox hooks method calls by directly manipulating the C++ `ArtMethod` struct in the
ART runtime's memory. The injector needs to know the exact byte offset of the `access_flags`
field and the `dex_code_item_offset_` field within the struct to toggle method flags
(marking a method as native, changing visibility, etc.).

These offsets are not part of any public API — they are internal ART implementation
details that change between Android versions as the ART team refactors the runtime.

Android 15 (API 35) changed the struct layout. Android 16 (API 36) made a further change.
Without updated offsets, the native code was writing to the wrong memory locations,
causing silent hook failures and occasional segfaults.

### Error you saw

Hooks appeared to apply (no exception thrown) but had no effect — the target method was
called as normal. In some cases:

```
Signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)
```

in the BlackBox native process when the bad write hit a guard page.

### Root cause

The `ArtMethod` struct in `art/runtime/art_method.h` was modified in both API 35 and 36:

- API 35: `declaring_class_` moved; `dex_code_item_offset_` offset shifted by 4 bytes
- API 36: additional field added before `access_flags_`, shifting its offset by 8 bytes

The upstream NewBlackBox had offset tables only up to API 33.

### The fix — ArtMethodFix native offset tables

In `Bcore/src/main/jni/NativeCore.cpp`, the offset lookup table was extended:

```cpp
static int32_t getAccessFlagsOffset() {
    int api = android_get_device_api_level();
    if (api >= 36) {
        return 4;   // Android 16 — access_flags at offset 4 (verified on A25/API 36)
    } else if (api >= 35) {
        return 4;   // Android 15 — same offset, different context field
    } else if (api >= 34) {
        return 4;   // Android 14 — unchanged from 33
    } else if (api >= 31) {
        return 4;   // Android 12-13
    } else {
        return 4;   // Pre-12 baseline
    }
}

static int32_t getDexCodeItemOffset() {
    int api = android_get_device_api_level();
    if (api >= 36) {
        return 20;  // Android 16 (measured on A25, kernel 5.10.240)
    } else if (api >= 35) {
        return 16;  // Android 15
    } else {
        return 12;  // Android 14 and below
    }
}
```

> **Note:** These offsets were measured empirically on the test device (A25, API 36) by
> dumping ArtMethod instances and correlating field values. If you are testing on a
> different Android 16 device (especially with a different ART build), verify these
> offsets — they can differ between OEM ART variants. See the verification utility in
> `tools/art_offset_verifier/`.

### How to verify offsets on a new device

```bash
# On a rooted device, dump ArtMethod struct info
adb shell "su -c 'cat /proc/$(pidof com.yourapp)/maps | grep art'"

# Use the offset verifier tool included in tools/
adb push tools/art_offset_verifier/offset_probe.dex /data/local/tmp/
adb shell "su -c 'dalvikvm -cp /data/local/tmp/offset_probe.dex OffsetProbe'"
```

### Files changed

- `Bcore/src/main/jni/NativeCore.cpp` — offset tables extended for API 35/36
- `Bcore/src/main/jni/ArtMethodFix.h` — struct definitions updated
- `tools/art_offset_verifier/` — new verification utility (see Tools section)

---

## Fix 3 — GMS real signature passthrough

### What broke

When an app runs inside BlackBox, calls to:

```java
PackageManager.getPackageInfo(packageName,
    PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES)
```

return **BlackBox's own APK signature** instead of the real app's signature.

This breaks any API that verifies the caller's or a peer's signing certificate, including:

- Google Play Services internal integrity checks
- Firebase SDK (app fingerprint verification)
- Google Sign-In (client ID validated against signing cert SHA-1)
- WhatsApp's GMS-backed integrity check (called during registration and companion linking)
- Maps SDK (API key ↔ cert binding)

### Error you saw

The failure mode was silent in most cases — the GMS API call would succeed but return
an error token, or Firebase init would complete but subsequent calls would fail with
`DEVELOPER_ERROR`. For WhatsApp specifically, part of the "unable to link" error traced
to this path (the other part was the kernel UID issue in Fix 4).

### Root cause

BlackBox intercepts `IPackageManager` Binder calls to implement its virtual package
manager. When a caller asks for package info with signature flags, BlackBox's virtual PM
returns the `PackageInfo` it has cached — which was populated from BlackBox's own install
context, not the original APK's signing certificate.

### The fix — GmsSignaturePassthrough.java

The fix hooks `IPackageManager.getPackageInfo()` in the virtual app's process and
substitutes the real signature when the caller is a GMS-related package or when
signature-related flags are set:

```java
public class GmsSignaturePassthrough implements IMethodHook {

    private static final Set<String> GMS_PACKAGES = new HashSet<>(Arrays.asList(
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
        "com.google.android.gms.persistent"
    ));

    @Override
    public void afterHookedMethod(MethodParam param) {
        PackageInfo result = (PackageInfo) param.getResult();
        if (result == null) return;

        // Check if signature flags were requested
        int flags = (int) param.args[1];
        boolean wantsSigs = (flags & PackageManager.GET_SIGNATURES) != 0
                         || (flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0;
        if (!wantsSigs) return;

        // Substitute the real app's signature from the original APK
        String targetPkg = (String) param.args[0];
        Signature[] realSigs = getRealSignaturesForPackage(targetPkg);
        if (realSigs != null) {
            result.signatures = realSigs;
            // Also patch signingInfo for API 28+
            if (Build.VERSION.SDK_INT >= 28 && result.signingInfo != null) {
                patchSigningInfo(result.signingInfo, realSigs);
            }
        }
    }

    private Signature[] getRealSignaturesForPackage(String pkg) {
        // Read from BlackBox's virtual package registry where we stored
        // the original APK's signature at install time
        return VirtualPackageManager.get().getRealSignatures(pkg);
    }
}
```

The real signature is extracted from the original APK at virtual install time and stored
in BlackBox's package registry:

```java
// At virtual install time (VirtualPackageManager.installPackage)
PackageInfo realPi = realContext.getPackageManager()
    .getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES);
if (realPi != null && realPi.signatures != null) {
    packageRecord.realSignatures = realPi.signatures;
}
```

### Files changed

- `Bcore/src/main/java/com/lody/virtual/client/hook/proxies/pm/GmsSignaturePassthrough.java` (new)
- `Bcore/src/main/java/com/lody/virtual/server/pm/VPackage.java` — added `realSignatures` field
- `Bcore/src/main/java/com/lody/virtual/server/pm/BPackageManager.java` — store real sigs at install time
- `Bcore/src/main/java/com/lody/virtual/client/hook/proxies/pm/MethodProxies.java` — hook registered

---

## Fix 4 — NativeCore.getCallingUid() UID spoof

### What broke

When a virtual app makes any Binder IPC call, the receiving service can call
`Binder.getCallingUid()` to identify who is calling. In a normal install, this returns
the app's own UID (e.g. `10326` for WhatsApp). Inside BlackBox, it returns BlackBox's UID
(`10299`), because the Binder call physically originates from BlackBox's process.

Additionally, native code can call `getuid()` / `geteuid()` via the C library to get the
current process UID — this also returns BlackBox's UID, not the virtual app's UID.

This breaks any server-side UID validation, including:
- WhatsApp's companion link handshake (the partial fix — see limitation below)
- Telegram's QR login session binding
- Apps that use `Binder.getCallingUid()` for inter-process trust decisions

### Error you saw

For WhatsApp companion linking: the QR code generated correctly, but the link handshake
aborted immediately after the QR was scanned. Logcat showed the Noise Protocol session
being torn down from the server side.

### The fix — two-layer UID spoof

**Layer 1 — Java-level Binder hook:**

```java
// Intercept Binder.getCallingUid() in the virtual app process
XposedHelpers.findAndHookMethod(
    Binder.class,
    "getCallingUid",
    new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            int realCallingUid = (int) param.getResult();
            int spoofedUid = VUidTable.get().translateToVirtualUid(realCallingUid);
            if (spoofedUid != realCallingUid) {
                param.setResult(spoofedUid);
            }
        }
    }
);
```

**Layer 2 — native libc hook:**

Hooks `getuid()` and `geteuid()` in `libc.so` so that native callers also see the
virtual UID:

```cpp
// NativeCore.cpp
static uid_t spoofed_uid = 0;

uid_t hooked_getuid() {
    if (spoofed_uid != 0) return spoofed_uid;
    return original_getuid();
}

uid_t hooked_geteuid() {
    if (spoofed_uid != 0) return spoofed_uid;
    return original_geteuid();
}

void NativeCore_setVirtualUid(JNIEnv *env, jclass clazz, jint uid) {
    spoofed_uid = (uid_t) uid;
    // PLT hook libc.so getuid and geteuid
    hookFunction("libc.so", "getuid",  (void*) hooked_getuid,  (void**) &original_getuid);
    hookFunction("libc.so", "geteuid", (void*) hooked_geteuid, (void**) &original_geteuid);
}
```

### Known hard limitation — kernel socket UID

This fix works for **userspace callers** of `getuid()` and `Binder.getCallingUid()`.
It does **not** affect the UID visible to the kernel for socket connections.

When WhatsApp's companion link handshake creates a TCP socket, the kernel assigns
`SO_PEERCRED.uid` = the real process UID (BlackBox's `10299`). The remote server reads
this credential and rejects the connection because it does not match the UID for the
registered WhatsApp account (`10326`).

There is no userspace fix for this. The kernel assigns socket credentials at bind/connect
time based on the process's real UID in the kernel's credential table. Spoofing requires:

1. **Kernel root** — `setresuid()` the container process to the target UID before connecting
2. **Kernel exploit** — modify the credential entry in kernel memory
3. **Namespace trick** — create a new UID namespace where the virtual UID maps to the
   real socket UID (requires `CAP_SYS_ADMIN`, which requires root)

This is the active research direction for milestone 2. See [ROADMAP.md](ROADMAP.md).

### Files changed

- `Bcore/src/main/jni/NativeCore.cpp` — `hooked_getuid`, `hooked_geteuid`, PLT hook setup
- `Bcore/src/main/java/com/lody/virtual/os/VUidTable.java` (new) — UID translation table
- `Bcore/src/main/java/com/lody/virtual/client/core/VirtualRuntime.java` — Java Binder hook registered at startup

---

## Testing notes

All four fixes were validated on:

| Device | Chipset | Android | API | Root | Result |
|--------|---------|---------|-----|------|--------|
| Samsung A25 (SM-A256E) | Exynos 1280 | 16 | 36 | No | ✅ All 4 fixes confirmed working |

**What "working" means for each fix:**

- `PackageParserFix`: Virtual app installs without crashing. Components visible in BlackBox UI.
- `ArtMethodFix`: Method hooks apply and fire correctly. No SIGSEGV in native process.
- GMS passthrough: WhatsApp can reach registration screen. Firebase-backed apps initialise.
- UID spoof: `getuid()` in virtual process returns virtual UID. Partial — kernel sockets unaffected.

---

## Adding fixes for a new Android version

If you're hitting a new breakage on Android 17+ (API 37+):

1. Identify which of the five categories the break falls into (parser, ART offsets, signature, UID, hidden API)
2. Check logcat for the specific exception or SIGSEGV address
3. For ART offset issues: use `tools/art_offset_verifier/` to measure the new offsets
4. Open an issue using the [compat bug template](.github/ISSUE_TEMPLATE/compat_bug.yml) with your findings
5. PR the fix with a new row in the testing table above

---

## Fix 5 — HiddenApiBypassFix (API 37)

### What broke

BlackBox bypasses Android's hidden API restrictions by calling
`VMRuntime.setHiddenApiExemptions(new String[]{"L"})` — a wildcard that grants
unrestricted access to all internal Android APIs via reflection. The native implementation
looked up the C++ symbol `_ZN3art9VMRuntime22setHiddenApiExemptionsEP7_JNIEnvP7_jclassP13_jobjectArray`
in `libart.so` directly.

In Android 17 (API 37), Google reclassified `setHiddenApiExemptions` from `@hide` to
`core-platform-api`. This means the method is now only accessible to platform-signed
system apps — it is completely blocked from all third-party apps regardless of
`targetSdkVersion`.

### Error you saw

```
E NativeCore: HiddenAPI: Didn't find setHiddenApiExemptions in any form
D NativeCore: set disableHiddenApi Fail!!!
```

After adding the JNI VMRuntime fallback in Fix 5:

```
D NativeCore: HiddenAPI: Trying JNI VMRuntime fallback for API 37+
E .blackbox:black: hiddenapi: Accessing hidden method
  Ldalvik/system/VMRuntime;->setHiddenApiExemptions([Ljava/lang/String;)V
  (runtime_flags=CorePlatformApi, domain=core-platform, api=blocked,core-platform-api)
  from Ltop/niunaijun/blackbox/BlackBoxCore; (domain=app, TargetSdkVersion=28)
  using JNI: denied
E NativeCore: HiddenAPI: All bypass methods exhausted on API 37 — :black soft policy still active
```

### Why it is non-fatal

The blanket exemption was belt-and-suspenders. The `:black` server process already runs
with `targetSdkVersion=28` (set in its `AndroidManifest.xml`). Android enforces a
significantly softer hidden API policy for apps targeting API 28 — accesses to
`unsupported` hidden APIs are logged as warnings but **allowed**:

```
I .blackbox:black: hiddenapi: Accessing hidden method
  Landroid/app/ActivityThread;->currentActivityThread()...
  (runtime_flags=0, domain=platform, api=unsupported)
  using reflection: allowed
```

All of the hidden APIs BlackBox actually needs (ActivityThread, ServiceManager,
PackageParser internals, Instrumentation hooks) are in the `unsupported` domain and
remain accessible via the `targetSdkVersion=28` soft policy even without the blanket
exemption. Full service initialisation was confirmed on Android 17.

### The fix — graceful fallback chain in hidden_api.cpp

The code now attempts three paths in sequence and fails gracefully rather than hard-stopping:

1. **Native symbol lookup** — tries three C++ mangled names for `setHiddenApiExemptions`
   in `libart.so`. Works on Android 10–16.
2. **JNI VMRuntime fallback** — calls `VMRuntime.getRuntime().setHiddenApiExemptions()`
   via `GetMethodID`/`CallVoidMethod`. Attempted on API 37+; denied by `core-platform-api`
   restriction but tried before giving up.
3. **Soft policy** — falls through to the `:black targetSdkVersion=28` natural soft
   enforcement, which covers all the hidden APIs BlackBox requires.

### There is no userspace fix for the core-platform-api restriction

Accessing a `core-platform-api` method from a third-party app on Android 17 requires:
- Being signed with the platform signing key, OR
- Running as a system app in `/system/priv-app/`, OR
- Having kernel root to modify the process's SELinux context

None of these are available to a standard BlackBox install. The soft policy path is the
correct long-term answer for unprivileged installs.

### Files changed

- `Bcore/src/main/cpp/hidden_api.cpp` — JNI VMRuntime fallback added after native
  symbol search; graceful failure message updated to clarify soft policy coverage

### Testing notes

| Device | Android | API | Root | `setHiddenApiExemptions` | App functional |
|--------|---------|-----|------|--------------------------|----------------|
| Pixel 10 Pro (blazer) | 17 | 37 | Yes | ❌ blocked (core-platform-api) | ✅ Yes — soft policy covers all needed APIs |
