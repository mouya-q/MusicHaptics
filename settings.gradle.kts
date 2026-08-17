// GitHub Actions (CI) uses official repos; local builds use CN mirrors.


pluginManagement {
    repositories {
        if (System.getenv("GITHUB_ACTIONS")?.equals("true", ignoreCase = true) == true) {
            maven {
                url = uri("local-maven")
            }
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven {
                url = uri("https://maven.aliyun.com/repository/google/")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/gradle-plugin/")
            }
            maven {
                url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
            }
            maven {
                url = uri("https://dl.google.com/android/maven2/")
            }
            mavenCentral()
            gradlePluginPortal()
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("GITHUB_ACTIONS")?.equals("true", ignoreCase = true) == true) {
            maven {
                url = uri("local-maven")
            }
            google()
            mavenCentral()
        } else {
            maven {
                url = uri("local-maven")
            }
            maven {
                url = uri("https://maven.aliyun.com/repository/google/")
            }
            maven {
                url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
            }
            maven {
                url = uri("https://dl.google.com/android/maven2/")
            }
            mavenCentral()
        }
    }
}
rootProject.name = "MusicHapticsX"
include(":app")
