#include "bench_core.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <mutex>
#include <vector>

#include <sched.h>
#include <unistd.h>
#include <cerrno>

#include "ggml-backend.h"
#include "llama.h"

int64_t bench_now_ns() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

std::string bench_affinity() {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof set, &set) != 0) return "?";
    std::string out;
    const int n = (int) sysconf(_SC_NPROCESSORS_CONF);
    for (int i = 0; i < n; i++) {
        if (!CPU_ISSET(i, &set)) continue;
        int j = i;
        while (j + 1 < n && CPU_ISSET(j + 1, &set)) j++;
        if (!out.empty()) out += ",";
        out += std::to_string(i);
        if (j > i) out += "-" + std::to_string(j);
        i = j;
    }
    return out;
}

int bench_reset_affinity() {
    cpu_set_t set;
    CPU_ZERO(&set);
    const int n = (int) sysconf(_SC_NPROCESSORS_CONF);
    for (int i = 0; i < n; i++) CPU_SET(i, &set);
    return sched_setaffinity(0, sizeof set, &set) == 0 ? 0 : errno;
}

static double ms_since(int64_t t0) { return (bench_now_ns() - t0) / 1e6; }

// Same list and order as AI-Agent-Local's llama-android.cpp (CPU_VARIANTS).
static const char *const CPU_VARIANTS[] = {
    "libggml-cpu-android_armv8.6_1.so",
    "libggml-cpu-android_armv8.2_2.so",
    "libggml-cpu-android_armv8.2_1.so",
    "libggml-cpu-android_armv8.0_1.so",
};

std::string bench_load_backend() {
    static std::once_flag once;
    static std::string loaded;
    std::call_once(once, [] {
        for (const char *v : CPU_VARIANTS) {
            if (ggml_backend_load(v) != nullptr) {
                loaded = v;
                break;
            }
        }
        llama_backend_init();
    });
    return loaded;
}

// VmHWM (peak resident set) of this process, in kB, or -1.
static long vm_hwm_kb() {
    FILE *f = fopen("/proc/self/status", "re");
    if (!f) return -1;
    char line[256];
    long kb = -1;
    while (fgets(line, sizeof line, f)) {
        if (sscanf(line, "VmHWM: %ld kB", &kb) == 1) break;
    }
    fclose(f);
    return kb;
}

// FNV-1a of the generated text: equal hashes mean both paths produced the same output.
static uint64_t fnv1a(const std::string &s) {
    uint64_t h = 1469598103934665603ULL;
    for (unsigned char c : s) {
        h ^= c;
        h *= 1099511628211ULL;
    }
    return h;
}

static std::string error_json(const char *what) {
    return std::string("{\"ok\":false,\"error\":\"") + what + "\"}";
}

std::string bench_run(const BenchParams &p, TokenSink sink, void *user) {
    const std::string affinity = bench_affinity();
    const std::string variant = bench_load_backend();
    if (variant.empty()) return error_json("no CPU backend variant loaded");

    const int64_t t_start = bench_now_ns();
    llama_model_params mp = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(p.model_path, mp);
    if (!model) return error_json("llama_model_load_from_file failed");
    const double load_ms = ms_since(t_start);

    int n_threads = p.n_threads;
    if (n_threads <= 0) n_threads = std::max(1, std::min(8, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));

    const int64_t t_ctx = bench_now_ns();
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) p.n_ctx;
    cp.n_batch = (uint32_t) p.n_ctx;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;  // as AI-Agent-Local
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        return error_json("llama_init_from_model failed");
    }
    const double ctx_ms = ms_since(t_ctx);

    const llama_vocab *vocab = llama_model_get_vocab(model);
    const int plen = (int) strlen(p.prompt);
    std::vector<llama_token> toks(plen + 8);
    int n_prompt = llama_tokenize(vocab, p.prompt, plen, toks.data(), (int) toks.size(), true, true);
    if (n_prompt < 0) {
        toks.resize(-n_prompt);
        n_prompt = llama_tokenize(vocab, p.prompt, plen, toks.data(), (int) toks.size(), true, true);
    }
    toks.resize(std::max(n_prompt, 0));

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    const int64_t t_prompt = bench_now_ns();
    if (llama_decode(ctx, llama_batch_get_one(toks.data(), (int32_t) toks.size())) != 0) {
        llama_sampler_free(smpl);
        llama_free(ctx);
        llama_model_free(model);
        return error_json("prompt decode failed");
    }
    const double prompt_ms = ms_since(t_prompt);

    std::string text;
    double first_token_ms = -1, sink_ms = 0;
    int n_gen = 0;
    const int64_t t_gen = bench_now_ns();
    char piece[256];
    for (; n_gen < p.n_predict; n_gen++) {
        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        int n = llama_token_to_piece(vocab, tok, piece, sizeof piece, 0, true);
        if (n < 0) n = 0;
        text.append(piece, (size_t) n);
        const int64_t t_tok = bench_now_ns();
        if (first_token_ms < 0) first_token_ms = (t_tok - t_prompt) / 1e6;
        if (sink) {
            sink(user, piece, (size_t) n, t_tok);
            sink_ms += ms_since(t_tok);
        }
        if (llama_decode(ctx, llama_batch_get_one(&tok, 1)) != 0) break;
    }
    const double gen_ms = ms_since(t_gen);
    const long hwm = vm_hwm_kb();

    llama_sampler_free(smpl);
    llama_free(ctx);
    llama_model_free(model);

    char buf[1024];
    snprintf(buf, sizeof buf,
             "{\"ok\":true,\"variant\":\"%s\",\"threads\":%d,\"load_ms\":%.1f,\"ctx_ms\":%.1f,"
             "\"prompt_tokens\":%d,\"prompt_ms\":%.1f,\"first_token_ms\":%.1f,\"gen_tokens\":%d,"
             "\"gen_ms\":%.1f,\"tok_per_s\":%.2f,\"sink_ms\":%.2f,\"total_ms\":%.1f,"
             "\"vm_hwm_kb\":%ld,\"text_hash\":\"%016llx\",\"affinity\":\"%s\",\"affinity_after\":\"%s\"}",
             variant.c_str(), n_threads, load_ms, ctx_ms, (int) toks.size(), prompt_ms,
             first_token_ms, n_gen, gen_ms, n_gen > 0 ? n_gen / (gen_ms / 1000.0) : 0.0, sink_ms,
             ms_since(t_start), hwm, (unsigned long long) fnv1a(text), affinity.c_str(),
             bench_affinity().c_str());
    return buf;
}
