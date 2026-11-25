import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "custom-ide-builder"
}

android {
    namespace = "org.appdevforall.customidebuilder"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.appdevforall.customidebuilder"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
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
                "META-INF/NOTICE.txt",
                // ARSCLib carries Android's framework resource tables (2.4 MB) for its decoder. Only the table
                // reader and writer are used here, never the decoder, so they stay out of the .cgp.
                "frameworks/**"
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
    // plugin-api is the IDE-side contract: compile against it, the IDE provides it.
    compileOnly(files("../../libs/plugin-api.jar"))

    // Code on the Go loads classes parent-first and carries its own AndroidX, so the Fragment and
    // Activity Result classes this screen uses come from the host, never from a bundled copy.
    compileOnly("androidx.fragment:fragment:1.8.8")

    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Signs the APK (v1, v2 and v3) and verifies the result. It is the same version as the Android
    // Gradle plugin in settings.gradle.kts, which publishes apksig beside itself on Google Maven.
    // Bundled into the .cgp: the host does not carry it.
    implementation("com.android.tools.build:apksig:9.3.1")

    // Reads and rewrites the host's resources.arsc (app name, icons) and reads a plugin's minSdkVersion out of its
    // binary manifest, with no platform parser. Apache-2.0, bundled into the .cgp. It also bundles three
    // android.* / org.xmlpull.* interface shims that the platform already provides; see README.md.
    implementation("io.github.reandroid:ARSCLib:1.4.0")

    // Tests run on a computer's Java. There Android's org.json is an empty stub, so they bring their own,
    // and BouncyCastle writes the BKS keystores that Android's built-in provider reads and makes the test certificates.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.78")
}

tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}
