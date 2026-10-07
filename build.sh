#!/bin/bash
# MoeMemos（Markdown 文件夹版）本地构建脚本
# 说明：本机 services.gradle.org 不可达，因此 Gradle 走腾讯云镜像手动下载，
#       不使用项目自带的 gradlew wrapper。
set -euo pipefail

ROOT="/Users/wj/WorkBuddy/2026-10-06-17-45-27"
TOOLCHAIN="$ROOT/toolchain"
PROJECT="$ROOT/moememos-build"

export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$HOME/.gradle"
export PATH="$JAVA_HOME/bin:$TOOLCHAIN/gradle-9.7.1/bin:$PATH"

echo "JAVA_HOME   = $JAVA_HOME"
echo "ANDROID_HOME= $ANDROID_HOME"
echo "GRADLE      = $(command -v gradle)"
java -version 2>&1 | head -2

cd "$PROJECT"
exec gradle "$@"
