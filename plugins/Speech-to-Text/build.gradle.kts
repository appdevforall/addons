plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "speech-to-text"
}

android {
    namespace = "com.itsaky.androidide.plugins.stt"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.itsaky.androidide.plugins.stt"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(files("../../libs/plugin-api.jar"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Material Design & AndroidX
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core:1.12.0")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}

tasks.matching {
    it.name.contains("checkDebugAarMetadata") || it.name.contains("checkReleaseAarMetadata")
}.configureEach { enabled = false }
