import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ADFA-7281 spike 1. Throwaway: lives under spikes/, so `addons discover` never
// publishes it. Build with ../../gradlew assemblePluginDebug.

plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "worker-exec-probe"
}

android {
    namespace = "org.appdevforall.spikes.execprobe"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "org.appdevforall.spikes.execprobe"
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "0.3.3"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(files("../../libs/plugin-api.jar"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
}

// The probe is an *executable*, which AGP's CMake integration does not package, so it is
// compiled straight with the NDK's clang into the default jniLibs folder (gitignored).
// The lib*.so name is what makes the plugin manager extract it and mark it executable.
val ndkDir = androidComponents.sdkComponents.ndkDirectory
val probeSrc = layout.projectDirectory.file("src/main/cpp/exec_probe.c")
val probeOut = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libcogo_exec_probe.so")

val probeBuildId = android.defaultConfig.versionName ?: "unknown"

val buildExecProbe by tasks.registering(Exec::class) {
    inputs.file(probeSrc)
    inputs.property("probeBuildId", probeBuildId)
    outputs.file(probeOut)
    doFirst {
        probeOut.asFile.parentFile.mkdirs()
        val host = if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"
        executable = ndkDir.get().file("toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android28-clang").asFile.absolutePath
    }
    args(
        "-O2", "-Wall", "-Wextra", "-fPIE", "-pie",
        "-DPROBE_BUILD=\"$probeBuildId\"",
        "-Wl,-z,max-page-size=16384",
        "-o", probeOut.asFile.absolutePath, probeSrc.asFile.absolutePath,
    )
}

// Spike 3. The production llama.cpp libraries come straight from AI-Agent-Local's committed AAR,
// so the bench runs exactly what users run. Only the small bench core is compiled here, against
// the headers of the llama.cpp commit that AAR was built from (the AI-Agent-Local submodule:
// `git submodule update --init --depth 1 plugins/AI-Agent-Local/subprojects/llama.cpp`).
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
val llamaAar = layout.projectDirectory.file("../../plugins/AI-Agent-Local/libs/v8/llama-v8-release.aar")
val llamaSrc = layout.projectDirectory.dir("../../plugins/AI-Agent-Local/subprojects/llama.cpp")
val benchDir = layout.projectDirectory.dir("src/main/cpp/bench")

val copyLlamaLibs by tasks.registering(Copy::class) {
    from(zipTree(llamaAar)) {
        include("jni/arm64-v8a/*.so")
        exclude("jni/arm64-v8a/libllama-android.so") // AI-Agent-Local's JNI layer, not needed here
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(jniLibsDir)
}

fun registerBenchBinary(taskName: String, output: String, extraArgs: List<String>, sources: List<String>) =
    tasks.register<Exec>(taskName) {
        dependsOn(copyLlamaLibs)
        inputs.files(sources.map { benchDir.file(it) }, benchDir.file("bench_core.h"))
        outputs.file(jniLibsDir.file(output))
        doFirst {
            check(llamaSrc.file("include/llama.h").asFile.isFile) {
                "llama.cpp headers missing: run git submodule update --init --depth 1 plugins/AI-Agent-Local/subprojects/llama.cpp"
            }
            val host = if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"
            executable = ndkDir.get().file("toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android28-clang++").asFile.absolutePath
        }
        args(
            listOf("-O2", "-std=c++17", "-Wall", "-Wextra") + extraArgs +
                sources.map { benchDir.file(it).asFile.absolutePath } +
                listOf(
                    "-I${llamaSrc.dir("include").asFile}", "-I${llamaSrc.dir("ggml/include").asFile}",
                    "-Wl,-z,max-page-size=16384", "-static-libstdc++",
                    "-L${jniLibsDir.asFile}", "-lllama", "-lggml", "-lggml-base",
                    "-o", jniLibsDir.file(output).asFile.absolutePath,
                ),
        )
    }

val buildBenchWorker = registerBenchBinary(
    "buildBenchWorker", "libcogo_bench_worker.so",
    listOf("-fPIE", "-pie", "-Wl,-rpath,\$ORIGIN"), listOf("bench_core.cpp", "bench_worker.cpp"),
)
val buildBenchJni = registerBenchBinary(
    "buildBenchJni", "libcogo_bench_jni.so",
    listOf("-fPIC", "-shared"), listOf("bench_core.cpp", "bench_jni.cpp"),
)

tasks.named("preBuild") { dependsOn(buildExecProbe, buildBenchWorker, buildBenchJni) }

tasks.matching {
    it.name.contains("checkDebugAarMetadata") || it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}
