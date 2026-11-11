plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// Depends on the log only, so an app can use the recommendation side without Minim's library.
dependencies {
    api(project(":log"))
    testImplementation(testFixtures(project(":log")))
}
