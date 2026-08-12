# Changelog

All notable changes to BlackBox Reborn are documented here.

---

## [1.3.0] - 2026-08-12

### Fixed

- **Fix 8: ServiceConnectionDelegate AbstractMethodError (API 37)**
  Android 17 added `IBinderSession` as a 4th parameter to `IServiceConnection.connected()`.
  `ServiceConnectionDelegate` only implemented the old 3-param signature, causing
  `AbstractMethodError` on every GMS service bind. Added `onTransact()` override to
  intercept the new transaction and delegate to the 3-param implementation.
  Root cause found during verification: missing AIDL presence-flag `int` before
  `ComponentName.CREATOR.createFromParcel()` shifted all subsequent reads by 4 bytes,
  causing silent `NullPointerException` inside `ComponentName.<init>` on every GMS bind.
  Both issues fixed. Confirmed zero Binder warnings on Pixel 10 Pro (Android 17).

---

## [1.2.0] - 2026-08-12

### Fixed

- **Fix 6: PropertyInvalidatedCache re-entrancy StackOverflowError (API 37)**
  Android 17 changed `PropertyInvalidatedCache.query()` to call `Binder.getCallingUid()`
  internally. BlackBox's `getCallingUid()` hook called back into `getPackageInfo()` which
  uses the cache, creating infinite recursion. Added `ThreadLocal` re-entrancy guards to
  both `NativeCore.getCallingUid()` and `IPackageManagerProxy.GetPackageInfo.hook()`.

- **Fix 7: ProxyVpnService routing all traffic to dead tun interface**
  `addRoute("0.0.0.0", 0)` sent ALL traffic into a tun fd that was never read, silently
  dropping every network call from virtual apps (DNS failures, no companion link QR).
  Changed to `addRoute("10.88.0.0", 24)` — VPN established for permission/protect()
  only, real internet traffic flows through the normal Android network stack.
  DNS resolution and g.whatsapp.net:5222 TCP connections confirmed working after fix.

---

## [1.1.0] - 2026-08-12

### Added

- **ART Offset Verifier** — instrumented test (`ArtOffsetVerifierTest`) that measures
  live `ArtMethod` field offsets on the connected device. Confirms correct offset values
  before native hooks are applied. Build scripts in `tools/run_verifier.bat/.sh`.

### Fixed

- **Fix 5: HiddenApiBypassFix (API 37)**
  `setHiddenApiExemptions` reclassified as `core-platform-api` in Android 17; JNI
  fallback tried and denied. Resolved by setting `:black` module `targetSdkVersion=28`,
  which falls under the soft-policy exemption covering all APIs needed by BlackBox.

### Research (Milestone 2)

- Confirmed `setresuid(target_uid) → connect() → SO_PEERCRED` works on Pixel 10 Pro
  (Android 17, kernel 6.6.118). PoC tools in `tools/m2-uid-poc/`.
- Companion link root cause traced: QR generates and TCP connects to g.whatsapp.net:5222;
  failure is server-side Play Integrity rejection (token signed for `top.niunaijun.blackbox`
  not `com.whatsapp`). Not fixable at the app layer without spoofing Google-signed tokens.

---

## [1.0.0] - 2026-08-12

First stable release of BlackBox Reborn — the **first known working BlackBox build on
Android 16 (API 36)**, tested on Samsung A25 (SM-A256E, Exynos 1280, unrooted).
Also boots and runs on Android 17 (API 37) on Pixel 10 Pro (blazer, Tensor G5).

### Fixed

- **Fix 1: PackageParserFix (API 34+)**
  `android.content.pm.PackageParser` removed in Android 14. Replaced with
  `PackageManager.getPackageArchiveInfo()` + `XmlResourceParser` fallback for
  component detail. Virtual app install no longer crashes on Android 14+.

- **Fix 2: ArtMethodFix (API 35/36)**
  Updated `ArtMethod` struct field offsets for Android 15/16. Previous offset table
  only covered up to API 33. access_flags=4, dex_code_item=20 for API 36.

- **Fix 3: GMS real signature passthrough**
  Virtual WhatsApp/GMS calls now receive the real system package signature instead
  of BlackBox's own signature, preventing authentication failures.

- **Fix 4: NativeCore.getCallingUid() UID spoof**
  PLT hooks on `getuid()`/`geteuid()` in `libc.so` plus Java `Binder.getCallingUid()`
  intercept. Virtual apps receive their expected UID in IPC calls.

### Infrastructure

- CI: GitHub Actions `build.yml` (debug + release APK on push, artifact upload)
- Release: `release.yml` triggers on `v*.*.*` tags, signs APK with keystore secret
- `COMPAT.md` — full technical breakdown of all fixes
- `ROADMAP.md` — Milestone plan with M2 UID research findings
