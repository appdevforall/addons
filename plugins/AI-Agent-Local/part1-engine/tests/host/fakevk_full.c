/* fakevk_full.c (review-only host stand-in for libvulkan): enough of the Vulkan API for gpubw to reach its dispatch loop on x86.
 * One device, one memory type (DEVICE_LOCAL|HOST_VISIBLE|HOST_COHERENT|HOST_CACHED), memory = malloc. A dispatch of mode 0 (read) writes
 * into the out buffer the per-lane XOR of the source words, so the kit's verification passes; timestamps report FAKE_VK_NS ns (default 1e6).
 * P1-03H: FAKE_VK_NS_LIST=a,b,c makes the successive timed dispatches report those ns (cycling), for the dispatch-rate list and the gpu_rate_unstable label (B1).
 * For `gpubw --probe` (C3): FAKE_VK_NODEV=1 (no physical device), FAKE_VK_INST_VER=<minor> (default 3), FAKE_VK_API=<minor> (device 1.<minor>.<patch 128>, default 3), FAKE_VK_EXTS=a,b (device extensions),
 * FAKE_VK_F16 / FAKE_VK_I8 / FAKE_VK_DOT = 1 (feature bits), FAKE_VK_NOCOMPUTE=1 (graphics-only queue family), FAKE_VK_NAME, FAKE_VK_VENDOR, FAKE_VK_DEVICE, FAKE_VK_DRIVER. */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

/* FAKE_VK_FORBID_ALLOC=1: the probe must not create a device, a buffer or memory (it only queries); any such call aborts. */
#define FORBID() do { if (getenv("FAKE_VK_FORBID_ALLOC")) { fputs("forbidden Vulkan call in the probe\n", stderr); abort(); } } while (0)
typedef struct { VkDeviceSize size; uint8_t *mem; } fbuf_t;
static fbuf_t *g_set[3];
static uint32_t g_pc[2];
static uint32_t g_W;
static int g_has_dispatch;

