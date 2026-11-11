plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.minim.fermata.sample.MainKt")
}
