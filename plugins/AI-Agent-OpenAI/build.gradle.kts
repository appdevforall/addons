plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "ai-agent-openai"
}

android {
    namespace = "com.itsaky.androidide.plugins.aiagentopenai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.itsaky.androidide.plugins.aiagentopenai"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
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

    // 'implementation' (not 'compileOnly') for the androidx/Material libraries: AAPT2 needs them
    // at compile time to process the settings pane's layout, as in every CoGo plugin with XML.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("com.google.android.material:material:1.10.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation(files("../../libs/plugin-api.jar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.json:json:20231013")
}

// No SecureApiKeyStore parity check any more: the AES/GCM core is the host's KeystoreSecretStore
// (plugin-api), so there is one implementation in the process rather than copies to keep in step.

// AAR metadata checks are disabled by convention for these application-as-library plugins.
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach { enabled = false }
