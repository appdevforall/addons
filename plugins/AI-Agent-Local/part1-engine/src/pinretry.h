/* pinretry.h (ADFA-6876 kit v4d-5h, log-7 PLAN 16 action 2 item 1): the ONE shared in-binary pin helper of the kit binaries that pin themselves
 * (membw workers + membw --pace-chase/--pace-idle pacer kernels, c6cpu workers + --repack-time; gpubw, c6gpu, bsamp, bsamp5 never call sched_setaffinity).
 * Why: core_ctl (Qualcomm 6T/r8q) can re-isolate a core between lib/pinwait.sh's successful pin and the binary's own sched_setaffinity (K4d5g F07;
 * r8q cpu7 EINVAL again at spin+2 s). pin_retry() retries sched_setaffinity(0, {cpu}) every PIN_RETRY_STEP_MS (50 ms) for up to PIN_RETRY_MAX_MS (5 s);
 * between attempts the calling thread SPINS (busy loop, unpinned: it runs on any core its current mask allows) so the cluster sees load. No extra
 * load thread is started and nothing is held during the row (a held unpinned load could land on the target core and share it).
 * A pin counts only when the mask reads back as exactly {cpu} AND sched_getcpu() == cpu (one sched_yield allowed for the migration).
 * Failure after the deadline: the binary prints "# pin ... result=repin_failed" and exits PIN_RETRY_EXIT (87); lib/ilib.sh row_run labels the row
 * repin_failed (not a generic rc != 0 fail). Every pin prints one line (stdout, flushed):
 *   # pin who=<w> cpu=<c> attempts=<a> time_to_pin_ms=<t> result=pinned|repin_failed [err=EINVAL|mask_mismatch|not_on_cpu|<strerror>]
 * Host tests only: PINRETRY_STEP_MS / PINRETRY_MAX_MS environment overrides (tests/pinretry_test.c, tests/fakeaff.c LD_PRELOAD). */
#ifndef PINRETRY_H
#define PINRETRY_H
#include <errno.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define PIN_RETRY_EXIT 87
#define PIN_RETRY_STEP_MS 50
#define PIN_RETRY_MAX_MS 5000

typedef struct { int cpu, attempts, ok, err; double ms; } pin_res_t;

static uint64_t pin_mono_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec; }
static long pin_env_ms(const char *name, long dflt) { const char *v = getenv(name); if (!v || !*v) return dflt; long x = atol(v); return x > 0 ? x : dflt; }
/* last failure: EINVAL = sched_setaffinity refused (isolated/offline/not in the cpuset); mask_mismatch = mask did not read back as {cpu}; not_on_cpu = still elsewhere */
static const char *pin_errname(int e) { return e == EINVAL ? "EINVAL" : e == EAGAIN ? "mask_mismatch" : e == ESRCH ? "not_on_cpu" : strerror(e); }
static int pin_once(int cpu, int *err) {
    cpu_set_t set; CPU_ZERO(&set); CPU_SET(cpu, &set);
    if (sched_setaffinity(0, sizeof set, &set) != 0) { *err = errno; return 0; }
    cpu_set_t chk; CPU_ZERO(&chk);
    if (sched_getaffinity(0, sizeof chk, &chk) != 0 || CPU_COUNT(&chk) != 1 || !CPU_ISSET(cpu, &chk)) { *err = EAGAIN; return 0; }
    if (sched_getcpu() != cpu) { sched_yield(); if (sched_getcpu() != cpu) { *err = ESRCH; return 0; } }
    *err = 0; return 1;
}
/* pin the calling thread to cpu with retry; fills *r; returns 1 pinned / 0 repin_failed. who: label printed in the "# pin" line. */
static int pin_retry(int cpu, const char *who, pin_res_t *r) {
    long step = pin_env_ms("PINRETRY_STEP_MS", PIN_RETRY_STEP_MS), mx = pin_env_ms("PINRETRY_MAX_MS", PIN_RETRY_MAX_MS);
    uint64_t t0 = pin_mono_ns(), dl = t0 + (uint64_t)mx * 1000000ull; volatile uint64_t spin = 0; int err = 0;
    memset(r, 0, sizeof *r); r->cpu = cpu;
    for (;;) {
        uint64_t ta = pin_mono_ns(); r->attempts++;
        if (pin_once(cpu, &err)) { r->ok = 1; break; }
        if (ta >= dl) break;   /* the last attempt is made at the deadline: 0, 50, ..., 5000 ms = 101 attempts */
        uint64_t next = ta + (uint64_t)step * 1000000ull; if (next > dl) next = dl;
        while (pin_mono_ns() < next) spin++;   /* spin unpinned between attempts (load on any core); no sleep */
    }
    r->err = r->ok ? 0 : err; r->ms = (double)(pin_mono_ns() - t0) / 1e6;
    if (r->ok) printf("# pin who=%s cpu=%d attempts=%d time_to_pin_ms=%.1f result=pinned\n", who, cpu, r->attempts, r->ms);
    else printf("# pin who=%s cpu=%d attempts=%d time_to_pin_ms=%.1f result=repin_failed err=%s\n", who, cpu, r->attempts, r->ms, pin_errname(r->err));
    fflush(stdout); return r->ok;
}
#endif
