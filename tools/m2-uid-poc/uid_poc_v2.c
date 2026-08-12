/*
 * BlackBox Reborn — Milestone 2 UID PoC (v2)
 *
 * Correct test sequence:
 *   1. Parent (root) creates + binds the server socket
 *   2. Parent forks
 *   3. Child calls setresuid(target_uid) THEN connect()
 *   4. Parent accepts and reads SO_PEERCRED
 *
 * This matches the real WhatsApp companion link scenario:
 *   - Server (WhatsApp on device) waits for companion connection
 *   - Client (BlackBox virtual WA) connects after a root helper sets its UID
 *   - Key question: does SO_PEERCRED.uid reflect the setresuid() call?
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <sys/stat.h>

#define SOCK_PATH "/data/local/tmp/uid_poc_v2.sock"

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <target_uid>\n", argv[0]);
        return 1;
    }

    uid_t target_uid = (uid_t) atoi(argv[1]);

    printf("╔═══════════════════════════════════════════════╗\n");
    printf("║  BlackBox Reborn — M2 UID PoC v2              ║\n");
    printf("╚═══════════════════════════════════════════════╝\n\n");
    printf("Target UID: %d\n", target_uid);
    printf("Running as: uid=%d\n\n", getuid());

    if (getuid() != 0) {
        fprintf(stderr, "ERROR: must run as root\n");
        return 1;
    }

    /* Step 1: parent (root) creates + binds server socket */
    unlink(SOCK_PATH);
    int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) { perror("socket"); return 1; }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, SOCK_PATH, sizeof(addr.sun_path) - 1);

    if (bind(server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        perror("bind"); return 1;
    }
    /* chmod so the non-root child can connect */
    chmod(SOCK_PATH, 0777);
    if (listen(server_fd, 1) < 0) { perror("listen"); return 1; }

    printf("[parent] server socket ready at %s\n", SOCK_PATH);

    /* Step 2: fork */
    pid_t child = fork();
    if (child < 0) { perror("fork"); return 1; }

    if (child == 0) {
        /* ── CHILD ── */
        close(server_fd);

        /* Step 3a: setresuid() to target_uid */
        printf("[child]  before setresuid: uid=%d\n", getuid());

        if (setresuid(target_uid, target_uid, target_uid) != 0) {
            fprintf(stderr, "[child]  setresuid(%d) FAILED: %s\n",
                target_uid, strerror(errno));
            return 1;
        }
        printf("[child]  after  setresuid: uid=%d\n", getuid());

        /* Step 3b: connect() — credentials captured HERE by kernel */
        int client_fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (client_fd < 0) { perror("[child] socket"); return 1; }

        struct sockaddr_un caddr;
        memset(&caddr, 0, sizeof(caddr));
        caddr.sun_family = AF_UNIX;
        strncpy(caddr.sun_path, SOCK_PATH, sizeof(caddr.sun_path) - 1);

        if (connect(client_fd, (struct sockaddr*)&caddr, sizeof(caddr)) < 0) {
            perror("[child]  connect"); return 1;
        }
        printf("[child]  connected as uid=%d\n", getuid());

        /* Keep connection open until parent reads SO_PEERCRED */
        sleep(2);
        close(client_fd);
        return 0;
    }

    /* ── PARENT ── */

    /* Step 4: accept connection and read SO_PEERCRED */
    int conn_fd = accept(server_fd, NULL, NULL);
    if (conn_fd < 0) { perror("accept"); kill(child, SIGKILL); return 1; }

    struct ucred cred;
    socklen_t cred_len = sizeof(cred);
    memset(&cred, 0, sizeof(cred));

    if (getsockopt(conn_fd, SOL_SOCKET, SO_PEERCRED, &cred, &cred_len) < 0) {
        perror("getsockopt SO_PEERCRED"); return 1;
    }

    printf("\n[parent] ── SO_PEERCRED of connected client ──\n");
    printf("[parent] uid = %d\n", cred.uid);
    printf("[parent] gid = %d\n", cred.gid);
    printf("[parent] pid = %d\n\n", cred.pid);

    if (cred.uid == target_uid) {
        printf("╔═══════════════════════════════════════════════╗\n");
        printf("║ RESULT: ✅ SO_PEERCRED uid = %d           ║\n", cred.uid);
        printf("║  setresuid() → connect() → SO_PEERCRED WORKS ║\n");
        printf("║  Companion link UID fix is VALID on this SoC  ║\n");
        printf("╚═══════════════════════════════════════════════╝\n");
    } else {
        printf("╔═══════════════════════════════════════════════╗\n");
        printf("║ RESULT: ❌ SO_PEERCRED uid = %d (want %d)  \n",
            cred.uid, target_uid);
        printf("║  setresuid() did NOT change socket credential  ║\n");
        printf("║  This approach will NOT fix companion link      ║\n");
        printf("╚═══════════════════════════════════════════════╝\n");
    }

    int status;
    waitpid(child, &status, 0);
    close(conn_fd);
    close(server_fd);
    unlink(SOCK_PATH);

    return (cred.uid == target_uid) ? 0 : 1;
}
