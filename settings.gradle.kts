pluginManagement {
    val alexandriteVersion = providers.gradleProperty("alexandrite.version").get()
    plugins {
        id("org.unitedsoftware.alexandrite.plugin") version alexandriteVersion
    }
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // Alexandrite's SDK, KSP processor and testkit until they are on Maven Central
        mavenLocal {
            content { includeGroup("org.unitedsoftware.alexandrite") }
        }
    }
}

rootProject.name = "lattice"

include("lattice-core", "lattice-sqlite", "lattice-remote", "lattice-alexandrite")
