/* gpubw: GPU memory bandwidth with a Vulkan compute shader (ADFA-6890 E6). Standalone executable, no Java, no root.
 * GB/s = bytes moved / ns (1 GB = 1e9 B); read counts bytes read, write bytes written, copy read + write.
 * Usage: gpubw [--csv path] [--tag T] [--size-mb 512] [--dispatches 5] [--warmup-dispatches 20] [--modes 0,3,1,2,4,5] [--per-wg 1024,4096,16384] */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <poll.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <sys/prctl.h>   /* PART1 (P1-03H C3): dlopen for the OpenCL child-process check of --probe */
#include "shader_spv.h"
#include "../part1_common.h"   /* PART1: gate, memory guard, time limit, JSON writer (new file) */

#define PEAK_GBS 51.2
#define WORDK 0x9E3779B1u
#define CK(x) do { VkResult r_ = (x); if (r_ != VK_SUCCESS) { fprintf(stderr, "%s:%d %s = %d\n", __FILE__, __LINE__, #x, (int)r_); p1_exitf(P1_FAILED, P1_EXIT_FAILED, "Vulkan call failed: %s = %d (line %d)", #x, (int)r_, __LINE__); } } while (0)   /* PART1: was exit(1); now one JSON object and exit 1 */

static uint64_t boot_ns(void) { struct timespec t; clock_gettime(CLOCK_BOOTTIME, &t); return (uint64_t)t.tv_sec * 1000000000ull + t.tv_nsec; }
static uint64_t now_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000ull + t.tv_nsec; }
static int cmp_d(const void *a, const void *b) { double x = *(const double *)a, y = *(const double *)b; return x < y ? -1 : x > y; }

static VkPhysicalDeviceMemoryProperties g_mp;
static int g_src_type = -1, g_dst_type = -1;   /* --src-memtype / --dst-memtype / --memtype (both): memory type index, -1 = default (host-visible coherent) */
static int g_stage = 0;                        /* --stage: never map src/dst; fill and verify them through a host-visible staging buffer and vkCmdCopyBuffer */
static VkDevice g_dev; static VkQueue g_q; static VkCommandPool g_cp;
static uint32_t g_bufmask;   /* memoryTypeBits of a storage buffer, set by make_buf */
static uint32_t pick_type(uint32_t bits, VkMemoryPropertyFlags want, VkMemoryPropertyFlags avoid) {
    for (uint32_t i = 0; i < g_mp.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (g_mp.memoryTypes[i].propertyFlags & want) == want && !(g_mp.memoryTypes[i].propertyFlags & avoid)) return i;
    return ~0u;
}
typedef struct { VkBuffer buf; VkDeviceMemory mem; void *map; VkDeviceSize size; uint32_t type; } buf_t;
/* forced: memory type index or -1 for the default host-visible coherent type; want_map: map it for the host if the type is host-visible */
static buf_t make_buf(VkDevice dev, VkDeviceSize size, int forced, int want_map) {
    buf_t b = {0}; b.size = size;
    VkBufferCreateInfo bi = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, 0, 0, size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, 0};
    CK(vkCreateBuffer(dev, &bi, NULL, &b.buf));
    VkMemoryRequirements mr; vkGetBufferMemoryRequirements(dev, b.buf, &mr);
    VkMemoryPropertyFlags hv = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    uint32_t t = pick_type(mr.memoryTypeBits, hv | VK_MEMORY_PROPERTY_HOST_CACHED_BIT, 0);
    if (t == ~0u) t = pick_type(mr.memoryTypeBits, hv, 0);
    if (forced >= 0) { if (!(mr.memoryTypeBits & (1u << forced))) { fprintf(stderr, "memtype %d not allowed for a buffer (mask 0x%x)\n", forced, mr.memoryTypeBits); p1_exitf(P1_FAILED, P1_EXIT_FAILED, "memtype %d not allowed for a buffer", forced); } t = (uint32_t)forced; }
    g_bufmask = mr.memoryTypeBits; printf("# buffer memoryTypeBits 0x%x -> memtype %u\n", mr.memoryTypeBits, t);
    if (t == ~0u) { fprintf(stderr, "no usable memory type\n"); p1_exitf(P1_UNSUPPORTED, P1_EXIT_UNSUPPORTED, "no usable Vulkan memory type for a storage buffer"); }
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, 0, mr.size, t};
    CK(vkAllocateMemory(dev, &ai, NULL, &b.mem)); CK(vkBindBufferMemory(dev, b.buf, b.mem, 0));
    if (want_map && (g_mp.memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) CK(vkMapMemory(dev, b.mem, 0, size, 0, &b.map));
    b.type = t; return b;
}
static void free_buf(buf_t *b) { if (b->map) vkUnmapMemory(g_dev, b->mem); vkDestroyBuffer(g_dev, b->buf, NULL); vkFreeMemory(g_dev, b->mem, NULL); memset(b, 0, sizeof *b); }

/* non-coherent memory types need an explicit flush (host -> device) and invalidate (device -> host) */
static void sync_buf(VkDevice dev, const buf_t *b, int invalidate) {
    if (!b->map || (g_mp.memoryTypes[b->type].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) return;
    VkMappedMemoryRange r = {VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE, 0, b->mem, 0, VK_WHOLE_SIZE};
    CK(invalidate ? vkInvalidateMappedMemoryRanges(dev, 1, &r) : vkFlushMappedMemoryRanges(dev, 1, &r));
}
/* one-shot buffer copy followed by a transfer-write -> shader/transfer memory barrier, then wait */
static void copy_buf(VkBuffer s, VkBuffer d, VkDeviceSize n) {
    VkCommandBufferAllocateInfo ai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, 0, g_cp, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1}; VkCommandBuffer c; CK(vkAllocateCommandBuffers(g_dev, &ai, &c));
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, 0, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, 0}; CK(vkBeginCommandBuffer(c, &bi));
    VkBufferCopy r = {0, 0, n}; vkCmdCopyBuffer(c, s, d, 1, &r);
    VkMemoryBarrier mb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, 0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
    CK(vkEndCommandBuffer(c)); VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, 0, 0, 0, 0, 1, &c, 0, 0}; CK(vkQueueSubmit(g_q, 1, &si, 0)); CK(vkQueueWaitIdle(g_q)); vkFreeCommandBuffers(g_dev, g_cp, 1, &c);
}

static const char *CSV_HDR = "kernel,threads,cores,size_mb,prefetch_dist,run,median_ns,best_ns,bytes,gbs_median,gbs_best,pct_theoretical,minflt_timed,passes\n";
static void row(const char *csv, const char *name, const char *cores, double mb, const char *run, double med_ns, double best_ns, double bytes, int passes) {
    double gm = bytes / med_ns, gb = bytes / best_ns;
    printf("%-14s %-22s %6g %-8s %12.0f %12.0f %9.2f %9.2f %6.1f\n", name, cores, mb, run, med_ns, best_ns, gm, gb, 100 * gm / PEAK_GBS); fflush(stdout);
    if (csv) { int hdr = access(csv, F_OK) != 0; FILE *f = fopen(csv, "a"); if (!f) { perror(csv); return; } if (hdr) fputs(CSV_HDR, f);
        fprintf(f, "%s,1,%s,%g,0,%s,%.0f,%.0f,%.0f,%.3f,%.3f,%.2f,0,%d\n", name, cores, mb, run, med_ns, best_ns, bytes, gm, gb, 100 * gm / PEAK_GBS, passes); fclose(f); }
}

