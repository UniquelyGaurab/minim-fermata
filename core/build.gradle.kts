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

// LeanBackupsTest.bench (FERMATA_BENCH=1) holds a 50,000-event history several times over.
tasks.test {
    if (System.getenv("FERMATA_BENCH") != null) maxHeapSize = "3g"
}
