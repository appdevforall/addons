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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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

    sourceSets {
        // The golden set is committed once, under src/test/resources, and also ships in the test APK.
        getByName("androidTest").assets.srcDir("src/test/resources")
        // The instrumented tests of the real runner start the two engine programs from the test APK's own native library
        // directory: the same committed files, packaged into the test APK only (see androidComponents below).
        getByName("androidTest").jniLibs.srcDir("src/main/jniLibs")
    }

    packaging {
        jniLibs {
            // The two prebuilt engine programs must reach the .cgp byte for byte (their sha256 is pinned by a test):
            // no stripping and no other rewriting of them. See NATIVE-BUILD.md, "Part 1 engine".
            keepDebugSymbols += setOf("**/libpart1_membw.so", "**/libpart1_gpubw.so")
        }
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

// Test APK only: extract its native libraries to disk (legacy packaging), so that `applicationInfo.nativeLibraryDir` of the
// test APK holds the engine programs as executable files. The app variants, and therefore the .cgp, keep the default packaging
// (stored, not extracted); the .cgp byte-identity check in assemblePlugin* is unchanged. See NATIVE-BUILD.md, "Part 1 engine".
androidComponents {
    onVariants { variant ->
        variant.androidTest?.packaging?.jniLibs?.useLegacyPackaging?.set(true)
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
    // plugin-api's prompt loader parses YAML with the host's copy; JVM tests need their own, same version
    testImplementation("org.snakeyaml:snakeyaml-engine:2.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    // Production code uses Android's built-in org.json; the JVM has none, so unit tests bring the same API.
    testImplementation("org.json:json:20250517")
    // LiveData's postValue needs the arch-core executor swapped for a synchronous one; the
    // settings pane publishes its state through it, so its tests cannot run without this.
    testImplementation("androidx.arch.core:core-testing:2.2.0")

    // The native fallback ladder only runs on a device. The AAR is a file dependency, so its own
    // slf4j-api is not pulled in, and the host that normally supplies it is absent from a test APK.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.slf4j:slf4j-api:2.0.12")
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
    // The source the committed AAR's JNI wrapper must have been built from; a change here reruns the test.
    val wrapperSource = file("llama-impl/src/main/cpp/llama-android.cpp")
    inputs.file(wrapperSource)
    systemProperty("llamaAndroidSourcePath", wrapperSource.absolutePath)
    // The Part 1 index asset the device-match tests read straight from the source tree.
    val part1Index = file("src/main/assets/part1/part1-dbindex-v1.json.gz")
    inputs.file(part1Index)
    systemProperty("part1IndexAssetPath", part1Index.absolutePath)
    // The Part 1 engine programs the build must package unchanged, and the folder the debug .cgp is built into.
    systemProperty("part1EngineLibDir", file("src/main/jniLibs/$expectedAbi").absolutePath)
    systemProperty("part1EngineDir", file("part1-engine").absolutePath)
    systemProperty("builtPluginDir", layout.buildDirectory.dir("plugin").get().asFile.absolutePath)
    // Lab examples are never committed; the lab-set test runs only when this is given on the command line.
    System.getProperty("part1.requireCgp")?.let { systemProperty("part1.requireCgp", it) }
    System.getProperty("part1.labExamplesDir")?.let { systemProperty("part1.labExamplesDir", it) }
    // The main sources of the three new Part 1 packages (engine, staticread, store): a test scans them.
    systemProperty("part1MainSrcDir", file("src/main/kotlin").absolutePath)
    // Optional: a directory (outside the repository) to receive the static inputs and descriptors the engine,
    // static-read and store tests produce, so generate.py and validate.py can judge them.
    System.getProperty("part1.engineOutDir")?.let { systemProperty("part1.engineOutDir", it) }
    // Optional: a directory (outside the repository) to receive the golden-set descriptors, so an external
    // validator can check them.
    System.getProperty("part1.goldenOutDir")?.let { systemProperty("part1.goldenOutDir", it) }
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

        // The unit tests open the debug .cgp. (With -Dpart1.requireCgp=true a missing .cgp fails them instead of skipping.) They do not depend on building it (plugin-builder moves the APK into the
        // .cgp, so a second assemble with nothing changed produces none), but when both are asked for in one
        // invocation the assemble runs first.
        if (isDebug) tasks.withType<Test>().configureEach { mustRunAfter(assembleTaskName) }

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

                    // The Part 1 engine programs must be packaged byte for byte (no strip, no rewrite).
                    val committed = file("src/main/jniLibs/$abi")
                    ZipFile(cgp).use { zip ->
                        listOf("libpart1_membw.so", "libpart1_gpubw.so").forEach { lib ->
                            val entry = zip.getEntry("lib/$abi/$lib")
                                ?: throw GradleException("${cgp.name} does not contain lib/$abi/$lib.")
                            val packaged = zip.getInputStream(entry).use { it.readBytes() }
                            if (!packaged.contentEquals(File(committed, lib).readBytes())) {
                                throw GradleException("${cgp.name}: lib/$abi/$lib differs from the committed file; packaging must not rewrite it.")
                            }
                        }
                    }
                    logger.lifecycle("${cgp.name}: the Part 1 engine programs are byte-identical to the committed files")
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
