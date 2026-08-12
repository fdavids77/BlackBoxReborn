# Contributing to BlackBox Reborn

## What's most useful right now

1. **Android 17 (API 37) testing** — try running the current build on an Android 17
   device and report any crash or hook failure using the compat bug template.

2. **ArtMethod offset verification** — if you have a non-Samsung Android 16 device,
   run `tools/art_offset_verifier/` and open a PR with the measured offsets added to
   the table in `COMPAT.md`.

3. **App compat reports** — virtualise any app and add a row to the compat matrix
   in `README.md` with status and notes.

4. **Kernel UID namespace research** — the companion link / `SO_PEERCRED` problem
   (described in ROADMAP M2) is the hardest open problem. If you have relevant
   kernel or eBPF experience, open a discussion.

---

## Setting up the dev environment

```bash
git clone https://github.com/fdavids77/BlackBoxReborn
cd BlackBoxReborn

# Debug build
./gradlew :app:assembleDebug

# Install to device
adb install app/build/outputs/apk/debug/app-debug.apk

# Logcat (filter to BlackBox tags)
adb logcat -s "BlackBox" "VirtualCore" "BPackageManager" "NativeCore"
```

JDK 17 is required. The project does not build on JDK 21 yet (Gradle version constraint).

---

## Adding a fix for a new Android version

If you find a breakage on a new API level:

1. **Identify the category.** Check if it's one of the four documented fix categories
   in COMPAT.md (PackageParser, ArtMethod offsets, GMS signature, UID spoof) or
   something new.

2. **Write the fix** in the appropriate file. Keep it isolated — one logical change per
   fix class, gated by `Build.VERSION.SDK_INT` check.

3. **Add a test.** Even a simple unit test that verifies the right code path is taken
   on the target API level is better than nothing.

4. **Update COMPAT.md.** Add a row to the testing table at the bottom of the relevant
   fix section. Be honest about what "working" means for your test case.

5. **Update the compat matrix in README.md.**

---

## PR checklist

- [ ] Builds cleanly (`./gradlew :app:assembleDebug` with no warnings added)
- [ ] Gated by correct `SDK_INT` check — does not affect working API levels
- [ ] COMPAT.md updated with device/API tested
- [ ] README.md compat matrix updated
- [ ] Commit message describes the Android version and what broke

---

## Native (JNI) changes

For ArtMethod offset changes:

- Run `tools/art_offset_verifier/` on the target device first
- Add the measured offsets to the lookup function in `NativeCore.cpp`
- Verify with logcat that hooks are actually firing on the new API level

For new libc hooks:

- Use the existing PLT hook infrastructure in `NativeCore.cpp` — do not add a new
  hooking library
- Keep the hook function as thin as possible (just the UID translation or flag change)
- Always preserve the original function call path when the virtual UID table has
  no entry for the calling process

---

## Commit style

```
Fix PackageParserFix for API 37 — getPackageArchiveInfo returns null for split APKs
Fix ArtMethodFix offsets for Pixel 10 Pro Android 17 (API 37)
Add WhatsApp 2.26.x to app compat matrix — registration working on A25
```

Short imperative first line, no period. Body explains the why if it's non-obvious.
