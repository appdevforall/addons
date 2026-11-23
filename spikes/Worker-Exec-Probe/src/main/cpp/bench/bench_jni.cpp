// The in-process side of spike 3: the same bench_run, called over JNI from the plugin, the way
// AI-Agent-Local runs llama.cpp today. Each token crosses JNI as a byte[] callback, as each of
// LLamaAndroid's tokens crosses JNI on its way to Kotlin.

#include <jni.h>

#include <string>

#include "bench_core.h"

namespace {
struct JniSink {
    JNIEnv *env;
    jobject cb;
    jmethodID on_token;
};

void sink(void *user, const char *piece, size_t len, int64_t t_ns) {
    auto *s = static_cast<JniSink *>(user);
    jbyteArray arr = s->env->NewByteArray((jsize) len);
    s->env->SetByteArrayRegion(arr, 0, (jsize) len, reinterpret_cast<const jbyte *>(piece));
    s->env->CallVoidMethod(s->cb, s->on_token, arr, (jlong) t_ns);
    s->env->DeleteLocalRef(arr);
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_appdevforall_spikes_execprobe_BenchJni_run(JNIEnv *env, jclass, jstring jpath, jint n_ctx,
                                                    jint n_threads, jstring jprompt, jint n_predict,
                                                    jobject callback) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    JniSink s{env, callback, nullptr};
    if (callback != nullptr) {
        jclass cls = env->GetObjectClass(callback);
        s.on_token = env->GetMethodID(cls, "onToken", "([BJ)V");
    }
    BenchParams p{path, n_ctx, n_threads, prompt, n_predict};
    std::string stats = bench_run(p, s.on_token ? sink : nullptr, &s);
    env->ReleaseStringUTFChars(jpath, path);
    env->ReleaseStringUTFChars(jprompt, prompt);
    return env->NewStringUTF(stats.c_str());
}
