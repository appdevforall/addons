#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <iomanip>
#include <math.h>
#include <string>
#include <unordered_map>
#include <mutex>
#include <unistd.h>
#include <cstdint>
#include "llama.h"
#include "common.h"

#define TAG "AiAgentLocal.llama-android"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Verbose, per-token tracing. It fires once per prompt token and once per
// generated token — hundreds of log/JNI calls per reply on the hot path — so it
// is compiled out of release builds (NDEBUG). The __VA_ARGS__ are not evaluated
// in release, so any string-building in the arguments is skipped too.
#ifdef NDEBUG
#define LOGv(...) ((void) 0)
#else
#define LOGv(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#endif

jclass la_int_var;
jmethodID la_int_var_value;
jmethodID la_int_var_inc;

std::string cached_token_chars;
static std::unordered_map<llama_batch *, int> g_batch_n_tokens;
static std::vector<std::string> g_stop_strings;
static std::string g_generated_text;
static std::atomic<bool> g_stop_requested(false);
static std::mutex g_globals_mutex;

/**
 * Raises a Java exception, tolerating a FindClass that cannot resolve the name, since ThrowNew on a
 * null jclass is undefined behaviour. Callers still own their resources: release them first, because
 * only Release/Delete/Exception calls are legal once an exception is pending.
 *
 * @param env the calling thread's JNI environment
 * @param class_name JNI name of the exception to raise, e.g. "java/lang/IllegalStateException"
 * @param message the exception message
 */
static void throw_java(JNIEnv *env, const char *class_name, const char *message) {
    jclass exception_class = env->FindClass(class_name);
    if (!exception_class) {
        LOGe("jni: cannot raise %s (\"%s\"): class not found", class_name, message);
        return;
    }
    env->ThrowNew(exception_class, message);
    env->DeleteLocalRef(exception_class);
}

/**
 * The token capacity a batch was allocated with, recorded by new_batch(). llama_batch itself only
 * carries n_tokens (how full it is), not how large it is, so the map is the only record.
 *
 * @param batch a batch created by new_batch()
 * @return its capacity in tokens, or 0 if it was not created here
 */
static size_t batch_capacity_of(llama_batch *batch) {
    std::lock_guard<std::mutex> lock(g_globals_mutex);
    auto it = g_batch_n_tokens.find(batch);
    return it == g_batch_n_tokens.end() ? 0 : (size_t) std::max(0, it->second);
}

bool is_valid_utf8(const char *string) {
    if (!string) {
        return true;
    }

    const unsigned char *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }

    return true;
}

static JavaVM *g_jvm = nullptr;
static jclass g_llama_android_class = nullptr;
static jmethodID g_log_from_native_method = nullptr;
static std::atomic<int> g_n_threads(-1);
static std::atomic<int> g_n_threads_batch(-1);
static std::atomic<float> g_temperature(0.7f);
static std::atomic<float> g_top_p(0.9f);
static std::atomic<int> g_top_k(40);
/**
 * Context used when the caller passes a non-positive one; mirrors ContextSizePolicy's floor. Only a
 * guard against a bad argument — the size is chosen in Kotlin and passed to new_context per load.
 */
static constexpr int DEFAULT_N_CTX = 4096;
static std::atomic<bool> g_kv_cache_reuse(true);
static std::vector<llama_token> g_cached_tokens;

/**
 * Drops both the KV cache and the record of what it held, after a prefill that did not complete.
 * Leaving either behind would have the next turn reuse a prefix the cache no longer matches.
 *
 * @param context the context whose memory to clear
 */
static void forget_cached_prefix(llama_context *context) {
    llama_memory_clear(llama_get_memory(context), true);
    std::lock_guard<std::mutex> lock(g_globals_mutex);
    g_cached_tokens.clear();
}

