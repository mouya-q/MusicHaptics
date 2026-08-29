plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mouya.musichaptics"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mouya.musichaptics"
        minSdk = 28
        targetSdk = 34
        versionCode = 41304
        versionName = "4.13.4"
        ndkVersion = "27.0.12077973"
        // 云编译（GitHub Actions）环境下由 Gradle 直接驱动 CMake 编译 C++，
        // 使用 c++_static 静态链接 libc++，避免注入宿主进程时与旧版 libc++_shared.so 符号冲突。
        // DSP 引擎使用 ARM NEON SIMD 指令，仅支持 ARM 架构（arm64-v8a / armeabi-v7a）。
        // x86/x86_64 设备在现代 Android 生态中极少，且本项目目标设备（小米 10）为 arm64。
        ndk {
            // Only arm64-v8a for modern devices (Xiaomi 10 is arm64)
            // This reduces APK size by ~50%
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "2.0.21"
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.configureEach {
        if (name == "main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,ASL2.0,NOTICE,LICENSE,LICENSE.txt,LICENSE.md,NOTICE.txt,NOTICE.md}"
        }
    }

    lint {
        abortOnError = false
        disable += listOf(
            "MissingTranslation",
            "ExtraTranslation",
            "GooglePlayPolicyViolation"
        )
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation(files("libs/libxposed-interface-101.0.0.aar"))
    implementation(files("libs/libxposed-service-101.0.0.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.interpolator:interpolator:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("androidx.graphics:graphics-core:1.0.0")
    implementation("dev.chrisbanes.haze:haze:1.5.0")

    configurations.all {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "androidx.core" && requested.name.startsWith("core") ->
                    useVersion("1.13.1")
                requested.group == "androidx.activity" && requested.name.startsWith("activity") ->
                    useVersion("1.9.3")
            }
        }
    }
}