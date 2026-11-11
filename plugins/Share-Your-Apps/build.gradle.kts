// Imported, since in a Gradle build file, "java" alone means Gradle's java extension, not the package.
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "share-your-apps"
}

// Release signing: the same key and details as Share Your Apps Server's, from release.properties in Code On The Go's
// private folder, which is never in the project. Without it, a release build still works, but isn't signed.
val keystorePropsFile = File("/data/data/com.itsaky.androidide/files/home/keys/release.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasValidSigningProps = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { keystoreProps[it] != null }

android {
    namespace = "org.appdevforall.shareyourapps"
    compileSdk = 36 

    defaultConfig {
        applicationId = "org.appdevforall.shareyourapps"
        minSdk = 28 
        targetSdk = 36 
        versionCode = 3
        versionName = "1.0.2"
        // When this build was made, for the Share Your Apps tab's footer. Release builds use
        // --no-configuration-cache, so theirs is exact; a cached debug build may show an older time.
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    signingConfigs {
        if (hasValidSigningProps) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            if (hasValidSigningProps) signingConfig = signingConfigs.getByName("release")
            // No R8. With AndroidX compileOnly (below), all that's left to shrink is ZXing, and R8 kept breaking
            // what Code On The Go calls in ways it can't see: a NoSuchMethodError, a NullPointerException and an
            // AbstractMethodError. proguard-rules.pro stays, with what those taught, in case R8 is ever turned back on.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // BuildConfig.VERSION_NAME gives store pages a "Made with Share Your Apps 1.0.0" line.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17 
        targetCompatibility = JavaVersion.VERSION_17 
    }

    packaging {
        // Compress the compiled code in the .cgp. Android's default stores it uncompressed, so apps can run it in
        // place, but that made it 4.3 MB of the plugin's 4.5 MB. Code On The Go's class loader handles either.
        dex { useLegacyPackaging = true }
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

// Every plugin file (.cgp) must carry the server: it's left out of git, so a fresh clone doesn't
// have it, and without it the plugin can't install or update the server. This guards the tasks
// that make the .cgp — assemblePluginDebug, and assemblePlugin for a release — so neither kind can be made
// without it. Compiling and running the tests don't need it.
tasks.matching { it.name.startsWith("assemblePlugin") }.configureEach {
    val server = layout.projectDirectory.file("src/main/assets/share-your-apps-server.apk").asFile
    doFirst {
        check(server.isFile) {
            "src/main/assets/share-your-apps-server.apk is missing. Run ./gradlew downloadAssets, or build Share Your " +
                "Apps Server's release, copy its app/build/outputs/apk/release/app-release.apk there, and build again. " +
                "See the README's Bundling the server."
        }
    }
}

// Builds Share Your Apps Server's release from server/ and bundles it. scripts/build-plugins.sh runs any task named
// downloadAssets before assemblePlugin, as its own invocation, so CI makes the APK this way. In CI the server must be
// signed with its release key, from the SERVER_SIGNING_* variables: an unsigned or differently signed server can't
// update the one already on owners' phones, so the build stops instead.
tasks.register<Exec>("downloadAssets") {
    val serverDir = layout.projectDirectory.dir("server").asFile
    val built = layout.projectDirectory.file("server/app/build/outputs/apk/release/app-release.apk").asFile
    val bundled = layout.projectDirectory.file("src/main/assets/share-your-apps-server.apk").asFile
    val ci = System.getenv("CI") == "true"
    val signed = System.getenv("SERVER_SIGNING_STORE_FILE") != null
    workingDir = serverDir
    commandLine("sh", "gradlew", "--console=plain", ":app:assembleRelease")
    doFirst {
        check(!ci || signed) {
            "SERVER_SIGNING_STORE_FILE isn't set. In CI the server is signed with its release key, from the " +
                "SERVER_SIGNING_* repository secrets. See the design document's The release signing key."
        }
    }
    doLast { built.copyTo(bundled, overwrite = true) }
}

// The Kotlin library comes from Code On The Go at run time. AndroidX brings its own copies (Fragment depends on
// Kotlin 1.8, and older parts come with it), so they're excluded from what the plugin bundles — except for the
// unit tests, which run on a desktop and need their own.
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath") && !name.contains("UnitTest") && !name.contains("AndroidTest")) {
        listOf("kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8", "kotlin-stdlib-common").forEach {
            exclude(group = "org.jetbrains.kotlin", module = it)
        }
    }
}

dependencies {
    compileOnly(files("../../libs/plugin-api.jar"))
    // Pebble renders the catalog page. compileOnly: at runtime the plugin uses CodeOnTheGo's own
    // copy (4.1.1, kept intact by its build), found through the plugin's parent class loader.
    compileOnly("io.pebbletemplates:pebble:4.1.1")

    // QR codes for templates' qr() function (design, Appendix D.2); the same version as the server's.
    implementation("com.google.zxing:core:3.5.3")
    // No AppCompat or Material: the plugin uses neither — it draws its own views, in Code On The Go's screen —
    // and together they made up most of the .cgp's 13.9 MB.
    // Just Fragment, not fragment-ktx: the plugin uses only Fragment and Activity's result contracts.
    // compileOnly: Code On The Go loads classes parent-first, so its own AndroidX (fragment 1.8.9, activity 1.10.1)
    // is what runs, never a bundled copy. It has every class and method the plugin uses: Fragment, ActivityResult,
    // ActivityResultCallback, ActivityResultLauncher, ActivityResultContract and StartActivityForResult. Check
    // any new AndroidX use against Code On The Go's APK first: a missing one fails at run time, not in the build.
    compileOnly("androidx.fragment:fragment:1.8.8")
    // The Kotlin library comes from Code On The Go, as Pebble and the plugin API do, rather than being bundled.
    // gradle.properties sets kotlin.stdlib.default.dependency=false, so the Kotlin build doesn't add it itself.
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")

    // Tests (./gradlew testDebugUnitTest) run on a computer's Java, not on Android. There, Android's
    // org.json is only an empty stub, and Pebble isn't provided by CodeOnTheGo, so tests bring their own.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("io.pebbletemplates:pebble:4.1.1")
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
