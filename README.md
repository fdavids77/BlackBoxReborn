# BlackBox Reborn

> A maintained Android 16/17 compatible fork of NewBlackBox — app virtualisation for
> security research, multi-instance support, and GMS compatibility.

[![Build](https://github.com/fdavids77/BlackBoxReborn/actions/workflows/build.yml/badge.svg)](https://github.com/fdavids77/BlackBoxReborn/actions/workflows/build.yml)
[![Android](https://img.shields.io/badge/android-14%2B-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-34%2B-brightgreen.svg)](https://android-arsenal.com/api?level=34)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

BlackBox is an Android app virtualisation framework — it runs apps inside a sandboxed
container without root, giving each virtual instance its own isolated identity (separate
data, accounts, and app state). **BlackBox Reborn** is a maintained fork with full
Android 14/15/16 (API 34/35/36) compatibility, four critical upstream fixes, and a
roadmap for kernel-level UID research.

---

## Why this fork exists

The upstream NewBlackBox project is broken on Android 14+ due to four distinct API
changes. This fork documents exactly what broke, why it broke, and how each fix works.
It is the **first known working BlackBox build on Android 16 (API 36)**, tested on a
Samsung A25 (SM-A256E, One UI 8, unrooted).

---

## What's fixed vs upstream

| Fix | Introduced | Upstream | Details |
|-----|-----------|----------|---------|
| `PackageParserFix` | API 34 | ❌ Broken | `android.content.pm.PackageParser` fully removed; replaced with `PackageManager` archive parsing |
| `ArtMethodFix` | API 35/36 | ❌ Broken | ART internal `ArtMethod` struct offsets changed in Android 15 and 16; native offset tables updated |
| GMS signature passthrough | All | ❌ Broken | Virtual app's real APK signature returned to GMS callers instead of BlackBox's signature |
| `NativeCore.getCallingUid()` | All | ❌ Broken | Userspace UID spoofed via `libc.so` `getuid()`/`geteuid()` hooks and Java-level `Binder.getCallingUid()` intercept |

See [COMPAT.md](COMPAT.md) for the full technical breakdown of each fix.

---

## Compatibility matrix

| Android version | API level | Status | Tested device |
|----------------|-----------|--------|---------------|
| Android 14 | 34 | ✅ Working | — |
| Android 15 | 35 | ✅ Working | — |
| Android 16 | 36 | ✅ Working | Samsung A25 (SM-A256E, Exynos 1280) |
| Android 17 | 37 | 🔄 In progress | Pixel 10 Pro (blazer) |

### App compatibility

| App | Status | Notes |
|-----|--------|-------|
| WhatsApp (fresh install + registration) | ✅ Working | Reaches registration screen |
| WhatsApp (companion link) | ⚠️ Partial | QR code generates; handshake fails — see [Known limitations](#known-limitations) |
| WhatsApp (already-registered account) | ✅ Working | Existing sessions work fine |
| WhatsApp Business | 🔄 Untested | — |
| Telegram | 🔄 Untested | — |
| Instagram | 🔄 Untested | — |

---

## Known limitations

### Companion link / Noise Protocol handshake failure

Apps that verify the caller's UID at the kernel socket level (WhatsApp companion linking,
Telegram QR login) will fail. The virtual app process runs under BlackBox's UID at the
kernel level — userspace UID spoofing does not affect `SO_PEERCRED` on Unix domain sockets
or TCP socket metadata visible to the kernel.

**Root cause:** The Noise Protocol handshake reads the connecting process's UID via the
kernel socket credential mechanism. BlackBox's container UID (e.g. `10299`) does not
match the UID WhatsApp's server expects for the registered account (`10326`), so the
handshake is aborted.

**Fix direction:** Requires kernel-level privilege to either:
- Patch `SO_PEERCRED` handling in the kernel
- Use a kernel exploit to `setresuid()` the container process to the target UID
- Run via a root context that can `nsenter` the correct UID namespace

This is tracked as a research milestone — see [ROADMAP.md](ROADMAP.md).

### Strong Play Integrity

Apps requiring hardware-backed `STRONG` attestation (banking apps, Google Wallet) will
still fail unless the host device has a working TrickyStore/keybox setup. BlackBox itself
does not provide attestation spoofing.

### Direct camera HAL access

Apps using direct camera HAL (bypassing Camera2 API) are not intercepted. Affects a
small number of specialised apps.

---

## Building

**Requirements:** JDK 17+, Android SDK, `ANDROID_HOME` set.

```bash
git clone https://github.com/fdavids77/BlackBoxReborn
cd BlackBoxReborn
./gradlew :app:assembleDebug
```

Debug APK lands at `app/build/outputs/apk/debug/`.

### Release build (unsigned)

```bash
./gradlew :app:assembleRelease
```

### Installing to a specific user

```bash
# Install to main profile
adb install -t app/build/outputs/apk/debug/app-debug.apk

# Install to Private Space (user 10) or other profile
adb install -t --user 10 app/build/outputs/apk/debug/app-debug.apk
```

---

## Project structure

```
BlackBoxReborn/
├── app/                         # Host application (sample / entry point)
├── Bcore/                       # Core BlackBox library
│   └── src/main/
│       ├── java/com/lody/virtual/
│       │   ├── client/          # Runs inside the virtualised app process
│       │   ├── server/          # BlackBox server process (AM, PM stubs)
│       │   │   ├── am/          # Virtual ActivityManager
│       │   │   └── pm/          # Virtual PackageManager
│       │   ├── helper/          # Utility & reflection helpers
│       │   └── os/              # OS abstractions, hooks
│       └── jni/                 # Native hooks (ArtMethod, UID spoof, libc hooks)
├── COMPAT.md                    # Detailed fix documentation
├── ROADMAP.md                   # Milestones and research direction
└── CONTRIBUTING.md              # How to contribute
```

---

## Research context

BlackBox Reborn grew out of the PrivilegeKit research project — an investigation into
application-level Xposed injection for locked (non-rootable) Android devices. As Samsung
and other OEMs remove bootloader unlock support, userspace virtualisation frameworks like
BlackBox become increasingly important for security research.

The companion link blocker and the kernel UID problem are active research areas. See
[ROADMAP.md](ROADMAP.md) for the full plan.

---

## Contributing

PRs and issues are welcome, especially:

- Android 17 (API 37) compatibility testing and fixes
- Additional app compat reports (add a row to the table above)
- Kernel UID spoof research
- GMS compatibility improvements
- Documentation improvements to COMPAT.md

Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting.

---

## Disclaimer

This project is for **security research and educational purposes only**. Using app
virtualisation may violate some apps' Terms of Service. The authors are not responsible
for account suspensions or any other consequences. Use responsibly and legally.

---

## Credits

- Original BlackBox by [FBlackBB](https://github.com/FBlackBB/BlackBox)
- NewBlackBox fork (baseline for this project)
- Android 14/15/16 compat research and patches by [@fdavids77](https://github.com/fdavids77)

## License

GPL-3.0 — same as upstream BlackBox. See [LICENSE](LICENSE).
