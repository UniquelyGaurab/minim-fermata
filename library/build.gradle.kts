plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(project(":log"))
    testImplementation(testFixtures(project(":log")))
}
