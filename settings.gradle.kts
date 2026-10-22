// DSH PostAPI —— 根工程设置
//
// 为什么用 pluginManagement + dependencyResolutionManagement 的 repositoriesMode.FAIL_ON_PROJECT_REPOS：
//   依赖仓库只在这一处声明。子模块若自行加 repositories，Gradle 会直接报错，
//   避免"某个模块偷偷从别的仓库拉到同名依赖"这种极难排查的漂移。
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

rootProject.name = "DSH PostAPI"
include(":app")
