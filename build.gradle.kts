import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
}

// JitPack coordinates: com.github.UniquelyGaurab.minim-fermata:<module>:<tag>.
// includeBuild substitutes these coordinates with the local projects automatically.
allprojects {
    group = "com.github.UniquelyGaurab.minim-fermata"
    version = "0.1.0-SNAPSHOT"
}

val jvmModules = listOf("log", "library", "recs", "backup", "core", "sample")

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed")
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
        dependencies {
            "testImplementation"(libs.kotlin.test.junit5)
            "testRuntimeOnly"(libs.junit.jupiter.engine)
            "testRuntimeOnly"(libs.junit.platform.launcher)
        }

        if (name != "sample") {
            apply(plugin = "maven-publish")
            extensions.configure<JavaPluginExtension> { withSourcesJar() }
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("maven") { from(components["java"]) }
                }
            }
        }
    }
}

/** Runs the tests of every pure JVM module (no Android SDK needed). */
tasks.register("jvmTest") {
    group = "verification"
    description = "Runs the unit tests of the pure Kotlin/JVM modules."
    dependsOn(jvmModules.map { ":$it:test" })
}
