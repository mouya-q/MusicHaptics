plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mouya.musichaptics"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    // NDK 26 was partially installed in the build environment (clang was missing).
    // Use the complete installed NDK so externalNativeBuild is reproducible.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.mouya.musichaptics"
        minSdk = 28
        targetSdk = 34
        versionCode = 468
        versionName = "4.3.0"

        // externalNativeBuild {
        //     cmake {
        //         abiFilters += "arm64-v8a"
        //     }
        // }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Native libraries were bundled from src/main/jniLibs, but now we compile them:
    // externalNativeBuild {
    //     cmake {
    //         path("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

    sourceSets.configureEach {
        if (name == "main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packagingOptions {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "/META-INF/{AL2.0,LGPL2.1,ASL2.0,NOTICE,LICENSE,LICENSE.txt,LICENSE.md,NOTICE.txt,NOTICE.md}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs["debug"]
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Legacy XposedBridge API (MainHook.kt entry `IXposedHookLoadPackage`)
    compileOnly("de.robv.android.xposed:api:82")
    // v4.1: Modern libxposed API 102 (Maven Central)
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
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
    // AGSL RuntimeShader support for liquid glass effects - use older compatible version
    implementation("androidx.graphics:graphics-core:1.0.4")
    // Haze blur (librepods style)
    implementation("dev.chrisbanes.haze:haze:1.5.0")

    
}