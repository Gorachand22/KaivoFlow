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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KaivoFlow"

// Application shell
include(":app")

// Core layers (Android-free where possible, so they are unit-testable)
include(":core:model")
include(":core:engine")
include(":core:expressions")
include(":core:ai")
include(":core:design")
include(":core:data")
include(":core:network")
include(":core:nodes")
include(":core:work")

// Features
include(":feature:canvas")
include(":feature:flows")
include(":feature:settings")
include(":feature:permissions")
