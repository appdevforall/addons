/* fakeopen.c (host test only, P1-03H C2): LD_PRELOAD shim. Interposes fopen: when the path names a MIDR sysfs file (midr_el1) or the test's fake cpuinfo (cpuinfo-fake) and FAKE_OPEN_LOG is set,
 * one line "<path> cpu=<sched_getcpu> affinity=<CPUs in the affinity mask>" is appended to that file before the real fopen runs. It shows that the probe reads the MIDR while pinned on the CPU
 * (affinity=1, cpu=that CPU). Everything else is passed through. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
FILE *fopen(const char *path, const char *mode) {
    static FILE *(*real)(const char *, const char *);
    if (!real) real = dlsym(RTLD_NEXT, "fopen");
    const char *log = getenv("FAKE_OPEN_LOG");
    if (log && path && (strstr(path, "midr_el1") || strstr(path, "cpuinfo-fake"))) {
        cpu_set_t m; CPU_ZERO(&m); sched_getaffinity(0, sizeof m, &m);
        FILE *f = real(log, "a"); if (f) { fprintf(f, "%s cpu=%d affinity=%d\n", path, sched_getcpu(), CPU_COUNT(&m)); fclose(f); }
    }
    return real(path, mode);
}
