#!/usr/bin/env bash
# 下载构建依赖到 ./sdk（只在本机没有 SDK 时需要）
#   bash tools/setup_sdk.sh
# 可用 SDK=/path/to/sdk 指定目录；HTTPS_PROXY 走代理。
set -euo pipefail
cd "$(dirname "$0")/.."
SDK_DIR="${SDK:-$PWD/sdk}"
mkdir -p "$SDK_DIR"
cd "$SDK_DIR"

case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) BT="build-tools_r35_windows.zip" ;;
  Darwin)                   BT="build-tools_r35_macosx.zip" ;;
  *)                        BT="build-tools_r35_linux.zip" ;;
esac
PLATFORM="platform-35_r02.zip"
LIBXPOSED="https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar"

fetch() {
  local url="$1" out="$2"
  [ -s "$out" ] && { echo "  已存在 $out"; return; }
  echo "  下载 $out"
  curl -fL --retry 3 -o "$out.part" "$url"
  mv "$out.part" "$out"
}

fetch "https://dl.google.com/android/repository/$BT" "$BT"
fetch "https://dl.google.com/android/repository/$PLATFORM" "$PLATFORM"
fetch "$LIBXPOSED" "libxposed-api.aar"

echo "==> 解压 build-tools"
rm -rf bt && mkdir -p bt
unzip -q -o "$BT" -d bt        # 里面是 android-15/，build.sh 就用这个路径

echo "==> 解压 platform"
rm -rf pf && mkdir -p pf
unzip -q -o "$PLATFORM" -d pf  # 里面是 android-35/
[ -f pf/android-35/android.jar ] || { echo "platform 解压异常：找不到 pf/android-35/android.jar"; exit 1; }

echo "==> 解压 libxposed api"
rm -rf libx && mkdir -p libx
unzip -q -o libxposed-api.aar -d libx
[ -f libx/classes.jar ] || { echo "libxposed 解压异常：找不到 classes.jar"; exit 1; }

echo
echo "完成：$SDK_DIR"
echo "  bt/android-15/   build-tools 35"
echo "  pf/android-35/   platform 35"
echo "  libx/classes.jar libxposed api 102"
echo "现在可以执行：bash build.sh"
