# Tratto build environment: source this file before running ./gradlew
#   source env.sh && ./gradlew assembleDebug
export JAVA_HOME="$HOME/opt/jdk-21"
export ANDROID_HOME="$HOME/opt/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH:$ANDROID_HOME/platform-tools"