// Converts standard UTF-8 to UTF-16. NewStringUTF() is unusable here because it
// expects modified UTF-8 (CESU-8), so 4-byte sequences such as emoji would mangle.
// Invalid bytes become '?' so a truncated sequence cannot corrupt the remainder.
// @param text NUL-terminated UTF-8, may be null
// @return a new local reference to the converted string
static jstring new_jstring_utf8(JNIEnv *env, const char *text) {
    if (!text) {
        return env->NewStringUTF("");
    }

    const auto *bytes = reinterpret_cast<const unsigned char *>(text);
    std::u16string u16;
    u16.reserve(strlen(text));

    while (*bytes != 0x00) {
        uint32_t cp;
        int num;

        if ((bytes[0] & 0x80) == 0x00) {
            // U+0000 to U+007F
            cp = bytes[0];
            num = 1;
        } else if ((bytes[0] & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            cp = bytes[0] & 0x1Fu;
            num = 2;
        } else if ((bytes[0] & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            cp = bytes[0] & 0x0Fu;
            num = 3;
        } else if ((bytes[0] & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            cp = bytes[0] & 0x07u;
            num = 4;
        } else {
            u16.push_back(u'?');
            bytes += 1;
            continue;
        }

        bool ok = true;
        for (int i = 1; i < num; ++i) {
            if ((bytes[i] & 0xC0) != 0x80) {
                // Resync here; this also covers the terminator, so we never overrun.
                ok = false;
                num = i;
                break;
            }
            cp = (cp << 6) | (bytes[i] & 0x3Fu);
        }

        if (!ok || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            u16.push_back(u'?');
            bytes += num;
            continue;
        }

        if (cp <= 0xFFFF) {
            u16.push_back(static_cast<char16_t>(cp));
        } else {
            // Split an astral code point into a UTF-16 surrogate pair.
            cp -= 0x10000;
            u16.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            u16.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FFu)));
        }
        bytes += num;
    }

    return env->NewString(reinterpret_cast<const jchar *>(u16.data()),
                          static_cast<jsize>(u16.size()));
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_native_1configureThreads(JNIEnv *, jclass, jint n_threads,
                                                     jint n_threads_batch) {
    g_n_threads.store(n_threads);
    g_n_threads_batch.store(n_threads_batch);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_native_1configureSampling(JNIEnv *, jclass, jfloat temperature,
                                                      jfloat top_p, jint top_k) {
    float validated_temperature = temperature;
    if (validated_temperature < 0.0f) {
        validated_temperature = 0.0f;
    } else if (validated_temperature > 5.0f) {
        validated_temperature = 5.0f;
    }

    float validated_top_p = top_p;
    if (validated_top_p < 0.0f) {
        validated_top_p = 0.0f;
    } else if (validated_top_p > 1.0f) {
        validated_top_p = 1.0f;
    }

    int validated_top_k = top_k < 0 ? 0 : top_k;

    g_temperature.store(validated_temperature);
    g_top_p.store(validated_top_p);
    g_top_k.store(validated_top_k);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_native_1configureKvCacheReuse(JNIEnv *, jclass, jboolean enabled) {
    g_kv_cache_reuse.store(enabled == JNI_TRUE);
}

template<typename JVM>
static auto attach_current_thread_impl(JVM *jvm, JNIEnv **env, int)
-> decltype(jvm->AttachCurrentThread(env, nullptr), jint{}) {
    // Header has the JNIEnv** signature
    return jvm->AttachCurrentThread(env, nullptr);
}

template<typename JVM>
static jint attach_current_thread_impl(JVM *jvm, JNIEnv **env, long) {
    // Fallback for headers that want void** (e.g., Flox)
    void *venv = nullptr;
    jint r = jvm->AttachCurrentThread(&venv, nullptr);
    *env = reinterpret_cast<JNIEnv *>(venv);
    return r;
}

static inline jint attach_current_thread(JavaVM *jvm, JNIEnv **env) {
    return attach_current_thread_impl(jvm, env, 0);
}

void log_to_kotlin_bridge(ggml_log_level level, const char *message) {
    if (!g_jvm || !g_llama_android_class || !g_log_from_native_method) {
        __android_log_print(ANDROID_LOG_DEBUG, "llama.cpp", "%s", message);
        return;
    }

    JNIEnv *env = nullptr;
    bool did_attach_thread = false;

    jint get_env_result = g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if (attach_current_thread(g_jvm, &env) != JNI_OK || !env) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "AttachCurrentThread failed");
            return;
        }
        did_attach_thread = true;
    } else if (get_env_result != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "GetEnv failed");
        return;
    }

    jstring jni_message = new_jstring_utf8(env, message);
    if (jni_message == nullptr) {
        // Handle potential out-of-memory error
        if (did_attach_thread) {
            g_jvm->DetachCurrentThread();
        }
        return;
    }

    env->CallStaticVoidMethod(g_llama_android_class, g_log_from_native_method, (jint) level,
                              jni_message);
    env->DeleteLocalRef(jni_message);

    // ✨ THE FIX: Only detach the thread if we were the ones who attached it.
    // In the case of the Llm-RunLoop, we will NOT detach it.
    if (did_attach_thread) {
        g_jvm->DetachCurrentThread();
    }
}

