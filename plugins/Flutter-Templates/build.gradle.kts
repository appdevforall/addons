plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "flutter-templates"
}

android {
    namespace = "com.ali.fluttertemplate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ali.fluttertemplate"
        minSdk = 21
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
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

dependencies {
    compileOnly(files("../../libs/plugin-api.jar"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
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
