# MusicHapticsX — release (R8) rules

# ── Xposed module entry point (referenced by name from assets/xposed_init) ──
-keep class com.mouya.musichaptics.MainHook { *; }

# ── JNI bridge: native methods are looked up by name from C++ ──
-keepclasseswithmembernames class com.mouya.musichaptics.NativeBridge {
    native <methods>;
}
# Callbacks invoked from native code via cached jmethodIDs
-keepclassmembers class com.mouya.musichaptics.NativeBridge {
    void onBeatTrigger(java.lang.String, int);
    void onRootPipeTrigger(int, int);
}

# ── Xposed / LSPosed APIs (compileOnly + bundled AARs) ──
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**
-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**

# ── Classes referenced by name from the AndroidManifest ──
# (activities/services/providers are kept automatically; keep the Application
#  subclass explicitly since the manifest reference is the only one)
-keep class com.mouya.musichaptics.MusicHapticsApplication { *; }
-keep class com.mouya.musichaptics.ConfigProvider { *; }
-keep class com.mouya.musichaptics.VibrateProxyService { *; }

# ── LSPosed service callbacks are invoked reflectively by the framework ──
-keep class io.github.libxposed.service.** { *; }
-keepclassmembers class * implements io.github.libxposed.service.XposedServiceHelper$OnServiceListener {
    *;
}

# ── Kotlin metadata / intrinsics ──
-keepclassmembers class kotlin.** { *; }
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.intellij.lang.annotations.**
# The vendored com.kyant.backdrop sources reference AGSL shader strings only
# through plain String parameters; nothing reflective to keep.
-dontwarn androidx.compose.**

# Keep line numbers for crash triage in the field
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