void log_info_to_kt(const char *fmt, ...) {
    char buffer[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    log_to_kotlin_bridge((ggml_log_level) 4, buffer);
}

static void slf4j_log_callback(ggml_log_level level, const char *fmt, void *data) {
    log_to_kotlin_bridge(level, fmt);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }

    jclass local_class = env->FindClass("android/llama/cpp/LLamaAndroid");
    if (!local_class) return -1;
    g_llama_android_class = (jclass) env->NewGlobalRef(local_class);

    g_log_from_native_method = env->GetStaticMethodID(g_llama_android_class, "logFromNative",
                                                      "(ILjava/lang/String;)V");
    if (!g_log_from_native_method) return -1;

    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_load_1model(JNIEnv *env, jobject, jstring filename) {
    llama_model_params model_params = llama_model_default_params();

    auto path_to_model = env->GetStringUTFChars(filename, 0);
    LOGi("model: loading from %s", path_to_model);

    auto model = llama_model_load_from_file(path_to_model, model_params);
    env->ReleaseStringUTFChars(filename, path_to_model);

    if (!model) {
        LOGe("model: load_model() failed");
        throw_java(env, "java/lang/IllegalStateException", "load_model() failed");
        return 0;
    }

    return reinterpret_cast<jlong>(model);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1model(JNIEnv *, jobject, jlong model) {
    llama_model_free(reinterpret_cast<llama_model *>(model));
}

/**
 * Backstops a context size Kotlin chose: a misparsed header must not ask for more than the model
 * was trained for, and a non-positive argument falls back to the default. Never clamps below
 * DEFAULT_N_CTX, the context a 2048-trained model always got, so no prompt that fit regresses.
 *
 * @param requested the context asked for, in tokens
 * @param trained_ctx what the model was trained for, or 0 when it does not say
 * @param which which size is being clamped, so two clamps in one load read as two in the log
 * @return the context to configure, never above trained_ctx unless that is below DEFAULT_N_CTX
 */
static int clamp_context(int requested, int trained_ctx, const char *which) {
    int clamped = requested > 0 ? requested : DEFAULT_N_CTX;
    const int ceiling = std::max(trained_ctx, DEFAULT_N_CTX);
    if (trained_ctx > 0 && clamped > ceiling) {
        LOGi("context: %s n_ctx %d exceeds the model's trained %d; clamping to %d", which, clamped,
             trained_ctx, ceiling);
        clamped = ceiling;
    }
    return clamped;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1context(JNIEnv *env, jobject, jlong jmodel, jint jn_ctx,
                                                 jboolean jquantize_kv, jint jfallback_n_ctx) {
    auto model = reinterpret_cast<llama_model *>(jmodel);

    if (!model) {
        LOGe("context: model cannot be null");
        throw_java(env, "java/lang/IllegalArgumentException", "Model cannot be null");
        return 0;
    }

    int default_threads = std::max(1, std::min(8, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));
    int n_threads = g_n_threads.load();
    if (n_threads <= 0) {
        n_threads = default_threads;
    }
    int n_threads_batch = g_n_threads_batch.load();
    if (n_threads_batch <= 0) {
        n_threads_batch = n_threads;
    }
    LOGi("context: using %d threads (batch=%d)", n_threads, n_threads_batch);

    llama_context_params ctx_params = llama_context_default_params();

    const int trained_ctx = llama_model_n_ctx_train(model);
    const int requested_ctx = clamp_context(jn_ctx, trained_ctx, "requested");
    const bool quantize_kv = jquantize_kv == JNI_TRUE;

    // AUTO rather than ENABLED: it is AUTO that makes llama.cpp validate a quantized cache against
    // the model's head width and refuse it by returning null. ENABLED skips that check and aborts
    // inside ggml instead, taking the IDE down with it.
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    if (quantize_kv) {
        // A quantized V cache is only defined with flash attention, which AUTO may still refuse.
        ctx_params.type_k = GGML_TYPE_Q8_0;
        ctx_params.type_v = GGML_TYPE_Q8_0;
    }

    ctx_params.n_ctx = requested_ctx;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads_batch;

    // The request, not the outcome: whether AUTO resolved to on is llama.cpp's own line to log,
    // under the AiAgentLocal.llama.cpp tag, since no public getter exposes cparams.flash_attn.
    LOGi("Creating context: n_ctx = %d (model trained for %d), kv cache = %s, flash attention = %s",
         requested_ctx, trained_ctx, quantize_kv ? "q8_0" : "f16",
         llama_flash_attn_type_name(ctx_params.flash_attn_type));

    llama_context *context = llama_init_from_model(model, ctx_params);
    bool quantized_in_use = quantize_kv;

    // Two unrelated failures land here and want opposite retries: a refused quantized cache is not
    // a shortage and keeps its long context, while a shortage is answered only by fewer bytes.
    if (!context && quantize_kv) {
        // Only the cache type moves: AUTO was the default before this PR and downgrades itself on
        // an f16 cache, so disabling it would make a refused model slower than it is on main.
        // Sized by Kotlin against f16, and clamped here so a load that never retries logs no clamp.
        const int fallback_ctx = clamp_context(jfallback_n_ctx, trained_ctx, "fallback");
        LOGe("Context creation failed; retrying at f16 with n_ctx %d", fallback_ctx);
        ctx_params.type_k = GGML_TYPE_F16;
        ctx_params.type_v = GGML_TYPE_F16;
        ctx_params.n_ctx = fallback_ctx;
        context = llama_init_from_model(model, ctx_params);
        quantized_in_use = false;
    }

    // The only retry that shrinks the allocation, back to the context every load got before this
    // was sized per device; the f16 retry above cannot, costing per token what it saved in tokens.
    // Guarded on size alone: at the same n_ctx nothing left to change makes the attempt cheaper.
    // n_ctx is unsigned; every value compared here is a clamped positive.
    const int current_ctx = (int) ctx_params.n_ctx;
    const int floor_ctx = std::min(current_ctx, DEFAULT_N_CTX);
    if (!context && floor_ctx < current_ctx) {
        LOGe("Context creation failed; retrying at the n_ctx %d floor with f16", floor_ctx);
        ctx_params.type_k = GGML_TYPE_F16;
        ctx_params.type_v = GGML_TYPE_F16;
        ctx_params.n_ctx = floor_ctx;
        context = llama_init_from_model(model, ctx_params);
        quantized_in_use = false;
    }

    if (!context) {
        LOGe("context: llama_new_context_with_model() returned null");
        throw_java(env, "java/lang/IllegalStateException",
                   "llama_new_context_with_model() returned null)");
        return 0;
    }

    // n_ctx and the cache type now vary per model and device, so a wrong one is invisible in a
    // report without this.
    LOGi("Context created: n_ctx = %u (requested %d, model trained for %d), n_batch = %u, kv cache = %s",
         llama_n_ctx(context), requested_ctx, trained_ctx, llama_n_batch(context),
         quantized_in_use ? "q8_0" : "f16");

    // A fresh context has an empty KV cache, so the prefix record must start empty too.
    {
        std::lock_guard<std::mutex> lock(g_globals_mutex);
        g_cached_tokens.clear();
    }

    return reinterpret_cast<jlong>(context);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1context(JNIEnv *, jobject, jlong context) {
    llama_free(reinterpret_cast<llama_context *>(context));

    // g_cached_tokens outlives the context it describes; left stale, the next completion_init()
    // reuses a prefix this now-empty cache doesn't have and decodes from a truncated context.
    std::lock_guard<std::mutex> lock(g_globals_mutex);
    g_cached_tokens.clear();
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_backend_1free(JNIEnv *, jobject) {
    llama_backend_free();
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_log_1to_1android(JNIEnv *, jobject) {
    llama_log_set(slf4j_log_callback, NULL);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_bench_1model(
        JNIEnv *env,
        jobject,
        jlong context_pointer,
        jlong model_pointer,
        jlong batch_pointer,
        jint pp,
        jint tg,
        jint pl,
        jint nr
) {
    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const auto context = reinterpret_cast<llama_context *>(context_pointer);
    const auto model = reinterpret_cast<llama_model *>(model_pointer);
    const auto batch = reinterpret_cast<llama_batch *>(batch_pointer);

    const int n_ctx = llama_n_ctx(context);

    LOGi("bench: n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("bench: prompt processing (pp)");

        common_batch_clear(*batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(*batch, 0, i, {0}, false);
        }

        batch->logits[batch->n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, *batch) != 0) {
            LOGi("bench: llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("bench: text generation (tg)");

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {

            common_batch_clear(*batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(*batch, 0, i, {j}, true);
            }

            LOGi("bench: llama_decode() text generation: %d", i);
            if (llama_decode(context, *batch) != 0) {
                LOGi("bench: llama_decode() failed during text generation");
            }
        }

        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("bench: pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(model)) / 1e9;

    const auto backend = "(Android)";

    std::stringstream result;
    result << std::setprecision(2);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";

    return new_jstring_utf8(env, result.str().c_str());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1batch(JNIEnv *, jobject, jint n_tokens, jint embd,
                                               jint n_seq_max) {

    // Source: Copy of llama.cpp:llama_batch_init but heap-allocated.

    llama_batch *batch = new llama_batch{
            0,
            nullptr,
            nullptr,
            nullptr,
            nullptr,
            nullptr,
            nullptr,
    };

    if (embd) {
        batch->embd = (float *) malloc(sizeof(float) * n_tokens * embd);
    } else {
        batch->token = (llama_token *) malloc(sizeof(llama_token) * n_tokens);
    }

    batch->pos = (llama_pos *) malloc(sizeof(llama_pos) * n_tokens);
    batch->n_seq_id = (int32_t *) malloc(sizeof(int32_t) * n_tokens);
    batch->seq_id = (llama_seq_id **) malloc(sizeof(llama_seq_id *) * n_tokens);
    for (int i = 0; i < n_tokens; ++i) {
        batch->seq_id[i] = (llama_seq_id *) malloc(sizeof(llama_seq_id) * n_seq_max);
    }
    batch->logits = (int8_t *) malloc(sizeof(int8_t) * n_tokens);

    {
        std::lock_guard<std::mutex> lock(g_globals_mutex);
        g_batch_n_tokens[batch] = n_tokens;
    }
    return reinterpret_cast<jlong>(batch);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1batch(JNIEnv *, jobject, jlong batch_pointer) {
    const auto batch = reinterpret_cast<llama_batch *>(batch_pointer);
    if (!batch) return;

    free(batch->token);
    free(batch->embd);
    free(batch->pos);
    free(batch->n_seq_id);
    if (batch->seq_id) {
        std::lock_guard<std::mutex> lock(g_globals_mutex);
        auto it = g_batch_n_tokens.find(batch);
        if (it != g_batch_n_tokens.end()) {
            for (int i = 0; i < it->second; ++i) {
                free(batch->seq_id[i]);
            }
            g_batch_n_tokens.erase(it);
        }
    }
    free(batch->seq_id);
    free(batch->logits);
    delete batch;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1sampler(JNIEnv *, jobject) {
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sparams);

    // The last n tokens to consider for the repetition penalty.
    // -1 means use the entire context size. 64 is a common default.
    int32_t penalty_last_n = 64;

    // The penalty value. 1.0 means no penalty. 1.1 is a good start.
    float penalty_repeat = 1.1f;

    // The following two penalties are disabled (set to 0.0) but are required
    // by the function signature.
    float penalty_freq = 0.0f;
    float penalty_present = 0.0f;

    // **THE FIX:** Add the penalties sampler to the chain.
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            penalty_last_n,
            penalty_repeat,
            penalty_freq,
            penalty_present
    ));

    const float temperature = g_temperature.load();
    const float top_p = g_top_p.load();
    const int top_k = g_top_k.load();

    if (temperature > 0.0f) {
        if (top_k > 0) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        }
        if (top_p > 0.0f && top_p < 1.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        }
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        const auto seed = static_cast<uint32_t>(
                std::chrono::steady_clock::now().time_since_epoch().count()
        );
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed));
    } else {
        // The chain must end with a sampler that actually selects a token.
        // Greedy is the simplest (always picks the most likely token).
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    }

    return reinterpret_cast<jlong>(smpl);
}

/**
 * Build a sampler chain constrained by a GBNF grammar, so the model can only
 * emit tokens the grammar allows — used for reliable text-based tool calls on
 * weak local models.
 *
 * @param model_pointer native llama_model handle.
 * @param grammar       GBNF grammar text, entered at its "root" rule.
 * @return the native sampler handle, or 0 if the model is null or the grammar
 *         fails to parse (the caller falls back to the plain sampler).
 */
extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1grammar_1sampler(
        JNIEnv *env, jobject, jlong model_pointer, jstring grammar) {
    const auto model = reinterpret_cast<llama_model *>(model_pointer);
    if (model == nullptr) return 0;
    if (grammar == nullptr) return 0;  // no grammar string — caller falls back

    const llama_vocab *vocab = llama_model_get_vocab(model);
    if (vocab == nullptr) return 0;    // model has no vocab — can't build a grammar sampler

    // NULL under memory pressure (pending OOM); clear the exception and fall back.
    const char *grammar_cstr = env->GetStringUTFChars(grammar, nullptr);
    if (grammar_cstr == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 0;
    }
    llama_sampler *grmr = llama_sampler_init_grammar(vocab, grammar_cstr, "root");
    env->ReleaseStringUTFChars(grammar, grammar_cstr);
    if (grmr == nullptr) return 0;  // invalid grammar — caller falls back

    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sparams);

    // Grammar first: it masks tokens that would violate the grammar, so the
    // selector below only ever picks a valid token.
    llama_sampler_chain_add(smpl, grmr);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));
    // Greedy: with the grammar mask in place, pick the most likely valid token.
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    return reinterpret_cast<jlong>(smpl);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1sampler(JNIEnv *, jobject, jlong sampler_pointer) {
    llama_sampler_free(reinterpret_cast<llama_sampler *>(sampler_pointer));
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_backend_1init(JNIEnv *, jobject, jboolean numa) {
    llama_backend_init();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_system_1info(JNIEnv *env, jobject) {
    return new_jstring_utf8(env, llama_print_system_info());
}

static int g_prompt_tokens = 0;

extern "C"
JNIEXPORT jint JNICALL
Java_android_llama_cpp_LLamaAndroid_completion_1init(
        JNIEnv *env,
        jobject,
        jlong context_pointer,
        jlong batch_pointer,
        jstring jtext,
        jboolean format_chat,
        jint n_len, jobjectArray stop) {

    {
        std::lock_guard<std::mutex> lock(g_globals_mutex);
        cached_token_chars.clear();
        g_generated_text.clear();
        g_stop_requested.store(false);
        // Parse stop strings from the Java array
        g_stop_strings.clear();
    }
    if (stop != nullptr) {
        int stop_count = env->GetArrayLength(stop);
        for (int i = 0; i < stop_count; i++) {
            auto jstr = (jstring) env->GetObjectArrayElement(stop, i);
            if (jstr) {
                const char *chars = env->GetStringUTFChars(jstr, nullptr);
                if (chars) {
                    {
                        std::lock_guard<std::mutex> lock(g_globals_mutex);
                        g_stop_strings.emplace_back(chars);
                    }
                    env->ReleaseStringUTFChars(jstr, chars);
                }
                env->DeleteLocalRef(jstr);
            }
        }
    }

    const auto text = env->GetStringUTFChars(jtext, 0);
    const auto context = reinterpret_cast<llama_context *>(context_pointer);
    const auto batch = reinterpret_cast<llama_batch *>(batch_pointer);

    bool parse_special = (format_chat == JNI_TRUE);
    const auto tokens_list = common_tokenize(context, text, true, parse_special);

    int n_ctx = llama_n_ctx(context);
    size_t n_kv_req = tokens_list.size() + static_cast<size_t>(n_len);
    LOGi("prefill: n_len = %d, n_ctx = %d, n_kv_req = %zu", n_len, n_ctx, n_kv_req);

    if (n_kv_req > n_ctx) {
        LOGe("prefill: n_kv_req > n_ctx, the required KV cache size is not big enough");
        // Released before returning, as on every other exit from here: jtext is pinned until it is.
        env->ReleaseStringUTFChars(jtext, text);
        throw_java(env, "java/lang/IllegalArgumentException",
                   "Prompt is too long for the model's context size.");
        return 0;
    }

    g_prompt_tokens = static_cast<int>(tokens_list.size());

    for (auto id: tokens_list) {
        LOGv("prefill: token `%s` -> %d", common_token_to_piece(context, id).c_str(), id);
    }

    // Reuse the longest common prefix with the cached sequence so the unchanged prefix (system prompt) isn't re-prefilled.
    size_t lcp = 0;
    {
        std::lock_guard<std::mutex> lock(g_globals_mutex);
        if (g_kv_cache_reuse.load() && !g_cached_tokens.empty()) {
            const size_t maxlcp = std::min(g_cached_tokens.size(), tokens_list.size());
            while (lcp < maxlcp && g_cached_tokens[lcp] == tokens_list[lcp]) {
                lcp++;
            }
        }
    }

    // Always leave at least one token to decode, so we get logits for the next token.
    if (lcp == tokens_list.size() && lcp > 0) {
        lcp--;
    }

    llama_memory_t mem = llama_get_memory(context);
    if (lcp == 0) {
        // Nothing reusable — full reset.
        llama_memory_clear(mem, true);
    } else {
        // Evict cached positions past the common prefix.
        llama_memory_seq_rm(mem, 0, (llama_pos) lcp, -1);
    }

    // Sliced: the batch's fixed capacity can now sit far below n_ctx, and overrunning it wrecks the heap.
    const size_t batch_capacity = batch_capacity_of(batch);
    const size_t chunk_limit = std::min<size_t>(batch_capacity, llama_n_batch(context));

    if (chunk_limit == 0) {
        // Not llama_n_batch(context): an untracked batch has an unknown allocation to overrun.
        LOGe("prefill: batch was not created by new_batch(), so its capacity is unknown");
        forget_cached_prefix(context);
        env->ReleaseStringUTFChars(jtext, text);
        throw_java(env, "java/lang/IllegalStateException",
                   "Batch capacity is unknown.");
        return 0;
    }

    const size_t prefill_tokens = tokens_list.size() - lcp;
    const size_t slices = (prefill_tokens + chunk_limit - 1) / chunk_limit;
    // The only direct evidence the chunked path ran rather than the old single-batch prefill.
    LOGi("prefill: %zu tokens (%zu reused from cache) in %zu slice(s) of at most %zu",
         prefill_tokens, lcp, slices, chunk_limit);

    for (size_t start = lcp; start < tokens_list.size(); start += chunk_limit) {
        const size_t end = std::min(start + chunk_limit, tokens_list.size());
        common_batch_clear(*batch);
        for (size_t i = start; i < end; i++) {
            common_batch_add(*batch, tokens_list[i], (llama_pos) i, {0}, false);
        }

        // Only the last prompt token needs logits; earlier slices just populate the KV cache.
        if (end == tokens_list.size() && batch->n_tokens > 0) {
            batch->logits[batch->n_tokens - 1] = true;
        }

        if (batch->n_tokens > 0 && llama_decode(context, *batch) != 0) {
            LOGe("prefill: llama_decode() failed for tokens %zu..%zu", start, end);
            forget_cached_prefix(context);
            env->ReleaseStringUTFChars(jtext, text);
            throw_java(env, "java/lang/IllegalStateException",
                       "Failed to process the prompt.");
            return 0;
        }
    }

    // Recorded only after every slice decoded, so the record matches what the KV cache holds.
    {
        std::lock_guard<std::mutex> lock(g_globals_mutex);
        g_cached_tokens.assign(tokens_list.begin(), tokens_list.end());
    }

    env->ReleaseStringUTFChars(jtext, text);

    return g_prompt_tokens;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_completion_1loop(
        JNIEnv *env,
        jobject,
        jlong context_pointer,
        jlong batch_pointer,
        jlong sampler_pointer,
        jint n_len,
        jobject intvar_ncur
) {
    const auto context = reinterpret_cast<llama_context *>(context_pointer);
    const auto batch = reinterpret_cast<llama_batch *>(batch_pointer);
    const auto sampler = reinterpret_cast<llama_sampler *>(sampler_pointer);
    const auto model = llama_get_model(context);
    const auto vocab = llama_model_get_vocab(model);

    if (!la_int_var) la_int_var = env->GetObjectClass(intvar_ncur);
    if (!la_int_var_value) la_int_var_value = env->GetMethodID(la_int_var, "getValue", "()I");
    if (!la_int_var_inc) la_int_var_inc = env->GetMethodID(la_int_var, "inc", "()V");

    if (g_stop_requested.load()) {
        return nullptr;
    }

    const auto new_token_id = llama_sampler_sample(sampler, context, -1);

    const auto n_cur = env->CallIntMethod(intvar_ncur, la_int_var_value);
    const auto generated = n_cur - g_prompt_tokens;
    if (llama_vocab_is_eog(vocab, new_token_id) || generated >= n_len) {
        return nullptr;
    }

    auto new_token_chars = common_token_to_piece(context, new_token_id);
    {
        std::lock_guard<std::mutex> lock(g_globals_mutex);
        cached_token_chars += new_token_chars;
    }

    jstring new_token = nullptr;
    std::string cached_snapshot;
    {
        std::lock_guard<std::mutex> lock(g_globals_mutex);
        cached_snapshot = cached_token_chars;
    }
    if (is_valid_utf8(cached_snapshot.c_str())) {
        size_t prior_len = 0;
        {
            std::lock_guard<std::mutex> lock(g_globals_mutex);
            prior_len = g_generated_text.size();
            g_generated_text += cached_token_chars;
        }

        // Check if any stop string has been generated
        std::vector<std::string> stop_strings_snapshot;
        {
            std::lock_guard<std::mutex> lock(g_globals_mutex);
            stop_strings_snapshot = g_stop_strings;
        }
        std::string generated_snapshot;
        {
            std::lock_guard<std::mutex> lock(g_globals_mutex);
            generated_snapshot = g_generated_text;
        }
        for (const auto &stop_str : stop_strings_snapshot) {
            if (!stop_str.empty() && generated_snapshot.length() >= stop_str.length()) {
                auto pos = generated_snapshot.find(stop_str);
                if (pos != std::string::npos) {
                    LOGi("generate: stop string matched: %s", stop_str.c_str());
                    size_t prefix_len = pos > prior_len ? pos - prior_len : 0;
                    if (prefix_len > 0) {
                        std::string prefix;
                        {
                            std::lock_guard<std::mutex> lock(g_globals_mutex);
                            cached_token_chars = cached_token_chars.substr(0, prefix_len);
                            prefix = cached_token_chars;
                        }
                        new_token = new_jstring_utf8(env, prefix.c_str());
                    } else {
                        {
                            std::lock_guard<std::mutex> lock(g_globals_mutex);
                            cached_token_chars.clear();
                        }
                        new_token = new_jstring_utf8(env, "");
                    }
                    {
                        std::lock_guard<std::mutex> lock(g_globals_mutex);
                        g_generated_text = g_generated_text.substr(0, pos);
                        cached_token_chars.clear();
                    }
                    g_stop_requested.store(true);
                    return new_token;
                }
            }
        }

        {
            std::lock_guard<std::mutex> lock(g_globals_mutex);
            new_token = new_jstring_utf8(env, cached_token_chars.c_str());
        }

#ifndef NDEBUG
        // Per-token JNI upcall into the Kotlin logger — debug-only; on the hot
        // path in release it would add a JNI round-trip (and a lock) per token.
        {
            std::lock_guard<std::mutex> lock(g_globals_mutex);
            log_info_to_kt("cached: %s, new_token_chars: `%s`, id: %d", cached_token_chars.c_str(),
                       new_token_chars.c_str(), new_token_id);
        }
#endif

        {
            std::lock_guard<std::mutex> lock(g_globals_mutex);
            cached_token_chars.clear();
        }
    } else {
        new_token = new_jstring_utf8(env, "");
    }

    common_batch_clear(*batch);
    common_batch_add(*batch, new_token_id, n_cur, {0}, true);

    env->CallVoidMethod(intvar_ncur, la_int_var_inc);

    if (llama_decode(context, *batch) != 0) {
        LOGe("generate: llama_decode() returned null");
        return nullptr;
    }

    if (g_kv_cache_reuse.load()) {
        std::lock_guard<std::mutex> lock(g_globals_mutex);
        g_cached_tokens.push_back(new_token_id);
    }

    return new_token;
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_kv_1cache_1clear(JNIEnv *, jobject, jlong context) {
    llama_memory_clear(llama_get_memory(reinterpret_cast<llama_context *>(context)), true);
    std::lock_guard<std::mutex> lock(g_globals_mutex);
    g_cached_tokens.clear();
}


extern "C"
JNIEXPORT jint JNICALL
Java_android_llama_cpp_LLamaAndroid_model_1n_1ctx(
        JNIEnv *env,
        jobject /* this */,
        jlong context_ptr) {
    auto *context = reinterpret_cast<llama_context *>(context_ptr);
    if (!context) {
        return 0;
    }
    return llama_n_ctx(context);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_android_llama_cpp_LLamaAndroid_tokenize(
        JNIEnv *env,
        jobject /* this */,
        jlong context_ptr,
        jstring text_to_tokenize,
        jboolean add_bos) {
    auto *context = reinterpret_cast<llama_context *>(context_ptr);
    if (!context) {
        return env->NewIntArray(0); // Return empty array if context is invalid
    }

    const char *text_chars = env->GetStringUTFChars(text_to_tokenize, nullptr);
    std::string text(text_chars);
    env->ReleaseStringUTFChars(text_to_tokenize, text_chars);

    bool parse_special = false;
    const std::vector<llama_token> tokens_list = common_tokenize(context, text, add_bos,
                                                                 parse_special);

    jintArray result = env->NewIntArray(tokens_list.size());

    if (!tokens_list.empty()) {
        env->SetIntArrayRegion(result, 0, tokens_list.size(),
                               reinterpret_cast<const jint *>(tokens_list.data()));
    }

    return result;
}
