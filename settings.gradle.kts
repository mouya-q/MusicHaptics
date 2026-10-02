pluginManagement {
    repositories {
        maven {
            url = uri("https://dl.google.com/android/maven2/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // In-repo Maven repository that carries the Xposed API stub
        // (de.robv.android.xposed:api:82). The upstream repo api.xposed.info is
        // frequently unreachable from CI runners, so the artifact is vendored.
        maven {
            url = uri("local-maven")
        }
        maven {
            url = uri("https://dl.google.com/android/maven2/")
        }
        mavenCentral()
    }
}
rootProject.name = "MusicHapticsX"
include(":app", ":liquidglass")