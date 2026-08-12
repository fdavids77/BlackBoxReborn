/*
 * BlackBox Reborn — Milestone 2: Syscall Hijack UID Injector
 *
 * Instead of injecting shellcode at the current PC (which fails when the
 * process is mid-syscall), this version uses PTRACE_SYSCALL to catch the
 * TARGET's next syscall ENTRY and replaces it with setresuid(target_uid).
 *
 * No memory writes. No shellcode. Just register manipulation at the
 * exact moment the kernel is about to execute the next syscall.
 *
 * Usage: ./uid_inject_v2 <pid> <target_uid>
 *
 * Mechanism:
 *   1. PTRACE_ATTACH — stop the process
 *   2. PTRACE_SYSCALL — run to next syscall entry OR exit
 *   3. If stopped at syscall EXIT (current mid-call finishing): PTRACE_SYSCALL again
 *   4. At syscall ENTRY: save x8 (orig syscall nr), swap in setresuid
 *   5. PTRACE_SYSCALL — run to syscall exit (setresuid completes)
 *   6. Read x0 return value
 *   7. PTRACE_DETACH — resume
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

#define NT_PRSTATUS 1
#define SYS_setresuid 147

struct arm64_regs {
    unsigned long long regs[31]; /* x0-x30 */
    unsigned long long sp;
    unsigned long long pc;
    unsigned long long pstate;
};

static int get_regs(pid_t pid, struct arm64_regs *r) {
    struct iovec iov = { r, sizeof(*r) };
    return ptrace(PTRACE_GETREGSET, pid, (void*)NT_PRSTATUS, &iov);
}
static int set_regs(pid_t pid, struct arm64_regs *r) {
    struct iovec iov = { r, sizeof(*r) };
    return ptrace(PTRACE_SETREGSET, pid, (void*)NT_PRSTATUS, &iov);
}

