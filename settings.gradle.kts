pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Explicit Central endpoint: also avoids the alternate host's certificate issue on this machine.
        maven("https://repo1.maven.org/maven2") {
            content { includeGroup("com.alphacephei"); includeGroup("net.java.dev.jna") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Install Lock"
include(":app")
include(":secretvault")

