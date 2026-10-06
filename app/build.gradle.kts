plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key.
//
// The keystore is never committed: it lives in GitHub as the repository secret
// MHX_KEYSTORE_B64 (base64 of the PKCS12 container) and is restored by the CI
// workflow into mhx-release.jks before Gradle runs. Signing with the throwaway
// debug key instead gave every release a different signature, so Android
// refused to install over the previously installed build ("signatures do not
// match") and forced an uninstall on each upgrade.
//
// MHX_KEYSTORE_FILE, MHX_KEYSTORE_PASS and MHX_KEY_ALIAS are set by that
// workflow step. On a fork or a local build they are absent, the file does not
// exist, and release falls back to the debug config so the build still
// succeeds. Artifacts from such runs are debug-signed.
val releaseKeystoreFile: String? =
    providers.environmentVariable("MHX_KEYSTORE_FILE").orNull
val releaseKeystoreReady: Boolean =
    releaseKeystoreFile != null && file(releaseKeystoreFile!!).exists()

android {
    namespace = "com.mouya.musichaptics"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mouya.musichaptics"
        minSdk = 28
        targetSdk = 35
        versionCode = 50403
        versionName = "5.4.3"
        ndkVersion = "27.0.12077973"




        ndk {
            // Build only for arm64 devices.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            if (releaseKeystoreReady) {
                val storePass =
                    providers.environmentVariable("MHX_KEYSTORE_PASS").orNull
                val alias =
                    providers.environmentVariable("MHX_KEY_ALIAS").orNull
                val keyPass =
                    providers.environmentVariable("MHX_KEY_PASSWORD").orNull
                storeFile = file(releaseKeystoreFile!!)
                if (storePass != null) storePassword = storePass
                if (alias != null) keyAlias = alias
                // Store and key share one password in this keystore; an
                // explicit key password still takes precedence when set.
                if (keyPass != null) keyPassword = keyPass
                else if (storePass != null) keyPassword = storePass
            }
        }
    }

    buildTypes {
        debug {
            // Use the default debug signing config.
        }
        release {
            // Sign with the fixed release key when CI provided one, otherwise
            // fall back to debug so forks and local builds still succeed.
            signingConfig = if (releaseKeystoreReady) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // Shrink the release APK and resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    packaging {
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
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.ui:ui-text")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation(project(":liquidglass"))

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