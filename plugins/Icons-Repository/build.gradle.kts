import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "icons-repository"
    pluginVersion = "2.0.3"
}

android {
    namespace = "dev.omar.plugin.iconsrepo"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.omar.plugin.iconsrepo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "2.0.3"
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

    buildFeatures {
        viewBinding = true
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
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(files("../../libs/plugin-api.jar"))
    
    implementation("com.github.megatronking:svg-generator:1.3.2")
    implementation("com.github.megatronking:svg-support:1.3.2")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.fragment:fragment:1.8.8")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.caverock:androidsvg:1.4")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
tasks.wrapper {
    gradleVersion = "9.6.1"
    distributionType = Wrapper.DistributionType.BIN
}

tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}
configurations.all {
    resolutionStrategy {
        force("com.google.guava:guava:31.1-android")
    }
}