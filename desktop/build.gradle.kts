plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.gson)

    implementation(libs.slf4j.simple)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)

    implementation(libs.kotlin.argparser)

    implementation(project(":core"))
}