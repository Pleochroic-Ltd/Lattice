plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

description = "Lattice: a graph long-term memory engine (encode, recall, sleep), independent of any agent framework"

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    // The store contract every LatticeStore runs (testFixtures(project(":lattice-core")))
    testFixturesApi(libs.kotlin.test)
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)

    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}
