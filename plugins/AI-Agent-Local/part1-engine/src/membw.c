/* membw: memory bandwidth benchmark for the Pixel 6 Pro (ADFA-6890).
 * Every kernel is an asm volatile loop. GB/s = bytes moved / ns (1 GB = 1e9 B); read counts bytes read,
 * write counts bytes written, copy counts read + write. Theoretical peak 51.2 GB/s (LPDDR5-6400, 64-bit). */
#define _GNU_SOURCE
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <linux/perf_event.h>
#include <sys/syscall.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>
#include <sys/auxv.h>   /* PART1 (P1-03H C1): getauxval for --static-probe */
#ifdef __ANDROID__
#include <sys/system_properties.h>   /* PART1 (P1-03H C1): __system_property_get for the fixed property list of --static-probe */
#endif
#include "pinretry.h"   /* v4d-5h: shared pin-with-retry helper (PIN_RETRY_EXIT 87 = repin_failed) */
#include "part1_common.h"   /* PART1: gate, memory guard, time limit, JSON writer (new file) */

#define PEAK_GBS 51.2
#define MAXPASS 80000
#define MAXCPU 16
#define CACHED_MIN_MB 64
#define PASS_BYTES (256ull << 20)
#define WINDOW_NS 3000000000ull
#define MAXSAMP 400
#define WORDK 0x9E3779B97F4A7C15ull

typedef uint64_t (*kfn)(void *a, void *b, size_t n, size_t d);
typedef enum { READ, WRITE, COPY } kind_t;

#ifdef __aarch64__   /* PART1: kernel text below (to the matching #else) is the kit text, unchanged; the #else branch is the host test stub */
#define V_CLOB "v0","v1","v2","v3","v4","v5","v6","v7","v16","v17","v18","v19","v20","v21","v22","v23","v24","v25","v26","v27"
#define ZERO "movi v24.16b,#0\n movi v25.16b,#0\n movi v26.16b,#0\n movi v27.16b,#0\n"
#define FINISH "eor v24.16b,v24.16b,v25.16b\n eor v26.16b,v26.16b,v27.16b\n eor v24.16b,v24.16b,v26.16b\n mov %[r0],v24.d[0]\n mov %[r1],v24.d[1]\n"
#define EOR16 \
 "eor v24.16b,v24.16b,v0.16b\n eor v25.16b,v25.16b,v1.16b\n eor v26.16b,v26.16b,v2.16b\n eor v27.16b,v27.16b,v3.16b\n" \
 "eor v24.16b,v24.16b,v4.16b\n eor v25.16b,v25.16b,v5.16b\n eor v26.16b,v26.16b,v6.16b\n eor v27.16b,v27.16b,v7.16b\n" \
 "eor v24.16b,v24.16b,v16.16b\n eor v25.16b,v25.16b,v17.16b\n eor v26.16b,v26.16b,v18.16b\n eor v27.16b,v27.16b,v19.16b\n" \
 "eor v24.16b,v24.16b,v20.16b\n eor v25.16b,v25.16b,v21.16b\n eor v26.16b,v26.16b,v22.16b\n eor v27.16b,v27.16b,v23.16b\n"
#define LDP8(op, base) \
 op " q0,q1,[" base "]\n " op " q2,q3,[" base ",#32]\n " op " q4,q5,[" base ",#64]\n " op " q6,q7,[" base ",#96]\n" \
 op " q16,q17,[" base ",#128]\n " op " q18,q19,[" base ",#160]\n " op " q20,q21,[" base ",#192]\n " op " q22,q23,[" base ",#224]\n"
#define STP8(op, base) \
 op " q0,q1,[" base "]\n " op " q2,q3,[" base ",#32]\n " op " q4,q5,[" base ",#64]\n " op " q6,q7,[" base ",#96]\n" \
 op " q16,q17,[" base ",#128]\n " op " q18,q19,[" base ",#160]\n " op " q20,q21,[" base ",#192]\n " op " q22,q23,[" base ",#224]\n"

/* K01: scalar 64-bit loads, 64 B per iteration */
static uint64_t k01(void *a, void *b, size_t n, size_t d) {
    (void)b; (void)d; const uint8_t *p = a; uint64_t a0 = 0, a1 = 0;
    asm volatile("1:\n"
        "ldr x4,[%[p]],#8\n ldr x5,[%[p]],#8\n ldr x6,[%[p]],#8\n ldr x7,[%[p]],#8\n"
        "ldr x8,[%[p]],#8\n ldr x9,[%[p]],#8\n ldr x10,[%[p]],#8\n ldr x11,[%[p]],#8\n"
        "eor %[a0],%[a0],x4\n eor %[a1],%[a1],x5\n eor %[a0],%[a0],x6\n eor %[a1],%[a1],x7\n"
        "eor %[a0],%[a0],x8\n eor %[a1],%[a1],x9\n eor %[a0],%[a0],x10\n eor %[a1],%[a1],x11\n"
        "subs %[n],%[n],#64\n b.ne 1b\n"
        : [p] "+r"(p), [n] "+r"(n), [a0] "+r"(a0), [a1] "+r"(a1) : : "x4","x5","x6","x7","x8","x9","x10","x11","cc","memory");
    return a0 ^ a1;
}
/* K02: ldp q,q pairs, 256 B per iteration */
static uint64_t k02(void *a, void *b, size_t n, size_t d) {
    (void)b; (void)d; const uint8_t *p = a; uint64_t r0, r1;
    asm volatile(ZERO "1:\n" LDP8("ldp", "%[p]") "add %[p],%[p],#256\n" EOR16 "subs %[n],%[n],#256\n b.ne 1b\n" FINISH
        : [p] "+r"(p), [n] "+r"(n), [r0] "=&r"(r0), [r1] "=&r"(r1) : : V_CLOB, "cc", "memory");
    return r0 ^ r1;
}
/* K03: ld1 {4 regs} post-increment, 64 B per instruction, 256 B per iteration */
static uint64_t k03(void *a, void *b, size_t n, size_t d) {
    (void)b; (void)d; const uint8_t *p = a; uint64_t r0, r1;
    asm volatile(ZERO "1:\n"
        "ld1 {v0.16b-v3.16b},[%[p]],#64\n ld1 {v4.16b-v7.16b},[%[p]],#64\n ld1 {v16.16b-v19.16b},[%[p]],#64\n ld1 {v20.16b-v23.16b},[%[p]],#64\n"
        EOR16 "subs %[n],%[n],#256\n b.ne 1b\n" FINISH
        : [p] "+r"(p), [n] "+r"(n), [r0] "=&r"(r0), [r1] "=&r"(r1) : : V_CLOB, "cc", "memory");
    return r0 ^ r1;
}
/* K04: K02 plus one prfm pldl2strm per 64 B, distance d bytes ahead */
static uint64_t k04(void *a, void *b, size_t n, size_t d) {
    (void)b; const uint8_t *p = a; uint64_t r0, r1; size_t d0 = d, d1 = d + 64, d2 = d + 128, d3 = d + 192;
    asm volatile(ZERO "1:\n" LDP8("ldp", "%[p]")
        "prfm pldl2strm,[%[p],%[d0]]\n prfm pldl2strm,[%[p],%[d1]]\n prfm pldl2strm,[%[p],%[d2]]\n prfm pldl2strm,[%[p],%[d3]]\n"
        "add %[p],%[p],#256\n" EOR16 "subs %[n],%[n],#256\n b.ne 1b\n" FINISH
        : [p] "+r"(p), [n] "+r"(n), [r0] "=&r"(r0), [r1] "=&r"(r1) : [d0] "r"(d0), [d1] "r"(d1), [d2] "r"(d2), [d3] "r"(d3) : V_CLOB, "cc", "memory");
    return r0 ^ r1;
}
/* K05: ldnp (non-temporal pairs) */
static uint64_t k05(void *a, void *b, size_t n, size_t d) {
    (void)b; (void)d; const uint8_t *p = a; uint64_t r0, r1;
    asm volatile(ZERO "1:\n" LDP8("ldnp", "%[p]") "add %[p],%[p],#256\n" EOR16 "subs %[n],%[n],#256\n b.ne 1b\n" FINISH
        : [p] "+r"(p), [n] "+r"(n), [r0] "=&r"(r0), [r1] "=&r"(r1) : : V_CLOB, "cc", "memory");
    return r0 ^ r1;
}
/* K06: two independent streams (front half and back half of the buffer), prfm on both, 128 B per stream per iteration */
static uint64_t k06(void *a, void *b, size_t n, size_t d) {
    (void)b; const uint8_t *p = a, *q = (const uint8_t *)a + n / 2; uint64_t r0, r1; size_t d0 = d, d1 = d + 64;
    asm volatile(ZERO "1:\n"
        "ldp q0,q1,[%[p]]\n ldp q2,q3,[%[p],#32]\n ldp q4,q5,[%[p],#64]\n ldp q6,q7,[%[p],#96]\n"
        "ldp q16,q17,[%[q]]\n ldp q18,q19,[%[q],#32]\n ldp q20,q21,[%[q],#64]\n ldp q22,q23,[%[q],#96]\n"
        "prfm pldl2strm,[%[p],%[d0]]\n prfm pldl2strm,[%[p],%[d1]]\n prfm pldl2strm,[%[q],%[d0]]\n prfm pldl2strm,[%[q],%[d1]]\n"
        "add %[p],%[p],#128\n add %[q],%[q],#128\n" EOR16 "subs %[n],%[n],#256\n b.ne 1b\n" FINISH
        : [p] "+r"(p), [q] "+r"(q), [n] "+r"(n), [r0] "=&r"(r0), [r1] "=&r"(r1) : [d0] "r"(d0), [d1] "r"(d1) : V_CLOB, "cc", "memory");
    return r0 ^ r1;
}
#define SETPAT "dup v0.16b,%w[pat]\n mov v1.16b,v0.16b\n mov v2.16b,v0.16b\n mov v3.16b,v0.16b\n mov v4.16b,v0.16b\n mov v5.16b,v0.16b\n mov v6.16b,v0.16b\n mov v7.16b,v0.16b\n" \
 "mov v16.16b,v0.16b\n mov v17.16b,v0.16b\n mov v18.16b,v0.16b\n mov v19.16b,v0.16b\n mov v20.16b,v0.16b\n mov v21.16b,v0.16b\n mov v22.16b,v0.16b\n mov v23.16b,v0.16b\n"
/* K07: stp q,q (write-allocate / RFO expected); d carries the pattern byte of this pass */
static uint64_t k07(void *a, void *b, size_t n, size_t d) {
    (void)b; uint8_t *p = a; uint32_t pat = (uint32_t)d & 0xFF;
    asm volatile(SETPAT "1:\n" STP8("stp", "%[p]") "add %[p],%[p],#256\n subs %[n],%[n],#256\n b.ne 1b\n"
        : [p] "+r"(p), [n] "+r"(n) : [pat] "r"(pat) : V_CLOB, "cc", "memory");
    return 0;
}
/* K08: stnp (non-temporal store pairs) */
static uint64_t k08(void *a, void *b, size_t n, size_t d) {
    (void)b; uint8_t *p = a; uint32_t pat = (uint32_t)d & 0xFF;
    asm volatile(SETPAT "1:\n" STP8("stnp", "%[p]") "add %[p],%[p],#256\n subs %[n],%[n],#256\n b.ne 1b\n"
        : [p] "+r"(p), [n] "+r"(n) : [pat] "r"(pat) : V_CLOB, "cc", "memory");
    return 0;
}
/* K09: dc zva, 64 B blocks (zero-fill, no read-for-ownership) */
static uint64_t k09(void *a, void *b, size_t n, size_t d) {
    (void)b; (void)d; uint8_t *p = a; uint8_t *q;
    asm volatile("1:\n dc zva,%[p]\n add %[q],%[p],#64\n dc zva,%[q]\n add %[q],%[p],#128\n dc zva,%[q]\n add %[q],%[p],#192\n dc zva,%[q]\n"
        "add %[p],%[p],#256\n subs %[n],%[n],#256\n b.ne 1b\n"
        : [p] "+r"(p), [n] "+r"(n), [q] "=&r"(q) : : "cc", "memory");
    return 0;
}
/* K10: ldp/stp copy, 256 B per iteration; a = src, b = dst, n = bytes copied */
static uint64_t k10(void *a, void *b, size_t n, size_t d) {
    (void)d; const uint8_t *s = a; uint8_t *t = b;
    asm volatile("1:\n" LDP8("ldp", "%[s]") STP8("stp", "%[t]") "add %[s],%[s],#256\n add %[t],%[t],#256\n subs %[n],%[n],#256\n b.ne 1b\n"
        : [s] "+r"(s), [t] "+r"(t), [n] "+r"(n) : : V_CLOB, "cc", "memory");
    return 0;
}
/* K11: ldp load, stnp store, prfm on the source, distance d */
static uint64_t k11(void *a, void *b, size_t n, size_t d) {
    const uint8_t *s = a; uint8_t *t = b; size_t d0 = d, d1 = d + 64, d2 = d + 128, d3 = d + 192;
    asm volatile("1:\n" LDP8("ldp", "%[s]")
        "prfm pldl2strm,[%[s],%[d0]]\n prfm pldl2strm,[%[s],%[d1]]\n prfm pldl2strm,[%[s],%[d2]]\n prfm pldl2strm,[%[s],%[d3]]\n"
        STP8("stnp", "%[t]") "add %[s],%[s],#256\n add %[t],%[t],#256\n subs %[n],%[n],#256\n b.ne 1b\n"
        : [s] "+r"(s), [t] "+r"(t), [n] "+r"(n) : [d0] "r"(d0), [d1] "r"(d1), [d2] "r"(d2), [d3] "r"(d3) : V_CLOB, "cc", "memory");
    return 0;
}

