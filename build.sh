#!/system/bin/sh
# Build script for MusicHapticsX - runs in Shizuku/Android environment
export ANDROID_HOME=/data/data/com.itsaky.androidide/files/home/android-sdk
export ANDROID_SDK_ROOT=/data/data/com.itsaky.androidide/files/home/android-sdk
export JAVA_HOME=/data/data/com.itsaky.androidide/files/usr/opt/openjdk-17.0
export PATH=$JAVA_HOME/bin:$PATH
cd /storage/emulated/0/AndroidIDEProjects/MusicHapticsX
java -Dorg.gradle.appname=gradlew -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleDebug --no-daemon 2>&1
echo "BUILD_EXIT_CODE=$?"