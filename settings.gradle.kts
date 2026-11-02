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
        // NewPipeExtractor (personal build only) is published there and nowhere else.
        maven("https://jitpack.io") { content { includeGroupByRegex("(?i)com\\.github\\.teamnewpipe.*") } }
    }
}

rootProject.name = "FocusLock"
include(":app")
 