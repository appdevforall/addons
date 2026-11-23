import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "ai-chat"
}

android {
    namespace = "org.appdevforall.aichat"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.appdevforall.aichat"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
}

// The chat UI is a Vite + React app in web/ (adapted from Jan's web UI; see web/NOTICE). It is
// built into src/main/assets/web/ before every Android build, so a published .cgp always carries
// the bundle that matches its source. The bundle is a build artifact: gitignored, never committed.
// Needs Node 20+ and npm on the PATH (the GitHub runners have both).
val webDir = layout.projectDirectory.dir("web")

val npmCi by tasks.registering(Exec::class) {
    workingDir = webDir.asFile
    inputs.file(webDir.file("package-lock.json"))
    outputs.dir(webDir.dir("node_modules"))
    commandLine("npm", "ci", "--no-audit", "--no-fund")
}

val buildWeb by tasks.registering(Exec::class) {
    dependsOn(npmCi)
    workingDir = webDir.asFile
    inputs.dir(webDir.dir("src"))
    inputs.files(webDir.file("index.html"), webDir.file("package.json"), webDir.file("vite.config.ts"), webDir.file("tsconfig.json"))
    outputs.dir(layout.projectDirectory.dir("src/main/assets/web"))
    commandLine("npm", "run", "build")
}

tasks.named("preBuild") { dependsOn(buildWeb) }

tasks.matching {
    it.name.contains("checkDebugAarMetadata") || it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}
