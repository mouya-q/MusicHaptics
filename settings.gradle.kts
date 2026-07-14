pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // 关键修改：改成 PREFER_PROJECT，允许我们在 app 自身的 build.gradle 里直接塞仓库地址
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MusicHapticsX"
include(":app")
