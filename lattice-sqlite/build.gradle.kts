plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Lattice's local store: one SQLite file per graph"

dependencies {
    api(project(":lattice-core"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}
