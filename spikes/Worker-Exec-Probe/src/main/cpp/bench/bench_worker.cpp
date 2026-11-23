// The worker side of spike 3, built as a PIE executable named libcogo_bench_worker.so.
//
//   libcogo_bench_worker.so --bench SOCKET_NAME
//
// Connects to the plugin's abstract socket and receives, in one message, the model fd
// (SCM_RIGHTS) and a header line:
//     "<n_ctx> <n_threads> <n_predict> <stream 0|1> <prompt_len>\n"
// followed by prompt_len bytes of prompt. Loads /proc/self/fd/<fd> (what spike 2 showed
// works), runs bench_run and writes, on the same socket:
//     "H <pid> <t_ns>\n"                      once, when connected
//     "T <t_ns> <len>\n<len bytes>"           per token, only when stream is 1
//     "S <stats json>\n"                      at the end
// With COGO_RESET_AFFINITY=1 in the environment it first widens its CPU affinity to every
// CPU, undoing a narrower mask inherited from the thread that spawned it.
// Exits when done. Log noise from llama.cpp goes to stderr, never the socket.

#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "bench_core.h"
#include "llama.h"

static int g_sock = -1;

static bool write_all(const void *buf, size_t len) {
    const char *p = static_cast<const char *>(buf);
    while (len > 0) {
        ssize_t w = write(g_sock, p, len);
        if (w < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        p += w;
        len -= (size_t) w;
    }
    return true;
}

static void sink(void *, const char *piece, size_t len, int64_t t_ns) {
    char hdr[64];
    int n = snprintf(hdr, sizeof hdr, "T %lld %zu\n", (long long) t_ns, len);
    write_all(hdr, (size_t) n);
    write_all(piece, len);
}

static int connect_abstract(const char *name) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    size_t len = strlen(name);
    if (len > sizeof addr.sun_path - 1) len = sizeof addr.sun_path - 1;
    memcpy(addr.sun_path + 1, name, len);
    socklen_t alen = (socklen_t) (offsetof(sockaddr_un, sun_path) + 1 + len);
    if (connect(fd, (sockaddr *) &addr, alen) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

// Reads one message carrying the fd and at least the header line; returns the fd, and
// leaves everything read so far in `got`.
static int recv_fd_and_bytes(std::string &got) {
    char data[4096];
    iovec iov{data, sizeof data};
    union {
        char buf[CMSG_SPACE(sizeof(int))];
        cmsghdr align;
    } cbuf{};
    msghdr m{};
    m.msg_iov = &iov;
    m.msg_iovlen = 1;
    m.msg_control = cbuf.buf;
    m.msg_controllen = sizeof cbuf.buf;
    ssize_t n = recvmsg(g_sock, &m, MSG_CMSG_CLOEXEC);
    if (n <= 0) return -1;
    int mfd = -1;
    for (cmsghdr *c = CMSG_FIRSTHDR(&m); c; c = CMSG_NXTHDR(&m, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) memcpy(&mfd, CMSG_DATA(c), sizeof(int));
    }
    got.assign(data, (size_t) n);
    return mfd;
}

static bool read_more(std::string &got, size_t want) {
    char buf[4096];
    while (got.size() < want) {
        ssize_t r = read(g_sock, buf, sizeof buf);
        if (r <= 0) return false;
        got.append(buf, (size_t) r);
    }
    return true;
}

int main(int argc, char **argv) {
    if (argc < 3 || strcmp(argv[1], "--bench") != 0) {
        fprintf(stderr, "usage: %s --bench SOCKET_NAME\n", argv[0]);
        return 2;
    }
    std::string inherited = bench_affinity();
    int reset_err = -1;  // -1: not asked
    const char *reset = getenv("COGO_RESET_AFFINITY");
    if (reset && strcmp(reset, "1") == 0) reset_err = bench_reset_affinity();

    g_sock = connect_abstract(argv[2]);
    if (g_sock < 0) {
        fprintf(stderr, "connect: %s\n", strerror(errno));
        return 3;
    }
    char hello[64];
    int hl = snprintf(hello, sizeof hello, "H %d %lld\n", getpid(), (long long) bench_now_ns());
    write_all(hello, (size_t) hl);
    // Second line: the CPU mask inherited at exec, and the reset result (-1 not asked, 0 ok, errno).
    char aff[160];
    int al = snprintf(aff, sizeof aff, "A %s %d\n", inherited.c_str(), reset_err);
    write_all(aff, (size_t) al);

    std::string got;
    int mfd = recv_fd_and_bytes(got);
    if (mfd < 0) {
        fprintf(stderr, "no fd received\n");
        return 3;
    }
    while (got.find('\n') == std::string::npos) {
        if (!read_more(got, got.size() + 1)) return 3;
    }
    size_t nl = got.find('\n');
    int n_ctx = 0, n_threads = 0, n_predict = 0, stream = 0;
    size_t prompt_len = 0;
    if (sscanf(got.substr(0, nl).c_str(), "%d %d %d %d %zu", &n_ctx, &n_threads, &n_predict, &stream, &prompt_len) != 5) {
        fprintf(stderr, "bad header\n");
        return 3;
    }
    if (!read_more(got, nl + 1 + prompt_len)) return 3;
    std::string prompt = got.substr(nl + 1, prompt_len);

    char path[64];
    snprintf(path, sizeof path, "/proc/self/fd/%d", mfd);
    BenchParams p{path, n_ctx, n_threads, prompt.c_str(), n_predict};
    std::string stats = bench_run(p, stream ? sink : nullptr, nullptr);

    std::string line = "S " + stats + "\n";
    write_all(line.data(), line.size());
    close(mfd);
    close(g_sock);
    return 0;
}
