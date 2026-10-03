#!/bin/bash
# Build inside WSL (JDK 17 + Gradle 8.9 + Android SDK 34).
#
#   wsl.exe -e bash -c 'bash /mnt/d/AndroidProjects/垃圾分类/构建.sh assembleDebug'
#
# On Windows, python tools\deploy.py build compiles and can install.

export JAVA_HOME="$HOME/toolchain/jdk"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$HOME/.gradle"

cd "$(dirname "$(readlink -f "$0")")" || exit 1

"$HOME/toolchain/gradle-8.9/bin/gradle" --no-daemon --console=plain "${@:-assembleDebug}" 2>&1 \
    | grep -v -E "^Download|^\s*$"

# The pipe would hide gradle's exit code. Keep it.
exit "${PIPESTATUS[0]}"
