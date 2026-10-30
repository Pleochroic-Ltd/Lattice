plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Lattice's local store: one SQLite file per graph"

dependencies {
    api(project(":lattice-core"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.api)

    testImplementation(testFixtures(project(":lattice-core")))
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    // ./gradlew :lattice-sqlite:test -PupdateGolden rewrites src/test/resources/golden/schema.sql
    if (project.hasProperty("updateGolden")) systemProperty("lattice.updateGolden", "true")
}
