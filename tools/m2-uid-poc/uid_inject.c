/*
 * BlackBox Reborn — Milestone 2: ptrace UID Injector
 *
 * Injects a setresuid(target_uid) syscall into a running Android process
 * from a root context (Magisk su). After injection, all new socket
 * connections from that process will carry target_uid in SO_PEERCRED.
 *
 * Usage (must run as root):
 *   ./uid_inject <pid> <target_uid>
 *
 * Example — inject WhatsApp's real UID into a BlackBox virtual WA process:
 *   WA_PID=$(adb shell pidof com.whatsapp)
 *   WA_UID=$(adb shell stat -c %u /data/user/0/com.whatsapp)
 *   adb shell su -c "/data/local/tmp/uid_inject $WA_PID $WA_UID"
 *
 * Mechanism (ARM64):
 *   1. PTRACE_ATTACH   — stop the target process
 *   2. GETREGSET       — save all registers + PC
 *   3. POKEDATA        — write SVC #0 + BRK #0 at current PC
 *   4. Modify regs     — x0=x1=x2=target_uid, x8=__NR_setresuid(147)
 *   5. PTRACE_CONT     — run until BRK fires (SIGTRAP)
 *   6. Verify x0==0    — setresuid returned success
 *   7. POKEDATA        — restore original bytes at PC
 *   8. SETREGSET       — restore original registers
 *   9. PTRACE_DETACH   — resume normal execution
 *
 * SELinux note: Magisk su runs in u:r:magisk:s0 which has ptrace
 * permission to untrusted_app on standard rooted devices. If SELinux
 * blocks this, run: su -c setenforce 0 (temporarily, for testing).
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <sys/uio.h>

/* ARM64 NT_PRSTATUS — same as ELF note type 1 */
#define NT_PRSTATUS 1

/* ARM64 register layout for PTRACE_GETREGSET/NT_PRSTATUS */
struct arm64_regs {
    unsigned long long regs[31]; /* x0 – x30 */
    unsigned long long sp;
    unsigned long long pc;
    unsigned long long pstate;
};

/* ARM64 syscall numbers */
#define SYS_setresuid 147

/* ARM64 instruction encodings (little-endian) */
#define INSN_SVC0 0xD4000001ULL  /* SVC #0  — invoke syscall */
#define INSN_BRK0 0xD4200000ULL  /* BRK #0  — raise SIGTRAP  */

/* Pack two 4-byte instructions into one 8-byte POKEDATA word */
#define SHELLCODE_WORD ((INSN_BRK0 << 32) | INSN_SVC0)

static int get_regs(pid_t pid, struct arm64_regs *regs) {
    struct iovec iov = { regs, sizeof(*regs) };
    return ptrace(PTRACE_GETREGSET, pid, (void*)NT_PRSTATUS, &iov);
}