VKAPI_ATTR VkResult VKAPI_CALL vkCreateInstance(const VkInstanceCreateInfo *ci, const VkAllocationCallbacks *a, VkInstance *i) { (void)ci; (void)a; *i = (VkInstance)malloc(64); return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkEnumeratePhysicalDevices(VkInstance i, uint32_t *n, VkPhysicalDevice *d) { (void)i; if (getenv("FAKE_VK_NODEV")) { *n = 0; return VK_SUCCESS; } if (d) d[0] = (VkPhysicalDevice)malloc(64); *n = 1; return VK_SUCCESS; }
VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceProperties(VkPhysicalDevice p, VkPhysicalDeviceProperties *pp) {
    (void)p; memset(pp, 0, sizeof *pp); const char *nm = getenv("FAKE_VK_NAME"), *ap = getenv("FAKE_VK_API"), *vd = getenv("FAKE_VK_VENDOR"), *dv = getenv("FAKE_VK_DEVICE"), *dr = getenv("FAKE_VK_DRIVER");
    strcpy(pp->deviceName, nm ? nm : "fake"); pp->apiVersion = ap ? VK_MAKE_API_VERSION(0, 1, (uint32_t)atoi(ap), 128) : VK_API_VERSION_1_1; pp->vendorID = vd ? (uint32_t)atoi(vd) : 0x13b5; pp->deviceID = dv ? (uint32_t)atoi(dv) : 0x92000000u; pp->driverVersion = dr ? (uint32_t)atoi(dr) : 42;
    pp->deviceType = VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU; pp->limits.timestampPeriod = 1.0f;
    pp->limits.timestampComputeAndGraphics = VK_TRUE; pp->limits.maxStorageBufferRange = 0xffffffffu; pp->limits.maxComputeWorkGroupInvocations = 1024; }
VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceMemoryProperties(VkPhysicalDevice p, VkPhysicalDeviceMemoryProperties *m) {
    (void)p; memset(m, 0, sizeof *m); m->memoryTypeCount = 1; m->memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
    m->memoryHeapCount = 2; m->memoryHeaps[0].size = 1ull << 34; m->memoryHeaps[1].size = 1ull << 29; }
VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceQueueFamilyProperties(VkPhysicalDevice p, uint32_t *n, VkQueueFamilyProperties *q) {
    (void)p; if (q) { memset(q, 0, sizeof *q); q[0].queueFlags = getenv("FAKE_VK_NOCOMPUTE") ? VK_QUEUE_GRAPHICS_BIT : VK_QUEUE_COMPUTE_BIT; q[0].queueCount = 1; q[0].timestampValidBits = 64; } *n = 1; }
VKAPI_ATTR VkResult VKAPI_CALL vkCreateDevice(VkPhysicalDevice p, const VkDeviceCreateInfo *c, const VkAllocationCallbacks *a, VkDevice *d) { FORBID(); (void)p; (void)c; (void)a; *d = (VkDevice)malloc(64); return VK_SUCCESS; }
VKAPI_ATTR void VKAPI_CALL vkGetDeviceQueue(VkDevice d, uint32_t f, uint32_t i, VkQueue *q) { (void)d; (void)f; (void)i; *q = (VkQueue)malloc(64); }
VKAPI_ATTR VkResult VKAPI_CALL vkCreateCommandPool(VkDevice d, const VkCommandPoolCreateInfo *c, const VkAllocationCallbacks *a, VkCommandPool *p) { (void)d; (void)c; (void)a; *p = (VkCommandPool)malloc(8); return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkCreateBuffer(VkDevice d, const VkBufferCreateInfo *c, const VkAllocationCallbacks *a, VkBuffer *b) { FORBID(); (void)d; (void)a; fbuf_t *f = calloc(1, sizeof *f); f->size = c->size; *b = (VkBuffer)f; return VK_SUCCESS; }
VKAPI_ATTR void VKAPI_CALL vkGetBufferMemoryRequirements(VkDevice d, VkBuffer b, VkMemoryRequirements *r) { (void)d; r->size = ((fbuf_t *)b)->size; r->alignment = 256; r->memoryTypeBits = 1; }
VKAPI_ATTR VkResult VKAPI_CALL vkAllocateMemory(VkDevice d, const VkMemoryAllocateInfo *i, const VkAllocationCallbacks *a, VkDeviceMemory *m) { FORBID(); (void)d; (void)a; void *p = calloc(1, i->allocationSize); if (!p) return VK_ERROR_OUT_OF_HOST_MEMORY; *m = (VkDeviceMemory)p; return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkBindBufferMemory(VkDevice d, VkBuffer b, VkDeviceMemory m, VkDeviceSize o) { (void)d; ((fbuf_t *)b)->mem = (uint8_t *)m + o; return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkMapMemory(VkDevice d, VkDeviceMemory m, VkDeviceSize o, VkDeviceSize s, VkMemoryMapFlags f, void **pp) { (void)d; (void)s; (void)f; *pp = (uint8_t *)m + o; return VK_SUCCESS; }
VKAPI_ATTR void VKAPI_CALL vkUnmapMemory(VkDevice d, VkDeviceMemory m) { (void)d; (void)m; }
VKAPI_ATTR void VKAPI_CALL vkDestroyBuffer(VkDevice d, VkBuffer b, const VkAllocationCallbacks *a) { (void)d; (void)a; free((void *)b); }
VKAPI_ATTR void VKAPI_CALL vkFreeMemory(VkDevice d, VkDeviceMemory m, const VkAllocationCallbacks *a) { (void)d; (void)a; free((void *)m); }
VKAPI_ATTR VkResult VKAPI_CALL vkFlushMappedMemoryRanges(VkDevice d, uint32_t n, const VkMappedMemoryRange *r) { (void)d; (void)n; (void)r; return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkInvalidateMappedMemoryRanges(VkDevice d, uint32_t n, const VkMappedMemoryRange *r) { (void)d; (void)n; (void)r; return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkCreateDescriptorSetLayout(VkDevice d, const VkDescriptorSetLayoutCreateInfo *c, const VkAllocationCallbacks *a, VkDescriptorSetLayout *l) { (void)d; (void)c; (void)a; *l = (VkDescriptorSetLayout)malloc(8); return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkCreatePipelineLayout(VkDevice d, const VkPipelineLayoutCreateInfo *c, const VkAllocationCallbacks *a, VkPipelineLayout *l) { (void)d; (void)c; (void)a; *l = (VkPipelineLayout)malloc(8); return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkCreateShaderModule(VkDevice d, const VkShaderModuleCreateInfo *c, const VkAllocationCallbacks *a, VkShaderModule *m) { (void)d; (void)c; (void)a; *m = (VkShaderModule)malloc(8); return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkCreateComputePipelines(VkDevice d, VkPipelineCache pc, uint32_t n, const VkComputePipelineCreateInfo *c, const VkAllocationCallbacks *a, VkPipeline *p) { (void)d; (void)pc; (void)c; (void)a; for (uint32_t i = 0; i < n; i++) p[i] = (VkPipeline)malloc(8); return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkCreateDescriptorPool(VkDevice d, const VkDescriptorPoolCreateInfo *c, const VkAllocationCallbacks *a, VkDescriptorPool *p) { (void)d; (void)c; (void)a; *p = (VkDescriptorPool)malloc(8); return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkAllocateDescriptorSets(VkDevice d, const VkDescriptorSetAllocateInfo *i, VkDescriptorSet *s) { (void)d; (void)i; *s = (VkDescriptorSet)malloc(8); return VK_SUCCESS; }
VKAPI_ATTR void VKAPI_CALL vkUpdateDescriptorSets(VkDevice d, uint32_t n, const VkWriteDescriptorSet *w, uint32_t c, const VkCopyDescriptorSet *cp) { (void)d; (void)c; (void)cp; for (uint32_t i = 0; i < n && i < 3; i++) g_set[w[i].dstBinding] = (fbuf_t *)w[i].pBufferInfo->buffer; }
VKAPI_ATTR VkResult VKAPI_CALL vkCreateQueryPool(VkDevice d, const VkQueryPoolCreateInfo *c, const VkAllocationCallbacks *a, VkQueryPool *q) { (void)d; (void)c; (void)a; *q = (VkQueryPool)malloc(8); return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkAllocateCommandBuffers(VkDevice d, const VkCommandBufferAllocateInfo *i, VkCommandBuffer *c) { (void)d; (void)i; *c = (VkCommandBuffer)malloc(64); return VK_SUCCESS; }
VKAPI_ATTR void VKAPI_CALL vkFreeCommandBuffers(VkDevice d, VkCommandPool p, uint32_t n, const VkCommandBuffer *c) { (void)d; (void)p; (void)n; (void)c; }
VKAPI_ATTR VkResult VKAPI_CALL vkBeginCommandBuffer(VkCommandBuffer c, const VkCommandBufferBeginInfo *i) { (void)c; (void)i; g_has_dispatch = 0; return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkEndCommandBuffer(VkCommandBuffer c) { (void)c; return VK_SUCCESS; }
VKAPI_ATTR void VKAPI_CALL vkCmdResetQueryPool(VkCommandBuffer c, VkQueryPool q, uint32_t f, uint32_t n) { (void)c; (void)q; (void)f; (void)n; }
VKAPI_ATTR void VKAPI_CALL vkCmdBindPipeline(VkCommandBuffer c, VkPipelineBindPoint b, VkPipeline p) { (void)c; (void)b; (void)p; }
VKAPI_ATTR void VKAPI_CALL vkCmdBindDescriptorSets(VkCommandBuffer c, VkPipelineBindPoint b, VkPipelineLayout l, uint32_t f, uint32_t n, const VkDescriptorSet *s, uint32_t dn, const uint32_t *d) { (void)c; (void)b; (void)l; (void)f; (void)n; (void)s; (void)dn; (void)d; }
VKAPI_ATTR void VKAPI_CALL vkCmdPushConstants(VkCommandBuffer c, VkPipelineLayout l, VkShaderStageFlags s, uint32_t o, uint32_t n, const void *v) { (void)c; (void)l; (void)s; (void)o; memcpy(g_pc, v, n > 8 ? 8 : n); }
VKAPI_ATTR void VKAPI_CALL vkCmdWriteTimestamp(VkCommandBuffer c, VkPipelineStageFlagBits s, VkQueryPool q, uint32_t i) { (void)c; (void)s; (void)q; (void)i; }
VKAPI_ATTR void VKAPI_CALL vkCmdDispatch(VkCommandBuffer c, uint32_t x, uint32_t y, uint32_t z) { (void)c; (void)y; (void)z; g_W = x; g_has_dispatch = 1; }
VKAPI_ATTR void VKAPI_CALL vkCmdCopyBuffer(VkCommandBuffer c, VkBuffer s, VkBuffer d, uint32_t n, const VkBufferCopy *r) { (void)c; for (uint32_t i = 0; i < n; i++) memcpy(((fbuf_t *)d)->mem + r[i].dstOffset, ((fbuf_t *)s)->mem + r[i].srcOffset, r[i].size); }
VKAPI_ATTR void VKAPI_CALL vkCmdPipelineBarrier(VkCommandBuffer c, VkPipelineStageFlags a, VkPipelineStageFlags b, VkDependencyFlags f, uint32_t n1, const VkMemoryBarrier *m, uint32_t n2, const VkBufferMemoryBarrier *bm, uint32_t n3, const VkImageMemoryBarrier *im) { (void)c; (void)a; (void)b; (void)f; (void)n1; (void)m; (void)n2; (void)bm; (void)n3; (void)im; }
VKAPI_ATTR VkResult VKAPI_CALL vkQueueSubmit(VkQueue q, uint32_t n, const VkSubmitInfo *s, VkFence f) {
    (void)q; (void)n; (void)s; (void)f;
    if (g_has_dispatch && g_pc[0] == 0 && g_set[0] && g_set[2]) {   /* mode 0 read: out[0..3] = per-lane XOR of all source words; the other workgroups write 0 */
        const uint32_t *src = (const uint32_t *)g_set[0]->mem; size_t nw = g_set[0]->size / 4; uint32_t x[4] = {0, 0, 0, 0};
        for (size_t i = 0; i < nw; i++) x[i & 3] ^= src[i];
        uint32_t *o = (uint32_t *)g_set[2]->mem; memset(o, 0, (size_t)g_W * 16); memcpy(o, x, 16);
    }
    return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkQueueWaitIdle(VkQueue q) { (void)q; return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkGetQueryPoolResults(VkDevice d, VkQueryPool q, uint32_t f, uint32_t n, size_t sz, void *data, VkDeviceSize st, VkQueryResultFlags fl) {
    (void)d; (void)q; (void)f; (void)n; (void)sz; (void)st; (void)fl; uint64_t *t = data; const char *e = getenv("FAKE_VK_NS"), *l = getenv("FAKE_VK_NS_LIST"); t[0] = 0; t[1] = e ? (uint64_t)atoll(e) : 1000000ull;
    if (l && *l) { static int call; int want = call++, n = 1; for (const char *c = l; *c; c++) n += *c == ','; want %= n; const char *c = l; while (want--) { c = strchr(c, ',') + 1; } t[1] = (uint64_t)atoll(c); }
    return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceVersion(uint32_t *v) { const char *e = getenv("FAKE_VK_INST_VER"); *v = VK_MAKE_API_VERSION(0, 1, e ? (uint32_t)atoi(e) : 3, 0); return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateDeviceExtensionProperties(VkPhysicalDevice p, const char *layer, uint32_t *n, VkExtensionProperties *out) {
    (void)p; (void)layer; const char *e = getenv("FAKE_VK_EXTS"); char *dup = strdup(e ? e : ""), *sv = NULL; uint32_t k = 0;
    for (char *t = strtok_r(dup, ",", &sv); t; t = strtok_r(NULL, ",", &sv)) { if (out && k < *n) { memset(&out[k], 0, sizeof out[k]); snprintf(out[k].extensionName, sizeof out[k].extensionName, "%s", t); out[k].specVersion = 1; } k++; }
    free(dup); if (!out) { *n = k; return VK_SUCCESS; } if (k < *n) *n = k; return VK_SUCCESS; }
VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceFeatures2(VkPhysicalDevice p, VkPhysicalDeviceFeatures2 *f) {
    (void)p; int f16 = getenv("FAKE_VK_F16") != NULL, i8 = getenv("FAKE_VK_I8") != NULL, dot = getenv("FAKE_VK_DOT") != NULL;
    for (VkBaseOutStructure *b = (VkBaseOutStructure *)f->pNext; b; b = b->pNext) {
        if (b->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES) { VkPhysicalDeviceShaderFloat16Int8Features *x = (void *)b; x->shaderFloat16 = f16; x->shaderInt8 = i8; }
        else if (b->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES) { VkPhysicalDeviceVulkan12Features *x = (void *)b; x->shaderFloat16 = f16; x->shaderInt8 = i8; }
        else if (b->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_INTEGER_DOT_PRODUCT_FEATURES) { VkPhysicalDeviceShaderIntegerDotProductFeatures *x = (void *)b; x->shaderIntegerDotProduct = dot; }
        else if (b->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES) { VkPhysicalDeviceVulkan13Features *x = (void *)b; x->shaderIntegerDotProduct = dot; }
    } }
