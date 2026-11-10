/* fakevk.c (host test only): stand-ins for the two Vulkan entry points gpubw calls first, so that the option parsing, gate, memory guard,
 * time limit and the "no Vulkan device" refusal run on a desk machine. Every other vk* symbol is left unresolved (never called). */
#include <stdlib.h>
#include <unistd.h>
#include <vulkan/vulkan.h>
VKAPI_ATTR VkResult VKAPI_CALL vkCreateInstance(const VkInstanceCreateInfo *ci, const VkAllocationCallbacks *a, VkInstance *i) {
    (void)ci; (void)a; const char *s = getenv("FAKE_VK_SLEEP"); if (s) sleep((unsigned)atoi(s)); *i = (VkInstance)0x1; return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL vkEnumeratePhysicalDevices(VkInstance i, uint32_t *n, VkPhysicalDevice *d) { (void)i; (void)d; *n = 0; return VK_SUCCESS; }
/* P1-03H: gpubw --probe asks for the instance version first. FAKE_VK_INST_VER=minor (default 3) gives 1.<minor>. */
VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceVersion(uint32_t *v) { const char *e = getenv("FAKE_VK_INST_VER"); *v = VK_MAKE_API_VERSION(0, 1, e ? (uint32_t)atoi(e) : 3, 0); return VK_SUCCESS; }
