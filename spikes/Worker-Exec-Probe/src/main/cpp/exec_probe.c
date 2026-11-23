// ADFA-7281 spike 1: can a plugin start a native executable it ships as lib*.so?
//
// Built as a PIE *executable* (not a shared library) and packaged under
// lib/arm64-v8a/ so the plugin manager extracts it next to the plugin's other
// native libs. ExecProbePlugin starts it with ProcessBuilder in each mode below
// and checks what it prints. Every mode prints exactly one JSON line on stdout.
//
//   --identify        who am I: pid, uid, SELinux domain, exe path, CPU features
//   --env             echo COGO_PROBE_TOKEN, to check the environment reaches us
//   --abort           call abort(), as GGML_ASSERT does; the parent must survive
//   --stdin-eof       print "ready <pid>", count stdin bytes until EOF, then exit 0
//                     (the worker's planned shutdown signal is EOF on its input)
//   --socket NAME     connect to the abstract unix socket NAME, send a hello,
//                     print the reply (the worker's planned IPC channel)

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

// aarch64 HWCAP bits, from <asm/hwcap.h>; repeated so the probe builds on any NDK.
// Set by the build (-DPROBE_BUILD=...) to the plugin's versionName, so the plugin can
// tell whether the extracted binary came from its own build or a stale earlier one.
#ifndef PROBE_BUILD
#define PROBE_BUILD "unknown"
#endif

#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1UL << 20)
#endif
#ifndef HWCAP_SVE
#define HWCAP_SVE (1UL << 22)
#endif
#ifndef HWCAP2_I8MM
#define HWCAP2_I8MM (1UL << 13)
#endif

// Reads a small text file (procfs) into buf, trimming trailing NULs and newlines.
static void read_small(const char *path, char *buf, size_t n) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        snprintf(buf, n, "<%s>", strerror(errno));
        return;
    }
    ssize_t r = read(fd, buf, n - 1);
    close(fd);
    if (r < 0) r = 0;
    buf[r] = 0;
    while (r > 0 && (buf[r - 1] == '\n' || buf[r - 1] == 0)) buf[--r] = 0;
}

// Prints s as a JSON string body: escapes quotes, backslashes and control bytes.
static void json_str(const char *s) {
    putchar('"');
    for (; *s; s++) {
        unsigned char c = (unsigned char) *s;
        if (c == '"' || c == '\\') printf("\\%c", c);
        else if (c < 0x20) printf("\\u%04x", c);
        else putchar(c);
    }
    putchar('"');
}

static int identify(void) {
    char selinux[256], exe[512];
    read_small("/proc/self/attr/current", selinux, sizeof selinux);
    ssize_t n = readlink("/proc/self/exe", exe, sizeof exe - 1);
    exe[n < 0 ? 0 : n] = 0;
    unsigned long hw = getauxval(AT_HWCAP), hw2 = getauxval(AT_HWCAP2);

    printf("{\"mode\":\"identify\",\"build\":\"" PROBE_BUILD "\",\"pid\":%d,\"ppid\":%d,\"uid\":%d,\"gid\":%d,\"selinux\":",
           getpid(), getppid(), getuid(), getgid());
    json_str(selinux);
    printf(",\"exe\":");
    json_str(exe);
    printf(",\"page_size\":%ld,\"hwcap\":\"0x%lx\",\"hwcap2\":\"0x%lx\","
           "\"dotprod\":%s,\"i8mm\":%s,\"sve\":%s}\n",
           sysconf(_SC_PAGESIZE), hw, hw2,
           (hw & HWCAP_ASIMDDP) ? "true" : "false",
           (hw2 & HWCAP2_I8MM) ? "true" : "false",
           (hw & HWCAP_SVE) ? "true" : "false");
    return 0;
}

static int env(void) {
    const char *t = getenv("COGO_PROBE_TOKEN");
    printf("{\"mode\":\"env\",\"token\":");
    json_str(t ? t : "");
    printf("}\n");
    return 0;
}

static int do_abort(void) {
    printf("{\"mode\":\"abort\",\"pid\":%d}\n", getpid());
    fflush(stdout);
    abort();
}

static int stdin_eof(void) {
    printf("ready %d\n", getpid());
    fflush(stdout);
    char buf[4096];
    long total = 0;
    ssize_t r;
    while ((r = read(STDIN_FILENO, buf, sizeof buf)) > 0) total += r;
    printf("{\"mode\":\"stdin-eof\",\"bytes\":%ld,\"read_errno\":%d}\n", total, r < 0 ? errno : 0);
    return 0;
}

static int sock(const char *name) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        printf("{\"mode\":\"socket\",\"error\":\"socket: %s\"}\n", strerror(errno));
        return 3;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    size_t len = strlen(name);
    if (len > sizeof addr.sun_path - 1) len = sizeof addr.sun_path - 1;
    memcpy(addr.sun_path + 1, name, len);  // sun_path[0] == 0: abstract namespace
    socklen_t alen = (socklen_t) (offsetof(struct sockaddr_un, sun_path) + 1 + len);
    if (connect(fd, (struct sockaddr *) &addr, alen) < 0) {
        printf("{\"mode\":\"socket\",\"error\":\"connect: %s\"}\n", strerror(errno));
        close(fd);
        return 3;
    }
    char hello[64];
    int hl = snprintf(hello, sizeof hello, "hello %d\n", getpid());
    if (write(fd, hello, (size_t) hl) != hl) {
        printf("{\"mode\":\"socket\",\"error\":\"write: %s\"}\n", strerror(errno));
        close(fd);
        return 3;
    }
    char reply[128];
    size_t got = 0;
    while (got < sizeof reply - 1) {
        ssize_t r = read(fd, reply + got, 1);
        if (r <= 0 || reply[got] == '\n') break;
        got++;
    }
    reply[got] = 0;
    close(fd);
    printf("{\"mode\":\"socket\",\"reply\":");
    json_str(reply);
    printf("}\n");
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 2) {
        if (!strcmp(argv[1], "--identify")) return identify();
        if (!strcmp(argv[1], "--env")) return env();
        if (!strcmp(argv[1], "--abort")) return do_abort();
        if (!strcmp(argv[1], "--stdin-eof")) return stdin_eof();
        if (!strcmp(argv[1], "--socket") && argc >= 3) return sock(argv[2]);
    }
    fprintf(stderr, "usage: %s --identify|--env|--abort|--stdin-eof|--socket NAME\n", argv[0]);
    return 2;
}
