pluginManagement {
    // Set only when the optional Alexandrite plugin module is built (see gradle.properties)
    val alexandriteVersion = providers.gradleProperty("alexandrite.version").orNull
    if (alexandriteVersion != null) {
        plugins {
            id("org.unitedsoftware.alexandrite.plugin") version alexandriteVersion
        }
    }
    repositories {
        // Alexandrite's Gradle plugin, until it is on the Gradle Plugin Portal
        if (alexandriteVersion != null) mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val alexandriteVersion: String? = providers.gradleProperty("alexandrite.version").orNull

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        if (alexandriteVersion != null) {
            // Alexandrite's SDK, KSP processor and testkit until they are on Maven Central
            mavenLocal {
                content { includeGroup("org.unitedsoftware.alexandrite") }
            }
        }
    }
}

rootProject.name = "lattice"

include("lattice-core", "lattice-sqlite", "lattice-remote")

// The Alexandrite plugin module needs Alexandrite's artifacts, which are not published yet: it is built only with
// -Palexandrite.version=<version> (or alexandrite.version in ~/.gradle/gradle.properties).
if (alexandriteVersion != null) include("lattice-alexandrite")
