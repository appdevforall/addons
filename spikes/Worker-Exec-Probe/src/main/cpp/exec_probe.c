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
#include <inttypes.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
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

// Connects to the abstract unix socket NAME. Returns the fd, or -1 with err filled in.
static int connect_abstract(const char *name, char *err, size_t errlen) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        snprintf(err, errlen, "socket: %s", strerror(errno));
        return -1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    size_t len = strlen(name);
    if (len > sizeof addr.sun_path - 1) len = sizeof addr.sun_path - 1;
    memcpy(addr.sun_path + 1, name, len);  // sun_path[0] == 0: abstract namespace
    socklen_t alen = (socklen_t) (offsetof(struct sockaddr_un, sun_path) + 1 + len);
    if (connect(fd, (struct sockaddr *) &addr, alen) < 0) {
        snprintf(err, errlen, "connect: %s", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static int sock(const char *name) {
    char err[128];
    int fd = connect_abstract(name, err, sizeof err);
    if (fd < 0) {
        printf("{\"mode\":\"socket\",\"error\":");
        json_str(err);
        printf("}\n");
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

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}

// Maps fd read-only, checks the GGUF header and touches every page, as llama.cpp's
// mmap loader does when it reads the tensors. Appends a JSON object to stdout.
static void map_and_touch(int fd, off_t size) {
    double t0 = now_ms();
    void *p = mmap(NULL, (size_t) size, PROT_READ, MAP_SHARED, fd, 0);
    if (p == MAP_FAILED) {
        printf("{\"ok\":false,\"error\":\"mmap: %s\"}", strerror(errno));
        return;
    }
    const unsigned char *b = p;
    int magic = size >= 24 && memcmp(b, "GGUF", 4) == 0;
    uint32_t version = 0;
    uint64_t tensors = 0, kvs = 0;
    if (magic) {
        memcpy(&version, b + 4, 4);
        memcpy(&tensors, b + 8, 8);
        memcpy(&kvs, b + 16, 8);
    }
    long page = sysconf(_SC_PAGESIZE);
    volatile unsigned long sum = 0;
    for (off_t off = 0; off < size; off += page) sum += b[off];
    double ms = now_ms() - t0;
    munmap(p, (size_t) size);
    printf("{\"ok\":true,\"gguf\":%s,\"version\":%" PRIu32 ",\"tensors\":%" PRIu64
           ",\"kvs\":%" PRIu64 ",\"touch_ms\":%.1f,\"mb_per_s\":%.0f}",
           magic ? "true" : "false", version, tensors, kvs, ms,
           ms > 0 ? (size / 1048576.0) / (ms / 1000.0) : 0.0);
}

// Opens path read-only, then maps and touches it. Appends a JSON object to stdout.
static void open_and_map(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        printf("{\"ok\":false,\"error\":\"open: %s\"}", strerror(errno));
        return;
    }
    struct stat st;
    fstat(fd, &st);
    map_and_touch(fd, st.st_size);
    close(fd);
}

static int recv_fd(const char *name, const char *plain_path) {
    char err[128];
    int s = connect_abstract(name, err, sizeof err);
    if (s < 0) {
        printf("{\"mode\":\"recv-fd\",\"error\":");
        json_str(err);
        printf("}\n");
        return 3;
    }
    // The parent sends "pfd=<its own fd number>\n" with the fd attached as SCM_RIGHTS.
    char data[64] = {0};
    struct iovec iov = {data, sizeof data - 1};
    union {
        char buf[CMSG_SPACE(sizeof(int))];
        struct cmsghdr align;
    } cbuf;
    struct msghdr m;
    memset(&m, 0, sizeof m);
    m.msg_iov = &iov;
    m.msg_iovlen = 1;
    m.msg_control = cbuf.buf;
    m.msg_controllen = sizeof cbuf.buf;
    ssize_t n = recvmsg(s, &m, MSG_CMSG_CLOEXEC);
    int mfd = -1;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&m); c; c = CMSG_NXTHDR(&m, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) memcpy(&mfd, CMSG_DATA(c), sizeof(int));
    }
    if (n <= 0 || mfd < 0) {
        printf("{\"mode\":\"recv-fd\",\"error\":\"no fd received (n=%zd, errno %s)\"}\n", n, strerror(errno));
        close(s);
        return 3;
    }
    int pfd = -1;
    sscanf(data, "pfd=%d", &pfd);

    struct stat st;
    fstat(mfd, &st);
    char target[512];
    char self_path[64];
    snprintf(self_path, sizeof self_path, "/proc/self/fd/%d", mfd);
    ssize_t tl = readlink(self_path, target, sizeof target - 1);
    target[tl < 0 ? 0 : tl] = 0;

    printf("{\"mode\":\"recv-fd\",\"size\":%lld,\"regular\":%s,\"target\":",
           (long long) st.st_size, S_ISREG(st.st_mode) ? "true" : "false");
    json_str(target);
    printf(",\"received_fd\":");
    map_and_touch(mfd, st.st_size);
    printf(",\"proc_self_fd\":");
    open_and_map(self_path);
    printf(",\"proc_parent_fd\":");
    if (pfd >= 0) {
        char ppath[64];
        snprintf(ppath, sizeof ppath, "/proc/%d/fd/%d", getppid(), pfd);
        open_and_map(ppath);
    } else {
        printf("null");
    }
    printf(",\"plain_path\":");
    if (plain_path) open_and_map(plain_path); else printf("null");
    printf("}\n");

    write(s, "done\n", 5);
    close(mfd);
    close(s);
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 2) {
        if (!strcmp(argv[1], "--identify")) return identify();
        if (!strcmp(argv[1], "--env")) return env();
        if (!strcmp(argv[1], "--abort")) return do_abort();
        if (!strcmp(argv[1], "--stdin-eof")) return stdin_eof();
        if (!strcmp(argv[1], "--socket") && argc >= 3) return sock(argv[2]);
        if (!strcmp(argv[1], "--recv-fd") && argc >= 3) return recv_fd(argv[2], argc >= 4 ? argv[3] : NULL);
    }
    fprintf(stderr, "usage: %s --identify|--env|--abort|--stdin-eof|--socket NAME|--recv-fd NAME [PATH]\n", argv[0]);
    return 2;
}