/* K12: four independent streams (quarters of the buffer), 64 B per stream per iteration, interleaved loads, no prefetch */
static uint64_t k12(void *a, void *b, size_t n, size_t d) {
    (void)b; (void)d; const uint8_t *p0 = a, *p1 = p0 + n / 4, *p2 = p0 + n / 2, *p3 = p0 + 3 * (n / 4); uint64_t r0, r1;
    asm volatile(ZERO "1:\n"
        "ldp q0,q1,[%[p0]]\n ldp q4,q5,[%[p1]]\n ldp q16,q17,[%[p2]]\n ldp q20,q21,[%[p3]]\n"
        "ldp q2,q3,[%[p0],#32]\n ldp q6,q7,[%[p1],#32]\n ldp q18,q19,[%[p2],#32]\n ldp q22,q23,[%[p3],#32]\n"
        "add %[p0],%[p0],#64\n add %[p1],%[p1],#64\n add %[p2],%[p2],#64\n add %[p3],%[p3],#64\n" EOR16 "subs %[n],%[n],#256\n b.ne 1b\n" FINISH
        : [p0] "+r"(p0), [p1] "+r"(p1), [p2] "+r"(p2), [p3] "+r"(p3), [n] "+r"(n), [r0] "=&r"(r0), [r1] "=&r"(r1) : : V_CLOB, "cc", "memory");
    return r0 ^ r1;
}

#else
#include "host_stub.h"   /* PART1: host-test kernels (not compiled for Android) */
#endif

typedef struct { const char *id, *name; kfn fn; kind_t kind; int pf; size_t defdist; int zva; uint8_t expect; } kern_t;
static const kern_t KERN[] = {
    {"K01", "read_scalar", k01, READ, 0, 0, 0, 0}, {"K02", "read_ldp", k02, READ, 0, 0, 0, 0},
    {"K03", "read_ld1x4", k03, READ, 0, 0, 0, 0}, {"K04", "read_ldp_pf", k04, READ, 1, 1024, 0, 0},
    {"K05", "read_ldnp", k05, READ, 0, 0, 0, 0}, {"K06", "read_2stream", k06, READ, 1, 1024, 0, 0},
    {"K07", "write_stp", k07, WRITE, 0, 0, 0, 0}, {"K08", "write_stnp", k08, WRITE, 0, 0, 0, 0},
    {"K09", "write_dczva", k09, WRITE, 0, 0, 1, 0x00}, {"K10", "copy_ldp_stp", k10, COPY, 0, 0, 0, 0},
    {"K11", "copy_ldp_stnp_pf", k11, COPY, 1, 1024, 0, 0}, {"K12", "read_ldp_4stream", k12, READ, 0, 0, 0, 0}};
#define NKERN (int)(sizeof KERN / sizeof KERN[0])

static uint64_t now_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000ull + t.tv_nsec; }
static volatile uint64_t g_sink;

/* ---- CPU classes from /proc/cpuinfo part numbers (A55 0xd05, A76 0xd0b, X1 0xd44) ---- */
typedef struct { int n; int cpu[MAXCPU]; } cpulist_t;
static cpulist_t C_LITTLE, C_MID, C_BIG, C_ALL;
static int detect_cpus(void) {
    FILE *f = fopen("/proc/cpuinfo", "r"); if (!f) return -1; char line[256]; int cur = -1;
    while (fgets(line, sizeof line, f)) {
        if (!strncmp(line, "processor", 9)) cur = atoi(strchr(line, ':') + 1);
        else if (!strncmp(line, "CPU part", 8) && cur >= 0 && cur < MAXCPU) {
            unsigned part = (unsigned)strtoul(strchr(line, ':') + 1, NULL, 16);
            cpulist_t *c = part == 0xd05 ? &C_LITTLE : part == 0xd0b ? &C_MID : part == 0xd44 ? &C_BIG : NULL;
            if (c) c->cpu[c->n++] = cur;
            C_ALL.cpu[C_ALL.n++] = cur;
        }
    }
    fclose(f); return C_ALL.n;
}
/* PART1: when /proc/cpuinfo yields no "CPU part" lines (non-ARM host test, or a restricted view) C_ALL falls back to the affinity mask; the class lists stay empty. */
static int detect_cpus_p1(void) {
    int n = detect_cpus(); if (n >= 1) return n;
    cpu_set_t m; CPU_ZERO(&m); if (sched_getaffinity(0, sizeof m, &m) != 0) return n;
    C_ALL.n = 0; for (int i = 0; i < MAXCPU && i < CPU_SETSIZE; i++) if (CPU_ISSET(i, &m)) C_ALL.cpu[C_ALL.n++] = i;
    return C_ALL.n;
}
static int parse_cores(const char *s, cpulist_t *out) {
    out->n = 0;
    if (!strcmp(s, "all")) { *out = C_ALL; return out->n ? 0 : -1; }
    if (!strcmp(s, "big")) { *out = C_BIG; return out->n ? 0 : -1; }
    if (!strcmp(s, "mid")) { *out = C_MID; return out->n ? 0 : -1; }
    if (!strcmp(s, "little")) { *out = C_LITTLE; return out->n ? 0 : -1; }
    char *dup = strdup(s), *tok = dup, *e;
    while (*tok) { long v = strtol(tok, &e, 10); if (e == tok || v < 0 || v >= MAXCPU || out->n >= MAXCPU) { free(dup); return -1; }
        out->cpu[out->n++] = (int)v; tok = e; while (*tok == ',' || *tok == '+') tok++; }
    free(dup); return out->n ? 0 : -1;
}
static void fmt_cores(const cpulist_t *c, int threads, char *buf, size_t sz) {
    buf[0] = 0; for (int i = 0; i < threads; i++) { char t[8]; snprintf(t, sizeof t, i ? "+%d" : "%d", c->cpu[i]); strncat(buf, t, sz - strlen(buf) - 1); }
}

/* ---- one configuration ---- */
/* cfg_t.k2 (--split): thread 1 runs k2 instead of k; the threads then run freely for the whole window (no per-pass barriers)
 * so that the two streams overlap, and each thread reports its own rate. */
typedef struct { const kern_t *k, *k2; int threads; cpulist_t cores; size_t size; size_t dist; double warm_s, settle_s; int iters; uint64_t window_ns; } cfg_t;
typedef struct { double med_ns, best_ns, bytes, gb_med, gb_best, pct; long minflt; int ok; int passes; char err[160];
    double t_med_ns[MAXCPU], t_best_ns[MAXCPU], t_bytes; long t_minflt[MAXCPU]; int t_passes[MAXCPU]; } res_t;
typedef struct { cfg_t *c; pthread_barrier_t bar; volatile int cont, fail; char err[160]; uint64_t t_begin; uint64_t (*t0)[MAXPASS], (*t1)[MAXPASS]; long *minflt; int passes_done; int npass[MAXCPU]; } shared_t;
typedef struct { shared_t *S; int idx; } targ_t;

static const char *g_tag = "", *g_timeline = NULL, *g_cpufreq = NULL;   /* PART1: g_heap (--map, /dev/dma_heap) removed: no dma_heap dependency */
static int g_allow_unpinned;   /* PART1: --allow-unpinned */
static pin_res_t g_pr[MAXCPU]; static int g_pr_ok[MAXCPU], g_pr_set[MAXCPU];   /* PART1: per-thread pin result, for the output */
static int g_runno;
static volatile int g_cpu_failed[MAXCPU];   /* PART1 (review F03, F06): per CPU, for the whole process: pin_retry failed under --allow-unpinned; the CPU is not retried again (at most one 5 s wait per CPU per process) */
static int g_pin_any, g_pin_unpinned, g_pin_att; static double g_pin_ms;   /* PART1: pin facts accumulated over every run and sweep size of the process */
static volatile int g_pinfail;   /* v4d-5h: a worker could not pin within the retry window -> exit PIN_RETRY_EXIT */

static void failmsg(shared_t *S, const char *fmt, int a, int b) { if (!S->fail) { snprintf(S->err, sizeof S->err, fmt, a, b); S->fail = 1; } }
static long thread_minflt(void) { struct rusage ru; getrusage(RUSAGE_THREAD, &ru); return ru.ru_minflt; }
static uint64_t clock_ns(clockid_t id) { struct timespec t; clock_gettime(id, &t); return (uint64_t)t.tv_sec * 1000000000ull + t.tv_nsec; }

/* ---- cpufreq sampler (--cpufreq-log): scaling_cur_freq of cpu 0, 4, 6, 7 every 500 ms, on the first A55 ---- */
static const int SAMPCPU[4] = {0, 4, 6, 7};
typedef struct { volatile int stop; int n; uint64_t mono[MAXSAMP]; long khz[MAXSAMP][4]; } samp_t;
static void *sampler(void *arg) {
    samp_t *s = arg; int fd[4];
    if (C_LITTLE.n) { cpu_set_t set; CPU_ZERO(&set); CPU_SET(C_LITTLE.cpu[0], &set); sched_setaffinity(0, sizeof set, &set); }
    for (int i = 0; i < 4; i++) { char p[96]; snprintf(p, sizeof p, "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq", SAMPCPU[i]); fd[i] = open(p, O_RDONLY); }
    while (!s->stop && s->n < MAXSAMP) {
        uint64_t next = clock_ns(CLOCK_MONOTONIC) + 500000000ull; s->mono[s->n] = clock_ns(CLOCK_MONOTONIC);
        for (int i = 0; i < 4; i++) { char b[32]; ssize_t r = fd[i] >= 0 ? pread(fd[i], b, sizeof b - 1, 0) : -1; if (r > 0) { b[r] = 0; s->khz[s->n][i] = atol(b); } else s->khz[s->n][i] = -1; }
        s->n++;
        while (!s->stop && clock_ns(CLOCK_MONOTONIC) < next) usleep(5000);
    }
    for (int i = 0; i < 4; i++) if (fd[i] >= 0) close(fd[i]);
    return NULL;
}

/* ---- F01 guards: per-pass write pattern, stamped lines, post-pass line checks ---- */
static inline uint8_t pass_pat(long p) { return (uint8_t)(0xA5 ^ (p & 0xFF)); }   /* p = -1 (verification pass and warm-up) gives 0x5A */
static size_t wr_offs(size_t sz, int i) { size_t o[3] = {0, (sz / 2) & ~63ul, sz - 64}; return o[i]; }
static size_t cp_offs(size_t sz, int i) { size_t h = sz / 2, o[3] = {0, (h / 2) & ~63ul, h - 64}; return o[i]; }
/* outside the timed window: put 0xFF into the lines the post-check reads (zva write: the buffer lines; copy: the destination lines) so a pass that does nothing is caught */
static void stamp_lines(const kern_t *k, uint8_t *buf, size_t sz) {
    if (k->kind == WRITE && k->zva) for (int i = 0; i < 3; i++) memset(buf + wr_offs(sz, i), 0xFF, 64);
    else if (k->kind == COPY) for (int i = 0; i < 3; i++) memset(buf + sz / 2 + cp_offs(sz, i), 0xFF, 64);
}
static int lines_ok(const kern_t *k, const uint8_t *buf, size_t sz, uint8_t pat) {
    if (k->kind == WRITE) { uint8_t want = k->zva ? 0 : pat; for (int i = 0; i < 3; i++) for (int j = 0; j < 64; j++) if (buf[wr_offs(sz, i) + j] != want) return 0; }
    else if (k->kind == COPY) for (int i = 0; i < 3; i++) if (memcmp(buf + cp_offs(sz, i), buf + sz / 2 + cp_offs(sz, i), 64)) return 0;
    return 1;
}

