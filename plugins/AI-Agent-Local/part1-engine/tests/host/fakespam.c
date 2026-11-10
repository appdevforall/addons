/* fakespam.c (host test only): LD_PRELOAD shim. Interposes _exit: just before the program really leaves, another thread prints a line to stdout and flushes it,
 * like a program that is still printing (gpubw prints a "# dispatch" line per dispatch) when its time limit fires. If the program still holds the stdout
 * lock (as 0.2.0 does from the emit of the timeout object until _exit) that thread blocks and nothing is printed; if the lock was released (0.1.0 released it
 * inside the emit) the line comes after the result object and the test sees a last line that is not the object (review F11). */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <unistd.h>
static void *late(void *a) { (void)a; puts("# late line after the result object"); fflush(stdout); return 0; }
void _exit(int code) {
    pthread_t t;
    if (pthread_create(&t, 0, late, 0) == 0) usleep(150000);
    void (*real)(int) = dlsym(RTLD_NEXT, "_exit");
    real(code);
    for (;;) ;
}
