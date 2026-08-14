# BlackBox Reborn — Development Log & Current State

## Overview

BlackBox Reborn is a fork of BlackBox adding Android 16/17 (API 36/37) compatibility.
The primary use case being investigated is running multiple WhatsApp instances — one per
virtual container — so each gets its own phone number.

---

## Fixes Shipped (v2.0.0)

| Fix | File | Description |
|-----|------|-------------|
| Fix 1 | `PackageParserFix` | API 34+ PackageParser removal |
| Fix 2 | `ArtMethodFix` | API 35/36 ArtMethod struct offset changes |
| Fix 3 | `IPackageManagerProxy` | GMS real signature passthrough |
| Fix 4 | `NativeCore` | `getCallingUid()` UID spoof via PLT hooks |
| Fix 5 | `HiddenApiBypassFix` | API 37 targetSdk=28 soft policy enforcement |
| Fix 6 | `NativeCore` + `IPackageManagerProxy` | PropertyInvalidatedCache re-entrancy StackOverflowError — ThreadLocal guards |
| Fix 7 | `ProxyVpnService` | Dead tun interface — `addRoute(10.88.0.0/24)` instead of `0.0.0.0/0` |
| Fix 8 | `ServiceConnectionDelegate` | AbstractMethodError + AIDL presence-flag on API 37 IBinderSession |
| Fix 9 | `IPackageManagerProxy` | `getInstalledPackages` ClassCastException — `ParceledListSlice→PackageInfoList` via `ParceledListSliceCompat` runtime check |
| Fix 10 | `IActivityManagerProxy.BindServiceCommon` | Suppress Play Integrity `ExpressIntegrityService` bindService |
| Phase 2A | `IPackageManagerProxy.GetPackageInfo` | Signature passthrough — virtual app querying host package with GET_SIGNING_CERTIFICATES gets real Meta cert |

---

## WhatsApp Registration Investigation

### The Problem

WhatsApp registration inside BlackBox fails with **"Download the official WhatsApp"**
(`CustomRegistrationBlockActivity` / parole block). The server validates client identity.

### Root Cause Chain (confirmed by logcat)

**Stage 1 (FIXED — Phase 2A):**
`libwhatsapp.so` calls `getPackageName()` → gets `"top.niunaijun.blackbox"`, then
`getPackageInfo(host, GET_SIGNING_CERTIFICATES)` → receives BlackBox debug cert →
generates a wrong registration token → server returns parole immediately.

**Fix:** In `IPackageManagerProxy.GetPackageInfo`, when any signature-flag query targets
the BlackBox host package and a virtual app is running, redirect to the virtual app's
real package name. `getPackageInfo("com.whatsapp", GET_SIGNING_CERTIFICATES)` now returns
Meta's real signing certificate. VerifyPhoneNumber now appears. ✅

**Stage 2 (ACTIVE BLOCKER):**
Server-side Play Integrity check fires **2.6 seconds** after the HTTP registration
request. The server validates the Play Integrity token:
- With Fix 10 enabled (GMS bind blocked): server receives no token → parole in 2.6s
- With Fix 10 disabled (GMS bind allowed): server receives token for `top.niunaijun.blackbox` → parole in 2.6s
- Same result either way — purely server-side cryptographic validation.

**Cannot be fixed without either:**
1. Meta's APK signing private key (impossible), OR
2. A Play Integrity token genuinely signed for `com.whatsapp` — requires the real WhatsApp process to make the request.

### Phase 2B: Token Bridge Architecture

The bridge routes token requests from the virtual WhatsApp through the real WhatsApp:

```
Virtual WA (BlackBox)                 Real WA (WaEnhancer/LSPosed)
        │                                        │
        │ broadcast: com.blackbox.integrity.REQUEST
        │ extras: nonce, requestor ──────────────►│
        │                                        │ calls StandardIntegrityManager
        │                                        │ requestIntegrityToken(nonce)
        │                                        │ → Google signs token for com.whatsapp
        │◄──────── broadcast: RESPONSE ──────────│
        │ extras: token                          │
        │                                        │
  include real token in                          │
  registration HTTP request                      │
```

**WaEnhancer side (IntegrityBridge.java) — PARTIALLY WORKING:**
- BroadcastReceiver registered, receives broadcasts ✅
- Confirmed working via `adb shell am broadcast` test ✅
- Play Core API class resolution FAILS — `com.google.android.play.core.integrity.*`
  classes are not accessible in WhatsApp 2.26.30.97 by canonical name in any classloader
  (WhatsApp, GMS, Play Store, System) ❌