/* ---- P1-03H C3: --probe. Measures nothing; asks the Vulkan loader for the instance, the first physical device (the one the measurement uses) and its properties, features, queue families and memory heaps;
 * creates no device and allocates no buffer. Then, for each OpenCL candidate name, whether dlopen succeeds IN THIS CHILD PROCESS (it may differ from loading inside the app process). One object
 * `part1-gpuprobe-v1`, not a row. No Vulkan device is not an error (exit 0, available false). ---- */
static const char *const GP_OCL[4] = {"libOpenCL.so", "libOpenCL-pixel.so", "/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so"};
enum { GP_O_LOADED, GP_O_DLERROR, GP_O_CRASHED, GP_O_TIMEOUT, GP_O_NOTRUN, GP_O_NOTTRIED };
static const char *const GP_O_NAME[6] = {"loaded", "dlerror", "crashed", "timeout", "not_run", "not_tried"};
static struct {
    int avail; char reason[200]; char name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE + 1]; const char *dtype; unsigned vendor, device, amaj, amin, apat, driver; int compute, f16, i8, dot; /* f16, i8, dot: 1/0, -1 = query not available */
    int nheaps; double heap_mib[VK_MAX_MEMORY_HEAPS];
    int ocl_ok[4], ocl_out[4], ocl_sig[4]; char ocl_err[4][240];   /* ocl_out: GP_O_* (P1-03I) */
} GP = {.f16 = -1, .i8 = -1, .dot = -1};
static const char *gp_dtype(VkPhysicalDeviceType t) {
    switch (t) { case VK_PHYSICAL_DEVICE_TYPE_OTHER: return "OTHER"; case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "INTEGRATED_GPU"; case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "DISCRETE_GPU";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "VIRTUAL_GPU"; case VK_PHYSICAL_DEVICE_TYPE_CPU: return "CPU"; default: return "UNKNOWN"; }
}
static void gp_write(FILE *f, p1_status_t st, int exit_code, const char *reason) {
    fprintf(f, "{\"format\":\"part1-gpuprobe-v1\",\"engine_version\":\"part1-engine/" P1_ENGINE_VERSION "\",\"status\":\"%s\",\"exit_code\":%d", p1_status_name(st), exit_code);
    if (st == P1_OK) {
        fprintf(f, ",\"vulkan\":{\"available\":%s", GP.avail ? "true" : "false");
        if (GP.avail) {
            fputs(",\"device_name\":", f); p1_jstr(f, GP.name); fputs(",\"device_type\":", f); p1_jstr(f, GP.dtype);
            fprintf(f, ",\"vendor_id\":%u,\"device_id\":%u,\"api_version\":\"%u.%u.%u\",\"driver_version\":%u,\"compute_queue\":%s", GP.vendor, GP.device, GP.amaj, GP.amin, GP.apat, GP.driver, GP.compute ? "true" : "false");
            fprintf(f, ",\"shader_float16\":%s,\"shader_int8\":%s,\"integer_dot_product\":%s,\"memory_heaps_mib\":[", GP.f16 < 0 ? "null" : GP.f16 ? "true" : "false", GP.i8 < 0 ? "null" : GP.i8 ? "true" : "false", GP.dot < 0 ? "null" : GP.dot ? "true" : "false");
            for (int i = 0; i < GP.nheaps; i++) fprintf(f, "%s%.2f", i ? "," : "", GP.heap_mib[i]);
            fputc(']', f);
        } else if (GP.reason[0]) { fputs(",\"reason\":", f); p1_jstr(f, GP.reason); }
        fputs("},\"opencl_child\":[", f);
        for (int i = 0; i < 4; i++) { fprintf(f, "%s{\"name\":", i ? "," : ""); p1_jstr(f, GP_OCL[i]); fprintf(f, ",\"loaded\":%s,\"outcome\":\"%s\",\"error\":", GP.ocl_out[i] == GP_O_LOADED ? "true" : "false", GP_O_NAME[GP.ocl_out[i]]);
            if (GP.ocl_out[i] == GP_O_DLERROR || GP.ocl_out[i] == GP_O_NOTRUN) p1_jstr(f, GP.ocl_err[i]); else fputs("null", f);
            fputs(",\"signal\":", f); if (GP.ocl_out[i] == GP_O_CRASHED) fprintf(f, "%d", GP.ocl_sig[i]); else fputs("null", f); fputc('}', f); }
        fputc(']', f);
        fputs(",\"foreground_cpuset\":", f); if (P1.cpuset_read) p1_jstr(f, P1.cpuset); else fputs("null", f);   /* P1-03I (review F01): what /proc/self/cpuset held, as the static probe records it (no gate here) */
    }
    if (reason && *reason) { fputs(",\"reason\":", f); p1_jstr(f, reason); }
    fputs("}\n", f);
}
static int gp_has_ext(const VkExtensionProperties *e, uint32_t n, const char *name) { for (uint32_t i = 0; i < n; i++) if (!strcmp(e[i].extensionName, name)) return 1; return 0; }
static void gp_vulkan(void) {
    uint32_t iv = VK_API_VERSION_1_1; if (vkEnumerateInstanceVersion(&iv) != VK_SUCCESS) iv = VK_API_VERSION_1_1;   /* ask for the newest version the loader has, up to 1.3: the core feature structs below need it */
    if (iv > VK_API_VERSION_1_3) iv = VK_API_VERSION_1_3;
    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, 0, "gpubw-probe", 1, "none", 1, iv};
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, 0, 0, &app, 0, 0, 0, 0};
    VkInstance inst; VkResult r = vkCreateInstance(&ici, NULL, &inst);
    if (r != VK_SUCCESS) { snprintf(GP.reason, sizeof GP.reason, "vkCreateInstance returned %d", (int)r); return; }
    uint32_t np = 0; if (vkEnumeratePhysicalDevices(inst, &np, NULL) != VK_SUCCESS || !np) { snprintf(GP.reason, sizeof GP.reason, "no Vulkan physical device"); return; }
    VkPhysicalDevice pd[8]; if (np > 8) np = 8; if (vkEnumeratePhysicalDevices(inst, &np, pd) < 0 || !np) { snprintf(GP.reason, sizeof GP.reason, "no Vulkan physical device"); return; }
    VkPhysicalDeviceProperties pp; vkGetPhysicalDeviceProperties(pd[0], &pp);
    GP.avail = 1; snprintf(GP.name, sizeof GP.name, "%s", pp.deviceName); GP.dtype = gp_dtype(pp.deviceType); GP.vendor = pp.vendorID; GP.device = pp.deviceID; GP.driver = pp.driverVersion;
    GP.amaj = VK_VERSION_MAJOR(pp.apiVersion); GP.amin = VK_VERSION_MINOR(pp.apiVersion); GP.apat = VK_VERSION_PATCH(pp.apiVersion);
    uint32_t nq = 0; vkGetPhysicalDeviceQueueFamilyProperties(pd[0], &nq, NULL); VkQueueFamilyProperties qf[16]; if (nq > 16) nq = 16; vkGetPhysicalDeviceQueueFamilyProperties(pd[0], &nq, qf);
    for (uint32_t i = 0; i < nq; i++) if (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) GP.compute = 1;
    VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(pd[0], &mp);
    GP.nheaps = (int)mp.memoryHeapCount; if (GP.nheaps > (int)VK_MAX_MEMORY_HEAPS) GP.nheaps = (int)VK_MAX_MEMORY_HEAPS; for (int i = 0; i < GP.nheaps; i++) GP.heap_mib[i] = (double)mp.memoryHeaps[i].size / 1048576.0;
    /* features: one chain, one struct per family (the extension's struct when the device lists the extension, else the core struct when min(instance, device version) has it, else not available) */
    static VkExtensionProperties ext[512]; uint32_t ne = 512; if (vkEnumerateDeviceExtensionProperties(pd[0], NULL, &ne, ext) < 0) ne = 0; if (ne > 512) ne = 512;
    uint32_t eff = pp.apiVersion < iv ? pp.apiVersion : iv;
    int x_ff = gp_has_ext(ext, ne, "VK_KHR_shader_float16_int8"), c_12 = !x_ff && eff >= VK_API_VERSION_1_2, x_dp = gp_has_ext(ext, ne, "VK_KHR_shader_integer_dot_product"), c_13 = !x_dp && eff >= VK_API_VERSION_1_3;
    VkPhysicalDeviceFeatures2 f2 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2}; void **tail = &f2.pNext;
    VkPhysicalDeviceShaderFloat16Int8Features ff = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES}; VkPhysicalDeviceVulkan12Features f12 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceShaderIntegerDotProductFeatures fd = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_INTEGER_DOT_PRODUCT_FEATURES}; VkPhysicalDeviceVulkan13Features f13 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
    if (x_ff) { *tail = &ff; tail = &ff.pNext; } else if (c_12) { *tail = &f12; tail = &f12.pNext; }
    if (x_dp) { *tail = &fd; tail = &fd.pNext; } else if (c_13) { *tail = &f13; tail = &f13.pNext; }
    if (x_ff || c_12 || x_dp || c_13) vkGetPhysicalDeviceFeatures2(pd[0], &f2);
    if (x_ff) { GP.f16 = ff.shaderFloat16 ? 1 : 0; GP.i8 = ff.shaderInt8 ? 1 : 0; } else if (c_12) { GP.f16 = f12.shaderFloat16 ? 1 : 0; GP.i8 = f12.shaderInt8 ? 1 : 0; }
    if (x_dp) GP.dot = fd.shaderIntegerDotProduct ? 1 : 0; else if (c_13) GP.dot = f13.shaderIntegerDotProduct ? 1 : 0;
}
/* P1-03I (P1-03H report F09, review F06), P1-03J (P1-03I F01-F04): each dlopen attempt runs in a forked helper process. A vendor library's start-up code runs inside dlopen and could crash or hang; that can
 * then only kill the helper. The helper is silent (stdin/stdout/stderr on /dev/null: nothing it prints can reach the probe's stdout), dies with the parent (PR_SET_PDEATHSIG) and with its own alarm,
 * and runs in its OWN PROCESS GROUP: the parent kills the whole group (SIGKILL) when the helper ended, crashed or timed out, so a process that the library's start-up code started does not survive
 * (one that leaves the group with setsid() does; that is stated in ENGINE.md). Protocol on the pipe: the helper writes the marker 'L' before _exit(0) when dlopen returned, or 'E' and the dlerror
 * text before _exit(1). "loaded" is reported only when the 'L' marker was read and the helper exited with status 0; the parent drains the pipe AFTER the helper was reaped (everything the helper wrote
 * is then in the pipe). Limits: GP_OCL_LIMIT_MS per candidate and GP_OCL_TOTAL_MS for the four together; a candidate that is not started because the total is used up is `not_tried`. The probe forks before
 * any Vulkan work, so no driver thread exists at fork time. */
