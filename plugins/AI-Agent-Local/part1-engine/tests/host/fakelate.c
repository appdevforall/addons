/* fakelate.c (host test only, P1-03J item 3): LD_PRELOAD shim that makes the read/reap race of P1-03I F01 certain.
 * FAKE_DLERROR_DELAY_MS=n: dlerror() sleeps n ms before it answers (the helper writes its dlerror text late).
 * FAKE_READ_DELAY_MS=n:    a read() that returns EAGAIN (the probe found the pipe empty) sleeps n ms before it returns (the probe is held between its read and its wait). */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdlib.h>
#include <unistd.h>
static char *(*real_dlerror)(void);
__attribute__((constructor)) static void late_init(void) { real_dlerror = dlsym(RTLD_NEXT, "dlerror"); }   /* resolved at start-up: a dlsym call after a failed dlopen would clear the error text */
char *dlerror(void) {
    const char *d = getenv("FAKE_DLERROR_DELAY_MS"); char *r = real_dlerror();
    if (d && *d) usleep((useconds_t)atoi(d) * 1000);
    return r;
}
ssize_t read(int fd, void *buf, size_t n) {
    ssize_t (*real)(int, void *, size_t) = dlsym(RTLD_NEXT, "read");
    ssize_t r = real(fd, buf, n);
    if (r < 0 && errno == EAGAIN) { int e = errno; const char *d = getenv("FAKE_READ_DELAY_MS"); if (d && *d) usleep((useconds_t)atoi(d) * 1000); errno = e; }
    return r;
}
ssize_t __read_chk(int fd, void *buf, size_t n, size_t buflen) {   /* the host compiler turns read() on a fixed-size buffer into this */
    (void)buflen; return read(fd, buf, n);
}
