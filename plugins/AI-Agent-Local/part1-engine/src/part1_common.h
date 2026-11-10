/* part1_common.h (Part 1 engine, ADFA-6876 job P1-03). NEW file, not in the kit. Shared by libpart1_membw.so and libpart1_gpubw.so.
 * Provides: engine id, exit codes, the foreground gate (/proc/self/cpuset), the memory guard (/proc/meminfo), the self time limit (watchdog),
 * and the one JSON writer (format part1-rows-v1, one object on the last stdout line). Nothing here touches a measurement kernel.
 * Host tests: compile with -DP1_HOST_BUILD to let the environment variables P1_CPUSET_PATH and P1_MEMINFO_PATH replace the two /proc paths.
 * A production (Android) build never reads those variables. */
#ifndef PART1_COMMON_H
#define PART1_COMMON_H
#define _GNU_SOURCE
#include <errno.h>
#include <stdarg.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#ifndef P1_ENGINE_VERSION
#define P1_ENGINE_VERSION "0.0.0-dev"
#endif

/* ---- exit codes (documented in EXIT-CODES.md) ---- */
#define P1_EXIT_OK 0
#define P1_EXIT_FAILED 1       /* measurement or runtime failure: verification mismatch, allocation failure, Vulkan error, ... */
#define P1_EXIT_USAGE 2        /* bad or missing option */
#define P1_EXIT_UNSUPPORTED 3  /* the kernel/device cannot do this row (DC ZVA with a block other than 64 B; no Vulkan device or compute queue) */
#define P1_EXIT_PIN 87         /* kit PIN_RETRY_EXIT: could not pin within 5 s and --allow-unpinned not given */
#define P1_EXIT_MEMORY 88      /* kit MEMBW_NA_MEM_EXIT: buffers would exceed the memory limit; nothing allocated */
#define P1_EXIT_GATE 97        /* own cpuset is not /top-app (or unreadable) with the gate on */
#define P1_EXIT_TIMEOUT 124    /* --max-seconds exceeded */

#define P1_MAXLABELS 12
#define P1_MAXCPUS 64
#define P1_MAXVALS 64

typedef enum { P1_OK, P1_VOID, P1_UNSUPPORTED, P1_SKIPPED, P1_FAILED } p1_status_t;
typedef enum { P1_PIN_NA, P1_PIN_OK, P1_PIN_UNPINNED, P1_PIN_FAILED } p1_pin_t;

typedef struct {
    const char *prog;             /* "membw" or "gpubw" */
    const char *tag, *tier, *kind;/* from --tag, --tier, --row-kind or derived; NULL = not known */
    int json;                     /* --json given */
    int gate_on;                  /* default 1 */
    char gate_basis[48];          /* cpuset_top_app | gate_off | (empty before the gate ran) */
    char cpuset[96];              /* own cpuset as read, "" when unreadable */
    int cpuset_read;              /* 1 when cpuset[] holds a real reading */
    int cpus[P1_MAXCPUS], ncpu;   /* CPUs the row asked for; always kept (the writer applies the one cpu_set rule below, P1-03H A3) */
    int threads;
    double buffer_mib;            /* per-thread buffer actually used, < 0 unknown */
    double vals[P1_MAXVALS]; int nvals;   /* per-repeat GB/s (unsorted) */
    double median;                /* GB/s median of the repeats, < 0 none */
    int repeats, passes;          /* passes < 0: unknown */
    long minflt; int have_minflt; /* minor faults in the timed window */
    char variant[96], note[320];
    char labels[P1_MAXLABELS][48]; int nlabels;
    p1_pin_t pin; int pin_attempts; double pin_ms; int have_pin;
    int probe; struct { int index, ok, attempts; double ms; char midr[24]; int midr_src; } pcpu[P1_MAXCPUS]; int npcpu;   /* --pin-probe (membw): the output is a part1-pinprobe-v1 object, not a row */
    double mem_total_mib, mem_avail_mib, mem_limit_mib;   /* < 0 unknown */
    int stream_threads; int have_stream; /* gpubw: label-only CPU stream (--cpu-set / --cpu-threads) */
    uint64_t t0_ns;
} p1_ctx_t;

