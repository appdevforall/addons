import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
    // Unit-test coverage. CoGo's root build uses JaCoCo 0.8.11 + Sonar; this
    // plugin keeps a single-module report (`./gradlew jacocoTestReport`) so the
    // covered-logic claim in the PR is reproducible. The UI layer is intentionally
    // 0% here — fragments + MapLibre GL are device-tested via android-qa, not JVM.
    jacoco
}

jacoco {
    toolVersion = "0.8.11"
}

pluginBuilder {
    pluginName = "maps"
}

android {
    namespace = "org.appdevforall.maps"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.appdevforall.maps"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
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

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        noCompress.add("pmtiles")
    }

    testOptions {
        unitTests {
            // Production code logs via android.util.Log; in plain JVM unit tests
            // android.util.Log is the stubbed android.jar and throws "not mocked".
            // Returning default values makes Log calls no-ops so the pure parsing
            // logic stays unit-testable.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // plugin-api jar is the canonical plugin contract; IDE provides at runtime.
    compileOnly(files("../../libs/plugin-api.jar"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.fragment:fragment:1.8.8")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // MapLibre OpenGL ES — Vulkan unreliable on API 26-29 target audience.
    // 13.x fixes PMTiles file-source range-header handling vs 11.x.
    implementation("org.maplibre.gl:android-sdk-opengl:13.1.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Hilbert-curve range decomposition for bbox→tile-id slicing.
    implementation("com.github.davidmoten:hilbert-curve:0.2.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20210307")
    // Local HTTP stub for unit-testing the range-fetch + download paths
    // (HttpRangeFetcher, RegionDownloader) without hitting a real server.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // plugin-api is compileOnly for the main set (host-provided at runtime). Unit
    // tests run on a plain JVM with no host, so they need it on the test classpath
    // to compile + run the host-facing classes (MapsPlugin) and the test fakes.
    testImplementation(files("../../libs/plugin-api.jar"))
}

tasks.wrapper {
    gradleVersion = "9.6.1"
    distributionType = Wrapper.DistributionType.BIN
}

// Disable AAR metadata checks that fail under the plugin-builder
// pipeline (Beepy and Forms use the same workaround).
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}

tasks.withType<Test>().configureEach {
    // Template tests read these files by path, so declare them or a template edit stays UP-TO-DATE.
    inputs.dir("src/main/assets/templates").withPropertyName("templates")
    System.getProperty("runOnlineSlicerTests")?.let {
        systemProperty("runOnlineSlicerTests", it)
    }
    testLogging {
        events("standardOut")
        showStandardStreams = true
    }
    // Match CoGo's JaCoCo agent config so coverage data is written.
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

// Single-module unit-test coverage report. Run: `./gradlew jacocoTestReport`
// (HTML at build/reports/jacoco/jacocoTestReport/html/index.html). UI classes
// register 0% — they're device-tested via android-qa, not JVM-unit-testable.
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    val fileFilter = listOf(
        "**/R.class", "**/R\$*.class", "**/BuildConfig.*",
        "**/Manifest*.*", "**/*Test*.*", "**/databinding/**",
    )
    // AGP 9 compiles Kotlin itself and writes the classes here, not to tmp/kotlin-classes.
    val classes = fileTree(
        layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
    ) { exclude(fileFilter) }
    classDirectories.setFrom(classes)
    // A wrong class path yields an empty report that still passes, so fail instead.
    doFirst {
        check(!classes.isEmpty) { "jacocoTestReport: no main classes under ${classes.dir}" }
    }
    sourceDirectories.setFrom(files("src/main/kotlin"))
    // Scope execution data to the exact exec file (not a build-dir-wide scan) so
    // Gradle doesn't see this task as implicitly consuming other tasks' outputs.
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