static int set_regs(pid_t pid, struct arm64_regs *regs) {
    struct iovec iov = { regs, sizeof(*regs) };
    return ptrace(PTRACE_SETREGSET, pid, (void*)NT_PRSTATUS, &iov);
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s <pid> <target_uid>\n", argv[0]);
        fprintf(stderr, "Example: %s 12345 10326\n", argv[0]);
        return 1;
    }

    pid_t target_pid = (pid_t) atoi(argv[1]);
    unsigned long long target_uid = (unsigned long long) atoi(argv[2]);

    printf("╔══════════════════════════════════════════════╗\n");
    printf("║  BlackBox Reborn — ptrace UID Injector       ║\n");
    printf("╚══════════════════════════════════════════════╝\n\n");
    printf("Target PID : %d\n", target_pid);
    printf("Target UID : %llu\n\n", target_uid);

    if (getuid() != 0) {
        fprintf(stderr, "ERROR: must run as root (current uid=%d)\n", getuid());
        return 1;
    }

    /* ── Step 1: Attach ── */
    printf("[1] PTRACE_ATTACH to pid %d...\n", target_pid);
    if (ptrace(PTRACE_ATTACH, target_pid, NULL, NULL) < 0) {
        fprintf(stderr, "    FAILED: %s\n", strerror(errno));
        if (errno == EPERM) {
            fprintf(stderr, "    SELinux may be blocking ptrace.\n");
            fprintf(stderr, "    Try: su -c 'setenforce 0' first (testing only)\n");
        }
        return 1;
    }

    int status;
    waitpid(target_pid, &status, 0);
    if (!WIFSTOPPED(status)) {
        fprintf(stderr, "    Process did not stop as expected\n");
        ptrace(PTRACE_DETACH, target_pid, NULL, NULL);
        return 1;
    }
    printf("    Process stopped (signal %d)\n", WSTOPSIG(status));

    /* ── Step 2: Save registers ── */
    printf("[2] Saving registers...\n");
    struct arm64_regs orig_regs;
    if (get_regs(target_pid, &orig_regs) < 0) {
        fprintf(stderr, "    GETREGSET failed: %s\n", strerror(errno));
        ptrace(PTRACE_DETACH, target_pid, NULL, NULL);
        return 1;
    }
    printf("    PC = 0x%llx\n", orig_regs.pc);
    printf("    SP = 0x%llx\n", orig_regs.sp);
    printf("    x0 = 0x%llx\n", orig_regs.regs[0]);

    /* ── Step 3: Save original bytes at PC ── */
    unsigned long long inject_addr = orig_regs.pc;
    printf("[3] Saving 8 bytes at PC (0x%llx)...\n", inject_addr);

    errno = 0;
    unsigned long long orig_word = (unsigned long long)
        ptrace(PTRACE_PEEKDATA, target_pid, (void*)inject_addr, NULL);
    if (errno != 0) {
        fprintf(stderr, "    PEEKDATA failed: %s\n", strerror(errno));
        ptrace(PTRACE_DETACH, target_pid, NULL, NULL);
        return 1;
    }
    printf("    Original bytes: 0x%llx\n", orig_word);

    /* ── Step 4: Write shellcode (SVC #0 + BRK #0) at PC ── */
    printf("[4] Writing shellcode at 0x%llx...\n", inject_addr);
    printf("    SVC #0 (0x%08X) + BRK #0 (0x%08X)\n",
        (unsigned)INSN_SVC0, (unsigned)INSN_BRK0);

    if (ptrace(PTRACE_POKEDATA, target_pid,
               (void*)inject_addr, (void*)SHELLCODE_WORD) < 0) {
        fprintf(stderr, "    POKEDATA failed: %s\n", strerror(errno));
        ptrace(PTRACE_DETACH, target_pid, NULL, NULL);
        return 1;
    }

    /* ── Step 5: Set up registers for setresuid syscall ── */
    printf("[5] Setting up registers for setresuid(%llu, %llu, %llu)...\n",
        target_uid, target_uid, target_uid);

    struct arm64_regs call_regs = orig_regs;
    call_regs.regs[0] = target_uid;  /* ruid */
    call_regs.regs[1] = target_uid;  /* euid */
    call_regs.regs[2] = target_uid;  /* suid */
    call_regs.regs[8] = SYS_setresuid;
    call_regs.pc      = inject_addr;

    if (set_regs(target_pid, &call_regs) < 0) {
        fprintf(stderr, "    SETREGSET failed: %s\n", strerror(errno));
        ptrace(PTRACE_POKEDATA, target_pid, (void*)inject_addr, (void*)orig_word);
        ptrace(PTRACE_DETACH, target_pid, NULL, NULL);
        return 1;
    }

    /* ── Step 6: Continue until BRK fires ── */
    printf("[6] PTRACE_CONT — executing setresuid syscall...\n");
    if (ptrace(PTRACE_CONT, target_pid, NULL, NULL) < 0) {
        fprintf(stderr, "    CONT failed: %s\n", strerror(errno));
        ptrace(PTRACE_POKEDATA, target_pid, (void*)inject_addr, (void*)orig_word);
        set_regs(target_pid, &orig_regs);
        ptrace(PTRACE_DETACH, target_pid, NULL, NULL);
        return 1;
    }

    waitpid(target_pid, &status, 0);
    if (!WIFSTOPPED(status)) {
        fprintf(stderr, "    Process exited unexpectedly (status=0x%x)\n", status);
        return 1;
    }
    printf("    Stopped on signal %d\n", WSTOPSIG(status));

    /* ── Step 7: Check syscall return value ── */
    struct arm64_regs post_regs;
    get_regs(target_pid, &post_regs);
    long long retval = (long long) post_regs.regs[0];

    printf("[7] setresuid return value: %lld", retval);
    if (retval == 0) {
        printf(" (success ✅)\n");
    } else {
        printf(" (FAILED — errno %lld: %s)\n", -retval, strerror((int)-retval));
    }

    /* ── Step 8: Restore original code ── */
    printf("[8] Restoring original bytes at 0x%llx...\n", inject_addr);
    if (ptrace(PTRACE_POKEDATA, target_pid,
               (void*)inject_addr, (void*)orig_word) < 0) {
        fprintf(stderr, "    WARNING: POKEDATA restore failed: %s\n", strerror(errno));
    }

    /* ── Step 9: Restore original registers and detach ── */
    printf("[9] Restoring registers and detaching...\n");
    set_regs(target_pid, &orig_regs);
    ptrace(PTRACE_DETACH, target_pid, NULL, (void*)SIGCONT);

    printf("\n");
    if (retval == 0) {
        printf("╔══════════════════════════════════════════════╗\n");
        printf("║ RESULT: ✅ setresuid injected successfully   ║\n");
        printf("║  PID %5d now runs as UID %-5llu           ║\n",
            target_pid, target_uid);
        printf("║  New socket connections will carry this UID  ║\n");
        printf("║  Attempt WhatsApp companion link now.        ║\n");
        printf("╚══════════════════════════════════════════════╝\n");
        return 0;
    } else {
        printf("╔══════════════════════════════════════════════╗\n");
        printf("║ RESULT: ❌ setresuid failed (errno %lld)      ║\n", -retval);
        printf("║  Process UID unchanged — check permissions   ║\n");
        printf("╚══════════════════════════════════════════════╝\n");
        return 1;
    }
}