static p1_ctx_t P1 = { .gate_on = 1, .buffer_mib = -1, .median = -1, .passes = -1, .mem_total_mib = -1, .mem_avail_mib = -1, .mem_limit_mib = -1, .repeats = 1 };
static pthread_mutex_t p1_lock = PTHREAD_MUTEX_INITIALIZER;
static int p1_emitted;
static const char *p1_row_kinds[] = {"pinprobe", "read", "write", "copy", "gpu", "sweep", "prefetch", "pacer", "sustained", NULL};   /* the row schema's `kind` enum; --row-kind accepts only these */
static const char *p1_tiers[] = {"T1", "T2", "T3", NULL};   /* the row schema's `tier` enum; --tier is checked before it is stored, so a usage-error record never carries a bad value (review F02 of P1-03F) */
static int p1_tier_ok(const char *t) { for (int i = 0; p1_tiers[i]; i++) if (!strcmp(t, p1_tiers[i])) return 1; return 0; }
static int p1_row_kind_ok(const char *k) { for (int i = 0; p1_row_kinds[i]; i++) if (!strcmp(k, p1_row_kinds[i])) return 1; return 0; }

static uint64_t p1_now_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec; }
static void p1_init(const char *prog) { P1.prog = prog; P1.t0_ns = p1_now_ns(); }
static void p1_label(const char *l) {
    for (int i = 0; i < P1.nlabels; i++) if (!strcmp(P1.labels[i], l)) return;
    if (P1.nlabels < P1_MAXLABELS) snprintf(P1.labels[P1.nlabels++], sizeof P1.labels[0], "%s", l);
}
/* ---- the note (conditions.note): sentences joined by "; " in a fixed buffer. P1-03H (review F05 of P1-03G): text is never cut silently. When anything does not fit, the record gets
 * the label `note_truncated`. A priority sentence (the sweep points, the GPU dispatch rates) makes room by dropping the OLDEST non-priority sentences first (the gate and pin sentences);
 * only when it still does not fit is the priority text itself cut. A non-priority sentence that does not fit is cut. ---- */
