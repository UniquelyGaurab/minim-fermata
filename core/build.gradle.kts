plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// The Fermata facade: ties the log, library, recs and backup modules together.
dependencies {
    api(project(":log"))
    api(project(":library"))
    api(project(":recs"))
    api(project(":backup"))
    testImplementation(testFixtures(project(":log")))
    testImplementation(libs.kotlinx.serialization.json) // to rewrite manifests in tests
}
