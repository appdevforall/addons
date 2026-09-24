import java.io.File
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "ai-agent-local"
}

android {
    namespace = "com.itsaky.androidide.plugins.aiagentlocal"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.itsaky.androidide.plugins.aiagentlocal"
        minSdk = 33
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            // Disable minification to prevent JNI method stripping (IntVar.getValue)
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    compileOnly(files("../../libs/plugin-api.jar"))

    implementation(files("libs/v8/llama-v8-release.aar"))
    implementation(files("libs/llama-api.jar"))

    // 'implementation' (not 'compileOnly') for the androidx/Material libraries: AAPT2 needs them
    // at compile time to process the settings pane's layout, as in every CoGo plugin with XML.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("com.google.android.material:material:1.10.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation(files("../../libs/plugin-api.jar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    // LiveData's postValue needs the arch-core executor swapped for a synchronous one; the
    // settings pane publishes its state through it, so its tests cannot run without this.
    testImplementation("androidx.arch.core:core-testing:2.2.0")
}

// The one ABI this plugin ships. Shared by the packaging check and the unit tests.
val expectedAbi = "arm64-v8a"

// Matched on the variant suffix rather than an exact name: plugin-builder owns the file name.
fun File.isPluginArtifactFor(isDebug: Boolean) =
    name.endsWith(".cgp") && name.endsWith("-debug.cgp") == isDebug

// Unit tests read the committed AAR; pass its path so they do not depend on the working directory.
tasks.withType<Test>().configureEach {
    systemProperty("prebuiltAarPath", file("libs/v8/llama-v8-release.aar").absolutePath)
    systemProperty("expectedAbi", expectedAbi)
}

// Guards the abiFilters above: a regenerated AAR or a dropped filter would silently re-inflate the .cgp.
// The check runs as the assemble task's last action rather than as a finalizer: a finalizer executes even
// after a failed assemble, so it would bury the real compile error under a "no .cgp found" failure in CI.
// It stays inline rather than moving to an applied script: apply(from = "*.gradle.kts") crashes
// lintVitalAnalyzeRelease ("Cannot find a KaModule for the VirtualFile") with this AGP/lint version.
// plugin-builder creates these tasks untyped inside its own afterEvaluate, so the task name is the only
// handle; tasks.named fails loudly if that name changes, where a name filter would silently stop wiring.
afterEvaluate {
    mapOf("assemblePlugin" to false, "assemblePluginDebug" to true).forEach { (assembleTaskName, isDebug) ->
        // Captured as a provider and read inside the action, so no path resolves at configuration time.
        val pluginDir = layout.buildDirectory.dir("plugin")
        val abi = expectedAbi

        tasks.named(assembleTaskName) {
            // Runs before plugin-builder copies the new artifact in, so the check below can only ever see
            // this run's output. A leftover .cgp (an earlier build under a different pluginName, say) is
            // released by CI verbatim, so dropping it here keeps the shipped set and the checked set equal.
            doFirst {
                pluginDir.get().asFile.listFiles()
                    ?.filter { it.isPluginArtifactFor(isDebug) }
                    ?.forEach { it.delete() }
            }

            doLast {
                val dir = pluginDir.get().asFile
                val artifacts = dir.listFiles()
                    ?.filter { it.isPluginArtifactFor(isDebug) }
                    ?.sortedBy { it.name }
                    .orEmpty()
                if (artifacts.isEmpty()) {
                    throw GradleException("$assembleTaskName produced no .cgp under $dir.")
                }

                artifacts.forEach { cgp ->
                    val abis = sortedSetOf<String>()
                    ZipFile(cgp).use { zip ->
                        val entries: Enumeration<out ZipEntry> = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry: ZipEntry = entries.nextElement()
                            val name: String = entry.name
                            if (!entry.isDirectory && name.startsWith("lib/") && name.endsWith(".so")) {
                                abis.add(name.removePrefix("lib/").substringBefore('/'))
                            }
                        }
                    }

                    if (abis != sortedSetOf(abi)) {
                        throw GradleException(
                            "${cgp.name} packages native libraries for $abis but must package exactly [$abi]. " +
                                "Check the ndk.abiFilters block in build.gradle.kts.",
                        )
                    }
                    logger.lifecycle("${cgp.name}: native libraries limited to $abi")
                }
            }
        }
    }
}

// AAR metadata checks are disabled by convention for these application-as-library
// plugins. The prebuilt llama .aar carries a "core library desugaring required"
// flag, but this module's minSdk (33) makes desugaring unnecessary at runtime,
// so the check is a false positive here.
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach { enabled = false }