/* Returns 1 if stopped at syscall entry, 0 at exit, -1 on error */
static int wait_for_syscall(pid_t pid) {
    int status;
    for (;;) {
        if (ptrace(PTRACE_SYSCALL, pid, NULL, NULL) < 0) return -1;
        if (waitpid(pid, &status, 0) < 0) return -1;
        if (WIFEXITED(status) || WIFSIGNALED(status)) return -1;
        if (WIFSTOPPED(status) && (WSTOPSIG(status) & 0x80)) {
            /* SIGTRAP|0x80 = syscall-stop. Entry/exit alternates. */
            return 1;
        }
        /* Other signal — deliver and keep waiting */
        if (WIFSTOPPED(status)) {
            ptrace(PTRACE_SYSCALL, pid, NULL, (void*)(long)WSTOPSIG(status));
        }
    }
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s <pid> <target_uid>\n", argv[0]);
        return 1;
    }

    pid_t target_pid   = (pid_t) atoi(argv[1]);
    unsigned long long target_uid = (unsigned long long) atoi(argv[2]);

    printf("╔══════════════════════════════════════════════════╗\n");
    printf("║  BlackBox Reborn — Syscall Hijack UID Injector   ║\n");
    printf("╚══════════════════════════════════════════════════╝\n\n");
    printf("Target PID : %d\n", target_pid);
    printf("Target UID : %llu\n\n", target_uid);

    if (getuid() != 0) {
        fprintf(stderr, "ERROR: must run as root\n"); return 1;
    }

    /* ── 1. Attach ── */
    printf("[1] PTRACE_ATTACH...\n");
    if (ptrace(PTRACE_ATTACH, target_pid, NULL, NULL) < 0) {
        fprintf(stderr, "    FAILED: %s\n", strerror(errno));
        if (errno == EPERM)
            fprintf(stderr, "    Try: su -c 'setenforce 0' then retry\n");
        return 1;
    }

    int status;
    waitpid(target_pid, &status, 0);
    printf("    Attached (signal %d)\n", WSTOPSIG(status));

    /* Enable syscall-tracing. PTRACE_O_TRACESYSGOOD makes the stop signal
     * have bit 7 set (SIGTRAP|0x80) so we can tell syscall-stops apart. */
    if (ptrace(PTRACE_SETOPTIONS, target_pid, NULL,
               (void*)PTRACE_O_TRACESYSGOOD) < 0) {
        fprintf(stderr, "    SETOPTIONS failed: %s\n", strerror(errno));
    }

    /* ── 2. Advance past the current syscall (if mid-syscall) ── */
    printf("[2] Waiting for next syscall entry...\n");

    struct arm64_regs regs;
    int stop_count = 0;

    for (;;) {
        if (wait_for_syscall(target_pid) < 0) {
            fprintf(stderr, "    Process exited unexpectedly\n");
            return 1;
        }
        stop_count++;

        get_regs(target_pid, &regs);
        unsigned long long nr = regs.regs[8]; /* x8 = syscall number on ARM64 */

        /* On ARM64, at syscall ENTRY x8 holds the syscall number.
         * At syscall EXIT x8 is often still the same number.
         * We distinguish entry from exit by the return value: at exit,
         * x0 holds the return value; at entry x0 holds the first arg.
         * Simpler heuristic: even stops = entry, odd = exit (alternating).
         * We use stop_count parity — first stop after ATTACH is entry. */
        int is_entry = (stop_count % 2 == 1);

        printf("    stop %d: x8=%llu (syscall %s), x0=0x%llx  [%s]\n",
            stop_count, nr,
            nr == SYS_setresuid ? "setresuid" : "other",
            regs.regs[0],
            is_entry ? "ENTRY" : "EXIT");

        if (is_entry && nr != SYS_setresuid) {
            /* Found a real syscall entry that isn't already setresuid */
            printf("    → Hijacking syscall %llu → setresuid(%llu,%llu,%llu)\n",
                nr, target_uid, target_uid, target_uid);
            break;
        }

        if (stop_count > 20) {
            fprintf(stderr, "    Couldn't find clean syscall entry after 20 stops\n");
            ptrace(PTRACE_DETACH, target_pid, NULL, NULL);
            return 1;
        }
    }

    /* ── 3. Hijack: replace syscall number and args ── */
    struct arm64_regs hijack_regs = regs;
    unsigned long long orig_syscall = regs.regs[8];

    hijack_regs.regs[0] = target_uid;    /* ruid */
    hijack_regs.regs[1] = target_uid;    /* euid */
    hijack_regs.regs[2] = target_uid;    /* suid */
    hijack_regs.regs[8] = SYS_setresuid;

    printf("[3] Setting registers for setresuid...\n");
    if (set_regs(target_pid, &hijack_regs) < 0) {
        fprintf(stderr, "    SETREGSET failed: %s\n", strerror(errno));
        ptrace(PTRACE_DETACH, target_pid, NULL, NULL);
        return 1;
    }

    /* ── 4. Run to syscall EXIT ── */
    printf("[4] Running to setresuid exit...\n");
    if (wait_for_syscall(target_pid) < 0) {
        fprintf(stderr, "    Process exited unexpectedly\n");
        return 1;
    }

    struct arm64_regs exit_regs;
    get_regs(target_pid, &exit_regs);
    long long retval = (long long) exit_regs.regs[0];
    printf("    setresuid return value: %lld\n", retval);

    /* ── 5. Check /proc/status BEFORE detach ── */
    printf("[5] Reading /proc/%d/status (UID) before detach...\n", target_pid);
    char cmd[128];
    snprintf(cmd, sizeof(cmd), "grep ^Uid /proc/%d/status", target_pid);
    system(cmd);

    /* ── 6. Detach ── */
    printf("[6] Detaching (PTRACE_DETACH)...\n");
    ptrace(PTRACE_DETACH, target_pid, NULL, NULL);

    /* ── 7. Final /proc check after detach ── */
    printf("[7] Reading /proc/%d/status after detach...\n", target_pid);
    system(cmd);

    printf("\n");
    if (retval == 0) {
        printf("╔══════════════════════════════════════════════════╗\n");
        printf("║ RESULT: ✅ setresuid returned 0 (success)        ║\n");
        printf("║  PID %5d — check Uid line above for confirmation║\n", target_pid);
        printf("╚══════════════════════════════════════════════════╝\n");
        return 0;
    } else {
        printf("╔══════════════════════════════════════════════════╗\n");
        printf("║ RESULT: ❌ setresuid returned %lld               ║\n", retval);
        printf("╚══════════════════════════════════════════════════╝\n");
        return 1;
    }
}
