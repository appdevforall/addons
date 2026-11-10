/* fakeaff.c (host test only): LD_PRELOAD shim. sched_setaffinity fails with EINVAL when the requested mask contains the CPU named in FAKE_BAD_CPU
 * for a request of exactly that one CPU (simulates a core that core_control has isolated). Everything else is passed to the real function.
 * P1-03F: FAKE_BAD_FROM=a and FAKE_BAD_TO=b (optional) make only the matching calls number a..b-1 (counted from 0, per process) fail, so a CPU can
 * pin in one run and fail in another; without them every matching call fails, as before. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <sched.h>
#include <stdlib.h>
static volatile int calls;
/* P1-03J (P1-03I F07): FAKE_AFF_FAIL_GET=1 makes sched_getaffinity fail with EINVAL (with no readable /proc/cpuinfo this leaves the engine without a CPU list: exit 1). */
int sched_getaffinity(pid_t pid, size_t sz, cpu_set_t *mask) {
    const char *g = getenv("FAKE_AFF_FAIL_GET");
    if (g && *g == '1') { errno = EINVAL; return -1; }
    int (*real)(pid_t, size_t, cpu_set_t *) = dlsym(RTLD_NEXT, "sched_getaffinity");
    return real(pid, sz, mask);
}
int sched_setaffinity(pid_t pid, size_t sz, const cpu_set_t *mask) {
    const char *b = getenv("FAKE_BAD_CPU");
    if (b && *b && CPU_COUNT(mask) == 1 && CPU_ISSET(atoi(b), mask)) {   /* only a single-CPU request can be refused, like an isolated core */
        int n = __sync_fetch_and_add(&calls, 1);
        const char *f = getenv("FAKE_BAD_FROM"), *t = getenv("FAKE_BAD_TO");
        int from = f && *f ? atoi(f) : 0, to = t && *t ? atoi(t) : 0x7fffffff;
        if (n >= from && n < to) { errno = EINVAL; return -1; }
    }
    int (*real)(pid_t, size_t, const cpu_set_t *) = dlsym(RTLD_NEXT, "sched_setaffinity");
    return real(pid, sz, mask);
}
