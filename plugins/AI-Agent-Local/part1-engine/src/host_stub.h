/* host_stub.h (Part 1 engine, NEW, host tests only): plain-C stand-ins for the AArch64 asm kernels K01..K12 so that the whole program
 * (option parsing, gate, memory guard, pinning, time limit, JSON) can run on an x86 desk machine. They keep the kit's contract:
 * read kernels return the XOR of all 64-bit words, write kernels fill with the pattern in d (K09 zeroes), copy kernels copy n bytes a -> b.
 * They are NOT measurement code; this file is never compiled into an Android binary (membw.c includes it only when __aarch64__ is undefined). */
static uint64_t hs_read(void *a, size_t n) { const uint64_t *p = a; uint64_t x = 0; for (size_t i = 0; i < n / 8; i++) x ^= p[i]; return x; }
static uint64_t k01(void *a, void *b, size_t n, size_t d) { (void)b; (void)d; return hs_read(a, n); }
static uint64_t k02(void *a, void *b, size_t n, size_t d) { (void)b; (void)d; return hs_read(a, n); }
static uint64_t k03(void *a, void *b, size_t n, size_t d) { (void)b; (void)d; return hs_read(a, n); }
static uint64_t k04(void *a, void *b, size_t n, size_t d) { (void)b; (void)d; return hs_read(a, n); }
static uint64_t k05(void *a, void *b, size_t n, size_t d) { (void)b; (void)d; return hs_read(a, n); }
static uint64_t k06(void *a, void *b, size_t n, size_t d) { (void)b; (void)d; return hs_read(a, n); }
static uint64_t k07(void *a, void *b, size_t n, size_t d) { (void)b; memset(a, (int)(d & 0xFF), n); return 0; }
static uint64_t k08(void *a, void *b, size_t n, size_t d) { (void)b; memset(a, (int)(d & 0xFF), n); return 0; }
static uint64_t k09(void *a, void *b, size_t n, size_t d) { (void)b; (void)d; memset(a, 0, n); return 0; }
static uint64_t k10(void *a, void *b, size_t n, size_t d) { (void)d; memcpy(b, a, n); return 0; }
static uint64_t k11(void *a, void *b, size_t n, size_t d) { (void)d; memcpy(b, a, n); return 0; }
static uint64_t k12(void *a, void *b, size_t n, size_t d) { (void)b; (void)d; return hs_read(a, n); }
