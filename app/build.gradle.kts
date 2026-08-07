plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mouya.musichaptics"
    compileSdk = 34
    
    // NDK 26 was partially installed in the build environment (clang was missing).
    // Use the complete installed NDK so externalNativeBuild is reproducible.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.mouya.musichaptics"
        minSdk = 28
        targetSdk = 34
        versionCode = 440
        versionName = "3.13.0-semantic-alpha"

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
    // Official modern LSPosed Service API binaries. Kept locally because their
    // published AAR metadata requires compileSdk 36, while this module is built
    // against API 34 for the installed AndroidIDE/AAPT2 toolchain.
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
    // AGSL RuntimeShader support for liquid glass effects - use older compatible version
    implementation("androidx.graphics:graphics-core:1.0.0")
    // Haze blur (librepods style)
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