import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "rainbow-brackets"
}

android {
    namespace = "org.appdevforall.rainbowbrackets"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.appdevforall.rainbowbrackets"
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
    // The plugin-api jar is the canonical contract for plugins. Available
    // at compile time; the IDE provides it at runtime. This plugin only
    // supplies a color palette, so plugin-api + the Kotlin stdlib is all it needs.
    compileOnly(files("../../libs/plugin-api.jar"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")

    testImplementation("junit:junit:4.13.2")
}

tasks.wrapper {
    gradleVersion = "9.6.1"
    distributionType = Wrapper.DistributionType.BIN
}

// Disable AAR metadata checks that fail under the plugin-builder
// pipeline (Voice Alerts + Forms use the same workaround).
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}