- DexKit found the constants class `X.HIq` (contains `BIND_EXPRESS_INTEGRITY_SERVICE`
  string) but `X.HIq` has 0 declared methods — it's just a string constants holder ❌

**BlackBox side (IntegrityProxy.java) — PLACEHOLDER:**
- `fetchTokenFromBridge()` and `buildBridgedTask()` written but never called ❌
- Fix 10 blocks GMS bind but does not trigger the bridge ❌
- The nonce is never extracted and sent to WaEnhancer ❌

### DexKit Investigation Findings

- Binding string in `classes9.dex` at string pool offset 2494521
- `setRequestHash` appears unobfuscated in string pool of `classes9.dex` at offset 2726515
- `cloudProjectNumber` at offset 1678365
- WhatsApp APK has 11 DEX files (`classes.dex` through `classes11.dex`)
- `findAllMethodUsingStrings` (scanning method bodies) is very slow — hangs WA startup if used in `doHook()`
- `findFirstClassUsingStrings` is fast but only finds the constants class, not the manager

---

## Recommended Next Steps

### Option A — Raw Binder call (RECOMMENDED for bridge)

Instead of finding obfuscated Play Core Java classes, bind directly to
`ExpressIntegrityService` from WaEnhancer using a raw Binder transaction:

```java
Intent intent = new Intent(
    "com.google.android.play.core.expressintegrityservice.BIND_EXPRESS_INTEGRITY_SERVICE");
intent.setPackage("com.android.vending");
context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
// In onServiceConnected: use IBinder to transact() with nonce
```

The `IExpressIntegrityService` AIDL is public in the Play Core SDK source on GitHub.
The relevant method is `requestIntegrityToken(nonce)`.
This approach is version-agnostic and doesn't depend on any obfuscated class names.

### Option B — BlackBox trigger wiring

In `IActivityManagerProxy.BindServiceCommon`, when Fix 10 fires for an integrity bind:
1. Extract the nonce from the pending token request object (passed via the intent extras or
   through the IServiceConnection)
2. Call `IntegrityProxy.fetchTokenFromBridge(nonce)` synchronously on a background thread
3. Return the token to the caller via `buildBridgedTask()`
4. Return a fake `IBinder` (not 0) so Play Core doesn't immediately fail

### Option C — Session import (quickest to try)

The Pixel has 12 registered WA instances in Private Space (users 11–15). Copy the data
from one (`key`, `msgstore.db`, `wa.db`, `axolotl.db`) into BlackBox's virtual WA
directory and boot as pre-registered. Requires correct file ownership (`u0_a45X:u0_a45X`).

---

## LSPosed / WaEnhancer Setup Notes

- WaEnhancer must be enabled for `com.whatsapp` (NOT business) in LSPosed scope
- Every WaEnhancer reinstall (even same signature) requires a **full device reboot** for
  LSPosed to pick up the new dex — plan all changes in one batch per reboot cycle
- WaEnhancer internal bridge (`WaeIIFace`) requires System Framework scope in LSPosed
  (separate from IntegrityBridge, not currently needed)
- Avoid `findAllMethodUsingStrings` in `doHook()` — it scans all methods and is too slow

---

## Key Files

```
BlackBox Reborn (C:\Dev\BBR):
  Bcore/src/main/java/top/niunaijun/blackbox/
    fake/service/IActivityManagerProxy.java   ← Fix 10, Phase 2A
    fake/service/IPackageManagerProxy.java    ← Fix 6, Fix 9, Phase 2A signature passthrough
    core/NativeCore.java                      ← Fix 6
    fake/delegate/ServiceConnectionDelegate.java ← Fix 8
    hooks/IntegrityProxy.java                 ← Phase 2B BlackBox side (placeholder)
    BlackBoxCore.java                         ← IntegrityProxy.install() call

WaEnhancer (C:\Users\w7037127\Downloads\Software\Android Development\WaEnhancer):
  app/src/main/java/com/wmods/wppenhacer/xposed/
    features/general/IntegrityBridge.java     ← Phase 2B WaEnhancer side (receiver works, resolution fails)
    core/FeatureLoader.java                   ← IntegrityBridge registered in plugins()
  app/src/main/res/values/arrays.xml          ← version support extended to 2.26.30.xx
```


