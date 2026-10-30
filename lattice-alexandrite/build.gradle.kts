plugins {
    alias(libs.plugins.kotlin.serialization)
    id("org.unitedsoftware.alexandrite.plugin")
}

description = "Lattice as an Alexandrite plugin: recall into each turn, capture after it, sleep at night"

alexandrite {
    pluginId = "lattice"
}

dependencies {
    implementation(project(":lattice-core"))
    implementation(project(":lattice-sqlite"))
    implementation(project(":lattice-remote"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}
