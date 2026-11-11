pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "minim-fermata"

// Pure Kotlin/JVM modules: no Android imports, run anywhere a JVM runs.
include(":log", ":library", ":recs", ":backup", ":core", ":sample")

// The Android module needs an Android SDK. Pass -Pfermata.skipAndroid=true to leave it out
// (for example on a machine that only runs the JVM tests).
if (providers.gradleProperty("fermata.skipAndroid").orNull != "true") {
    include(":android")
}
