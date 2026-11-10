/* fakeocl.c (host test only, P1-03I item 5): a stand-in for a vendor OpenCL library whose start-up code (a constructor) misbehaves. Built three times with -DFAKE_OCL_MODE=<n>:
 * 1 crash (SIGSEGV in the constructor), 2 hang (writes its pid to the file named by FAKE_OCL_PIDFILE, then sleeps), 3 chatter (prints to stdout and stderr, then loads fine),
 * 4 abort (SIGABRT), 5 exit(0) in the constructor (dlopen never returns), 6 starts a grandchild process (pid in FAKE_OCL_GCPIDFILE) and loads fine, 7 starts a grandchild and then hangs,
 * 8 starts a grandchild and then crashes (P1-03J). Never part of the engine; the production build has no such code. */
#include <signal.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>
#if FAKE_OCL_MODE >= 6
static void grandchild(void) {   /* a process started by the library's start-up code: lives 100 s unless the probe kills its group */
    pid_t g = fork();
    if (g == 0) { const char *p = getenv("FAKE_OCL_GCPIDFILE"); if (p) { FILE *f = fopen(p, "w"); if (f) { fprintf(f, "%d\n", (int)getpid()); fclose(f); } } for (;;) sleep(100); }
    for (int i = 0; i < 200; i++) { const char *p = getenv("FAKE_OCL_GCPIDFILE"); if (p && access(p, F_OK) == 0) { FILE *f = fopen(p, "r"); int v = 0; if (f) { if (fscanf(f, "%d", &v) != 1) v = 0; fclose(f); } if (v) break; } usleep(5000); }   /* the pid file is complete before the constructor goes on */
}
#endif
static void no_core(void) { struct rlimit r = {0, 0}; setrlimit(RLIMIT_CORE, &r); prctl(PR_SET_DUMPABLE, 0); }   /* a crash must end the process at once: no core handler (apport) holds it for a second or more under load */
__attribute__((constructor)) static void fake_ocl_ctor(void) {
#if FAKE_OCL_MODE == 1
    no_core(); *(volatile int *)0 = 1;
#elif FAKE_OCL_MODE == 2
    const char *p = getenv("FAKE_OCL_PIDFILE"); if (p) { FILE *f = fopen(p, "w"); if (f) { fprintf(f, "%d\n", (int)getpid()); fclose(f); } }
    for (;;) sleep(100);
#elif FAKE_OCL_MODE == 3
    printf("{\"format\":\"not-the-probe\"}\n"); fprintf(stderr, "vendor chatter\n"); fflush(NULL);
#elif FAKE_OCL_MODE == 4
    no_core(); abort();
#elif FAKE_OCL_MODE == 5
    exit(0);
#elif FAKE_OCL_MODE == 6
    grandchild();
#elif FAKE_OCL_MODE == 7
    grandchild(); for (;;) sleep(100);
#elif FAKE_OCL_MODE == 8
    grandchild(); no_core(); *(volatile int *)0 = 1;
#endif
}
