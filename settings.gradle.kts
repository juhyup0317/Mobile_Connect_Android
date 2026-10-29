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
        google()
        mavenCentral()
        // Termux's terminal emulator (the Android counterpart of SwiftTerm) is only published on JitPack.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.termux.termux-app") }
        }
    }
}

rootProject.name = "mobile_connect_android"
include(":app")
