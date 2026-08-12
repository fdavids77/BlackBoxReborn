/*
 * BlackBox Reborn — Milestone 2 UID PoC
 *
 * Tests whether setresuid() from a root context correctly changes the UID
 * that appears in SO_PEERCRED on a Unix domain socket peer.
 *
 * This is the key validation for the companion link fix approach:
 * if SO_PEERCRED reports the remapped UID, WhatsApp's server-side
 * Noise Protocol handshake will see the correct device identity.
 *
 * Usage (must run as root):
 *   ./uid_poc <target_uid>
 *
 * Expected output on success:
 *   [parent] initial UID: 0 (root)
 *   [parent] setresuid(10326) → success
 *   [parent] getuid() after setresuid: 10326
 *   [child]  SO_PEERCRED.uid from peer: 10326   ← KEY RESULT
 *   [child]  SO_PEERCRED.pid from peer: <pid>
 *   RESULT: SO_PEERCRED reflects setresuid UID ✓
 *
 * If SO_PEERCRED still shows the original root UID (0), the approach
 * won't work and an alternative must be found.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <sys/types.h>

static void print_creds(const char *label) {
    printf("[%s] uid=%d euid=%d ruid=%d\n",
        label, getuid(), geteuid(), getuid());
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <target_uid>\n", argv[0]);
        fprintf(stderr, "Example: %s 10326\n", argv[0]);
        return 1;
    }

    uid_t target_uid = (uid_t) atoi(argv[1]);

    printf("╔═══════════════════════════════════════╗\n");
    printf("║  BlackBox Reborn — M2 UID PoC         ║\n");
    printf("╚═══════════════════════════════════════╝\n\n");

    print_creds("initial");

    if (getuid() != 0) {
        fprintf(stderr, "ERROR: must run as root (current uid=%d)\n", getuid());
        return 1;
    }

    /* Create a connected socket pair for peer credential testing */
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) < 0) {
        perror("socketpair");
        return 1;
    }
    printf("[parent] socketpair created: fd[0]=%d fd[1]=%d\n\n", sv[0], sv[1]);

    /* Set the target UID BEFORE forking so both parent and child have it */
    printf("[parent] calling setresuid(%d, %d, %d)...\n",
        target_uid, target_uid, target_uid);

    if (setresuid(target_uid, target_uid, target_uid) != 0) {
        fprintf(stderr, "[parent] setresuid FAILED: %s\n", strerror(errno));
        return 1;
    }

    print_creds("after setresuid");

    pid_t child_pid = fork();
    if (child_pid < 0) {
        perror("fork");
        return 1;
    }

    if (child_pid == 0) {
        /* ── Child: reads SO_PEERCRED of parent's socket end ── */
        close(sv[0]);

        struct ucred cred;
        socklen_t cred_len = sizeof(cred);

        memset(&cred, 0, sizeof(cred));
        if (getsockopt(sv[1], SOL_SOCKET, SO_PEERCRED, &cred, &cred_len) < 0) {
            perror("[child] getsockopt SO_PEERCRED");
            return 1;
        }

        printf("\n[child]  ── SO_PEERCRED from parent's socket end ──\n");
        printf("[child]  uid = %d\n", cred.uid);
        printf("[child]  gid = %d\n", cred.gid);
        printf("[child]  pid = %d\n\n", cred.pid);

        if (cred.uid == target_uid) {
            printf("RESULT: ✅ SO_PEERCRED reflects setresuid UID (%d)\n", cred.uid);
            printf("        Companion link UID fix is VALID on this device.\n");
            printf("        The approach works: root setresuid() → correct socket credential.\n");
        } else {
            printf("RESULT: ❌ SO_PEERCRED shows uid=%d, expected %d\n",
                cred.uid, target_uid);
            printf("        SO_PEERCRED ignores setresuid — kernel reports original UID.\n");
            printf("        This approach will NOT fix companion link.\n");
        }
        return 0;
    }

    /* ── Parent: waits for child ── */
    close(sv[1]);

    int status;
    waitpid(child_pid, &status, 0);

    printf("\n[parent] child exited with status %d\n", WEXITSTATUS(status));

    /* Second test: create a Unix domain socket, connect from child with new UID */
    printf("\n══ Test 2: bound Unix socket (closer to real WA scenario) ══\n");

    const char *sock_path = "/data/local/tmp/uid_poc_test.sock";
    unlink(sock_path);

    int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) { perror("socket"); return 1; }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, sock_path, sizeof(addr.sun_path) - 1);

    if (bind(server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        perror("bind"); return 1;
    }
    if (listen(server_fd, 1) < 0) { perror("listen"); return 1; }

    printf("[parent] Unix socket bound at %s\n", sock_path);

    pid_t child2 = fork();
    if (child2 == 0) {
        /* child connects */
        close(server_fd);
        int client_fd = socket(AF_UNIX, SOCK_STREAM, 0);
        struct sockaddr_un caddr;
        memset(&caddr, 0, sizeof(caddr));
        caddr.sun_family = AF_UNIX;
        strncpy(caddr.sun_path, sock_path, sizeof(caddr.sun_path) - 1);

        if (connect(client_fd, (struct sockaddr*)&caddr, sizeof(caddr)) < 0) {
            perror("[child2] connect"); return 1;
        }
        printf("[child2] connected. my uid=%d\n", getuid());
        close(client_fd);
        return 0;
    }

    int conn_fd = accept(server_fd, NULL, NULL);
    if (conn_fd < 0) { perror("accept"); return 1; }

    struct ucred conn_cred;
    socklen_t conn_len = sizeof(conn_cred);
    getsockopt(conn_fd, SOL_SOCKET, SO_PEERCRED, &conn_cred, &conn_len);

    printf("[parent] accepted connection — SO_PEERCRED.uid = %d\n", conn_cred.uid);

    if (conn_cred.uid == target_uid) {
        printf("RESULT: ✅ Connected socket peer reports UID %d (correct)\n", target_uid);
    } else {
        printf("RESULT: ❌ Connected socket peer reports UID %d (expected %d)\n",
            conn_cred.uid, target_uid);
    }

    waitpid(child2, &status, 0);
    close(conn_fd);
    close(server_fd);
    unlink(sock_path);

    return 0;
}