---

## Phase 2B Update — 2026-08-14 (bundled StandardIntegrityManager)

**Decision:** dropped the raw-Binder-to-ExpressIntegrityService plan. The Express
service is a two-phase warm-up/request protocol with internal client-side token
assembly and a callback-based reply — not a simple nonce-in/token-out transaction,
so a hand-rolled Binder transaction is high-risk. Instead WaEnhancer now **bundles
the official Play Integrity library** and calls `StandardIntegrityManager` directly
from inside the real `com.whatsapp` process. Obfuscation and crypto are handled by
Google's own code. (See WaEnhancer `INTEGRITY_BRIDGE.md`.)

### WaEnhancer side — DONE (buildable)
- `IntegrityBridge.java` rewritten: bundled `StandardIntegrityManager`,
  `prepareIntegrityToken(cpn)` cached + warmed, `request(requestHash)` per bridge
  request, token returned via `ACTION_RESPONSE`. Compile-verified against
  Play Integrity 1.4.0.
- `build.gradle.kts` + `libs.versions.toml`: added `com.google.android.play:integrity:1.4.0`.
- Needs WhatsApp's **cloud project number** to actually mint a token (pref
  `integrity_cloud_project`, or `cloud_project` broadcast extra, or hardcode).

### BlackBox side — trigger wired + routing fix
- `BindServiceCommon` already wraps the integrity `IServiceConnection` and routes
  through `IntegrityProxy` (present since v2.0.0 commit). Confirmed.
- **Fix:** `IntegrityProxy.fetchTokenFromBridge` now sets `requestor =
  BlackBoxCore.getHostPkg()` (was the virtual package). The virtual package would
  have routed WaEnhancer's `setPackage(requestor)` reply to the REAL WhatsApp app
  instead of back to this host-owned virtual process.
- Added per-transaction diagnostic logging in the wrapped integrity binder.

### Remaining hard problem (next session)
- The Express service delivers the token on a **separate callback binder**, not in
  the transaction `reply`. So `IntegrityProxy.onTransact` writing the bridged token
  into `reply` will not reach WhatsApp's success listener as-is. Use the new
  `onTransact code/dataSize/iface` logs to map the real protocol, then either:
  (a) intercept the callback binder and deliver the token through it, or
  (b) capture `requestHash` + `cloudProjectNumber` from the transaction and inject
      the bridged token higher up (e.g. where WA inserts it into the registration
      HTTP payload).
- Also forward the captured `cloudProjectNumber` to WaEnhancer as the
  `cloud_project` broadcast extra so it need not be hardcoded.


### Update — cloudProjectNumber auto-capture (implemented)

- `IntegrityProxy` now scans each Express Integrity transaction parcel for a
  plausible GCP project number (10–13 digit little-endian long, position-restoring
  read) and caches it (`sCloudProject`). It is forwarded to WaEnhancer as the
  `cloud_project` broadcast extra, so WaEnhancer no longer needs it hardcoded.
- Heuristic: confirm the real value from the logged `Captured candidate
  cloudProjectNumber=...` line, then it can be pinned if desired.
- Both APKs rebuilt clean: WaEnhancer `app-whatsapp-release.apk`,
  BlackBox `BlackBox_4.0.0_*-debug.apk` (BUILD SUCCESSFUL).


## Adding more WhatsApp numbers (future capability)

BlackBox virtual WhatsApp cannot register (integrity/parole). Every number must be
registered where WhatsApp passes integrity, then migrated in. Repeatable loop:

1. Register the new number in a spare Android user (like users 11–15) — receive its OTP there.
2. In BlackBox, add a new WhatsApp clone → creates the next container slot (`user/1`, `user/2`, …). One container per number. (For the first one you can reuse container `0`.)
3. Migrate:  `su -c "sh /data/local/tmp/wa_migrate.sh <SRC_USER> <CONTAINER>"`  (script in `tools/wa_migrate.sh`).
4. Launch the clone in BlackBox → boots registered. Retire the source user so the number runs only in BlackBox.

Notes: source and container WhatsApp versions must match. Never run the source user's WA
and the migrated clone at the same time (same number → one gets logged out). Host BlackBox
app must hold the runtime permissions (contacts/storage/media/camera/mic/phone) — grant via
`pm grant top.niunaijun.blackbox <perm>` or Settings; the clone inherits them.
