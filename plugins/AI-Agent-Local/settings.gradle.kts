@file:Suppress("UnstableApiUsage")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(files("../../libs/plugin-api.jar"))
        classpath(files("../../libs/gradle-plugin.jar"))
        classpath("com.android.tools.build:gradle:8.11.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ai-agent-local"

// The llama.cpp native modules are only needed to regenerate the prebuilt
// AAR under libs/ (see scripts/rebuild-llama-aar.sh). A normal plugin build
// consumes the committed AAR and needs neither the submodule nor the NDK, so
// they are included only when the submodule is checked out.
if (file("subprojects/llama.cpp/CMakeLists.txt").exists()) {
    include(":llama-api")
    include(":llama-impl")
}
