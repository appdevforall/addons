plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "ai-agent-mcp"
}

android {
    namespace = "com.itsaky.androidide.plugins.aiagentmcp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.itsaky.androidide.plugins.aiagentmcp"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        viewBinding = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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

    // 'implementation' (not 'compileOnly') for the androidx/Material libraries: AAPT2 needs them
    // at compile time to process the settings pane's layouts, as in every CoGo plugin with XML.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("com.google.android.material:material:1.10.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation(files("../../libs/plugin-api.jar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// No MCP SDK dependency on purpose: the official Kotlin SDK is KMP with no stated Android target,
// ships no HTTP engine, and needed Kotlin 2.4.10 when it was tried (ADFA-5083) against the 2.3.0
// these plugins standardise on. The transport here is HttpURLConnection and org.json, both of
// which the platform already provides — and never OkHttp, which resolves to the host's older copy.

// AAR metadata checks are disabled by convention for these application-as-library plugins.
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach { enabled = false }
