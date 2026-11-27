import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "sqlite-explorer"
}

android {
    namespace = "org.appdevforall.sqliteexplorer"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.appdevforall.sqliteexplorer"
        minSdk = 26
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // plugin-api is the IDE-side contract: compile against it, the IDE provides it.
    compileOnly(files("../../libs/plugin-api.jar"))

    // Code on the Go loads classes parent-first and carries its own AndroidX, so the Fragment and back-press
    // classes this screen uses come from the host, never from a bundled copy.
    compileOnly("androidx.fragment:fragment:1.8.8")

    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Tests run on a computer's Java, where Android's org.json is an empty stub, so they bring their own.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}
