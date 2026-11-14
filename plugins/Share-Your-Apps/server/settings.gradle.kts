pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

// AGP 9 compiles Kotlin itself and refuses the `org.jetbrains.kotlin.android` plugin.
// It takes the compiler from the kotlin-gradle-plugin jar on this classpath, so this
// is where the Kotlin version is pinned, beside AGP: the two must share a class loader.
// Code On The Go injects the on-device Maven repo here so it resolves offline.
buildscript {
  repositories {
    google()
    mavenCentral()
  }
  dependencies {
    classpath("com.android.tools.build:gradle:9.3.1")
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "Share Your Apps Server"

include(":app")
