// AGP and the Kotlin compiler are on the buildscript classpath in settings.gradle.kts.

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
