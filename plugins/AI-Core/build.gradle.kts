plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "ai-core"
}

android {
    namespace = "com.itsaky.androidide.plugins.aicore"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.itsaky.androidide.plugins.aicore"
        minSdk = 33
        targetSdk = 36
        versionCode = 5
        versionName = "3.1.0"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
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
    // at compile time to process this plugin's layouts, as in every CoGo plugin with XML.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Markdown rendering for the chat transcript
    implementation("io.noties.markwon:core:4.6.2")

    // JSON serialization for session persistence
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(files("../../libs/plugin-api.jar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
}

// AAR metadata checks are disabled by convention for these application-as-library
// plugins: the `application`-as-library packaging trips them.
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach { enabled = false }
