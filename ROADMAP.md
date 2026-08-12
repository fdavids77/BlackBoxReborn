# BlackBox Reborn — Roadmap

---

## Milestone 1 — Stable Android 16/17 build (current)

**Goal:** A clean, documented, reproducible build on Android 14/15/16 with all four
upstream fixes properly documented and CI-verified.

- [x] `PackageParserFix` — API 34+ PackageParser removal
- [x] `ArtMethodFix` — API 35/36 ArtMethod struct offsets
- [x] GMS real signature passthrough
- [x] `NativeCore.getCallingUid()` userspace UID spoof
- [x] Tested on Samsung A25 (SM-A256E, API 36, unrooted)
- [x] COMPAT.md documentation for all five fixes (including Fix 5 API 37)
- [x] GitHub Actions CI (build on push, artifact upload)
- [x] App compat matrix baseline (WhatsApp on API 36/37)
- [x] `tools/art_offset_verifier/` — ART offset measurement utility (ArtOffsetVerifierTest)
- [x] Android 17 (API 37) compatibility pass — confirmed on Pixel 10 Pro

---

## Milestone 2 — Kernel UID spoof research

**Goal:** Solve the companion link / Noise Protocol handshake failure. This requires
kernel-level UID assignment for the container process.

### Research status: PATH A VALIDATED ✅

PoC (`tools/m2-uid-poc/uid_poc_v2.c`) confirmed on Pixel 10 Pro (blazer, Android 17,
Magisk root):

```
[child]  before setresuid: uid=0
[child]  after  setresuid: uid=10326
[child]  connected as uid=10326
[parent] SO_PEERCRED uid = 10326
RESULT: ✅ SO_PEERCRED uid = 10326
        setresuid() → connect() → SO_PEERCRED WORKS
        Companion link UID fix is VALID on this SoC
```

`setresuid(target_uid)` from a root context, followed by `connect()`, correctly
produces a socket credential that the peer reads as `target_uid` via `SO_PEERCRED`.
This is the key validation — the WhatsApp companion link server checks the connecting
process's UID, and it will see the correct value.

### Implementation approach (rooted devices)

A Magisk service running as root watches for the BlackBox virtual WhatsApp process.
When the companion link flow is detected (process reaches `RegisterAsCompanionActivity`),
a root helper:

1. Gets the virtual WhatsApp PID from BlackBox
2. Uses `ptrace` to inject a `setresuid(real_wa_uid)` syscall into the target process
3. After the ptrace injection, all new socket connections from that process carry the
   correct UID in `SO_PEERCRED`
4. Companion link handshake sees the correct identity and proceeds

Alternative (simpler, same result): the root helper forks a proxy process that runs
as the correct UID and handles the companion link socket connection, transparently
bridging to the virtual WhatsApp.

### Milestone 2 deliverables

- [x] PoC binary (`uid_poc_v2`) confirming `setresuid → SO_PEERCRED` works
- [ ] ptrace UID injector — inject `setresuid()` into a running process from root
- [ ] Integration with BlackBox — detect companion link attempt, trigger injection
- [ ] WhatsApp companion link confirmed working end-to-end on Pixel 10 Pro
- [ ] Magisk module packaging for the root helper service
- [ ] Document UID namespace approach as alternative for kernel-exploit path (A25)

### Research paths

**Path A — Root-based setresuid (confirmed working ✅)**

Requires Magisk or equivalent root on the device. Works today on any rooted device.
Validated by `uid_poc_v2.c` on Pixel 10 Pro (blazer, Android 17, kernel 6.6.118).

**Path B — CVE-2026-43499 kernel exploit on A25 (blocked pending port)**

The Samsung A25 (SM-A256E, kernel 5.10.240, May 2026 patch) is confirmed vulnerable.
No a25x (Exynos 1280) port exists yet. Once a port lands, Path A applies to the A25.
Tracking at Root My Galaxy.

**Path C — Kernel module / eBPF hook (long-term, rooted only)**

On rooted devices with `CONFIG_BPF_SYSCALL`, an eBPF program attached to the socket
layer could intercept `SO_PEERCRED` reads. Same root requirement as Path A but more
surgical — no process injection needed.

---

## Milestone 3 — Multi-instance UX and app compat expansion

**Goal:** Make BlackBox Reborn genuinely useful as a multi-instance launcher, not just
a research vehicle.

- [ ] Stable WhatsApp multi-instance (companion link working from M2)
- [ ] Telegram multi-instance
- [ ] Instagram multi-instance
- [ ] Per-instance data isolation verification
- [ ] Storage cleanup / virtual app uninstall
- [ ] Notification routing per instance
- [ ] Basic launcher UI (icon grid, per-instance labels)
- [ ] Export app compat matrix as a community-maintained wiki

---

## Milestone 4 — Play Integrity / GMS compat layer

**Goal:** Improve GMS compatibility so that apps requiring BASIC or DEVICE-level Play
Integrity can run inside the virtual container without the host device needing TrickyStore.

- [ ] Audit which GMS APIs BlackBox currently breaks
- [ ] `SafetyNet`/`Play Integrity API` response passthrough from host to virtual app
- [ ] Firebase Analytics compatibility
- [ ] Google Sign-In compatibility (signing cert fix from M1 is a prerequisite)

---

## Milestone 5 — Security research platform

**Goal:** Make BlackBox Reborn a proper platform for Android security research — not just
a multi-instance tool but a controlled sandbox for app behaviour analysis.

- [ ] Per-virtual-app network traffic capture (route through tun interface)
- [ ] Syscall monitoring in virtual app context
- [ ] `frida-server` integration inside virtual app process
- [ ] Export virtual app data directory for forensic analysis
- [ ] Snapshot / restore virtual app state
- [ ] Scripted test harness for app compat regression testing

---

## Out of scope (won't fix)

- Bypassing hardware-backed `STRONG` Play Integrity without a valid keybox (this is a
  host-level concern, handled by TrickyStore/keybox, not by BlackBox)
- Running BlackBox inside BlackBox
- Support for Android versions below API 34 (upstream handles those adequately)
