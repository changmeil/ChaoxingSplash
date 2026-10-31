#!/usr/bin/env bash
# 无 Gradle 构建：aapt2 + javac + d8 + zipalign + apksigner
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${SDK:-}" ]; then
  if [ -d ../sdk/bt ]; then SDK=../sdk; else SDK=./sdk; fi
fi
BT="$SDK/bt/android-15"
EXE=""; [ -f "$BT/aapt2.exe" ] && EXE=".exe"
AAPT2="$BT/aapt2$EXE"
ZIPALIGN="$BT/zipalign$EXE"
[ -f "$AAPT2" ] || { echo "找不到 aapt2：先跑 bash tools/setup_sdk.sh，或用 SDK=... 指定"; exit 1; }
PLATFORM="$SDK/pf/android-35/android.jar"
LIBS="$SDK/libx/classes.jar"
LIB_SERVICE="$SDK/libxs/classes.jar"
LIB_INTERFACE="$SDK/libxi/classes.jar"
for j in "$LIBS" "$LIB_SERVICE" "$LIB_INTERFACE"; do
  [ -f "$j" ] || { echo "缺依赖 $j：先跑 bash tools/setup_sdk.sh"; exit 1; }
done
OUT=build
NAME="学习通开屏替换-1.9.2"
VERSION="1.9.2"

command -v javac >/dev/null || { echo "need JDK"; exit 1; }

rm -rf "$OUT" dist
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/stub" dist

echo "==> 1/7 aapt2 compile"
"$AAPT2" compile --dir app/src/main/res -o "$OUT/res.zip"

echo "==> 2/7 aapt2 link"
"$AAPT2" link \
  -o "$OUT/app-unsigned.apk" \
  -I "$PLATFORM" \
  --manifest app/src/main/AndroidManifest.xml \
  -A app/src/main/assets \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "==> 3/7 编译编译期占位注解"
javac --release 17 -nowarn -d "$OUT/stub" $(find stub -name '*.java')

echo "==> 4/7 javac"
javac --release 17 -nowarn -encoding UTF-8 \
  -classpath "$PLATFORM;$LIBS;$LIB_SERVICE;$LIB_INTERFACE;$OUT/stub" \
  -d "$OUT/classes" \
  $(find app/src/main/java "$OUT/gen" -name '*.java')

echo "==> 5/7 d8 -> classes.dex"
# 把 libxposed-service / -interface 一起 dex 进来（手工构建没有 AGP 帮忙合并依赖）
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 26 \
  --lib "$PLATFORM" \
  --classpath "$LIBS" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') \
  "$LIB_SERVICE" "$LIB_INTERFACE"

echo "==> 6/7 打包 dex 与 META-INF/xposed"
python tools/package.py "$OUT/app-unsigned.apk" "$OUT/dex/classes.dex" \
  app/src/main/resources/META-INF/xposed "$OUT/app-raw.apk"

"$ZIPALIGN" -p -f 4 "$OUT/app-raw.apk" "$OUT/app-aligned.apk"

echo "==> 7/7 签名"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
java -jar "$BT/lib/apksigner.jar" sign \
  --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "dist/$NAME.apk" "$OUT/app-aligned.apk"

java -jar "$BT/lib/apksigner.jar" verify --print-certs "dist/$NAME.apk" | head -5

# adb install 的 incremental 模式不接受非 ASCII 文件名（会报 Invalid name），
# 额外留一份 ASCII 名副本方便命令行安装。
cp "dist/$NAME.apk" "dist/ChaoxingSplash-$VERSION.apk"
ls -la "dist/"
