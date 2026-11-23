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
        versionCode = 2
        versionName = "0.1.1"
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

tasks.named("preBuild") { dependsOn(buildExecProbe) }

tasks.matching {
    it.name.contains("checkDebugAarMetadata") || it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}
