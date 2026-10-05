import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "go-tools"
}

data class GoToolchain(
    val assetPath: String,
    val url: String,
    val sha256: String,
)

// Fetched at build time rather than committed: the payload is ~38 MB.
val goToolchains =
    listOf(
        GoToolchain(
            "toolchain/golang-aarch64.deb",
            "https://packages.termux.dev/apt/termux-main/pool/main/g/golang/golang_3%3a1.27.1_aarch64.deb",
            "95d7e100ed75278c74d01a1db4794b755526b70938d8ca4aca62b441658fe2f9",
        ),
        GoToolchain(
            "toolchain/gopls-aarch64.deb",
            "https://packages.termux.dev/apt/termux-main/pool/main/g/gopls/gopls_0.23.0_aarch64.deb",
            "6ae1811cd09168bb2edfcef4dcfe2cf868af9b94f8b97ae06579b0101274d73c",
        ),
    )

val generatedAssetsDir = layout.buildDirectory.dir("generated/toolchainAssets")

data class GrammarSource(
    val name: String,
    val version: String,
    val sha256: String,
) {
    val url = "https://registry.npmjs.org/tree-sitter-$name/-/tree-sitter-$name-$version.tgz"
}

val grammarSource = GrammarSource("go", "0.23.4", "8779d20d322b4319ad8c833ea72ecb6d109cf1fec8979bdcdb5315a2e511dd2c")
val grammarDir = layout.buildDirectory.dir("tree-sitter/${grammarSource.name}")

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val downloadGoToolchain =
    tasks.register("downloadGoToolchain") {
        group = "setup"
        description = "Download and verify the Go toolchain bundled into the plugin"

        val toolchains = goToolchains
        val outputDir = generatedAssetsDir
        inputs.property("toolchains", toolchains.map { "${it.assetPath} ${it.url} ${it.sha256}" })
        outputs.dir(outputDir)

        doLast {
            toolchains.forEach { toolchain ->
                val target = outputDir.get().file(toolchain.assetPath).asFile
                target.parentFile.mkdirs()

                if (target.exists() && sha256Of(target) == toolchain.sha256) {
                    logger.lifecycle("${toolchain.assetPath} is up-to-date (checksum matches).")
                    return@forEach
                }

                logger.lifecycle("Downloading ${toolchain.url} -> ${toolchain.assetPath}")
                val connection = URI(toolchain.url).toURL().openConnection()
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                try {
                    connection.getInputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: IOException) {
                    target.delete()
                    throw GradleException("Failed to download ${toolchain.url}: ${e.message}", e)
                }

                val actual = sha256Of(target)
                if (actual != toolchain.sha256) {
                    target.delete()
                    throw GradleException(
                        "Checksum mismatch for ${toolchain.assetPath} " +
                            "(expected ${toolchain.sha256}, got $actual)",
                    )
                }
            }
        }
    }

val downloadGrammar =
    tasks.register("downloadTreeSitterGrammar") {
        group = "setup"
        description = "Download, verify, and unpack the tree-sitter grammar compiled into the plugin"

        val source = grammarSource
        val outputDir = grammarDir
        val archive = layout.buildDirectory.file("tree-sitter/${source.name}-${source.version}.tgz")
        inputs.property("grammarVersion", source.version)
        inputs.property("grammarUrl", source.url)
        inputs.property("grammarSha256", source.sha256)
        outputs.dir(outputDir)

        doLast {
            val archiveFile = archive.get().asFile
            archiveFile.parentFile.mkdirs()
            if (!archiveFile.exists() || sha256Of(archiveFile) != source.sha256) {
                logger.lifecycle("Downloading ${source.url}")
                val connection = URI(source.url).toURL().openConnection()
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000
                try {
                    connection.getInputStream().use { input ->
                        archiveFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: IOException) {
                    archiveFile.delete()
                    throw GradleException("Failed to download ${source.url}: ${e.message}", e)
                }
                val actual = sha256Of(archiveFile)
                if (actual != source.sha256) {
                    archiveFile.delete()
                    throw GradleException("Checksum mismatch for ${source.url} (expected ${source.sha256}, got $actual)")
                }
            }
            val target = outputDir.get().asFile
            target.deleteRecursively()
            project.copy {
                from(project.tarTree(project.resources.gzip(archiveFile)))
                include("package/src/**")
                eachFile { path = path.removePrefix("package/") }
                includeEmptyDirs = false
                into(target)
            }
        }
    }

tasks.named("preBuild") {
    dependsOn(downloadGoToolchain, downloadGrammar)
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
    dependsOn(downloadGrammar)
}

downloadGrammar.configure {
    mustRunAfter(tasks.named("clean"))
}

// `gradlew clean assemblePlugin` otherwise lets clean delete what the download just wrote.
downloadGoToolchain.configure {
    mustRunAfter(tasks.named("clean"))
}

android {
    namespace = "com.appdevforall.go.plugin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.appdevforall.go.plugin"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DTREE_SITTER_GRAMMAR_NAME=${grammarSource.name}"
                arguments += "-DTREE_SITTER_GRAMMAR_DIR=${grammarDir.get().asFile.absolutePath}"
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "deb"
    }

    sourceSets {
        named("main") {
            assets.srcDir(generatedAssetsDir.get().asFile)
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(files("../../libs/plugin-api.jar"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

tasks.wrapper {
    gradleVersion = "9.6.1"
    distributionType = Wrapper.DistributionType.BIN
}

tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}