#define GP_OCL_LIMIT_MS 2000
#define GP_OCL_TOTAL_MS 2500
static volatile pid_t gp_live_pgid;   /* the helper group running now (0: none): killed by the --max-seconds watchdog too */
static void gp_kill_live_group(void) { pid_t g = gp_live_pgid; if (g > 0) kill(-g, SIGKILL); }
static const char *gp_ocl_path(int i) {
#ifdef P1_HOST_BUILD
    char e[24]; snprintf(e, sizeof e, "P1_OCL_PATH_%d", i); const char *v = getenv(e); if (v && *v) return v;   /* host tests only: the library actually opened (the name reported stays the candidate's) */
#endif
    return GP_OCL[i];
}
static void gp_ocl_helper(int i, int wfd, pid_t parent) {   /* runs in the child; never returns */
    setpgid(0, 0);
    prctl(PR_SET_PDEATHSIG, SIGKILL); if (getppid() != parent) _exit(2);
    alarm(GP_OCL_LIMIT_MS / 1000 + 1);
    int w = fcntl(wfd, F_DUPFD, 3); if (w < 0) w = wfd;
    int nul = open("/dev/null", O_RDWR); if (nul >= 0) { dup2(nul, 0); dup2(nul, 1); dup2(nul, 2); if (nul > 2) close(nul); } else { close(0); close(1); close(2); }
    dlerror(); void *h = dlopen(gp_ocl_path(i), RTLD_NOW | RTLD_LOCAL);
    if (h) { ssize_t r = write(w, "L", 1); (void)r; _exit(0); }   /* the marker: dlopen returned (a library whose start-up code calls exit(0) never gets here) */
    const char *e = dlerror(); if (!e || !*e) e = "dlopen failed (no dlerror text)";
    char m[241]; m[0] = 'E'; size_t n = strlen(e); if (n > 239) n = 239; memcpy(m + 1, e, n); ssize_t r = write(w, m, n + 1); (void)r; _exit(1);
}
static void gp_opencl_one(int i, uint64_t total_dl) {
    char *err = GP.ocl_err[i]; GP.ocl_out[i] = GP_O_NOTRUN;
    uint64_t t0 = p1_now_ns(); if (t0 >= total_dl) { GP.ocl_out[i] = GP_O_NOTTRIED; return; }   /* the overall limit is used up */
    uint64_t dl = t0 + (uint64_t)GP_OCL_LIMIT_MS * 1000000ull; if (dl > total_dl) dl = total_dl;
    int pf[2]; if (pipe(pf)) { snprintf(err, sizeof GP.ocl_err[i], "helper not started: pipe failed (errno %d)", errno); return; }
    fflush(NULL); pid_t me = getpid(), pid = fork();
    if (pid < 0) { snprintf(err, sizeof GP.ocl_err[i], "helper not started: fork failed (errno %d)", errno); close(pf[0]); close(pf[1]); return; }
    if (pid == 0) { close(pf[0]); gp_ocl_helper(i, pf[1], me); }
    setpgid(pid, pid); gp_live_pgid = pid;   /* both sides set it: whichever runs first, the group exists before the first possible kill */
    close(pf[1]); fcntl(pf[0], F_SETFL, O_NONBLOCK);
    int exited = 0, st = 0;
    while (p1_now_ns() < dl) {
        siginfo_t si; memset(&si, 0, sizeof si);
        if (waitid(P_PID, (id_t)pid, &si, WEXITED | WNOHANG | WNOWAIT) == 0 && si.si_pid == pid) { exited = 1; break; }   /* ended, not yet reaped: the pid and the group stay ours */
        struct pollfd p = {pf[0], POLLIN, 0}; if (poll(&p, 1, 5) > 0) { struct timespec ts = {0, 1000000}; nanosleep(&ts, NULL); }
    }
    kill(-pid, SIGKILL);   /* the whole group, whatever happened: a leftover process of the library's start-up code dies here (normal end, crash and timeout alike) */
    { uint64_t d2 = p1_now_ns() + 1000000000ull; for (;;) { if (waitpid(pid, &st, WNOHANG) == pid) break; if (p1_now_ns() >= d2) break; struct timespec ts = {0, 1000000}; nanosleep(&ts, NULL); } }
    gp_live_pgid = 0;
    char buf[241]; size_t n = 0;   /* drain after reaping: everything the helper wrote is in the pipe now */
    for (;;) { ssize_t r = read(pf[0], buf + n, sizeof buf - 1 - n); if (r > 0) { n += (size_t)r; if (n >= sizeof buf - 1) break; } else break; }
    close(pf[0]); buf[n] = 0;
    if (!exited) { GP.ocl_out[i] = GP_O_TIMEOUT; return; }   /* still running at its limit: it was killed with its group */
    if (WIFSIGNALED(st)) { if (WTERMSIG(st) == SIGALRM) GP.ocl_out[i] = GP_O_TIMEOUT; else { GP.ocl_out[i] = GP_O_CRASHED; GP.ocl_sig[i] = WTERMSIG(st); } return; }   /* its own alarm is a timeout, not a crash */
    if (WIFEXITED(st) && WEXITSTATUS(st) == 0 && n >= 1 && buf[0] == 'L') { GP.ocl_out[i] = GP_O_LOADED; return; }   /* loaded only when the marker was read */
    GP.ocl_out[i] = GP_O_DLERROR;
    if (WIFEXITED(st) && WEXITSTATUS(st) == 1 && n >= 2 && buf[0] == 'E') snprintf(err, sizeof GP.ocl_err[i], "%s", buf + 1);
    else if (WIFEXITED(st) && WEXITSTATUS(st) == 0) snprintf(err, sizeof GP.ocl_err[i], "helper exited with status 0 before dlopen returned (no loaded marker)");
    else snprintf(err, sizeof GP.ocl_err[i], "helper ended with status %d without a dlerror text", WIFEXITED(st) ? WEXITSTATUS(st) : -1);
}
static void gp_opencl(void) { uint64_t total_dl = p1_now_ns() + (uint64_t)GP_OCL_TOTAL_MS * 1000000ull; for (int i = 0; i < 4; i++) { gp_opencl_one(i, total_dl); GP.ocl_ok[i] = GP.ocl_out[i] == GP_O_LOADED; } }
/* Called first in main(): when --probe is on the command line this runs the whole probe and does not return. Allowed with it: --json and --max-seconds. */
static void gp_entry(int argc, char **argv) {
    int on = 0; for (int i = 1; i < argc; i++) if (!strcmp(argv[i], "--probe")) on = 1;
    if (!on) return;
    p1_custom_writer = gp_write; for (int i = 1; i < argc; i++) if (!strcmp(argv[i], "--json")) P1.json = 1;
    for (int i = 1; i < argc; i++) { const char *a = argv[i];
        if (!strcmp(a, "--probe") || !strcmp(a, "--json")) continue;
        if (!strcmp(a, "--max-seconds")) { if (i + 1 >= argc || p1_parse_double(argv[i + 1], &p1_max_seconds) || p1_max_seconds <= 0) p1_exitf(P1_FAILED, P1_EXIT_USAGE, "bad or missing value for --max-seconds"); i++; continue; }
        p1_exitf(P1_FAILED, P1_EXIT_USAGE, "option %s is not allowed with --probe (allowed: --json --max-seconds)", a); }
    p1_read_cpuset(); p1_timeout_hook = gp_kill_live_group; p1_start_watchdog(); gp_opencl(); gp_vulkan();   /* P1-03I: the helpers fork first (no Vulkan driver thread yet); the cpuset is read as in the static probe */
    if (!P1.json) { printf("# gpu-probe vulkan available=%d device=%s type=%s api=%u.%u.%u driver=%u compute_queue=%d f16=%d i8=%d dot=%d cpuset=%s heaps_mib=", GP.avail, GP.avail ? GP.name : "-", GP.avail ? GP.dtype : "-", GP.amaj, GP.amin, GP.apat, GP.driver, GP.compute, GP.f16, GP.i8, GP.dot, P1.cpuset_read ? P1.cpuset : "-");
        for (int i = 0; i < GP.nheaps; i++) { printf("%s%.0f", i ? "," : "", GP.heap_mib[i]); }
        printf("\n"); for (int i = 0; i < 4; i++) { printf("# gpu-probe opencl_child %s loaded=%d outcome=%s signal=%d %s\n", GP_OCL[i], GP.ocl_ok[i], GP_O_NAME[GP.ocl_out[i]], GP.ocl_out[i] == GP_O_CRASHED ? GP.ocl_sig[i] : 0, GP.ocl_err[i]); } fflush(stdout); }
    p1_exit(P1_OK, 0, NULL);
}

