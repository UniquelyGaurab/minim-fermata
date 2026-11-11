plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `java-test-fixtures`
}

// Events, ids, the store interface and merge. No dependencies besides the Kotlin stdlib.
// testFixtures holds a small event builder shared by the other modules' tests.
