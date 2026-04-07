plugins {
    alias(libs.plugins.kotlin.jvm)

    // TODO: we should probably get off of BuildConfig
    // https://www.reddit.com/r/androiddev/comments/1qi110y/agp_90_is_out_and_its_a_disaster_heres_full/
    id("com.github.gmazzo.buildconfig") version "6.0.9"
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kermit)

    testImplementation(libs.junit)
}

// TODO: remove BuildConfig which is removed in Gradle 9
buildConfig {
    packageName("com.emulnk")
    buildConfigField("DEBUG", true)
}