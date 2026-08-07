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
include(":app")
