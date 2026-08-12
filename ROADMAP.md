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
- [x] COMPAT.md documentation for all four fixes
- [ ] GitHub Actions CI (build on push, artifact upload)
- [ ] App compat matrix (WhatsApp, Telegram, Instagram baseline)
- [ ] `tools/art_offset_verifier/` — ART offset measurement utility
- [ ] Android 17 (API 37) compatibility pass

---

## Milestone 2 — Kernel UID spoof research

**Goal:** Solve the companion link / Noise Protocol handshake failure. This requires
kernel-level UID assignment for the container process.

### Background

The current userspace UID spoof (Fix 4) patches `getuid()` and `Binder.getCallingUid()`
in userspace. This is sufficient for most IPC scenarios but fails for kernel socket
credentials (`SO_PEERCRED`). WhatsApp's companion link handshake and Telegram's QR login
both verify the connecting process's UID via the kernel socket credential mechanism.

### Research paths

**Path A — Root-based UID namespace (near-term, requires kernel root)**

On a rooted device, Linux user namespaces (`clone(CLONE_NEWUSER)`) can remap UIDs.
If the container process enters a new user namespace where virtual UID `10326` maps to
real UID `10299`, the kernel socket credential will show `10326` to the peer.

Requirements:
- Kernel root (or `CAP_SYS_ADMIN` in the init namespace)
- `CONFIG_USER_NS=y` in the kernel config (most modern Android kernels have this)
- `unshare(CLONE_NEWUSER)` + write `/proc/self/uid_map`

This path is testable on the Pixel 10 Pro (rooted) today.

**Path B — CVE-2026-43499 kernel exploit on A25 (medium-term)**

The Samsung A25 (SM-A256E, kernel 5.10.240, May 2026 patch level) is confirmed vulnerable
to CVE-2026-43499. The June 2026 patch closes the window. A kernel exploit on the a25x
(Exynos 1280) target would provide `CAP_SYS_ADMIN` / root context from which Path A
becomes available on an unrooted device.

Status: No a25x-specific port exists yet. Tracking at Root My Galaxy.

**Path C — Kernel module / eBPF hook (long-term)**

On rooted devices with `CONFIG_BPF_SYSCALL`, an eBPF program attached to the socket layer
could intercept `SO_PEERCRED` reads and substitute the virtual UID. This would not require
a kernel exploit but does require root to load the eBPF program.

### Milestone 2 deliverables

- [ ] UID namespace proof-of-concept on Pixel 10 Pro (rooted, Android 17)
- [ ] WhatsApp companion link confirmed working via namespace path
- [ ] Document namespace setup in COMPAT.md
- [ ] Investigate eBPF `SO_PEERCRED` hook feasibility
- [ ] a25x CVE port (tracked externally — see PrivilegeKit repo)

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
