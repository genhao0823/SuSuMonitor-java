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
        // Termux terminal-view 经 JitPack 发布（官方 wiki：https://github.com/termux/termux-app/wiki/Termux-Libraries）
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "SuSuMonitor"
include(":app")
