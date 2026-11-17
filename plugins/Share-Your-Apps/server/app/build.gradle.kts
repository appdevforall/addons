
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
}

// Release signing. CI sets the four SERVER_SIGNING_* variables from repository secrets (see the
// design document's "The release signing key"). Without them, the values come from release.properties
// in Code On The Go's private folder, for a build on the phone. Neither is ever in the project.
val keystorePropsFile = File("/data/data/com.itsaky.androidide/files/home/keys/release.properties")
val keystoreProps = Properties()

if (System.getenv("SERVER_SIGNING_STORE_FILE") != null) {
    keystoreProps["storeFile"] = System.getenv("SERVER_SIGNING_STORE_FILE")
    System.getenv("SERVER_SIGNING_STORE_PASSWORD")?.let { keystoreProps["storePassword"] = it }
    System.getenv("SERVER_SIGNING_KEY_ALIAS")?.let { keystoreProps["keyAlias"] = it }
    System.getenv("SERVER_SIGNING_KEY_PASSWORD")?.let { keystoreProps["keyPassword"] = it }
} else if (keystorePropsFile.exists()) {
    FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
}

val hasValidSigningProps = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { key ->
    keystoreProps[key] != null
}


android {
    namespace = "org.appdevforall.shareyourapps.server"
    compileSdk = 36 
    
    // disable linter
    lint {
        checkReleaseBuilds = false
    }
        
    signingConfigs {
        if (hasValidSigningProps) {
            create("release") {
                // All four come from release.properties, which is never committed. storeFile may be
                // absolute, such as a key kept in Code On The Go's private folder.
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "org.appdevforall.shareyourapps.server"
        minSdk = 28 
        targetSdk = 36  
        versionCode = 50
        versionName = "1.0.$versionCode"
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        
        vectorDrawables { 
            useSupportLibrary = true
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17 
        targetCompatibility = JavaVersion.VERSION_17 
    }

    buildTypes {
        release {
            if (hasValidSigningProps) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
	buildFeatures {
}
        
    }
    packaging {
        dex { useLegacyPackaging = true }   // compress the code: about 150 KB smaller
        resources {
            resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            resources.excludes.add("META-INF/kotlinx_coroutines_core.version")
            // Kotlin reflection metadata: unused, since the server uses no Kotlin reflection.
            resources.excludes.add("**/*.kotlin_builtins")

            // The part below is only needed for compose builds.
            // This packaging block is required to solve interdependency conflicts.
            // They arise only when using local maven repo, so I suppose online repos have some way of solving such issues.

            // Caused by: com.android.builder.merge.DuplicateRelativeFileException: 4 files found with path 'commonMain/default/linkdata/module' from inputs:
            // - AndroidIDE\libs_source\gradle\localMvnRepository\androidx\collection\collection\1.4.2\collection-1.4.2.jar
            // - AndroidIDE\libs_source\gradle\localMvnRepository\androidx\lifecycle\lifecycle-common\2.8.7\lifecycle-common-2.8.7.jar
            // - AndroidIDE\libs_source\gradle\localMvnRepository\androidx\annotation\annotation\1.8.1\annotation-1.8.1.jar
            // - AndroidIDE\libs_source\gradle\localMvnRepository\org\jetbrains\kotlinx\kotlinx-coroutines-core\1.7.3\kotlinx-coroutines-core-1.7.3.jar
            // And some others.
            resources.pickFirsts.add("nonJvmMain/default/linkdata/package_androidx/0_androidx.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/module")

            resources.pickFirsts.add("nativeMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nativeMain/default/linkdata/module")

            resources.pickFirsts.add("commonMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("commonMain/default/linkdata/module")
            resources.pickFirsts.add("commonMain/default/linkdata/package_androidx/0_androidx.knm")

            resources.pickFirsts.add("META-INF/kotlin-project-structure-metadata.json")

            resources.merges.add("commonMain/default/manifest")
            resources.merges.add("nonJvmMain/default/manifest")
            resources.merges.add("nativeMain/default/manifest")
        }
    }
    
    configurations.all {
        resolutionStrategy {
            // Force the use of Kotlin stdlib 1.9.22 for all modules
            force("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.3.21")
    
            // Force specific AndroidX versions to avoid conflicts
            force("androidx.collection:collection:1.4.2")
            force("androidx.annotation:annotation:1.8.1")
            force("androidx.core:core-ktx:1.8.0")
            force("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")
            force("androidx.collection:collection-ktx:1.4.2")
        }
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
 


dependencies {
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")

    implementation("com.google.zxing:core:3.5.3")
}
