plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "layout-editor"
}

android {
    namespace = "org.appdevforall.codeonthego.layouteditor"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.appdevforall.codeonthego.layouteditor"
        minSdk = 26
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Host-provided at runtime (parent classloader) — link only, never bundle.
    compileOnly(files("../../libs/plugin-api.jar"))
    compileOnly(files("../../libs/eventbus-events.jar"))
    compileOnly(files("../../libs/common.jar"))
    compileOnly(files("../../libs/idetooltips.jar"))
    compileOnly("org.greenrobot:eventbus:3.3.1")
    compileOnly("org.slf4j:slf4j-api:2.0.9")

    // Bundled into the .cgp (vectormaster is sourced directly; the rest are libraries).
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0-beta02")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.jsibbold:zoomage:1.3.1")
    implementation("com.blankj:utilcodex:1.31.1")
    implementation("com.github.skydoves:colorpickerview:2.3.0")
    implementation(platform("io.github.Rosemoe.sora-editor:bom:0.23.6"))
    implementation("io.github.Rosemoe.sora-editor:editor")
    implementation("io.github.Rosemoe.sora-editor:language-textmate")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
}

// application-as-library packaging trips AAR metadata checks; disabling is intentional
// (documented convention in plugin-examples/CLAUDE.md).
tasks.matching {
    it.name.contains("checkDebugAarMetadata") || it.name.contains("checkReleaseAarMetadata")
}.configureEach { enabled = false }
