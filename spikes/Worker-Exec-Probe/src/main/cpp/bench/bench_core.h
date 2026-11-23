// ADFA-7281 spike 3: one llama.cpp benchmark, run unchanged in-process (bench_jni.cpp)
// and in the worker (bench_worker.cpp), so the only difference between the two numbers
// is where it runs and how tokens get back to the plugin.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

struct BenchParams {
    const char *model_path;
    int n_ctx;
    int n_threads;  // <= 0: AI-Agent-Local's default, min(8, cores - 2)
    const char *prompt;
    int n_predict;
};

// Called once per generated token with its text and the CLOCK_MONOTONIC time it was
// produced (the clock System.nanoTime() reads, so the plugin can compare timestamps).
using TokenSink = void (*)(void *user, const char *piece, size_t len, int64_t t_ns);

int64_t bench_now_ns();

// The calling thread's CPU affinity as a list ("0-5,7"), for diagnosing core placement.
std::string bench_affinity();

// Sets the calling thread's affinity to every configured CPU. Returns 0 or an errno.
int bench_reset_affinity();

// Loads the CPU backend the way AI-Agent-Local does: the best CPU variant by bare name.
// Returns the variant that loaded, or an empty string.
std::string bench_load_backend();

// Loads the model, decodes the prompt, generates n_predict tokens greedily (stopping early
// at end of generation), frees everything and returns the timings as one JSON object.
std::string bench_run(const BenchParams &p, TokenSink sink, void *user);