static void *worker(void *arg) {
    targ_t *ta = arg; shared_t *S = ta->S; cfg_t *c = S->c; int idx = ta->idx, cpu = c->cores.cpu[idx];
    const kern_t *k = (idx == 1 && c->k2) ? c->k2 : c->k;
    { char who[24]; pin_res_t pr; snprintf(who, sizeof who, "membw-t%d", idx);   /* v4d-5h: pin with retry (50 ms x 5 s, spin between attempts); mask and cpu verified */
      int pok, skipped = g_allow_unpinned && cpu >= 0 && cpu < MAXCPU && g_cpu_failed[cpu];   /* PART1: a CPU that already failed pin_retry in this process is not retried */
      if (skipped) { memset(&pr, 0, sizeof pr); pr.cpu = cpu; pok = 0; }
      else pok = pin_retry(cpu, who, &pr);
      g_pr[idx] = pr; g_pr_ok[idx] = pok; g_pr_set[idx] = 1;   /* PART1: keep the result for the output */
      if (!pok && g_allow_unpinned) { if (cpu >= 0 && cpu < MAXCPU) g_cpu_failed[cpu] = 1;
          printf(skipped ? "# pin: unpinned who=%s cpu=%d (--allow-unpinned: this CPU already failed pin_retry in this process, not retried; the row runs unpinned)\n" : "# pin: unpinned who=%s cpu=%d (--allow-unpinned: the row runs unpinned)\n", who, cpu), fflush(stdout); }
      else if (!pok) { g_pinfail = 1; failmsg(S, "thread %d: sched_setaffinity(cpu%d) failed after pin retry (repin_failed)", idx, cpu); } }
    size_t sz = c->size, al = 2u << 20, maplen = sz + al; uint8_t *raw, *buf = NULL; int dfd = -1;
    {   /* PART1: the kit's dma-buf heap branch (--map uncached|system) is removed; the anonymous-memory branch below is unchanged */
        raw = mmap(NULL, sz + al, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (raw == MAP_FAILED) failmsg(S, "thread %d: mmap failed (cpu%d)", idx, cpu);
        else { buf = (uint8_t *)(((uintptr_t)raw + al - 1) & ~(uintptr_t)(al - 1)); madvise(buf, sz, MADV_HUGEPAGE); }
    }
    uint64_t expect = 0; size_t dist = c->dist;
    if (buf && !S->fail) {
        uint64_t *w = (uint64_t *)buf; size_t nw = sz / 8;
        if (k->kind == READ) { for (size_t i = 0; i < nw; i++) { w[i] = (i + 1) * WORDK; expect ^= w[i]; } }
        else if (k->kind == WRITE) memset(buf, 0x00, sz);
        else { for (size_t i = 0; i < nw / 2; i++) w[i] = (i + 1) * WORDK; memset(buf + sz / 2, 0, sz / 2); }
        size_t n = k->kind == COPY ? sz / 2 : sz;
        stamp_lines(k, buf, sz);
        uint64_t acc = k->fn(buf, buf + sz / 2, n, k->kind == WRITE ? (size_t)pass_pat(-1) : dist);   /* untimed verification pass (also a second pre-fault pass); write pattern 0x5A */
        if (k->kind == READ && acc != expect) failmsg(S, "thread %d: read guard mismatch (cpu%d)", idx, cpu);
        if (!lines_ok(k, buf, sz, pass_pat(-1))) failmsg(S, "thread %d: verification-pass spot-check failed (cpu%d)", idx, cpu);
    }
    pthread_barrier_wait(&S->bar);
    if (S->fail) { if (raw != MAP_FAILED) munmap(raw, maplen); if (dfd >= 0) close(dfd); return NULL; }
    if (c->settle_s > 0) usleep((useconds_t)(c->settle_s * 1e6));
    size_t n = k->kind == COPY ? sz / 2 : sz, loops = sz < PASS_BYTES ? PASS_BYTES / sz : 1; uint64_t acc = 0;
    if (g_pr_ok[idx] && sched_getcpu() != cpu) failmsg(S, "thread %d: not running on cpu%d", idx, cpu);   /* PART1: only for a thread that pinned */
    pthread_barrier_wait(&S->bar);
    if (c->warm_s > 0) { uint64_t dl = now_ns() + (uint64_t)(c->warm_s * 1e9); while (now_ns() < dl) acc ^= k->fn(buf, buf + sz / 2, n, k->kind == WRITE ? (size_t)pass_pat(-1) : dist); }
    pthread_barrier_wait(&S->bar);
    long mf0 = thread_minflt(); int passes = 0; if (idx == 0) S->t_begin = now_ns();
    if (c->k2) {   /* split: free-running streams over a common window */
        pthread_barrier_wait(&S->bar);
        uint64_t dl = S->t_begin + c->window_ns;
        while (passes < MAXPASS) {
            if (now_ns() >= dl) break;
            stamp_lines(k, buf, sz); size_t darg = k->kind == WRITE ? (size_t)pass_pat(passes) : dist;
            uint64_t t0 = now_ns();
            for (size_t l = 0; l < loops; l++) acc ^= k->fn(buf, buf + sz / 2, n, darg);
            uint64_t t1 = now_ns(); S->t0[idx][passes] = t0; S->t1[idx][passes] = t1; passes++;
        }
        S->npass[idx] = passes;
    } else for (;;) {
        if (idx == 0) { uint64_t el = now_ns() - S->t_begin; S->cont = (c->iters > 0 ? passes < c->iters : el < c->window_ns) && passes < MAXPASS; }
        pthread_barrier_wait(&S->bar);
        if (!S->cont) break;
        stamp_lines(k, buf, sz); size_t darg = k->kind == WRITE ? (size_t)pass_pat(passes) : dist;   /* outside the timed window */
        uint64_t t0 = now_ns(); for (size_t l = 0; l < loops; l++) acc ^= k->fn(buf, buf + sz / 2, n, darg); uint64_t t1 = now_ns();
        S->t0[idx][passes] = t0; S->t1[idx][passes] = t1; passes++;
        pthread_barrier_wait(&S->bar);
    }
    S->minflt[idx] = thread_minflt() - mf0; if (idx == 0) S->passes_done = passes; S->npass[idx] = passes; g_sink ^= acc;
    /* guard on the LAST timed pass: write lines hold that pass's pattern (zva: zero after the 0xFF stamp), copy destination lines equal the source after the 0xFF stamp */
    if (!lines_ok(k, buf, sz, passes > 0 ? pass_pat(passes - 1) : pass_pat(-1))) failmsg(S, k->kind == WRITE ? "thread %d: write spot-check failed (cpu%d)" : "thread %d: copy spot-check failed (cpu%d)", idx, cpu);
    munmap(raw, maplen); if (dfd >= 0) close(dfd); return NULL;
}
static int cmp_u64(const void *a, const void *b) { uint64_t x = *(const uint64_t *)a, y = *(const uint64_t *)b; return x < y ? -1 : x > y; }

static void write_timeline(const cfg_t *c, const shared_t *S, int np, const uint64_t *start, const uint64_t *dur, double bytes) {
    uint64_t boot_off = clock_ns(CLOCK_BOOTTIME) - clock_ns(CLOCK_MONOTONIC), wall_off = clock_ns(CLOCK_REALTIME) - clock_ns(CLOCK_MONOTONIC);
    if (g_timeline) { int hdr = access(g_timeline, F_OK) != 0; FILE *f = fopen(g_timeline, "a");
        if (f) { if (hdr) fputs("run,pass,mono_ns,boot_ns,t_s,wall_unix_ms,gbs\n", f);
            for (int p = 0; p < np; p++) fprintf(f, "%s%d,%d,%llu,%llu,%.4f,%llu,%.3f\n", g_tag, g_runno, p, (unsigned long long)start[p], (unsigned long long)(start[p] + boot_off),
                (double)(start[p] - S->t_begin) / 1e9, (unsigned long long)((start[p] + wall_off) / 1000000ull), bytes / (double)dur[p]);
            fclose(f); } else perror(g_timeline); }
    (void)c;
}
static void write_cpufreq(const shared_t *S, const samp_t *sm) {
    uint64_t boot_off = clock_ns(CLOCK_BOOTTIME) - clock_ns(CLOCK_MONOTONIC);
    int hdr = access(g_cpufreq, F_OK) != 0; FILE *f = fopen(g_cpufreq, "a"); if (!f) { perror(g_cpufreq); return; }
    if (hdr) fputs("run,mono_ns,boot_ns,t_s,cpu0_khz,cpu4_khz,cpu6_khz,cpu7_khz\n", f);
    for (int i = 0; i < sm->n; i++) fprintf(f, "%s%d,%llu,%llu,%.3f,%ld,%ld,%ld,%ld\n", g_tag, g_runno, (unsigned long long)sm->mono[i], (unsigned long long)(sm->mono[i] + boot_off),
        ((double)sm->mono[i] - (double)S->t_begin) / 1e9, sm->khz[i][0], sm->khz[i][1], sm->khz[i][2], sm->khz[i][3]);
    fclose(f);
}

static res_t run_config(cfg_t *c) {
    res_t r; memset(&r, 0, sizeof r); shared_t S; memset(&S, 0, sizeof S); S.c = c;
    S.t0 = calloc(c->threads, sizeof *S.t0); S.t1 = calloc(c->threads, sizeof *S.t1); S.minflt = calloc(c->threads, sizeof(long));
    pthread_barrier_init(&S.bar, NULL, c->threads);
    pthread_t th[MAXCPU], sth; targ_t ta[MAXCPU]; samp_t *sm = NULL;
    if (g_cpufreq) { sm = calloc(1, sizeof *sm); pthread_create(&sth, NULL, sampler, sm); }
    for (int i = 0; i < c->threads; i++) { ta[i].S = &S; ta[i].idx = i; pthread_create(&th[i], NULL, worker, &ta[i]); }
    for (int i = 0; i < c->threads; i++) pthread_join(th[i], NULL);
    if (sm) { sm->stop = 1; pthread_join(sth, NULL); }
    if (S.fail) { snprintf(r.err, sizeof r.err, "%s", S.err); goto out; }
    size_t loops = c->size < PASS_BYTES ? PASS_BYTES / c->size : 1; double pbytes = (double)c->size * loops;
    if (c->k2) {   /* split: every thread has its own pass list; rates are per stream */
        for (int t = 0; t < c->threads; t++) {
            int np = S.npass[t]; if (np < 1) { snprintf(r.err, sizeof r.err, "no timed passes on thread %d", t); goto out; }
            uint64_t *d = malloc(np * sizeof *d); for (int p = 0; p < np; p++) d[p] = S.t1[t][p] - S.t0[t][p];
            qsort(d, np, sizeof *d, cmp_u64); r.t_med_ns[t] = (double)d[np / 2]; r.t_best_ns[t] = (double)d[0]; r.t_passes[t] = np; r.t_minflt[t] = S.minflt[t]; free(d);
        }
        r.t_bytes = pbytes; r.ok = 1; goto out;
    }
    int np = S.passes_done; if (np < 1) { snprintf(r.err, sizeof r.err, "no timed passes"); goto out; }
    uint64_t *dur = malloc(np * sizeof *dur), *dsort = malloc(np * sizeof *dsort), *start = malloc(np * sizeof *start);
    for (int p = 0; p < np; p++) { uint64_t mn = ~0ull, mx = 0; for (int t = 0; t < c->threads; t++) { if (S.t0[t][p] < mn) mn = S.t0[t][p]; if (S.t1[t][p] > mx) mx = S.t1[t][p]; } dur[p] = mx - mn; start[p] = mn; dsort[p] = dur[p]; }
    qsort(dsort, np, sizeof *dsort, cmp_u64);
    r.bytes = (double)c->threads * pbytes; r.med_ns = (double)dsort[np / 2]; r.best_ns = (double)dsort[0]; r.passes = np;
    r.gb_med = r.bytes / r.med_ns; r.gb_best = r.bytes / r.best_ns; r.pct = 100.0 * r.gb_med / PEAK_GBS; r.ok = 1;
    for (int t = 0; t < c->threads; t++) r.minflt += S.minflt[t];
    if (g_timeline) write_timeline(c, &S, np, start, dur, r.bytes);
    free(dur); free(dsort); free(start);
out:
    if (sm) { if (g_cpufreq) write_cpufreq(&S, sm); free(sm); }
    pthread_barrier_destroy(&S.bar); free(S.t0); free(S.t1); free(S.minflt); return r;
}

#ifdef __aarch64__
static int zva_ok(void) { uint64_t z; asm volatile("mrs %0, dczid_el0" : "=r"(z)); return !(z & 16) && (4u << (z & 15)) == 64; }
#else
static int zva_ok(void) { return 1; }
#endif
static const kern_t *find_kernel(const char *s) { for (int i = 0; i < NKERN; i++) if (!strcasecmp(s, KERN[i].id) || !strcmp(s, KERN[i].name)) return &KERN[i]; return NULL; }

static const char *CSV_HDR = "kernel,threads,cores,size_mb,prefetch_dist,run,median_ns,best_ns,bytes,gbs_median,gbs_best,pct_theoretical,minflt_timed,passes\n";
static void emit_row(const char *csv, const char *name, int threads, const char *cores, size_t size, size_t dd, const char *run, double med_ns, double best_ns, double bytes,
                     double gbm, double gbb, double pct, long minflt, int passes, int print_hdr) {
    double mb = (double)size / 1048576.0;
    if (print_hdr) printf("%-18s %3s %-16s %7s %6s %-8s %12s %12s %10s %10s %7s %8s\n", "kernel", "thr", "cores", "size_mb", "pfdist", "run", "median_ns", "best_ns", "GB/s_med", "GB/s_best", "%peak", "minflt");
    printf("%-18s %3d %-16s %7g %6zu %-8s %12.0f %12.0f %10.2f %10.2f %7.1f %8ld\n", name, threads, cores, mb, dd, run, med_ns, best_ns, gbm, gbb, pct, minflt);
    fflush(stdout);
    if (csv) { int hdr = access(csv, F_OK) != 0; FILE *f = fopen(csv, "a"); if (!f) { perror(csv); return; } if (hdr) fputs(CSV_HDR, f);
        fprintf(f, "%s,%d,%s,%g,%zu,%s,%.0f,%.0f,%.0f,%.3f,%.3f,%.2f,%ld,%d\n", name, threads, cores, mb, dd, run, med_ns, best_ns, bytes, gbm, gbb, pct, minflt, passes); fclose(f); }
}
static void emit(const char *csv, const cfg_t *c, const char *run, const res_t *r, int print_hdr) {
    char cores[64]; fmt_cores(&c->cores, c->threads, cores, sizeof cores);
    emit_row(csv, c->k->name, c->threads, cores, c->size, c->k->pf ? c->dist : 0, run, r->med_ns, r->best_ns, r->bytes, r->gb_med, r->gb_best, r->pct, r->minflt, r->passes, print_hdr);
}
static int cmp_d(const void *a, const void *b) { double x = *(const double *)a, y = *(const double *)b; return x < y ? -1 : x > y; }

/* one configuration x nruns; appends runs 1..n and "mom" (median of medians); returns the mom GB/s or -1 on failure */
static void p1_pin_acc(const cfg_t *c) {   /* PART1: fold the per-thread pin results of one run into the process-wide facts (worst case over threads and runs) */
    for (int i = 0; i < c->threads && i < MAXCPU; i++) if (g_pr_set[i]) {
        g_pin_any = 1; if (!g_pr_ok[i]) g_pin_unpinned = 1; if (g_pr[i].attempts > g_pin_att) g_pin_att = g_pr[i].attempts; if (g_pr[i].ms > g_pin_ms) g_pin_ms = g_pr[i].ms; g_pr_set[i] = 0; }
}
static void p1_pin_summary(void) {   /* PART1: the row's pin object: unpinned when ANY timed run of the process ran unpinned (review F03) */
    if (!g_pin_any) return;
    P1.have_pin = 1; P1.pin = g_pin_unpinned ? P1_PIN_UNPINNED : P1_PIN_OK; P1.pin_attempts = g_pin_att; P1.pin_ms = g_pin_ms;
    if (g_pin_unpinned) { static int noted; p1_label("unpinned"); if (!noted) { noted = 1; p1_note("%s", "pin failed within the retry window; --allow-unpinned: at least one timed run ran unpinned"); } }   /* the sentence once, not once per sweep size (review F04 of P1-03F) */
}
static void p1_pin_timeout_hook(void) {   /* PART1 (P1-03G F05, P1-03H A1/A2): called by the watchdog. Unpinned when an earlier run, sweep size or the run in progress ran (or is running) unpinned: a pin that has already failed is known (g_cpu_failed, or the per-thread result of the run in progress). cpu_set keeps the CPUs asked for (nothing was measured; one rule in part1_common.h). A pin_retry that is still waiting when the limit fires is not reported. */
    int unp = g_pin_any && g_pin_unpinned, att = g_pin_att; double ms = g_pin_ms;
    for (int i = 0; i < MAXCPU; i++) {
        if (g_cpu_failed[i]) unp = 1;
        if (g_pr_set[i]) { if (!g_pr_ok[i]) unp = 1; if (g_pr[i].attempts > att) att = g_pr[i].attempts; if (g_pr[i].ms > ms) ms = g_pr[i].ms; }
    }
    if (unp) { P1.have_pin = 1; P1.pin = P1_PIN_UNPINNED; P1.pin_attempts = att; P1.pin_ms = ms; p1_label("unpinned"); }
}
static void p1_fail_run(const char *what, const char *err) {   /* PART1: a failed run ends the program through the common exit path */
    if (g_pinfail) {   /* exit 87: nothing ran unpinned and --allow-unpinned was not given: pin failed, cpu_set keeps the CPUs asked for, no label, no note (review F05) */
        P1.have_pin = 1; P1.pin = P1_PIN_FAILED; P1.pin_attempts = g_pin_att; P1.pin_ms = g_pin_ms; p1_exitf(P1_FAILED, P1_EXIT_PIN, "%s: %s", what, err); }
    p1_pin_summary();
    p1_exitf(P1_FAILED, P1_EXIT_FAILED, "%s: %s", what, err);
}
static double do_runs(const char *csv, cfg_t *c, int hdr, int nruns) {
    double gm[8]; res_t last; int ok = 0; char tag[24]; long mf = 0;
    P1.nvals = 0; P1.passes = -1; P1.have_minflt = 0; P1.median = -1;   /* PART1: nothing left over from an earlier sweep size (review F02) */
    for (int i = 0; i < nruns; i++) {
        g_runno = i + 1; res_t r = run_config(c); p1_pin_acc(c);
        if (!r.ok) { fprintf(stderr, "FAIL %s: %s\n", c->k->name, r.err); p1_fail_run(c->k->name, r.err); }
        snprintf(tag, sizeof tag, "%s%d", g_tag, i + 1); emit(csv, c, tag, &r, hdr && i == 0); gm[ok++] = r.gb_med; last = r; mf += r.minflt; sleep(1);
    }
    P1.nvals = ok; for (int i = 0; i < ok; i++) P1.vals[i] = gm[i]; P1.passes = last.passes; P1.minflt = mf; P1.have_minflt = 1; p1_pin_summary();   /* PART1: values for the JSON, before the sort */
    qsort(gm, ok, sizeof gm[0], cmp_d); res_t m = last; m.gb_med = gm[ok / 2]; m.gb_best = gm[ok / 2]; m.pct = 100.0 * m.gb_med / PEAK_GBS; m.med_ns = m.best_ns = m.bytes / m.gb_med; m.passes = 0;
    P1.median = m.gb_med;
    snprintf(tag, sizeof tag, "%smom", g_tag); emit(csv, c, tag, &m, 0); return m.gb_med;
}

/* --split: stream A (thread 0, c->k) and stream B (thread 1, c->k2) run together; rows per run: A, B and the sum (kernel "A+B") */
static int do_split(const char *csv, cfg_t *c, int hdr, int nruns) {
    double ga[8], gb[8], gs[8]; int ok = 0; char tag[24], cores[64], c0[16], c1[16], nm[48];
    snprintf(c0, sizeof c0, "%d", c->cores.cpu[0]); snprintf(c1, sizeof c1, "%d", c->cores.cpu[1]); fmt_cores(&c->cores, 2, cores, sizeof cores);
    snprintf(nm, sizeof nm, "%s+%s", c->k->name, c->k2->name);
    for (int i = 0; i < nruns; i++) {
        g_runno = i + 1; res_t r = run_config(c); p1_pin_acc(c);
        if (!r.ok) { fprintf(stderr, "FAIL split %s: %s\n", nm, r.err); p1_fail_run(nm, r.err); }
        snprintf(tag, sizeof tag, "%s%d", g_tag, i + 1);
        double a = r.t_bytes / r.t_med_ns[0], b = r.t_bytes / r.t_med_ns[1], ab = r.t_bytes / r.t_best_ns[0], bb = r.t_bytes / r.t_best_ns[1];
        emit_row(csv, c->k->name, 1, c0, c->size, c->k->pf ? c->dist : 0, tag, r.t_med_ns[0], r.t_best_ns[0], r.t_bytes, a, ab, 100 * a / PEAK_GBS, r.t_minflt[0], r.t_passes[0], hdr && i == 0);
        emit_row(csv, c->k2->name, 1, c1, c->size, c->k2->pf ? c->dist : 0, tag, r.t_med_ns[1], r.t_best_ns[1], r.t_bytes, b, bb, 100 * b / PEAK_GBS, r.t_minflt[1], r.t_passes[1], 0);
        emit_row(csv, nm, 2, cores, c->size, 0, tag, 0, 0, 2 * r.t_bytes, a + b, ab + bb, 100 * (a + b) / PEAK_GBS, r.t_minflt[0] + r.t_minflt[1], r.t_passes[0] + r.t_passes[1], 0);
        ga[ok] = a; gb[ok] = b; gs[ok] = a + b; ok++; sleep(1);
    }
    qsort(ga, ok, sizeof ga[0], cmp_d); qsort(gb, ok, sizeof gb[0], cmp_d); qsort(gs, ok, sizeof gs[0], cmp_d);
    snprintf(tag, sizeof tag, "%smom", g_tag); double bytes = (double)c->size * (c->size < PASS_BYTES ? PASS_BYTES / c->size : 1);
    emit_row(csv, c->k->name, 1, c0, c->size, c->k->pf ? c->dist : 0, tag, bytes / ga[ok / 2], bytes / ga[ok / 2], bytes, ga[ok / 2], ga[ok / 2], 100 * ga[ok / 2] / PEAK_GBS, 0, 0, 0);
    emit_row(csv, c->k2->name, 1, c1, c->size, c->k2->pf ? c->dist : 0, tag, bytes / gb[ok / 2], bytes / gb[ok / 2], bytes, gb[ok / 2], gb[ok / 2], 100 * gb[ok / 2] / PEAK_GBS, 0, 0, 0);
    emit_row(csv, nm, 2, cores, c->size, 0, tag, 2 * bytes / gs[ok / 2], 2 * bytes / gs[ok / 2], 2 * bytes, gs[ok / 2], gs[ok / 2], 100 * gs[ok / 2] / PEAK_GBS, 0, 0, 0);
    return 0;
}


/* ---- P1: pace_chase (ADFA-6890 pacer round): dependent pointer chase over 64 B nodes with an optional cntvct_el0 rate limiter ---- */
static uint64_t splitmix(uint64_t *x) { uint64_t z = (*x += 0x9E3779B97F4A7C15ull); z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull; z = (z ^ (z >> 27)) * 0x94D049BB133111EBull; return z ^ (z >> 31); }
static int g_pace_dsb = 1;   /* --pace-mode dsb (default: padding is a chain of dsb sy, one retired instruction each) | spin (isb + mrs loop, the round-1 pacer, kept as a control) */
/* n dependent loads; ticks = minimum cntvct ticks between load releases (0 = unthrottled); *prev = cntvct of the previous release; *groups += dsb-mode padding groups (8 dsb + 5 other instructions each) */
#ifdef __aarch64__   /* PART1: the chase_loop asm is the kit text; the #else branch is the host stub */
static void *chase_loop(void *p, size_t n, uint64_t ticks, uint64_t *prev, uint64_t *groups) {
    uint64_t pv = *prev, now, t, g = *groups;
    if (ticks == 0) asm volatile("1:\n ldr %[p],[%[p]]\n subs %[n],%[n],#1\n b.ne 1b\n" : [p] "+r"(p), [n] "+r"(n) : : "cc", "memory");
    else if (g_pace_dsb) asm volatile("1:\n ldr %[p],[%[p]]\n 2:\n dsb sy\n dsb sy\n dsb sy\n dsb sy\n dsb sy\n dsb sy\n dsb sy\n dsb sy\n add %[g],%[g],#1\n mrs %[now],cntvct_el0\n"
        "sub %[t],%[now],%[pv]\n cmp %[t],%[tk]\n b.lo 2b\n mov %[pv],%[now]\n subs %[n],%[n],#1\n b.ne 1b\n"
        : [p] "+r"(p), [n] "+r"(n), [pv] "+r"(pv), [g] "+r"(g), [now] "=&r"(now), [t] "=&r"(t) : [tk] "r"(ticks) : "cc", "memory");
    else asm volatile("1:\n ldr %[p],[%[p]]\n 2:\n isb\n mrs %[now],cntvct_el0\n sub %[t],%[now],%[pv]\n cmp %[t],%[tk]\n b.lo 2b\n mov %[pv],%[now]\n"
        "subs %[n],%[n],#1\n b.ne 1b\n" : [p] "+r"(p), [n] "+r"(n), [pv] "+r"(pv), [now] "=&r"(now), [t] "=&r"(t) : [tk] "r"(ticks) : "cc", "memory");
    *prev = pv; *groups = g; return p;
}
#else
static void *chase_loop(void *p, size_t n, uint64_t ticks, uint64_t *prev, uint64_t *groups) { (void)ticks; (void)prev; (void)groups; while (n--) p = *(void **)p; return p; }
#endif
static int perf_open(uint32_t type, uint64_t config) {
    struct perf_event_attr a; memset(&a, 0, sizeof a); a.type = type; a.size = sizeof a; a.config = config; a.disabled = 1; a.exclude_kernel = 1; a.exclude_hv = 1;
    return (int)syscall(__NR_perf_event_open, &a, 0, -1, -1, 0);
}
/* Sattolo: p[i] = index of the node after node i, one cycle through all nodes */
static void *chase_build(uint8_t *buf, size_t nodes, uint64_t seed) {
    uint32_t *p = malloc(nodes * sizeof *p); if (!p) return NULL;
    for (size_t i = 0; i < nodes; i++) p[i] = (uint32_t)i;
    for (size_t i = nodes - 1; i > 0; i--) { size_t j = splitmix(&seed) % i; uint32_t t = p[i]; p[i] = p[j]; p[j] = t; }
    for (size_t i = 0; i < nodes; i++) *(void **)(buf + 64 * i) = buf + 64 * (size_t)p[i];
    free(p); return buf;
}
static size_t chase_walk(void *start, size_t cap) { void *p = start; size_t c = 0; do { p = *(void *volatile *)p; c++; } while (p != start && c <= cap); return c; }
#ifdef __aarch64__
static uint64_t cntfrq(void) { uint64_t f; asm volatile("mrs %0, cntfrq_el0" : "=r"(f)); return f; }
static uint64_t cntvct(void) { uint64_t v; asm volatile("isb\n mrs %0, cntvct_el0" : "=r"(v)); return v; }
#else
static uint64_t cntfrq(void) { return 1000000000ull; }
static uint64_t cntvct(void) { return now_ns(); }
#endif

/* writes one row in the all.csv schema (kernel pace_chase|pace_idle, prefetch_dist column = pace_ns, bytes = misses x 64, passes = misses) and one detail row */
static const char *g_stopfile = NULL;   /* --stop-file: the pacer ends its (single) cycle early when this file exists */
static int pace_main(const char *cpus, int idle, long pace_ns, double dur_s, int cycles, double gap_s, double settle_s, const char *csv, const char *detail, const char *timeline) {
    int cpu = atoi(cpus); pin_res_t pr;   /* v4d-5h: pacer kernel pins with retry; failure -> PIN_RETRY_EXIT (repin_failed) */
    P1.cpus[0] = cpu; P1.ncpu = 1; P1.threads = 1; double *pgb = calloc((size_t)cycles, sizeof *pgb); if (!pgb) p1_exitf(P1_FAILED, P1_EXIT_FAILED, "out of memory");
    int pok = pin_retry(cpu, idle ? "membw-pace-idle" : "membw-pace-chase", &pr); P1.have_pin = 1; P1.pin_attempts = pr.attempts; P1.pin_ms = pr.ms; P1.pin = pok ? P1_PIN_OK : P1_PIN_UNPINNED;   /* PART1: result kept for the output */
    if (!pok && !g_allow_unpinned) { fprintf(stderr, "sched_setaffinity(cpu%d): %s after %d attempts\n", cpu, strerror(pr.err), pr.attempts); P1.pin = P1_PIN_FAILED; p1_exitf(P1_FAILED, PIN_RETRY_EXIT, "pin failed: cpu%d not pinnable within the retry window (%s)", cpu, pin_errname(pr.err)); }
    if (!pok) { p1_label("unpinned"); p1_note("%s", "pin failed within the retry window; --allow-unpinned: the row ran unpinned"); printf("# pin: unpinned who=membw-pace cpu=%d (--allow-unpinned: the row runs unpinned)\n", cpu); fflush(stdout); }
    size_t bytes = idle ? 4096 : (256ul << 20), nodes = bytes / 64; uint64_t frq = cntfrq();
    uint8_t *buf = mmap(NULL, bytes + (2u << 20), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0); if (buf == MAP_FAILED) { perror("mmap"); p1_exitf(P1_FAILED, P1_EXIT_FAILED, "mmap failed"); }
    buf = (uint8_t *)(((uintptr_t)buf + (2u << 20) - 1) & ~(uintptr_t)((2u << 20) - 1)); madvise(buf, bytes, MADV_HUGEPAGE);
    void *start = chase_build(buf, nodes, 0x1234567ull + (uint64_t)cpu);
    if (!start) { fprintf(stderr, "chain build failed\n"); p1_exitf(P1_FAILED, P1_EXIT_FAILED, "chain build failed"); }
    size_t pre = chase_walk(start, nodes);
    uint64_t ticks = pace_ns > 0 ? ((uint64_t)pace_ns * frq + 999999999ull) / 1000000000ull : 0;
    double eff_ns = ticks ? (double)ticks * 1e9 / (double)frq : 0;
    size_t chunk = (size_t)(10e6 / (eff_ns > 120 ? eff_ns : 120)); if (chunk < 8) chunk = 8; if (chunk > 65536) chunk = 65536;
    printf("# pace %s cpu=%d pace_ns=%ld ticks=%llu eff_pace_ns=%.1f cntfrq=%llu nodes=%zu pre_walk=%zu chunk=%zu cycles=%d dur_s=%.2f\n", idle ? "idle" : "chase", cpu, pace_ns,
        (unsigned long long)ticks, eff_ns, (unsigned long long)frq, nodes, pre, chunk, cycles, dur_s); fflush(stdout);
    void *p = start; uint64_t prev = cntvct(); int rc = 0; uint64_t cum = 0, groups = 0;
    int fd_in = perf_open(PERF_TYPE_HARDWARE, PERF_COUNT_HW_INSTRUCTIONS), fd_l3 = perf_open(PERF_TYPE_RAW, 0x2A);   /* PMU counters on this thread if permitted: instructions and L3D_CACHE_REFILL (0x2A) */
    printf("# pmu: instructions fd=%d l3d_refill fd=%d (negative = perf_event_open not permitted: %s) mode=%s\n", fd_in, fd_l3, (fd_in < 0 || fd_l3 < 0) ? strerror(errno) : "ok", g_pace_dsb ? "dsb" : "spin");
    for (int cy = 1; cy <= cycles; cy++) {
        if (cy == 1 && settle_s > 0) usleep((useconds_t)(settle_s * 1e6));
        else if (cy > 1 && gap_s > 0) usleep((useconds_t)(gap_s * 1e6));
        uint64_t boot_off = clock_ns(CLOCK_BOOTTIME) - clock_ns(CLOCK_MONOTONIC); uint64_t t0 = now_ns(), dl = t0 + (uint64_t)(dur_s * 1e9), misses = 0, lastlog = t0; prev = cntvct(); groups = 0;
        if (fd_in >= 0) { ioctl(fd_in, PERF_EVENT_IOC_RESET, 0); ioctl(fd_in, PERF_EVENT_IOC_ENABLE, 0); } if (fd_l3 >= 0) { ioctl(fd_l3, PERF_EVENT_IOC_RESET, 0); ioctl(fd_l3, PERF_EVENT_IOC_ENABLE, 0); }
        int tlhdr = timeline && access(timeline, F_OK) != 0; FILE *tl = timeline ? fopen(timeline, "a") : NULL; uint64_t cum0 = cum;
        if (tl && tlhdr) fputs("tag_cycle,boot_ns,misses_cum\n", tl);
        while (1) { p = chase_loop(p, chunk, ticks, &prev, &groups); misses += chunk; cum += chunk; uint64_t t = now_ns();
            if (tl && t - lastlog >= 100000000ull) { fprintf(tl, "%s%d,%llu,%llu\n", g_tag, cy, (unsigned long long)(t + boot_off), (unsigned long long)(cum - cum0)); lastlog = t; }
            if (t >= dl || (g_stopfile && access(g_stopfile, F_OK) == 0)) break; }
        uint64_t t1 = now_ns(); if (tl) fclose(tl);
        long long pin = -1, pl3 = -1; if (fd_in >= 0) { ioctl(fd_in, PERF_EVENT_IOC_DISABLE, 0); if (read(fd_in, &pin, sizeof pin) != sizeof pin) pin = -1; }
        if (fd_l3 >= 0) { ioctl(fd_l3, PERF_EVENT_IOC_DISABLE, 0); if (read(fd_l3, &pl3, sizeof pl3) != sizeof pl3) pl3 = -1; }
        double ipm_est = ticks == 0 ? 3.0 : (g_pace_dsb ? 4.0 + 13.0 * (double)groups / (double)misses : -1.0), ipm_pmu = pin >= 0 ? (double)pin / (double)misses : -1.0, l3pl = pl3 >= 0 ? (double)pl3 / (double)misses : -1.0;
        double el = (double)(t1 - t0), mps = (double)misses / el * 1e9, gbs = (double)misses * 64.0 / el;
        int vf = 1; if (cy == cycles) { size_t post = chase_walk(p, nodes); vf = (pre == nodes && post == nodes); if (!vf) rc = 3; }   /* guard: the whole chain is one cycle of exactly `nodes` nodes, intact after the run */
        pgb[cy - 1] = gbs;
        char name[16], cr[8], run[24]; snprintf(name, sizeof name, "%s", idle ? "pace_idle" : "pace_chase"); snprintf(cr, sizeof cr, "%d", cpu); snprintf(run, sizeof run, "%s%d", g_tag, cy);
        printf("pace_row cycle=%d cpu=%d pace_ns=%ld misses=%llu elapsed_ns=%.0f misses_per_s=%.0f GB_s=%.4f verified=%d boot_ns_start=%llu boot_ns_end=%llu mode=%s groups=%llu ipm_est=%.2f ipm_pmu=%.2f l3_per_load=%.3f\n", cy, cpu, pace_ns,
            (unsigned long long)misses, el, mps, gbs, cy == cycles ? vf : -1, (unsigned long long)(t0 + boot_off), (unsigned long long)(t1 + boot_off), g_pace_dsb ? "dsb" : "spin", (unsigned long long)groups, ipm_est, ipm_pmu, l3pl); fflush(stdout);
        if (csv) { int hdr = access(csv, F_OK) != 0; FILE *f = fopen(csv, "a"); if (f) { if (hdr) fputs(CSV_HDR, f);
            fprintf(f, "%s,1,%s,%g,%ld,%s,%.1f,%.1f,%.0f,%.4f,%.4f,%.3f,0,%llu\n", name, cr, (double)bytes / 1048576.0, pace_ns, run, el / (double)misses, el / (double)misses, (double)misses * 64.0, gbs, gbs, 100.0 * gbs / PEAK_GBS, (unsigned long long)misses); fclose(f); } }
        if (detail) { int hdr = access(detail, F_OK) != 0; FILE *f = fopen(detail, "a"); if (f) { if (hdr) fputs("tag,cycle,cpu,idle,pace_ns,ticks,eff_pace_ns,misses,elapsed_ns,misses_per_s,gbs,verified,boot_ns_start,boot_ns_end,mode,groups,ipm_est,ipm_pmu,l3_per_load\n", f);
            fprintf(f, "%s,%d,%d,%d,%ld,%llu,%.1f,%llu,%.0f,%.0f,%.4f,%d,%llu,%llu,%s,%llu,%.2f,%.2f,%.3f\n", run, cy, cpu, idle, pace_ns, (unsigned long long)ticks, eff_ns, (unsigned long long)misses, el, mps, gbs, cy == cycles ? vf : -1,
                (unsigned long long)(t0 + boot_off), (unsigned long long)(t1 + boot_off), g_pace_dsb ? "dsb" : "spin", (unsigned long long)groups, ipm_est, ipm_pmu, l3pl); fclose(f); } }
    }
    g_sink ^= (uint64_t)(uintptr_t)p; printf("# verified=%d\n", rc == 0);
    if (rc) p1_exitf(P1_FAILED, P1_EXIT_FAILED, "pacer chain integrity check failed (the chain is not one intact cycle)");   /* PART1: kit exit 3 here collides with "unsupported"; reported as 1 */
    P1.nvals = cycles < P1_MAXVALS ? cycles : P1_MAXVALS; for (int i = 0; i < P1.nvals; i++) P1.vals[i] = pgb[i];
    { double *so = malloc((size_t)cycles * sizeof *so); if (so) { memcpy(so, pgb, (size_t)cycles * sizeof *so); qsort(so, (size_t)cycles, sizeof *so, cmp_d); P1.median = so[cycles / 2]; free(so); } }
    P1.repeats = cycles; P1.buffer_mib = (double)bytes / 1048576.0; free(pgb); return rc;
}

/* ---- P1-03H C2: MIDR of a CPU, read while the probe is pinned on it. 1) sysfs <cpu dir>/cpu<n>/regs/identification/midr_el1 (a hex number); 2) when that file is absent or unreadable, this CPU's entry in
 * /proc/cpuinfo (read during the spin): MIDR = implementer<<24 | variant<<20 | 0xf<<16 | part<<4 | revision (the "CPU architecture" line of cpuinfo says 8, the MIDR architecture field of an ARMv8 CPU is 0xf);
 * 3) neither: no MIDR. The host tests redirect the two paths (P1_SYSFS_CPU_DIR, P1_CPUINFO_PATH); a production build never reads those variables. ---- */
static int pp_midr_sysfs(int cpu, unsigned long long *out) {
    char path[256], ln[64]; snprintf(path, sizeof path, "%s/cpu%d/regs/identification/midr_el1", p1_path("P1_SYSFS_CPU_DIR", "/sys/devices/system/cpu"), cpu);
    FILE *f = fopen(path, "r"); if (!f) return 0;
    char *e = NULL; int ok = fgets(ln, sizeof ln, f) != NULL; fclose(f); if (!ok) return 0;
    errno = 0; unsigned long long v = strtoull(ln, &e, 16); if (e == ln || errno || (*e && *e != '\n' && *e != '\r' && *e != ' ')) return 0;
    *out = v & 0xffffffffull; return 1;
}
static int pp_midr_cpuinfo(int cpu, unsigned long long *out) {
    FILE *f = fopen(p1_path("P1_CPUINFO_PATH", "/proc/cpuinfo"), "r"); if (!f) return 0;
    char ln[256]; int cur = -1, have = 0; unsigned long long imp = 0, var = 0, part = 0, rev = 0; int g_imp = 0, g_var = 0, g_part = 0, g_rev = 0, got = 0;
    while (fgets(ln, sizeof ln, f)) {
        char *c = strchr(ln, ':'); if (!c) continue; c++;
        if (!strncmp(ln, "processor", 9)) { if (cur == cpu && g_imp && g_var && g_part && g_rev) { got = 1; break; } cur = atoi(c); g_imp = g_var = g_part = g_rev = 0; have = 1; }
        else if (cur == cpu && have) {
            if (!strncmp(ln, "CPU implementer", 15)) { imp = strtoull(c, NULL, 0); g_imp = 1; } else if (!strncmp(ln, "CPU variant", 11)) { var = strtoull(c, NULL, 0); g_var = 1; }
            else if (!strncmp(ln, "CPU part", 8)) { part = strtoull(c, NULL, 0); g_part = 1; } else if (!strncmp(ln, "CPU revision", 12)) { rev = strtoull(c, NULL, 0); g_rev = 1; }
        }
    }
    if (!got && cur == cpu && g_imp && g_var && g_part && g_rev) got = 1;
    fclose(f); if (!got) return 0;
    *out = ((imp & 0xff) << 24) | ((var & 0xf) << 20) | (0xfull << 16) | ((part & 0xfff) << 4) | (rev & 0xf); return 1;
}
static void pp_midr(int idx, int cpu) {   /* called while pinned on `cpu`, inside the spin */
    unsigned long long v; int src = 0;
    if (pp_midr_sysfs(cpu, &v)) src = 1; else if (pp_midr_cpuinfo(cpu, &v)) src = 2;
    if (src) { snprintf(P1.pcpu[idx].midr, sizeof P1.pcpu[idx].midr, "0x%08llx", v); P1.pcpu[idx].midr_src = src; }
}

static void pp_midr_unpinned(int idx, int cpu) {   /* P1-03I (PLAN-034): the pin failed, so read unpinned: the per-CPU sysfs file needs no reader on that CPU; else that CPU's /proc/cpuinfo entry; else none */
    unsigned long long v; int src = 0;
    if (pp_midr_sysfs(cpu, &v)) src = 3; else if (pp_midr_cpuinfo(cpu, &v)) src = 4;
    if (src) { snprintf(P1.pcpu[idx].midr, sizeof P1.pcpu[idx].midr, "0x%08llx", v); P1.pcpu[idx].midr_src = src; }
}

/* ---- P1 --pin-probe (job P1-03F B10): for each CPU in the list, in order: the kit's pin_retry (unchanged), then a short spin on that CPU that checks the running CPU.
 * The total spin budget is divided over the CPUs; a CPU whose pin fails costs at most the 5 s retry window and is reported `failed` (the probe itself still succeeds).
 * The original affinity mask is restored after each CPU so the retry spin of the next CPU is not confined to the previous one. No buffer is allocated. */
static void pinprobe_main(const cpulist_t *cl, double spin_ms_total) {
    cpu_set_t orig; CPU_ZERO(&orig); int have_orig = sched_getaffinity(0, sizeof orig, &orig) == 0;
    double per_ms = spin_ms_total / cl->n; if (per_ms < 1) per_ms = 1;
    for (int i = 0; i < cl->n && P1.npcpu < P1_MAXCPUS; i++) {
        int cpu = cl->cpu[i]; pin_res_t pr; char who[24]; snprintf(who, sizeof who, "pinprobe-cpu%d", cpu);
        int ok = pin_retry(cpu, who, &pr);
        int slot = P1.npcpu; P1.pcpu[slot].midr_src = 0; P1.pcpu[slot].midr[0] = 0;
        if (ok) { uint64_t dl = now_ns() + (uint64_t)(per_ms * 1e6); volatile uint64_t x = 0; int did = 0;
            do { for (int k = 0; k < 20000; k++) x += (uint64_t)k; if (sched_getcpu() != cpu) { ok = 0; break; }   /* spin, verifying the running CPU */
                 if (!did) { did = 1; pp_midr(slot, cpu); } } while (now_ns() < dl); }   /* P1-03H C2: the MIDR is read once, during the spin, while pinned on this CPU */
        if (have_orig) sched_setaffinity(0, sizeof orig, &orig);
        if (!ok && !P1.pcpu[slot].midr_src) pp_midr_unpinned(slot, cpu);   /* P1-03I (PLAN-034): a CPU whose pin failed keeps its identity */
        P1.pcpu[P1.npcpu].index = cpu; P1.pcpu[P1.npcpu].ok = ok; P1.pcpu[P1.npcpu].attempts = pr.attempts; P1.pcpu[P1.npcpu].ms = pr.ms; P1.npcpu++;
        printf("# pinprobe cpu=%d result=%s attempts=%d time_to_pin_ms=%.1f spin_ms=%.0f\n", cpu, ok ? "ok" : "failed", pr.attempts, pr.ms, ok ? per_ms : 0.0); fflush(stdout);
    }
}

static void usage(void) {
    fprintf(stderr, "libpart1_membw (Part 1 engine; kit membw kernels)\n"
        "membw --kernel <K01..K12|name> --threads N --cores <list e.g. 6 or 6+7 or 0,1,2|all> --size-mb M --iters I --warmup-s W\n"
        "      [--csv path] [--prefetch-dist BYTES] [--settle-s S] [--allow-cached] [--runs N (1..8)] [--tag T] [--window-s S]\n"
        "      [--timeline path] [--cpufreq-log path]     (timeline: one row per pass; cpufreq-log: cpu 0/4/6/7 every 500 ms)\n"
        "membw --split <write-kernel> [--kernel <read-kernel, default K02>] [--cores 6+7] ...   (thread 0 reads, thread 1 writes, same window; no --json)\n"
        "membw --sweep [--sizes \"0.5,1,2,...\" (total MiB, split across threads)] --kernel K --threads N --cores L ...\n"
        "membw --pace-chase|--pace-idle --cores CPU [--pace-ns N] --duration-s S [--cycles K (1..64) --gap-s G --settle-s S] [--pace-mode dsb|spin] [--csv path] [--detail path] [--timeline path] [--stop-file path] [--tag T]\n"
        "membw --pin-probe --cores LIST [--spin-ms 2000 (total, divided over the CPUs)] [--json] [--gate on|off] [--max-seconds S]   (one part1-pinprobe-v1 object, not a row)\n"
        "membw --static-probe [--json] [--max-seconds S]   (one part1-staticprobe-v1 object, not a row; no gate, nothing measured)\n"
        "membw --list\n"
        "Part 1 options: --json (one part1-rows-v1 object on the last stdout line)  --gate on|off (default on; own cpuset must be /top-app, else exit 97)\n"
        "      --allow-unpinned (run unpinned when the pin retry fails; default exit 87)  --mem-limit-mib N  --fit-buffer (shrink the per-thread buffer to fit, floor 64 MiB; else exit 88)\n"
        "      --max-seconds S (self time limit, exit 124)  --tier T1|T2|T3  --row-kind <kind>  (see ENGINE.md and EXIT-CODES.md)\n");
}
#define USAGE(...) do { usage(); p1_exitf(P1_FAILED, P1_EXIT_USAGE, __VA_ARGS__); } while (0)
/* PART1: option-value checks of the P1 options, out of line so that main() keeps its shape */
static const char *__attribute__((noinline)) p1_opt_row_kind(const char *v) {
    if (!p1_row_kind_ok(v)) USAGE("bad --row-kind %s (pinprobe|read|write|copy|gpu|sweep|prefetch|pacer|sustained)", v);
    return v;
}
static const char *__attribute__((noinline)) p1_opt_tier(const char *v) {   /* validated before it is stored (review F02 of P1-03F) */
    if (!p1_tier_ok(v)) USAGE("bad --tier %s (T1|T2|T3)", v);
    return v;
}
/* review F06 of P1-03F: a CPU twice in --cores is a usage error for rows, as it already is for the pin probe */
static void __attribute__((noinline)) p1_check_cores_unique(const cpulist_t *cl) {
    for (int a = 0; a < cl->n; a++) for (int b = a + 1; b < cl->n; b++) if (cl->cpu[a] == cl->cpu[b]) USAGE("--cores lists CPU %d twice", cl->cpu[a]);
}
/* review F08 of P1-03F: the kind of a usage-error record follows the invocation when it can be known (--sweep, --pace-chase/--pace-idle seen anywhere on the command line); main() sets the final kind later */
static void __attribute__((noinline)) p1_prescan_kind(int argc, char **argv) {
    for (int i = 1; i < argc; i++) if (!strcmp(argv[i], "--sweep")) P1.kind = "sweep";
    for (int i = 1; i < argc; i++) if (!strcmp(argv[i], "--pace-chase") || !strcmp(argv[i], "--pace-idle")) P1.kind = "pacer";
}
static double __attribute__((noinline)) p1_opt_spin_ms(const char *v) {
    double ms; if (p1_parse_double(v, &ms) || ms <= 0 || ms > 600000) USAGE("bad --spin-ms %s (a total in ms, 0 < ms <= 600000)", v);
    return ms;
}
/* PART1 B10: with --pin-probe only these options are allowed; checked once, before the option loop (kept out of main() so main() compiles as before) */
static void __attribute__((noinline)) probe_check_args(int argc, char **argv) {
    for (int i = 1; i < argc; i++) { const char *a = argv[i];
        if (a[0] == '-' && a[1] == '-' && strcmp(a, "--pin-probe") && strcmp(a, "--json") && strcmp(a, "--cores") && strcmp(a, "--spin-ms") && strcmp(a, "--gate") && strcmp(a, "--max-seconds"))
            USAGE("option %s is not allowed with --pin-probe (allowed: --cores --spin-ms --json --gate --max-seconds)", a); }
}
/* ---- P1-03H C1: --static-probe. Measures nothing, pins nothing, allocates nothing, no foreground gate (it runs at every plugin start; it records the cpuset it saw). One object `part1-staticprobe-v1`, not a row.
 * The system properties read are exactly SP_PROPS: compiled in, not extendable from the command line, no serial, no fingerprint, no network property. ---- */
#define SP_NPROPS 7
static const char *const SP_PROPS[SP_NPROPS] = {"ro.board.platform", "ro.hardware", "ro.product.board", "ro.product.device", "ro.soc.model", "ro.soc.manufacturer", "ro.build.version.sdk"};
static const struct { const char *name; int w2; unsigned bit; } SP_FEATURES[] = {   /* arm64 <asm/hwcap.h>: HWCAP_ASIMD 1, ASIMDHP 10, ASIMDDP 20, SVE 22; HWCAP2_SVE2 1, I8MM 13, BF16 14, SME 23 */
    {"asimd", 0, 1}, {"asimdhp", 0, 10}, {"asimddp", 0, 20}, {"i8mm", 1, 13}, {"bf16", 1, 14}, {"sve", 0, 22}, {"sve2", 1, 1}, {"sme", 1, 23}};
#define SP_NFEAT ((int)(sizeof SP_FEATURES / sizeof SP_FEATURES[0]))
static struct { long page; unsigned long hw, hw2; char cpus[3][160]; int have_cpus[3]; char prop[SP_NPROPS][128]; } SP;
static const char *const SP_CPU_FILES[3] = {"possible", "present", "online"};
static void sp_prop(const char *name, char *out) {   /* out[128]; "" when the property is unset */
    out[0] = 0;
#if defined(__ANDROID__)
    char v[PROP_VALUE_MAX + 4]; v[0] = 0; if (__system_property_get(name, v) > 0) snprintf(out, 128, "%s", v);
#elif defined(P1_HOST_BUILD)
    char e[96] = "P1_PROP_"; size_t n = strlen(e);   /* host tests only: the value comes from P1_PROP_<NAME with . as _ and upper case>; only names of SP_PROPS are ever looked up */
    for (const char *c = name; *c && n < sizeof e - 1; c++) e[n++] = *c == '.' ? '_' : (*c >= 'a' && *c <= 'z') ? (char)(*c - 32) : *c;
    e[n] = 0; const char *v = getenv(e); if (v) snprintf(out, 128, "%s", v);
#else
    (void)name;
#endif
}
static void sp_collect(void) {
    SP.page = sysconf(_SC_PAGESIZE); SP.hw = getauxval(AT_HWCAP); SP.hw2 = getauxval(AT_HWCAP2);
#ifdef P1_HOST_BUILD
    { const char *a = getenv("P1_FAKE_HWCAP"), *b = getenv("P1_FAKE_HWCAP2"); if (a && *a) SP.hw = strtoul(a, NULL, 16); if (b && *b) SP.hw2 = strtoul(b, NULL, 16); }   /* host tests: the decoding is exercised with chosen values */
#endif
    for (int i = 0; i < 3; i++) {
        char path[256]; snprintf(path, sizeof path, "%s/%s", p1_path("P1_SYSFS_CPU_DIR", "/sys/devices/system/cpu"), SP_CPU_FILES[i]);
        FILE *f = fopen(path, "r"); if (!f) continue;
        if (fgets(SP.cpus[i], sizeof SP.cpus[i], f)) { size_t n = strlen(SP.cpus[i]); while (n && (SP.cpus[i][n - 1] == '\n' || SP.cpus[i][n - 1] == '\r')) SP.cpus[i][--n] = 0; SP.have_cpus[i] = 1; }
        fclose(f);
    }
    for (int i = 0; i < SP_NPROPS; i++) sp_prop(SP_PROPS[i], SP.prop[i]);
    p1_read_cpuset();
}
static void sp_write(FILE *f, p1_status_t st, int exit_code, const char *reason) {
    fprintf(f, "{\"format\":\"part1-staticprobe-v1\",\"engine_version\":\"part1-engine/" P1_ENGINE_VERSION "\",\"status\":\"%s\",\"exit_code\":%d", p1_status_name(st), exit_code);
    if (st == P1_OK) {
        fprintf(f, ",\"page_size\":%ld,\"hwcap\":\"0x%lx\",\"hwcap2\":\"0x%lx\",\"features\":{", SP.page, SP.hw, SP.hw2);
        for (int i = 0; i < SP_NFEAT; i++) fprintf(f, "%s\"%s\":%s", i ? "," : "", SP_FEATURES[i].name, ((SP_FEATURES[i].w2 ? SP.hw2 : SP.hw) >> SP_FEATURES[i].bit) & 1 ? "true" : "false");
        for (int i = 0; i < 3; i++) { fprintf(f, "%s\"cpus_%s\":", i ? "," : "},", SP_CPU_FILES[i]); if (SP.have_cpus[i]) p1_jstr(f, SP.cpus[i]); else fputs("null", f); }
        fputs(",\"properties\":{", f);
        for (int i = 0; i < SP_NPROPS; i++) { fprintf(f, "%s", i ? "," : ""); p1_jstr(f, SP_PROPS[i]); fputc(':', f); p1_jstr(f, SP.prop[i]); }
        fputs("},\"foreground_cpuset\":", f); if (P1.cpuset_read) p1_jstr(f, P1.cpuset); else fputs("null", f);
    }
    if (reason && *reason) { fputs(",\"reason\":", f); p1_jstr(f, reason); }
    fputs("}\n", f);
}
/* Called first in main(), before anything else can fail: when --static-probe is on the command line this runs the whole probe and does not return. Allowed with it: --json and --max-seconds. */
static void __attribute__((noinline)) p1_static_probe_entry(int argc, char **argv) {
    int on = 0; for (int i = 1; i < argc; i++) if (!strcmp(argv[i], "--static-probe")) on = 1;
    if (!on) return;
    p1_custom_writer = sp_write;
    for (int i = 1; i < argc; i++) { const char *a = argv[i];
        if (!strcmp(a, "--static-probe") || !strcmp(a, "--json")) continue;
        if (!strcmp(a, "--max-seconds")) { if (i + 1 >= argc || p1_parse_double(argv[i + 1], &p1_max_seconds) || p1_max_seconds <= 0) USAGE("bad or missing value for --max-seconds"); i++; continue; }
        USAGE("option %s is not allowed with --static-probe (allowed: --json --max-seconds)", a); }
    p1_start_watchdog(); sp_collect();
    if (!P1.json) { printf("# static-probe page_size=%ld hwcap=0x%lx hwcap2=0x%lx features=", SP.page, SP.hw, SP.hw2);
        for (int i = 0; i < SP_NFEAT; i++) if (((SP_FEATURES[i].w2 ? SP.hw2 : SP.hw) >> SP_FEATURES[i].bit) & 1) printf("%s ", SP_FEATURES[i].name);
        printf("cpus_possible=%s cpus_present=%s cpus_online=%s cpuset=%s\n", SP.have_cpus[0] ? SP.cpus[0] : "-", SP.have_cpus[1] ? SP.cpus[1] : "-", SP.have_cpus[2] ? SP.cpus[2] : "-", P1.cpuset_read ? P1.cpuset : "-");
        for (int i = 0; i < SP_NPROPS; i++) { printf("# static-probe property %s=%s\n", SP_PROPS[i], SP.prop[i]); }
        fflush(stdout); }
    p1_done_ok();
}
/* PART1 B10: the whole probe command, kept out of main() (noinline) so that main() and the pacer loop inlined into it compile as before. Does not return. */
static void __attribute__((noinline)) pinprobe_run(const char *cores, double spin_ms) {   /* the gate applies; no buffer is allocated, so there is no memory guard */
    if (!cores) USAGE("--pin-probe needs --cores LIST");
    cpulist_t pl; if (parse_cores(cores, &pl)) USAGE("bad --cores %s", cores);
    for (int a = 0; a < pl.n; a++) for (int b = a + 1; b < pl.n; b++) if (pl.cpu[a] == pl.cpu[b]) USAGE("--cores lists CPU %d twice", pl.cpu[a]);
    p1_start_watchdog(); p1_gate();
    pinprobe_main(&pl, spin_ms); p1_done_ok();
}
static void setcfg(cfg_t *c, const char *kern, int threads, const char *cores, size_t size_mb, double warm, double settle, size_t dist) {
    memset(c, 0, sizeof *c); c->k = find_kernel(kern); c->threads = threads; parse_cores(cores, &c->cores); c->size = size_mb << 20; c->warm_s = warm; c->settle_s = settle;
    c->dist = dist ? dist : c->k->defdist; c->window_ns = WINDOW_NS;
}
static int suite(const char *csv) {
    cpulist_t x1 = {1, {C_BIG.cpu[0]}}, a76 = {1, {C_MID.cpu[0]}}, a55 = {1, {C_LITTLE.cpu[0]}}, bm; bm.n = 0;
    for (int i = 0; i < C_BIG.n; i++) bm.cpu[bm.n++] = C_BIG.cpu[i]; for (int i = 0; i < C_MID.n; i++) bm.cpu[bm.n++] = C_MID.cpu[i];
    char b1[16], b2[16], b4[32]; snprintf(b1, sizeof b1, "%d", x1.cpu[0]); snprintf(b2, sizeof b2, "%d", a76.cpu[0]); snprintf(b4, sizeof b4, "%d", a55.cpu[0]);
    char big2[32] = "", bm4[48] = ""; for (int i = 0; i < C_BIG.n; i++) { char t[8]; snprintf(t, sizeof t, i ? "+%d" : "%d", C_BIG.cpu[i]); strcat(big2, t); }
    for (int i = 0; i < bm.n; i++) { char t[8]; snprintf(t, sizeof t, i ? "+%d" : "%d", bm.cpu[i]); strcat(bm4, t); }
    cfg_t c; int hdr = 1; double v;
#define RUN(...) do { setcfg(&c, __VA_ARGS__); v = do_runs(csv, &c, hdr, 3); hdr = 0; if (v < 0) return 1; sleep(1); } while (0)
    printf("# step 1: single-core ceiling per core type (K02, 1 thread)\n");
    RUN("K02", 1, b1, 256, 2, 0, 0); RUN("K02", 1, b2, 256, 2, 0, 0); RUN("K02", 1, b4, 256, 2, 0, 0);
    printf("# step 2: scaling (K02): 1 X1, 2 X1, 4 (X1+A76), 8\n");
    RUN("K02", 1, b1, 256, 2, 0, 0); RUN("K02", 2, big2, 256, 2, 0, 0); RUN("K02", 4, bm4, 256, 2, 0, 0); RUN("K02", 8, "all", 256, 2, 0, 0);
    printf("# step 3: instruction variants, 8 threads, all cores\n");
    const char *rk[] = {"K01", "K02", "K03", "K05"};
    for (int i = 0; i < 4; i++) RUN(rk[i], 8, "all", 256, 2, 0, 0);
    size_t ds[3] = {512, 1024, 2048};
    for (int i = 0; i < 3; i++) RUN("K04", 8, "all", 256, 2, 0, ds[i]);
    RUN("K06", 8, "all", 256, 2, 0, 1024);
    printf("# step 4: write variants, 8 threads\n");
    RUN("K07", 8, "all", 256, 2, 0, 0); RUN("K08", 8, "all", 256, 2, 0, 0);
    if (zva_ok()) RUN("K09", 8, "all", 256, 2, 0, 0); else printf("# K09 skipped: dczid_el0 does not allow a 64 B DC ZVA\n");
    printf("# step 5: copy variants, 8 threads\n");
    RUN("K10", 8, "all", 256, 2, 0, 0); RUN("K11", 8, "all", 256, 2, 0, 1024);
    printf("# step 6: buffer-size sanity (K02, 8 threads): 1 MB (cached) vs 256 MB\n");
    setcfg(&c, "K02", 8, "all", 1, 2, 0, 0); v = do_runs(csv, &c, 0, 3); if (v < 0) return 1; sleep(1);
    RUN("K02", 8, "all", 256, 2, 0, 0);
    printf("# step 7: warm-up sanity (K02, 8 threads): warm-up 0 vs 2 s, both with a 1 s idle settle after setup\n");
    RUN("K02", 8, "all", 256, 0, 1, 0); RUN("K02", 8, "all", 256, 2, 1, 0);
    return 0;
}
/* buffer-size sweep: each total size (MiB) is split evenly across the threads; sizes are rounded down to a multiple of 256 B */
static int sweep(const char *csv, cfg_t *c, const char *sizes) {
    char *dup = strdup(sizes), *save = NULL; int hdr = 1; double v = 0; char pts[600] = ""; int pts_cut = 0; double lastv = -1, lastbuf = -1; double lastvals[8]; int lastn = 0, lastpass = -1; long mf = 0;
    for (char *tok = strtok_r(dup, ", ", &save); tok; tok = strtok_r(NULL, ", ", &save)) {
        double total = atof(tok); size_t per = (size_t)(total * 1048576.0 / c->threads) & ~(size_t)255;
        if (per < 256) { char bad[64]; snprintf(bad, sizeof bad, "%.48s", tok); free(dup); dup = NULL; USAGE("sweep size %s too small", bad); }   /* P1-03I (review F02): `tok` points into `dup`; the reason uses a copy, never the freed buffer */
        c->size = per; v = do_runs(csv, c, hdr, 3); hdr = 0; if (v < 0) { free(dup); p1_exitf(P1_FAILED, P1_EXIT_FAILED, "sweep run failed"); } sleep(1);
        lastv = v; lastbuf = (double)per / 1048576.0; lastn = P1.nvals; for (int i = 0; i < lastn; i++) lastvals[i] = P1.vals[i]; lastpass = P1.passes; mf += P1.minflt;
        size_t n = strlen(pts); if (snprintf(pts + n, sizeof pts - n, "%s%s=%.2f", n ? "," : "", tok, v) >= (int)(sizeof pts - n)) pts_cut = 1;   /* PART1: "total MiB=GB/s" points for the note */
    }
    /* PART1: the row value is the largest (last) size, as the plan says; every point is in the note */
    P1.median = lastv; P1.buffer_mib = lastbuf; P1.nvals = lastn; for (int i = 0; i < lastn; i++) P1.vals[i] = lastvals[i]; P1.passes = lastpass; P1.minflt = mf;
    if (pts_cut) p1_label("note_truncated");   /* P1-03H A5: the points never get cut silently */
    p1_note_prio("sweep total_MiB=GB/s: %s", pts);   /* priority: older sentences (gate, pin) give way */
    free(dup); return 0;
}
/* PART1: the sweep size list after the memory guard: with --fit-buffer sizes above the limit are dropped */
static int cut_sizes(const char *sizes, double lim, char *out, size_t osz, double *largest) {
    char *dup = strdup(sizes), *save = NULL; int kept = 0; out[0] = 0; *largest = 0;
    for (char *tok = strtok_r(dup, ", ", &save); tok; tok = strtok_r(NULL, ", ", &save)) { double z = atof(tok); if (z <= lim) { size_t n = strlen(out); snprintf(out + n, osz - n, "%s%s", n ? "," : "", tok); kept++; if (z > *largest) *largest = z; } }
    free(dup); return kept;
}
static double largest_size(const char *sizes) {
    char *dup = strdup(sizes), *save = NULL; double mx = 0;
    for (char *tok = strtok_r(dup, ", ", &save); tok; tok = strtok_r(NULL, ", ", &save)) { double z = atof(tok); if (z > mx) mx = z; }
    free(dup); return mx;
}
int main(int argc, char **argv) {
    p1_init("membw"); (void)suite; p1_clear_host_env(); p1_timeout_hook = p1_pin_timeout_hook;   /* PART1: PINRETRY_STEP_MS / PINRETRY_MAX_MS are ignored in a production build (review F16) */
    for (int i = 1; i < argc; i++) { if (!strcmp(argv[i], "--json")) P1.json = 1;   /* so that even a usage error in the option loop is reported as JSON */
        if (!strcmp(argv[i], "--pin-probe")) P1.probe = 1; }   /* ... and in the pin-probe format when --pin-probe is given */
    p1_static_probe_entry(argc, argv);   /* P1-03H C1: does not return when --static-probe is given */
    p1_prescan_kind(argc, argv);
    if (P1.probe) probe_check_args(argc, argv);
    if (detect_cpus_p1() < 1) p1_exitf(P1_FAILED, P1_EXIT_FAILED, "cannot read the CPU list (/proc/cpuinfo and the affinity mask)");
    const char *kern = NULL, *kern2 = NULL, *cores = NULL, *csv = NULL, *sizes = "0.5,1,1.5,2,3,4,6,8,12,16,32,64,128,256"; int pace = 0, pace_idle = 0, cycles = 1; long pace_ns = 0; double dur_s = 20, gap_s = 0; const char *detail = NULL; int threads = 1, iters = 0, allow_cached = 0, do_sweep = 0, nruns = 3; size_t dist = 0; double size_mb = 256, warm = 2, settle = 0, window_s = 3;
    double mem_user = -1; int fit = 0, dist_given = 0; const char *row_kind = NULL; char sizes_cut[300]; double spin_ms = 2000;
    for (int i = 1; i < argc; i++) {
        const char *a = argv[i]; const char *v = i + 1 < argc ? argv[i + 1] : NULL;
        if (!strcmp(a, "--pin-probe")) continue;
        if (!strcmp(a, "--suite")) USAGE("--suite is not part of the engine"); else if (!strcmp(a, "--allow-cached")) allow_cached = 1; else if (!strcmp(a, "--pace-chase")) pace = 1; else if (!strcmp(a, "--pace-idle")) pace_idle = 1; else if (!strcmp(a, "--sweep")) do_sweep = 1;
        else if (!strcmp(a, "--json")) P1.json = 1; else if (!strcmp(a, "--allow-unpinned")) g_allow_unpinned = 1; else if (!strcmp(a, "--fit-buffer")) fit = 1;
        else if (!strcmp(a, "--list")) {
            printf("big(X1):"); for (int j = 0; j < C_BIG.n; j++) printf(" %d", C_BIG.cpu[j]); printf("\nmid(A76):"); for (int j = 0; j < C_MID.n; j++) printf(" %d", C_MID.cpu[j]);
            printf("\nlittle(A55):"); for (int j = 0; j < C_LITTLE.n; j++) printf(" %d", C_LITTLE.cpu[j]); printf("\nDC ZVA 64 B usable: %s\n", zva_ok() ? "yes" : "no"); return 0; }
        else if (!v) { usage(); p1_exitf(P1_FAILED, P1_EXIT_USAGE, "option %s needs a value (or is unknown)", a); } else if (!strcmp(a, "--kernel")) kern = argv[++i]; else if (!strcmp(a, "--threads")) threads = atoi(argv[++i]);
        else if (!strcmp(a, "--cores")) cores = argv[++i]; else if (!strcmp(a, "--size-mb")) size_mb = atof(argv[++i]); else if (!strcmp(a, "--iters")) iters = atoi(argv[++i]);
        else if (!strcmp(a, "--warmup-s")) warm = atof(argv[++i]); else if (!strcmp(a, "--settle-s")) settle = atof(argv[++i]); else if (!strcmp(a, "--csv")) csv = argv[++i];
        else if (!strcmp(a, "--prefetch-dist")) { dist = (size_t)atol(argv[++i]); dist_given = 1; } else if (!strcmp(a, "--runs")) nruns = atoi(argv[++i]);
        else if (!strcmp(a, "--pace-mode")) { const char *m = argv[++i]; if (!strcmp(m, "dsb")) g_pace_dsb = 1; else if (!strcmp(m, "spin")) g_pace_dsb = 0; else USAGE("bad --pace-mode %s", m); } else if (!strcmp(a, "--pace-ns")) pace_ns = atol(argv[++i]); else if (!strcmp(a, "--duration-s")) dur_s = atof(argv[++i]); else if (!strcmp(a, "--cycles")) cycles = atoi(argv[++i]);
        else if (!strcmp(a, "--gap-s")) gap_s = atof(argv[++i]); else if (!strcmp(a, "--detail")) detail = argv[++i]; else if (!strcmp(a, "--stop-file")) g_stopfile = argv[++i];
        else if (!strcmp(a, "--split")) kern2 = argv[++i]; else if (!strcmp(a, "--sizes")) sizes = argv[++i];
        else if (!strcmp(a, "--tag")) { static char tg[16]; snprintf(tg, sizeof tg, "%s-", argv[++i]); g_tag = tg; P1.tag = argv[i]; }   /* PART1: g_tag is the kit's run-name prefix; P1.tag is the full row tag */
        else if (!strcmp(a, "--window-s")) window_s = atof(argv[++i]); else if (!strcmp(a, "--timeline")) g_timeline = argv[++i]; else if (!strcmp(a, "--cpufreq-log")) g_cpufreq = argv[++i];
        else if (!strcmp(a, "--gate")) { const char *m = argv[++i]; if (!strcmp(m, "on")) P1.gate_on = 1; else if (!strcmp(m, "off")) P1.gate_on = 0; else USAGE("bad --gate %s (on|off)", m); }
        else if (!strcmp(a, "--mem-limit-mib")) { if (p1_parse_double(argv[++i], &mem_user) || mem_user < 0) USAGE("bad --mem-limit-mib %s", argv[i]); }
        else if (!strcmp(a, "--max-seconds")) { if (p1_parse_double(argv[++i], &p1_max_seconds) || p1_max_seconds <= 0) USAGE("bad --max-seconds %s", argv[i]); }
        else if (!strcmp(a, "--tier")) P1.tier = p1_opt_tier(argv[++i]);
        else if (!strcmp(a, "--row-kind")) row_kind = p1_opt_row_kind(argv[++i]);
        else if (!strcmp(a, "--spin-ms")) spin_ms = p1_opt_spin_ms(argv[++i]);
        else USAGE("unknown option %s", a);
    }
    if (P1.probe) pinprobe_run(cores, spin_ms);   /* PART1 B10: native pin probe (does not return) */
    if (nruns < 1 || nruns > 8) USAGE("--runs must be 1..8 (the kit buffer holds 8)");
    if (cycles < 1 || cycles > P1_MAXVALS) USAGE("--cycles must be 1..%d", P1_MAXVALS);
    if (pace || pace_idle) {
        if (!cores) USAGE("--pace-chase/--pace-idle needs --cores CPU");
        P1.kind = row_kind ? row_kind : "pacer"; snprintf(P1.variant, sizeof P1.variant, "%s pace_ns=%ld", pace_idle ? "pace_idle" : "pace_chase", pace_ns);
        p1_start_watchdog(); p1_gate();
        double need = pace_idle ? 0 : 272, lim = p1_mem_limit_mib(mem_user);   /* PART1: kit MEMFIX: 256 MiB chase buffer + 16 MiB chain table; fixed, --fit-buffer cannot shrink it */
        if (lim < 0) p1_exitf(P1_SKIPPED, P1_EXIT_MEMORY, "memory guard: MemTotal/MemAvailable unreadable, cannot prove the buffers fit; nothing allocated");
        if (need > lim) p1_exitf(P1_SKIPPED, P1_EXIT_MEMORY, "memory guard: fixed pacer buffer %.0f MiB > limit %.0f MiB = min(%s55%% of MemTotal %.0f MiB, 70%% of MemAvailable %.0f MiB); nothing allocated", need, lim, mem_user >= 0 ? "--mem-limit-mib, " : "", P1.mem_total_mib, P1.mem_avail_mib);
        P1.buffer_mib = need > 0 ? 256 : 0.00390625;
        int rc = pace_main(cores, pace_idle, pace_ns, dur_s, cycles, gap_s, settle, csv, detail, g_timeline);
        if (rc) p1_exitf(P1_FAILED, rc, "pacer failed (rc %d)", rc);
        p1_done_ok();
    }
    if (kern2 && !kern) kern = "K02";
    if (!kern) USAGE("--kernel is required");
    if (kern2 && !cores) cores = "big";
    if (!cores) cores = "all";
    cfg_t c; memset(&c, 0, sizeof c); c.k = find_kernel(kern);
    if (!c.k) USAGE("unknown kernel %s", kern);
    if (kern2) { c.k2 = find_kernel(kern2); if (!c.k2) USAGE("unknown kernel %s", kern2); threads = 2; if (P1.json) USAGE("--split has no --json form"); }
    if (parse_cores(cores, &c.cores)) USAGE("bad --cores %s", cores);
    p1_check_cores_unique(&c.cores);
    if (threads < 1 || threads > c.cores.n) USAGE("--threads %d needs %d cores in the list", threads, threads);
    if (size_mb < CACHED_MIN_MB && !allow_cached && !do_sweep) USAGE("--size-mb %g < %d needs --allow-cached", size_mb, CACHED_MIN_MB);
    P1.threads = threads; for (int i = 0; i < threads && i < P1_MAXCPUS; i++) P1.cpus[P1.ncpu++] = c.cores.cpu[i];
    P1.kind = row_kind ? row_kind : do_sweep ? "sweep" : (c.k->pf && dist_given) ? "prefetch" : c.k->kind == READ ? "read" : c.k->kind == WRITE ? "write" : "copy";
    snprintf(P1.variant, sizeof P1.variant, "%s", c.k->name); if (c.k->pf && !do_sweep) { size_t n = strlen(P1.variant); snprintf(P1.variant + n, sizeof P1.variant - n, " pfdist=%zu", dist ? dist : c.k->defdist); }
    p1_start_watchdog();
    p1_gate();   /* PART1: before anything is allocated or pinned */
    if ((c.k->zva || (c.k2 && c.k2->zva)) && !zva_ok()) { fprintf(stderr, "DC ZVA not usable with a 64 B block on this CPU: kernel skipped\n"); p1_exitf(P1_UNSUPPORTED, P1_EXIT_UNSUPPORTED, "DC ZVA not usable with a 64 B block on this CPU: kernel skipped"); }
    {   /* PART1: memory guard. need = what the row allocates: threads x per-thread buffer (a sweep: its largest total). */
        double lim = p1_mem_limit_mib(mem_user), need = do_sweep ? largest_size(sizes) : (double)threads * (double)((size_t)(size_mb * 1048576.0) & ~(size_t)255) / 1048576.0;
        if (lim < 0) p1_exitf(P1_SKIPPED, P1_EXIT_MEMORY, "memory guard: MemTotal/MemAvailable unreadable, cannot prove the buffers fit; nothing allocated");
        if (need > lim) {
            if (fit && do_sweep) { double lg; if (cut_sizes(sizes, lim, sizes_cut, sizeof sizes_cut, &lg) < 1) p1_exitf(P1_SKIPPED, P1_EXIT_MEMORY, "memory guard: no sweep size fits the limit %.0f MiB; nothing allocated", lim);
                sizes = sizes_cut; p1_label("smaller buffer"); }
            else if (fit && !do_sweep) { double x = floor(lim / threads);
                if (x < 64) p1_exitf(P1_SKIPPED, P1_EXIT_MEMORY, "memory guard: %d thread(s) x %.0f MiB > limit %.0f MiB and the smallest allowed buffer is 64 MiB per thread (floor(limit/threads) = %.0f); nothing allocated", threads, size_mb, lim, x);
                size_mb = x; p1_label("smaller buffer"); }
            else p1_exitf(P1_SKIPPED, P1_EXIT_MEMORY, "memory guard: working set %.0f MiB > limit %.0f MiB = min(%s55%% of MemTotal %.0f MiB, 70%% of MemAvailable %.0f MiB); nothing allocated", need, lim, mem_user >= 0 ? "--mem-limit-mib, " : "", P1.mem_total_mib, P1.mem_avail_mib); }
    }
    c.threads = threads; c.size = (size_t)(size_mb * 1048576.0) & ~(size_t)255; c.warm_s = warm; c.settle_s = settle; c.iters = iters; c.dist = dist ? dist : c.k->defdist; c.window_ns = (uint64_t)(window_s * 1e9);
    P1.buffer_mib = (double)c.size / 1048576.0;
    if (do_sweep) { sweep(csv, &c, sizes); p1_done_ok(); }
    if (kern2) { do_split(csv, &c, 1, nruns); return 0; }
    do_runs(csv, &c, 1, nruns); p1_done_ok();
    return 0;
}
