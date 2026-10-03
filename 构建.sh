#!/bin/bash
# 在 WSL 里编译。工具链和 题库扫描 / 镜译 共用（JDK 17 + Gradle 8.9 + Android SDK 34）。
#
#   wsl.exe -e bash -c 'bash /mnt/d/AndroidProjects/垃圾分类/构建.sh assembleDebug'
#
# Windows 上用 python tools\deploy.py build 更省事，那个会连编译带安装一起做。

export JAVA_HOME="$HOME/toolchain/jdk"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$HOME/.gradle"

cd "$(dirname "$(readlink -f "$0")")" || exit 1

"$HOME/toolchain/gradle-8.9/bin/gradle" --no-daemon --console=plain "${@:-assembleDebug}" 2>&1 \
    | grep -v -E "^Download|^\s*$"

# gradle 的退出码在管道里会丢，这里把它捞回来
exit "${PIPESTATUS[0]}"
