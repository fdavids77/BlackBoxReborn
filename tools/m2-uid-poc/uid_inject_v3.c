/*
 * BlackBox Reborn — Milestone 2: Syscall Hijack UID Injector v3
 *
 * Fixes from v2:
 *   - Handles restart_syscall state: PTRACE_CONT first, then SIGSTOP to park at
 *     a clean instruction boundary before switching to PTRACE_SYSCALL mode
 *   - Accepts both SIGTRAP (0x05) and SIGTRAP|0x80 (0x85) as syscall stops
 *   - Entry/exit discrimination via x7 sentinel (ARM64 kernel sets x7=0 at entry)
 *
 * Usage: ./uid_inject_v3 <pid> <target_uid>
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
#define MAX_SYSCALL_STOPS 30

struct arm64_regs {
    unsigned long long regs[31];
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

static int is_syscall_stop(int status) {
    if (!WIFSTOPPED(status)) return 0;
    int sig = WSTOPSIG(status);
    /* Accept SIGTRAP (5) or SIGTRAP|0x80 (0x85) */
    return (sig == SIGTRAP || sig == (SIGTRAP | 0x80));
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s <pid> <target_uid>\n", argv[0]);
        return 1;
    }
    pid_t target_pid = (pid_t) atoi(argv[1]);
    unsigned long long target_uid = (unsigned long long) atoi(argv[2]);

    printf("╔════════════════════════════════════════════════════╗\n");
    printf("║  BlackBox Reborn — Syscall Hijack UID Injector v3  ║\n");
    printf("╚════════════════════════════════════════════════════╝\n\n");
    printf("Target PID : %d\n", target_pid);
    printf("Target UID : %llu\n\n", target_uid);

    if (getuid() != 0) { fprintf(stderr, "ERROR: must run as root\n"); return 1; }

    /* ── 1. Attach ── */
    printf("[1] Attaching...\n");
    if (ptrace(PTRACE_ATTACH, target_pid, NULL, NULL) < 0) {
        fprintf(stderr, "    FAILED: %s\n", strerror(errno));
        if (errno == EPERM) fprintf(stderr, "    Try: setenforce 0\n");
        return 1;
    }
    int status;
    waitpid(target_pid, &status, 0);
    printf("    Stopped (signal %d)\n", WSTOPSIG(status));

    /* Enable syscall-stop with TRACESYSGOOD (best-effort) */
    ptrace(PTRACE_SETOPTIONS, target_pid, NULL, (void*)PTRACE_O_TRACESYSGOOD);

    /* ── 2. Let any current mid-syscall state finish ──
     * If the process is in restart_syscall, PTRACE_CONT lets it restart and
     * complete the pending syscall. Then we send SIGSTOP to park it cleanly. */
    printf("[2] Clearing restart_syscall state (PTRACE_CONT + SIGSTOP)...\n");
    ptrace(PTRACE_CONT, target_pid, NULL, NULL);
    usleep(50000); /* 50ms — let the restarted syscall get going */
    kill(target_pid, SIGSTOP);
    waitpid(target_pid, &status, 0);
    printf("    Parked (signal %d)\n", WSTOPSIG(status));

    /* ── 3. Switch to PTRACE_SYSCALL mode to catch next syscall boundary ── */
    printf("[3] Waiting for next syscall entry (PTRACE_SYSCALL)...\n");

    int stop_count = 0;
    int found_entry = 0;
    struct arm64_regs entry_regs;

    while (stop_count < MAX_SYSCALL_STOPS) {
        /* Step to next syscall boundary */
        if (ptrace(PTRACE_SYSCALL, target_pid, NULL, NULL) < 0) {
            fprintf(stderr, "    PTRACE_SYSCALL failed: %s\n", strerror(errno));
            ptrace(PTRACE_DETACH, target_pid, NULL, NULL);
            return 1;
        }

        if (waitpid(target_pid, &status, 0) < 0) {
            fprintf(stderr, "    waitpid failed: %s\n", strerror(errno));
            return 1;
        }

        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            fprintf(stderr, "    Process exited unexpectedly\n");
            return 1;
        }

        stop_count++;

        if (!is_syscall_stop(status)) {
            /* Non-syscall stop (e.g. real SIGSEGV) — deliver and keep going */
            printf("    stop %d: non-syscall signal %d, delivering\n",
                stop_count, WSTOPSIG(status));
            continue;
        }

        get_regs(target_pid, &entry_regs);
        unsigned long long nr = entry_regs.regs[8];

        /* ARM64 syscall entry vs exit:
         * At ENTRY: x7 == 0 (kernel clears it at entry)
         * At EXIT:  x7 == -1 (0xFFFFFFFFFFFFFFFF, kernel sets it at exit)
         * This is the most reliable distinguisher on ARM64 Linux. */
        int is_entry = (entry_regs.regs[7] == 0);
        printf("    stop %d: x8=%llu x7=0x%llx [%s]\n",
            stop_count, nr, entry_regs.regs[7],
            is_entry ? "ENTRY" : "EXIT");

        if (is_entry && nr != SYS_setresuid && nr != 0 /* restart_syscall */) {
            printf("    Found clean entry for syscall %llu — hijacking\n", nr);
            found_entry = 1;
            break;
        }
    }

    if (!found_entry) {
        fprintf(stderr, "    Could not find clean syscall entry after %d stops\n", stop_count);
        ptrace(PTRACE_DETACH, target_pid, NULL, NULL);
        return 1;
    }

    /* ── 4. Hijack: replace syscall with setresuid ── */
    printf("[4] Hijacking → setresuid(%llu, %llu, %llu)...\n",
        target_uid, target_uid, target_uid);

    struct arm64_regs hijack_regs = entry_regs;
    hijack_regs.regs[0] = target_uid;
    hijack_regs.regs[1] = target_uid;
    hijack_regs.regs[2] = target_uid;
    hijack_regs.regs[8] = SYS_setresuid;

    if (set_regs(target_pid, &hijack_regs) < 0) {
        fprintf(stderr, "    SETREGSET failed: %s\n", strerror(errno));
        ptrace(PTRACE_DETACH, target_pid, NULL, NULL);
        return 1;
    }

    /* ── 5. Run to syscall exit ── */
    printf("[5] Running to setresuid exit...\n");
    ptrace(PTRACE_SYSCALL, target_pid, NULL, NULL);
    waitpid(target_pid, &status, 0);

    struct arm64_regs exit_regs;
    get_regs(target_pid, &exit_regs);
    long long retval = (long long) exit_regs.regs[0];

    printf("    setresuid return value: %lld %s\n",
        retval, retval == 0 ? "(✅ success)" : "(❌ failed)");

    /* ── 6. Read /proc/status BEFORE detach to see the UID ── */
    printf("[6] /proc/%d/status before detach:\n", target_pid);
    char cmd[128];
    snprintf(cmd, sizeof(cmd), "grep '^Uid' /proc/%d/status", target_pid);
    system(cmd);

    /* ── 7. Detach with SIGCONT so process resumes ── */
    printf("[7] Detaching...\n");
    ptrace(PTRACE_DETACH, target_pid, NULL, (void*)(long)SIGCONT);
    usleep(100000);

    printf("[8] /proc/%d/status after detach:\n", target_pid);
    system(cmd);

    printf("\n");
    if (retval == 0) {
        printf("╔════════════════════════════════════════════════════╗\n");
        printf("║ RESULT: ✅ setresuid(%llu) injected (returned 0)  ║\n", target_uid);
        printf("║  Check Uid: line above — should show %llu          ║\n", target_uid);
        printf("╚════════════════════════════════════════════════════╝\n");
        return 0;
    } else {
        printf("╔════════════════════════════════════════════════════╗\n");
        printf("║ RESULT: ❌ setresuid returned %lld               ║\n", retval);
        printf("╚════════════════════════════════════════════════════╝\n");
        return 1;
    }
}
