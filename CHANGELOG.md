# Changelog

All notable changes to BlackBox Reborn are documented here.

---

## [Unreleased]

### In progress
- Android 17 (API 37) compatibility pass
- `tools/art_offset_verifier/` utility
- WhatsApp companion link via UID namespace (Milestone 2)

---

## [1.0.0] — upcoming

First stable release of BlackBox Reborn. This is the **first known working BlackBox
build on Android 16 (API 36)**, tested on Samsung A25 (SM-A256E, Exynos 1280, unrooted).

### Fixed

- **PackageParserFix** — `android.content.pm.PackageParser` was removed in Android 14
  (API 34). Replaced all usages with `PackageManager.getPackageArchiveInfo()` plus
  direct `AndroidManifest.xml` parsing via `XmlResourceParser` for component detail.
  Virtual app install no longer crashes on Android 14+.

- **ArtMethodFix** — Updated `ArtMethod` struct field offsets for Android 15 (API 35)
  and Android 16 (API 36). Previous offset table only covered up to API 33. Hooks now
  apply correctly and SIGSEGV in the native process is resolved.
  - `access_flags` offset: updated for API 35 and 36
  - `dex_code_item_offset` offset: updated for API 36

- **GMS real signature passthrough** — Virtual apps now return the real APK's signing
  certificate to GMS-related callers instead of BlackBox's own signature. Fixes Google
  Play Services integrity checks, Firebase SDK init, Google Sign-In, and WhatsApp's
  GMS-backed integrity verification.

- **NativeCore.getCallingUid() UID spoof** — Userspace UID is now spoofed at both the
  Java layer (`Binder.getCallingUid()` hook) and the native layer (`getuid()`/`geteuid()`
  PLT hooks via `libc.so`). Note: kernel socket UID (`SO_PEERCRED`) is not affected —
  see Known Limitations in README.

### Known limitations in 1.0.0

- WhatsApp companion linking fails (kernel socket UID mismatch — tracked in Milestone 2)
- Strong Play Integrity requires host-level TrickyStore/keybox setup
- Android 17 (API 37) not yet tested

### Baseline

Built on NewBlackBox fork of the original BlackBox by FBlackBB.
Upstream was last active on Android 12/13. All API 34/35/36 fixes are new in this fork.
