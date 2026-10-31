// ADFA-6810 measurement aid. Evicts one file's clean pages from the kernel page cache via
// posix_fadvise(DONTNEED), so a repeated model load can be measured cold without root (which
// `/proc/sys/vm/drop_caches` needs) and without a reboot.
//
// Standalone and NOT part of the ai-agent-local plugin: not referenced by any Gradle build here,
// never compiled by assemblePlugin, never packaged into the .cgp. Build and run it manually; see
// evict-page-cache.sh.
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
  if (argc != 2) {
    fprintf(stderr, "usage: %s <path-to-file>\n", argv[0]);
    return 2;
  }

  const char *path = argv[1];
  int fd = open(path, O_RDONLY);
  if (fd < 0) {
    perror("open");
    return 1;
  }

  // Whole-file range (offset 0, length 0): only evicts pages that are clean and unmapped, so it
  // is safe to run with other processes still holding the file open for unrelated reads.
  int rc = posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
  close(fd);

  if (rc != 0) {
    fprintf(stderr, "posix_fadvise: %s\n", strerror(rc));
    return 1;
  }

  printf("evicted: %s\n", path);
  return 0;
}
