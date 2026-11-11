plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(project(":log"))
    // Only the JsonElement API is used, so no serialization compiler plugin is needed.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(testFixtures(project(":log")))
}