#define P1_NOTE_SENT 12
static char p1_sent[P1_NOTE_SENT][sizeof ((p1_ctx_t *)0)->note]; static int p1_sent_prio[P1_NOTE_SENT], p1_nsent;
static size_t p1_note_used(void) { size_t n = 0; for (int i = 0; i < p1_nsent; i++) n += strlen(p1_sent[i]) + (i ? 2 : 0); return n; }
static void p1_note_rebuild(void) {
    size_t n = 0; P1.note[0] = 0;
    for (int i = 0; i < p1_nsent; i++) { int w = snprintf(P1.note + n, sizeof P1.note - n, "%s%s", i ? "; " : "", p1_sent[i]); if (w > 0 && (size_t)w < sizeof P1.note - n) n += (size_t)w; }
}
static void p1_note_add(int prio, const char *text) {
    size_t len = strlen(text), cap = sizeof P1.note - 1;
    if (prio) {   /* make room: drop the oldest non-priority sentences */
        for (int i = 0; i < p1_nsent && p1_note_used() + (p1_nsent ? 2 : 0) + len > cap; ) {
            if (p1_sent_prio[i]) { i++; continue; }
            for (int k = i; k + 1 < p1_nsent; k++) { memcpy(p1_sent[k], p1_sent[k + 1], sizeof p1_sent[k]); p1_sent_prio[k] = p1_sent_prio[k + 1]; }
            p1_nsent--; p1_label("note_truncated");
        }
    }
    size_t used = p1_note_used() + (p1_nsent ? 2 : 0), room = used < cap ? cap - used : 0;
    if (p1_nsent >= P1_NOTE_SENT) room = 0;
    if (len > room) { p1_label("note_truncated"); len = room; }
    if (len) { memcpy(p1_sent[p1_nsent], text, len); p1_sent[p1_nsent][len] = 0; p1_sent_prio[p1_nsent] = prio; p1_nsent++; }
    p1_note_rebuild();
}
static void p1_note(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void p1_note(const char *fmt, ...) {   /* append a sentence to the note; cut (and labelled) if it does not fit */
    char tb[2 * sizeof P1.note]; va_list ap; va_start(ap, fmt); vsnprintf(tb, sizeof tb, fmt, ap); va_end(ap); p1_note_add(0, tb);
}
static void p1_note_prio(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static void p1_note_prio(const char *fmt, ...) {   /* a priority sentence: older non-priority sentences give way */
    char tb[4 * sizeof P1.note]; va_list ap; va_start(ap, fmt); vsnprintf(tb, sizeof tb, fmt, ap); va_end(ap); p1_note_add(1, tb);
}

/* ---- JSON writer ---- */
static void p1_jstr(FILE *f, const char *s) {
    fputc('"', f);
    for (; s && *s; s++) {
        unsigned char c = (unsigned char)*s;
        if (c == '"' || c == '\\') { fputc('\\', f); fputc(c, f); }
        else if (c == '\n') fputs("\\n", f); else if (c == '\r') fputs("\\r", f); else if (c == '\t') fputs("\\t", f);
        else if (c < 0x20 || c >= 0x7f) fprintf(f, "\\u%04x", c < 0x80 ? c : 0xfffd);   /* never emit raw high bytes: output stays valid JSON */
        else fputc(c, f);
    }
    fputc('"', f);
}
static void p1_jnum(FILE *f, double v) { if (isfinite(v)) fprintf(f, "%.4f", v); else fputs("null", f); }
static const char *p1_status_name(p1_status_t s) { static const char *n[] = {"ok", "void", "unsupported", "skipped", "failed"}; return n[s]; }
static const char *p1_pin_name(p1_pin_t p) { static const char *n[] = {"not_applicable", "ok", "unpinned", "failed"}; return n[p]; }

/* A program with its own result object (membw --static-probe, gpubw --probe) installs its writer here; p1_write_json then calls it instead of writing a row. */
static void (*p1_custom_writer)(FILE *f, p1_status_t st, int exit_code, const char *reason);
/* --pin-probe output: one part1-pinprobe-v1 object (part1-pinprobe-v1.schema.json). Not a row. `reason` (optional) says why a refusal or error happened; `cpus` lists the CPUs probed so far. */
static void p1_write_probe(FILE *f, p1_status_t st, int exit_code, const char *reason) {
    double dur = (double)(p1_now_ns() - P1.t0_ns) / 1e6;
    fputs("{\"format\":\"part1-pinprobe-v1\",\"engine_version\":\"part1-engine/" P1_ENGINE_VERSION "\",\"gate_basis\":", f); p1_jstr(f, P1.gate_basis[0] ? P1.gate_basis : "none");
    fputs(",\"foreground_cpuset\":", f); if (P1.cpuset_read) p1_jstr(f, P1.cpuset); else fputs("null", f);
    fprintf(f, ",\"status\":\"%s\",\"exit_code\":%d,\"cpus\":[", p1_status_name(st), exit_code);
    for (int i = 0; i < P1.npcpu; i++) { fprintf(f, "%s{\"index\":%d,\"result\":\"%s\",\"attempts\":%d,\"time_to_pin_ms\":", i ? "," : "", P1.pcpu[i].index, P1.pcpu[i].ok ? "ok" : "failed", P1.pcpu[i].attempts); p1_jnum(f, P1.pcpu[i].ms);
        fputs(",\"midr\":", f); if (P1.pcpu[i].midr_src) p1_jstr(f, P1.pcpu[i].midr); else fputs("null", f);   /* P1-03H C2: MIDR of the CPU and where it came from (sysfs | cpuinfo | sysfs_unpinned | cpuinfo_unpinned; the last two for a CPU whose pin failed, P1-03I); null and null when neither gave it */
        fputs(",\"midr_source\":", f); if (P1.pcpu[i].midr_src) p1_jstr(f, (const char *[]){"", "sysfs", "cpuinfo", "sysfs_unpinned", "cpuinfo_unpinned"}[P1.pcpu[i].midr_src]); else fputs("null", f); fputc('}', f); }
    fputs("],\"duration_ms\":", f); p1_jnum(f, dur);
    if (reason && *reason) { fputs(",\"reason\":", f); p1_jstr(f, reason); }
    fputs("}\n", f);
}

/* Writes one part1-rows-v1 record (reason and minflt_timed are optional fields of the row schema since descriptor-v02 P24). */
static void p1_write_json(FILE *f, p1_status_t st, int exit_code, const char *reason) {
    if (p1_custom_writer) { p1_custom_writer(f, st, exit_code, reason); return; }
    if (P1.probe) { p1_write_probe(f, st, exit_code, reason); return; }
    double dur = (double)(p1_now_ns() - P1.t0_ns) / 1e9; int ok = st == P1_OK && P1.median >= 0;
    double mn = -1, mx = -1; if (P1.nvals >= 2) { mn = mx = P1.vals[0]; for (int i = 1; i < P1.nvals; i++) { if (P1.vals[i] < mn) mn = P1.vals[i]; if (P1.vals[i] > mx) mx = P1.vals[i]; } }
    int reps = P1.nvals > 0 ? P1.nvals : P1.repeats; if (reps < 1 || !ok) reps = 1;   /* a row without a value is one repeat: never a leftover count from an earlier sweep size (review F02) */
    fputs("{\"format\":\"part1-rows-v1\"", f);
    if (P1.tag) { fputs(",\"tag\":", f); p1_jstr(f, P1.tag); }
    fputs(",\"kind\":", f); p1_jstr(f, P1.kind ? P1.kind : "read");
    if (P1.tier) { fputs(",\"tier\":", f); p1_jstr(f, P1.tier); }
    p1_pin_t pr = P1.have_pin ? P1.pin : P1_PIN_NA;
    /* ONE rule for `cpu_set` (P1-03H A3): P1.ncpu always holds the CPUs the row asked for. The record lists them, except a row WITH a value (status ok) whose pin result is `unpinned`: it lists none (`[]`, conditions.cpu_set null), because no CPU was pinned for all of it. Every record without a value (failed, skipped, timeout) names the CPUs asked for, whatever its pin result. */
    int ncpu_out = (st == P1_OK && pr == P1_PIN_UNPINNED) ? 0 : P1.ncpu;
    fprintf(f, ",\"status\":\"%s\",\"cpu_set\":[", p1_status_name(st));
    for (int i = 0; i < ncpu_out; i++) fprintf(f, "%s%d", i ? "," : "", P1.cpus[i]);
    fprintf(f, "],\"threads\":%d,\"buffer_mib\":", P1.threads); if (P1.buffer_mib >= 0) p1_jnum(f, P1.buffer_mib); else fputs("null", f);
    fputs(",\"gbs_median\":", f); if (ok) p1_jnum(f, P1.median); else fputs("null", f);
    fputs(",\"gbs_min\":", f); if (ok && mn >= 0) p1_jnum(f, mn); else fputs("null", f);
    fputs(",\"gbs_max\":", f); if (ok && mx >= 0) p1_jnum(f, mx); else fputs("null", f);
    fprintf(f, ",\"repeats\":%d,\"single_shot\":%s", reps, reps == 1 ? "true" : "false");
    if (P1.passes >= 0) fprintf(f, ",\"passes\":%d", P1.passes);
    fputs(",\"variant\":", f); if (P1.variant[0]) p1_jstr(f, P1.variant); else fputs("null", f);
    fprintf(f, ",\"pin\":{\"result\":\"%s\",\"attempts\":", p1_pin_name(pr)); if (P1.have_pin) fprintf(f, "%d", P1.pin_attempts); else fputs("null", f);
    fputs(",\"time_to_pin_ms\":", f); if (P1.have_pin) p1_jnum(f, P1.pin_ms); else fputs("null", f);
    fprintf(f, "},\"exit_code\":%d,\"gate_basis\":", exit_code); p1_jstr(f, P1.gate_basis[0] ? P1.gate_basis : "none");
    fputs(",\"conditions\":{\"foreground_cpuset\":", f); if (P1.cpuset_read) p1_jstr(f, P1.cpuset); else fputs("null", f);
    fputs(",\"gate_basis\":", f); p1_jstr(f, P1.gate_basis[0] ? P1.gate_basis : "none");
    fputs(",\"buffer_mib\":", f); if (P1.buffer_mib >= 0) p1_jnum(f, P1.buffer_mib); else fputs("null", f);
    fputs(",\"cpu_set\":", f);
    if (ncpu_out) { fputc('"', f); for (int i = 0; i < ncpu_out; i++) fprintf(f, "%s%d", i ? "+" : "", P1.cpus[i]); fputc('"', f); } else fputs("null", f);
    fputs(",\"pin\":", f); if (P1.have_pin) p1_jstr(f, p1_pin_name(pr)); else fputs("null", f);
    fputs(",\"memavail_mib\":", f); if (P1.mem_avail_mib >= 0) p1_jnum(f, P1.mem_avail_mib); else fputs("null", f);
    fputs(",\"memtotal_mib\":", f); if (P1.mem_total_mib >= 0) p1_jnum(f, P1.mem_total_mib); else fputs("null", f);
    fprintf(f, ",\"regime\":%s,\"threads\":%d", ok ? (reps == 1 ? "\"single_shot\"" : "\"median_of_repeats\"") : "null", P1.threads);
    fputs(",\"labels\":[", f); for (int i = 0; i < P1.nlabels; i++) { if (i) fputc(',', f); p1_jstr(f, P1.labels[i]); }
    fputs("],\"note\":", f);   /* a refusal or error puts its reason in the note too, so the row is complete without the engine-only `reason` field */
    { char nb[sizeof P1.note + 320]; nb[0] = 0; if (P1.note[0]) snprintf(nb, sizeof nb, "%s", P1.note);
      if (st != P1_OK && reason && *reason) { size_t n = strlen(nb); snprintf(nb + n, sizeof nb - n, "%s%s", n ? "; " : "", reason); }
      if (nb[0]) p1_jstr(f, nb); else fputs("null", f); }
    fputs("},\"duration_s\":", f); p1_jnum(f, dur);
    fputs(",\"duration_basis\":\"wall_clock_since_process_start\",\"engine_version\":\"part1-engine/" P1_ENGINE_VERSION "\",\"origin\":\"measured_app\",\"labels\":[", f);
    for (int i = 0; i < P1.nlabels; i++) { if (i) fputc(',', f); p1_jstr(f, P1.labels[i]); }
    fputc(']', f);
    if (reason && *reason) { fputs(",\"reason\":", f); p1_jstr(f, reason); }
    if (P1.have_minflt) fprintf(f, ",\"minflt_timed\":%ld", P1.minflt);
    fputs("}\n", f);
}

/* Emits the result object once. Refusals and errors are always emitted (with or without --json); a success only with --json. */
/* hold: keep stdout locked after the line is written (the caller exits at once), so no other thread can print after the result line (review F11). */
static void p1_emit(p1_status_t st, int exit_code, const char *reason, int hold) {
    if (st == P1_OK && !P1.json) return;
    flockfile(stdout); fflush(stdout); p1_write_json(stdout, st, exit_code, reason); fflush(stdout); if (!hold) funlockfile(stdout);
}
/* Finish with a status: emit and exit(code). Used from the main thread. Idempotent against the watchdog. */
static void p1_exit(p1_status_t st, int code, const char *reason) {
    pthread_mutex_lock(&p1_lock);
    if (!p1_emitted) { p1_emitted = 1; p1_emit(st, code, reason, 1); }   /* stdout stays locked until exit */
    pthread_mutex_unlock(&p1_lock);
    exit(code);
}
static void p1_exitf(p1_status_t st, int code, const char *fmt, ...) __attribute__((format(printf, 3, 4)));
static void p1_exitf(p1_status_t st, int code, const char *fmt, ...) { char b[300]; va_list ap; va_start(ap, fmt); vsnprintf(b, sizeof b, fmt, ap); va_end(ap); fprintf(stderr, "%s: %s\n", P1.prog ? P1.prog : "part1", b); p1_exit(st, code, b); }
static void p1_done_ok(void) {
    if (P1.json && !P1.probe && !p1_custom_writer && P1.median < 0) p1_exitf(P1_FAILED, P1_EXIT_FAILED, "internal: the row finished without a measured value; reported as failed, never as ok");   /* with --json an ok row always has a number (review F04); without --json the programs behave as the kit's (review F03 of P1-03F: exit 0, no JSON line) */
    p1_exit(P1_OK, 0, NULL);
}

/* ---- the PINRETRY_* environment of the kit's pinretry.h is for host tests only: a production binary clears it (review F16) ---- */
static void p1_clear_host_env(void) {
#ifndef P1_HOST_BUILD
    unsetenv("PINRETRY_STEP_MS"); unsetenv("PINRETRY_MAX_MS");
#endif
}

/* ---- self time limit (--max-seconds): a watchdog thread reports `timeout` and exits 124 ---- */
static double p1_max_seconds = -1;
static void (*p1_timeout_hook)(void);   /* membw sets it: folds the pin facts of the runs finished so far into the record before the timeout object is written (review F05 of P1-03F) */
static void *p1_watchdog(void *arg) {
    (void)arg; uint64_t dl = P1.t0_ns + (uint64_t)(p1_max_seconds * 1e9);
    for (;;) { uint64_t n = p1_now_ns(); if (n >= dl) break; uint64_t d = dl - n; struct timespec ts = {(time_t)(d / 1000000000ull), (long)(d % 1000000000ull)}; nanosleep(&ts, NULL); }
    pthread_mutex_lock(&p1_lock);
    if (p1_emitted) { pthread_mutex_unlock(&p1_lock); return NULL; }   /* the row finished first */
    p1_emitted = 1; p1_label("timeout"); if (p1_timeout_hook) p1_timeout_hook();
    char b[120]; snprintf(b, sizeof b, "timeout: the row ran longer than --max-seconds %g", p1_max_seconds);
    fprintf(stderr, "%s: %s\n", P1.prog, b); p1_emit(P1_FAILED, P1_EXIT_TIMEOUT, b, 1); fflush(NULL); _exit(P1_EXIT_TIMEOUT);   /* stdout stays locked from the emit until _exit: the timeout object is the last stdout line (review F11) */
}
static void p1_start_watchdog(void) {
    if (p1_max_seconds <= 0) return;
    pthread_t t; if (pthread_create(&t, NULL, p1_watchdog, NULL) == 0) pthread_detach(t);
}

/* ---- paths (host tests may redirect them; production never reads the variables) ---- */
static const char *p1_path(const char *env, const char *dflt) {
#ifdef P1_HOST_BUILD
    const char *v = getenv(env); if (v && *v) return v;
#else
    (void)env;
#endif
    return dflt;
}

/* ---- foreground gate ---- */
static void p1_read_cpuset(void) {   /* the program's own cpuset as read (also used by the static probe, which has no gate) */
    FILE *f = fopen(p1_path("P1_CPUSET_PATH", "/proc/self/cpuset"), "r");
    if (f) { if (fgets(P1.cpuset, sizeof P1.cpuset, f)) { size_t n = strlen(P1.cpuset); while (n && (P1.cpuset[n - 1] == '\n' || P1.cpuset[n - 1] == '\r')) P1.cpuset[--n] = 0; P1.cpuset_read = 1; } fclose(f); }
}
static void p1_gate(void) {
    p1_read_cpuset();
    if (!P1.gate_on) { snprintf(P1.gate_basis, sizeof P1.gate_basis, "gate_off"); p1_label("gate_off"); p1_note("%s", "foreground gate was switched off (--gate off): desk/shell test only"); return; }
    if (P1.cpuset_read && !strcmp(P1.cpuset, "/top-app")) { snprintf(P1.gate_basis, sizeof P1.gate_basis, "cpuset_top_app"); return; }
    snprintf(P1.gate_basis, sizeof P1.gate_basis, "cpuset_not_top_app");
    p1_label("not_foreground");
    if (P1.cpuset_read) p1_exitf(P1_SKIPPED, P1_EXIT_GATE, "foreground gate: own cpuset is '%s', not /top-app; nothing measured", P1.cpuset);
    p1_exitf(P1_SKIPPED, P1_EXIT_GATE, "foreground gate: own cpuset could not be read; nothing measured");
}

/* ---- memory guard ---- */
static long long p1_meminfo_kb(const char *key) {
    FILE *f = fopen(p1_path("P1_MEMINFO_PATH", "/proc/meminfo"), "r"); char ln[160]; long long v = -1; size_t kl = strlen(key); if (!f) return -1;
    while (fgets(ln, sizeof ln, f)) if (!strncmp(ln, key, kl)) { v = atoll(ln + kl); break; }
    fclose(f); return v;
}
/* limit (MiB) = min(user_limit if given, 55 % MemTotal, 70 % MemAvailable). Returns < 0 when MemTotal or MemAvailable cannot be read (the caller refuses). */
static double p1_mem_limit_mib(double user_limit) {
    long long mt = p1_meminfo_kb("MemTotal:"), ma = p1_meminfo_kb("MemAvailable:");
    if (mt > 0) P1.mem_total_mib = (double)mt / 1024.0;
    if (ma >= 0) P1.mem_avail_mib = (double)ma / 1024.0;
    if (mt <= 0 || ma < 0) return -1;
    double lim = 0.55 * P1.mem_total_mib, l2 = 0.70 * P1.mem_avail_mib; if (l2 < lim) lim = l2;
    if (user_limit >= 0 && user_limit < lim) lim = user_limit;
    P1.mem_limit_mib = lim; return lim;
}

/* ---- option value parsing ---- */
static int p1_parse_double(const char *s, double *out) { char *e; errno = 0; double v = strtod(s, &e); if (e == s || *e || errno || !isfinite(v)) return -1; *out = v; return 0; }
#endif