int main(int argc, char **argv) {
    const char *csv = NULL, *tag = "G", *modes = "0,3,1,2", *perwgs = "1024,4096,16384"; double size_mb = 512; int ndisp = 5, nwarm = 20, hot_ms = 400; double loop_s = 0; const char *timeline = NULL;
    p1_init("gpubw"); p1_clear_host_env(); P1.kind = "gpu"; gp_entry(argc, argv);   /* P1-03H C3: --probe runs here and does not return */
    /* PART1: the kind comes from the program before any option error can be reported (review F07); PINRETRY_* ignored in production (F16) */
    double mem_user = -1; int fit = 0; char cpuset_list[64] = ""; int cpu_threads = -1; const char *row_kind = NULL;
    for (int i = 1; i < argc; i++) if (!strcmp(argv[i], "--json")) P1.json = 1;   /* so that even a usage error is reported as JSON */
    for (int i = 1; i < argc; i += 2) {
        if (!strcmp(argv[i], "--stage")) { g_stage = 1; i--; continue; }
        if (!strcmp(argv[i], "--json") || !strcmp(argv[i], "--fit-buffer") || !strcmp(argv[i], "--allow-unpinned")) { if (!strcmp(argv[i], "--fit-buffer")) fit = 1; i--; continue; }   /* PART1: flags without a value; --allow-unpinned is accepted and ignored (gpubw never pins) */
        if (i + 1 >= argc) { fprintf(stderr, "missing value for %s\n", argv[i]); p1_exitf(P1_FAILED, P1_EXIT_USAGE, "missing value for %s", argv[i]); }
        if (!strcmp(argv[i], "--csv")) csv = argv[i + 1]; else if (!strcmp(argv[i], "--tag")) { tag = argv[i + 1]; P1.tag = argv[i + 1]; } else if (!strcmp(argv[i], "--size-mb")) size_mb = atof(argv[i + 1]);
        else if (!strcmp(argv[i], "--dispatches")) ndisp = atoi(argv[i + 1]); else if (!strcmp(argv[i], "--warmup-dispatches")) nwarm = atoi(argv[i + 1]);
        else if (!strcmp(argv[i], "--modes")) modes = argv[i + 1]; else if (!strcmp(argv[i], "--per-wg")) perwgs = argv[i + 1]; else if (!strcmp(argv[i], "--memtype")) g_src_type = g_dst_type = atoi(argv[i + 1]);
        else if (!strcmp(argv[i], "--hot-ms")) hot_ms = atoi(argv[i + 1]); else if (!strcmp(argv[i], "--loop-seconds")) loop_s = atof(argv[i + 1]); else if (!strcmp(argv[i], "--timeline")) timeline = argv[i + 1]; else if (!strcmp(argv[i], "--src-memtype")) g_src_type = atoi(argv[i + 1]); else if (!strcmp(argv[i], "--dst-memtype")) g_dst_type = atoi(argv[i + 1]);
        else if (!strcmp(argv[i], "--gate")) { if (!strcmp(argv[i + 1], "on")) P1.gate_on = 1; else if (!strcmp(argv[i + 1], "off")) P1.gate_on = 0; else p1_exitf(P1_FAILED, P1_EXIT_USAGE, "bad --gate %s (on|off)", argv[i + 1]); }
        else if (!strcmp(argv[i], "--mem-limit-mib")) { if (p1_parse_double(argv[i + 1], &mem_user) || mem_user < 0) p1_exitf(P1_FAILED, P1_EXIT_USAGE, "bad --mem-limit-mib %s", argv[i + 1]); }
        else if (!strcmp(argv[i], "--max-seconds")) { if (p1_parse_double(argv[i + 1], &p1_max_seconds) || p1_max_seconds <= 0) p1_exitf(P1_FAILED, P1_EXIT_USAGE, "bad --max-seconds %s", argv[i + 1]); }
        else if (!strcmp(argv[i], "--tier")) { if (!p1_tier_ok(argv[i + 1])) p1_exitf(P1_FAILED, P1_EXIT_USAGE, "bad --tier %s (T1|T2|T3)", argv[i + 1]); P1.tier = argv[i + 1]; }
        else if (!strcmp(argv[i], "--cpu-set")) snprintf(cpuset_list, sizeof cpuset_list, "%s", argv[i + 1]);   /* PART1: label only, the CPU stream's CPUs when a membw runs beside this row (C8) */
        else if (!strcmp(argv[i], "--cpu-threads")) cpu_threads = atoi(argv[i + 1]);   /* PART1: label only */
        else if (!strcmp(argv[i], "--row-kind")) { row_kind = argv[i + 1]; if (!p1_row_kind_ok(row_kind)) p1_exitf(P1_FAILED, P1_EXIT_USAGE, "bad --row-kind %s (pinprobe|read|write|copy|gpu|sweep|prefetch|pacer|sustained)", row_kind); P1.kind = row_kind; }   /* PART1: "pacer" for the PC-pacer-<id>-on/off rows (GPU read with / without a pacer); default "gpu" */
        else { fprintf(stderr, "usage: gpubw [--csv p] [--tag T] [--size-mb M] [--dispatches N] [--warmup-dispatches N] [--modes 0,3,1,2] [--per-wg 1024,4096,16384] [--memtype N] [--src-memtype N] [--dst-memtype N] [--stage] [--hot-ms 400]\n"
                    "              [--json] [--gate on|off] [--mem-limit-mib N] [--fit-buffer] [--max-seconds S] [--tier T1|T2|T3] [--cpu-set LIST --cpu-threads N] [--row-kind gpu|pacer] (see ENGINE.md)\n"); p1_exitf(P1_FAILED, P1_EXIT_USAGE, "unknown option %s", argv[i]); }
    }
    P1.kind = row_kind ? row_kind : "gpu"; P1.threads = cpu_threads > 0 ? cpu_threads : 0;
    if (cpuset_list[0]) { char *sv = NULL, *d = strdup(cpuset_list); for (char *t = strtok_r(d, ",+ ", &sv); t && P1.ncpu < P1_MAXCPUS; t = strtok_r(NULL, ",+ ", &sv)) P1.cpus[P1.ncpu++] = atoi(t); free(d); }
    if (P1.json) {   /* PART1: one invocation is one row: exactly one mode and one per-wg, no sustained loop */
        if (strchr(modes, ',') || strchr(perwgs, ',') || loop_s > 0) p1_exitf(P1_FAILED, P1_EXIT_USAGE, "--json needs exactly one --modes value and one --per-wg value and no --loop-seconds (one invocation = one row)");
        if (ndisp < 1) p1_exitf(P1_FAILED, P1_EXIT_USAGE, "--dispatches must be >= 1");
    }
    p1_start_watchdog();
    p1_gate();   /* PART1: before any Vulkan call or allocation */
    {   /* PART1: memory guard: src + dst storage buffers (+ a staging buffer with --stage), each size_mb */
        int nbuf = g_stage ? 3 : 2; double lim = p1_mem_limit_mib(mem_user), need = nbuf * size_mb;
        if (lim < 0) p1_exitf(P1_SKIPPED, P1_EXIT_MEMORY, "memory guard: MemTotal/MemAvailable unreadable, cannot prove the buffers fit; nothing allocated");
        if (need > lim) {
            if (fit) { double x = floor(lim / nbuf); if (x < 64) p1_exitf(P1_SKIPPED, P1_EXIT_MEMORY, "memory guard: %d buffers x %.0f MiB > limit %.0f MiB and the smallest allowed buffer is 64 MiB (floor(limit/%d) = %.0f); nothing allocated", nbuf, size_mb, lim, nbuf, x); size_mb = x; p1_label("smaller buffer"); }
            else p1_exitf(P1_SKIPPED, P1_EXIT_MEMORY, "memory guard: working set %.0f MiB (%d buffers x %.0f MiB) > limit %.0f MiB = min(%s55%% of MemTotal %.0f MiB, 70%% of MemAvailable %.0f MiB); nothing allocated", need, nbuf, size_mb, lim, mem_user >= 0 ? "--mem-limit-mib, " : "", P1.mem_total_mib, P1.mem_avail_mib);
        }
        P1.buffer_mib = size_mb;
    }
    if (P1.json) {   /* PART1 (review F04): a mode or per-wg value that would measure nothing is a usage error, never an ok row without a number. Same arithmetic as the kit's skip test below. */
        char *e; long m = strtol(modes, &e, 10); if (e == modes || *e || m < 0 || m > 5) p1_exitf(P1_FAILED, P1_EXIT_USAGE, "bad --modes %s: one integer 0..5 is required with --json", modes);
        long pw = strtol(perwgs, &e, 10); if (e == perwgs || *e || pw < 1 || pw > 4294967295L) p1_exitf(P1_FAILED, P1_EXIT_USAGE, "bad --per-wg %s: one positive integer is required with --json", perwgs);
        size_t nv0 = (size_t)(size_mb * 1048576.0) / 16; uint32_t pwu = (uint32_t)pw, W0 = (uint32_t)(nv0 / pwu), mul = m == 5 ? 4096 : m == 4 ? 2048 : 1024;
        if (W0 < 1 || W0 > 65536 || nv0 % pwu || pwu % mul) p1_exitf(P1_FAILED, P1_EXIT_USAGE, "--per-wg %ld measures nothing for --modes %ld with a %.0f MiB buffer (it must divide the buffer in 16-byte units, be a multiple of %u, and give 1..65536 workgroups); use a value the kit's loop accepts, for example 4096", pw, m, size_mb, mul);
    }
    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, 0, "gpubw", 1, "none", 1, VK_API_VERSION_1_1};
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, 0, 0, &app, 0, 0, 0, 0};
    VkInstance inst; CK(vkCreateInstance(&ici, NULL, &inst));
    uint32_t np = 0; CK(vkEnumeratePhysicalDevices(inst, &np, NULL)); if (!np) { fprintf(stderr, "no Vulkan device\n"); p1_exitf(P1_UNSUPPORTED, P1_EXIT_UNSUPPORTED, "no Vulkan device"); }
    VkPhysicalDevice pd[8]; if (np > 8) np = 8; CK(vkEnumeratePhysicalDevices(inst, &np, pd));
    VkPhysicalDeviceProperties pp; vkGetPhysicalDeviceProperties(pd[0], &pp); vkGetPhysicalDeviceMemoryProperties(pd[0], &g_mp);
    printf("# device: %s  apiVersion %u.%u.%u  driverVersion 0x%x  timestampPeriod %.4f ns  maxStorageBufferRange %u  maxWorkGroupInvocations %u\n", pp.deviceName,
        VK_VERSION_MAJOR(pp.apiVersion), VK_VERSION_MINOR(pp.apiVersion), VK_VERSION_PATCH(pp.apiVersion), pp.driverVersion, pp.limits.timestampPeriod, pp.limits.maxStorageBufferRange, pp.limits.maxComputeWorkGroupInvocations);
    uint32_t nq = 0; vkGetPhysicalDeviceQueueFamilyProperties(pd[0], &nq, NULL); VkQueueFamilyProperties qf[16]; if (nq > 16) nq = 16; vkGetPhysicalDeviceQueueFamilyProperties(pd[0], &nq, qf);
    uint32_t qfi = ~0u; for (uint32_t i = 0; i < nq; i++) if (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { qfi = i; break; }
    if (qfi == ~0u) { fprintf(stderr, "no compute queue\n"); p1_exitf(P1_UNSUPPORTED, P1_EXIT_UNSUPPORTED, "no Vulkan compute queue"); }
    int ts_ok = pp.limits.timestampComputeAndGraphics && qf[qfi].timestampValidBits >= 32;
    printf("# compute queue family %u, timestampValidBits %u, timestamps %s\n", qfi, qf[qfi].timestampValidBits, ts_ok ? "supported" : "UNSUPPORTED: falling back to vkQueueWaitIdle + host clock");
    if (!ts_ok) p1_label("host_clock_timing");   /* PART1 */
    printf("# timing: %s\n", ts_ok ? "device timestamps" : "host clock (no timestamp support: no timestamp commands recorded, no query results read; the median/best rows below are host-clock times)");
    for (uint32_t i = 0; i < g_mp.memoryTypeCount; i++) printf("# memtype %u flags 0x%x heap %u\n", i, g_mp.memoryTypes[i].propertyFlags, g_mp.memoryTypes[i].heapIndex);
    float prio = 1; VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, 0, 0, qfi, 1, &prio};
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, 0, 0, 1, &qci, 0, 0, 0, 0, 0};
    VkDevice dev; CK(vkCreateDevice(pd[0], &dci, NULL, &dev)); VkQueue q; vkGetDeviceQueue(dev, qfi, 0, &q); g_dev = dev; g_q = q;
    VkCommandPoolCreateInfo cpi = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, 0, VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, qfi}; VkCommandPool cp; CK(vkCreateCommandPool(dev, &cpi, NULL, &cp)); g_cp = cp;

    size_t bytes_buf = (size_t)(size_mb * 1048576.0), nw = bytes_buf / 4, nv = bytes_buf / 16;
    buf_t src = make_buf(dev, bytes_buf, g_src_type, !g_stage), dst = make_buf(dev, bytes_buf, g_dst_type, !g_stage), out = make_buf(dev, 16u * 65536u, -1, 1);
    printf("# buffers: %.0f MiB src (memtype %u, %s), %.0f MiB dst (memtype %u, %s)\n", size_mb, src.type, src.map ? "host-mapped" : "staged", size_mb, dst.type, dst.map ? "host-mapped" : "staged");
    { int tdev = -1; for (uint32_t i = 0; i < g_mp.memoryTypeCount; i++) if ((g_bufmask & (1u << i)) && (g_mp.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) && !(g_mp.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) { tdev = (int)i; break; }
      printf("# T_dev (DEVICE_LOCAL, not HOST_VISIBLE, allowed for a storage buffer, mask 0x%x): %s\n", g_bufmask, tdev >= 0 ? "present" : "none on this driver; fallback: the least-coherent DEVICE_LOCAL|HOST_VISIBLE type allowed for buffers"); }
    uint32_t ex[4] = {0, 0, 0, 0}; buf_t stg = {0};
    {   /* fill src with the WORDK pattern, directly if host-mapped, else through a staging buffer and vkCmdCopyBuffer (freed before measuring) */
        buf_t fs = src.map ? src : make_buf(dev, bytes_buf, -1, 1); uint32_t *ws = fs.map;
        for (size_t i = 0; i < nw; i++) { ws[i] = (uint32_t)(i + 1) * WORDK; ex[i & 3] ^= ws[i]; }
        sync_buf(dev, &fs, 0);
        if (!src.map) { copy_buf(fs.buf, src.buf, bytes_buf); free_buf(&fs); printf("# src filled through a staging buffer (vkCmdCopyBuffer + barrier), stage freed\n"); }
    }
    if (!dst.map) stg = make_buf(dev, bytes_buf, -1, 1);   /* dst is verified through a copy back into this buffer */
    char sfx[32] = ""; if (g_src_type >= 0 || g_dst_type >= 0 || g_stage) snprintf(sfx, sizeof sfx, "-s%u-d%u%s", src.type, dst.type, g_stage ? "-stg" : "");

    VkDescriptorSetLayoutBinding lb[3]; for (int i = 0; i < 3; i++) lb[i] = (VkDescriptorSetLayoutBinding){(uint32_t)i, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, 0};
    VkDescriptorSetLayoutCreateInfo dlci = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, 0, 0, 3, lb}; VkDescriptorSetLayout dsl; CK(vkCreateDescriptorSetLayout(dev, &dlci, NULL, &dsl));
    VkPushConstantRange pcr = {VK_SHADER_STAGE_COMPUTE_BIT, 0, 8};
    VkPipelineLayoutCreateInfo plci = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, 0, 0, 1, &dsl, 1, &pcr}; VkPipelineLayout pl; CK(vkCreatePipelineLayout(dev, &plci, NULL, &pl));
    VkShaderModuleCreateInfo smci = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, 0, 0, SPV_LEN, (const uint32_t *)SPV}; VkShaderModule sm; CK(vkCreateShaderModule(dev, &smci, NULL, &sm));
    VkComputePipelineCreateInfo cpci = {VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO, 0, 0, {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, 0, 0, VK_SHADER_STAGE_COMPUTE_BIT, sm, "main", 0}, pl, 0, 0};
    VkPipeline pipe; CK(vkCreateComputePipelines(dev, 0, 1, &cpci, NULL, &pipe));
    VkDescriptorPoolSize ps = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 3}; VkDescriptorPoolCreateInfo dpci = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, 0, 0, 1, 1, &ps}; VkDescriptorPool dp; CK(vkCreateDescriptorPool(dev, &dpci, NULL, &dp));
    VkDescriptorSetAllocateInfo dsai = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, 0, dp, 1, &dsl}; VkDescriptorSet ds; CK(vkAllocateDescriptorSets(dev, &dsai, &ds));
    VkDescriptorBufferInfo dbi[3] = {{src.buf, 0, VK_WHOLE_SIZE}, {dst.buf, 0, VK_WHOLE_SIZE}, {out.buf, 0, VK_WHOLE_SIZE}}; VkWriteDescriptorSet wds[3];
    for (int i = 0; i < 3; i++) wds[i] = (VkWriteDescriptorSet){VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, 0, ds, (uint32_t)i, 0, 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, &dbi[i], 0};
    vkUpdateDescriptorSets(dev, 3, wds, 0, NULL);
    VkQueryPoolCreateInfo qpci = {VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO, 0, 0, VK_QUERY_TYPE_TIMESTAMP, 2, 0}; VkQueryPool qp; CK(vkCreateQueryPool(dev, &qpci, NULL, &qp));
    VkCommandBufferAllocateInfo cbai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, 0, cp, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1}; VkCommandBuffer cb; CK(vkAllocateCommandBuffers(dev, &cbai, &cb));

    static const char *NAME[6] = {"G01_read", "G02_write", "G03_copy", "G01b_read_seq", "G01c_read_x8", "G01d_read_x16"};
    int failed = 0; char dup[64]; snprintf(dup, sizeof dup, "%s", modes);
    printf("%-14s %-22s %6s %-8s %12s %12s %9s %9s %6s\n", "kernel", "variant", "size_mb", "run", "median_ns", "best_ns", "GB/s_med", "GB/s_best", "%peak");
    char *sv1 = NULL, *sv2 = NULL;
    for (char *ms = strtok_r(dup, ",", &sv1); ms; ms = strtok_r(NULL, ",", &sv1)) {
        uint32_t mode = (uint32_t)atoi(ms); char d2[64]; snprintf(d2, sizeof d2, "%s", perwgs);
        if (mode > 5) { fprintf(stderr, "bad mode %u\n", mode); continue; }
        const char *kname = NAME[mode];
        for (char *ws_ = strtok_r(d2, ",", &sv2); ws_; ws_ = strtok_r(NULL, ",", &sv2)) {
            uint32_t per_wg = (uint32_t)atoi(ws_), W = (uint32_t)(nv / per_wg); char var[56]; snprintf(var, sizeof var, "gpu-wg%u-per%u%s", W, per_wg, sfx);
            if (W < 1 || W > 65536 || nv % per_wg || per_wg % (mode == 5 ? 4096 : mode == 4 ? 2048 : 1024)) { fprintf(stderr, "skip per-wg %u\n", per_wg); continue; }
            uint32_t pc[2] = {mode, per_wg};
            VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, 0, 0, 0};
            CK(vkBeginCommandBuffer(cb, &bi)); vkCmdResetQueryPool(cb, qp, 0, 2);
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipe); vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pl, 0, 1, &ds, 0, NULL);
            vkCmdPushConstants(cb, pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 8, pc);
            if (ts_ok) vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, qp, 0); vkCmdDispatch(cb, W, 1, 1); if (ts_ok) vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, qp, 1);
            CK(vkEndCommandBuffer(cb));
            VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, 0, 0, 0, 0, 1, &cb, 0, 0};
            uint64_t bt0 = boot_ns();
            if (mode == 1 || mode == 2) { if (dst.map) { memset(dst.map, mode == 1 ? 0x5A : 0, bytes_buf); sync_buf(dev, &dst, 0); } else { memset(stg.map, mode == 1 ? 0x5A : 0, bytes_buf); sync_buf(dev, &stg, 0); copy_buf(stg.buf, dst.buf, bytes_buf); } }
            for (int i = 0; i < nwarm; i++) { CK(vkQueueSubmit(q, 1, &si, 0)); CK(vkQueueWaitIdle(q)); }
            if (loop_s > 0 && mode == 0) {   /* B3 sustained mode (added): back-to-back timed read dispatches for loop_s seconds, one timeline row per dispatch; the XOR guard is checked on dispatch 1, every 1000th and the last */
                FILE *tl = timeline ? fopen(timeline, "w") : NULL; if (tl) fputs("n,boot_ns_start,boot_ns_end,gpu_ns,bytes,verified\n", tl);
                uint64_t tend = now_ns() + (uint64_t)(loop_s * 1e9); long n = 0; int allok = 1;
                while (now_ns() < tend) {
                    int chk = (n == 0 || n % 1000 == 0); if (chk) { memset(out.map, 0, 16u * 65536u); sync_buf(dev, &out, 0); }
                    uint64_t b0 = boot_ns(); CK(vkQueueSubmit(q, 1, &si, 0)); CK(vkQueueWaitIdle(q)); uint64_t b1 = boot_ns();
                    uint64_t t[2] = {0, 0}; if (ts_ok) CK(vkGetQueryPoolResults(dev, qp, 0, 2, sizeof t, t, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT));
                    double g = ts_ok ? (double)(t[1] - t[0]) * pp.limits.timestampPeriod : (double)(b1 - b0); int vi = -1;
                    if (chk) { vi = 1; sync_buf(dev, &out, 1); uint32_t *o = out.map, x[4] = {0, 0, 0, 0}; for (uint32_t w = 0; w < W; w++) for (int l = 0; l < 4; l++) x[l] ^= o[w * 4 + l]; for (int l = 0; l < 4; l++) vi &= x[l] == ex[l]; allok &= vi; }
                    if (tl) { fprintf(tl, "%ld,%llu,%llu,%.0f,%.0f,%d\n", n, (unsigned long long)b0, (unsigned long long)b1, g, (double)bytes_buf, vi); if (n % 500 == 0) fflush(tl); }
                    n++;
                }
                { memset(out.map, 0, 16u * 65536u); sync_buf(dev, &out, 0); CK(vkQueueSubmit(q, 1, &si, 0)); CK(vkQueueWaitIdle(q)); sync_buf(dev, &out, 1); uint32_t *o = out.map, x[4] = {0, 0, 0, 0}; int vi = 1; for (uint32_t w = 0; w < W; w++) for (int l = 0; l < 4; l++) x[l] ^= o[w * 4 + l]; for (int l = 0; l < 4; l++) vi &= x[l] == ex[l]; allok &= vi; }
                if (tl) fclose(tl); printf("# loop %s %s dispatches=%ld verified=%d\n", kname, var, n, allok); failed |= !allok; continue;
            }
            double gts[32], hts[32]; if (ndisp > 32) ndisp = 32; int ok = 1;
            for (int i = 0; i < ndisp; i++) {
                /* every timed dispatch starts from a freshly initialised dst (and a cleared out) and is verified on its own; the 512 MiB memset and the check are outside the timestamp pair */
                if (mode == 1 || mode == 2) { uint8_t ini = mode == 1 ? 0x5A : 0x00;
                    if (dst.map) { memset(dst.map, ini, bytes_buf); sync_buf(dev, &dst, 0); } else { memset(stg.map, ini, bytes_buf); sync_buf(dev, &stg, 0); copy_buf(stg.buf, dst.buf, bytes_buf); } }
                if ((mode == 1 || mode == 2) && dst.map && hot_ms > 0) {   /* keep the GPU busy after the host-side init so its clocks do not drop, then stamp three lines: a timed dispatch that does nothing leaves 0xFF there and fails the check */
                    uint64_t until = now_ns() + (uint64_t)hot_ms * 1000000ull; while (now_ns() < until) { CK(vkQueueSubmit(q, 1, &si, 0)); CK(vkQueueWaitIdle(q)); }
                    size_t so[3] = {0, (bytes_buf / 2) & ~(size_t)63, bytes_buf - 64}; for (int k = 0; k < 3; k++) memset((uint8_t *)dst.map + so[k], 0xFF, 64); sync_buf(dev, &dst, 0); }
                memset(out.map, 0, 16u * 65536u); sync_buf(dev, &out, 0);
                uint64_t b0 = boot_ns(), h0 = now_ns(); CK(vkQueueSubmit(q, 1, &si, 0)); CK(vkQueueWaitIdle(q)); hts[i] = (double)(now_ns() - h0); uint64_t b1 = boot_ns();
                uint64_t t[2] = {0, 0}; if (ts_ok) CK(vkGetQueryPoolResults(dev, qp, 0, 2, sizeof t, t, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT));
                gts[i] = ts_ok ? (double)(t[1] - t[0]) * pp.limits.timestampPeriod : hts[i];
                int vi = 1; sync_buf(dev, &out, 1);
                if (mode != 1 && mode != 2) { uint32_t *o = out.map, x[4] = {0, 0, 0, 0}; for (uint32_t w = 0; w < W; w++) for (int l = 0; l < 4; l++) x[l] ^= o[w * 4 + l]; for (int l = 0; l < 4; l++) vi &= x[l] == ex[l]; }
                else { const uint32_t *d; if (dst.map) { sync_buf(dev, &dst, 1); d = dst.map; } else { copy_buf(dst.buf, stg.buf, bytes_buf); sync_buf(dev, &stg, 1); d = stg.map; }
                    if (mode == 1) { for (size_t k = 0; k < nw && vi; k++) vi &= d[k] == 0xA5A5A5A5u; } else { for (size_t k = 0; k < nw && vi; k++) vi &= d[k] == (uint32_t)(k + 1) * WORDK; } }
                printf("# dispatch %d: %.0f ns verified=%d boot_ns_start=%llu boot_ns_end=%llu\n", i + 1, gts[i], vi, (unsigned long long)b0, (unsigned long long)b1); ok &= vi;
            }
            printf("# %s %s boot_ns_start=%llu boot_ns_end=%llu\n", kname, var, (unsigned long long)bt0, (unsigned long long)boot_ns());
            if (!ok) { fprintf(stderr, "VERIFY FAILED: %s %s\n", kname, var); failed = 1; if (P1.json) p1_exitf(P1_FAILED, P1_EXIT_FAILED, "verification failed: %s %s", kname, var); continue; }
            double bytes = (mode == 2 ? 2.0 : 1.0) * (double)bytes_buf, sg[32], sh[32]; memcpy(sg, gts, sizeof(double) * ndisp); memcpy(sh, hts, sizeof(double) * ndisp);
            qsort(sg, ndisp, sizeof(double), cmp_d); qsort(sh, ndisp, sizeof(double), cmp_d);
            char tg[40], hn[40]; snprintf(hn, sizeof hn, "%s-hostclock", var);
            for (int i = 0; i < ndisp; i++) { snprintf(tg, sizeof tg, "%s-%d", tag, i + 1); row(csv, kname, var, size_mb, tg, gts[i], gts[i], bytes, 1); }
            snprintf(tg, sizeof tg, "%s-mom", tag); row(csv, kname, var, size_mb, tg, sg[ndisp / 2], sg[0], bytes, ndisp);
            P1.median = bytes / sg[ndisp / 2]; P1.passes = ndisp; P1.nvals = 0; snprintf(P1.variant, sizeof P1.variant, "%s", var);
            if (P1.json) {   /* PART1 (P1-03H B1, planner decision after P1-03S F205): every timed dispatch's GB/s goes into conditions.note, in dispatch order; the row value stays the kit's mom. No change to what is measured. */
                char dl[512]; size_t dn = (size_t)snprintf(dl, sizeof dl, "dispatch GB/s: ");
                for (int i = 0; i < ndisp && dn < sizeof dl; i++) dn += (size_t)snprintf(dl + dn, sizeof dl - dn, "%s%.2f", i ? ", " : "", bytes / gts[i]);
                p1_note_prio("%s", dl);
                if (sg[ndisp - 1] >= 1.25 * sg[0]) p1_label("gpu_rate_unstable");   /* largest rate / smallest rate = slowest time / fastest time >= 1.25 */
            }   /* PART1: the row value is the kit's mom: median of the ndisp device-timestamp dispatches */
            snprintf(tg, sizeof tg, "%s-hmom", tag); row(csv, kname, hn, size_mb, tg, sh[ndisp / 2], sh[0], bytes, ndisp);
        }
    }
    printf("# verification %s\n", failed ? "FAILED for at least one variant" : "passed for every variant");
    if (failed) p1_exitf(P1_FAILED, P1_EXIT_FAILED, "verification failed for at least one variant");
    p1_done_ok();
    return 0;
}
