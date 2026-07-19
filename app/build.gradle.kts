repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://api.xposed.info/") }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    
    // id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" 
}

android {
    namespace = "com.mouya.musichaptics"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mouya.musichaptics"
        minSdk = 31
        targetSdk = 34
        
        // ᔦ ° ꒳ ° ᔨ ̖́-  版本号进位 → Liquid Glass Engine v2
        //♡ ( ᗜ ˰ ᗜ )
        versionCode = 96
        versionName = "1.6.6_glass"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = false
        compose = true // 开启 Compose 特性
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.4.7" 
    }
}

dependencies {
    // Xposed 编译时依赖
    compileOnly("de.robv.android.xposed:api:82")

    // AndroidX 核心与 Activity 支持（修复 ComponentActivity 和 setContent）
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose 依赖（使用 BOM 统一管理版本）
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // kotlinx 协程支持（修复 delay 和 CoroutineScope 报错）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.github.Dimezis:BlurView:version-2.0.3")
}
/*
 (ᗜ ˰ ᗜ) ​   ₍ᵔ･•･ᵔ₎      ㅎㅅㅎ      ♪(′ε′‧̣̥̇)      ૮⸝⸝o̴̶̷᷄ ·̭ o̴̶̥᷅⸝⸝ა  
 ᡴ⁽˶ᵔᴗᵔ˶⁾ꪫ
*/