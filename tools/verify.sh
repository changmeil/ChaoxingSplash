#!/usr/bin/env bash
# 一键验证学习通开屏替换模块是否生效：
#   冷启动学习通 → 连续截屏 → 过滤 CxSplash 日志
# 用法：bash tools/verify.sh            （默认 adb 在 C:/adb/adb.exe）
#       ADB=/path/to/adb bash tools/verify.sh
set -uo pipefail
cd "$(dirname "$0")/.."

ADB="${ADB:-/c/adb/adb.exe}"
PKG="com.chaoxing.mobile"
ACT="$PKG/.activity.SplashActivity"
OUT="shots"
SHOTS=8
INTERVAL=0.5

[ -x "$ADB" ] || { echo "找不到 adb：$ADB（用 ADB=... 指定）"; exit 1; }

echo "==> 设备"
"$ADB" devices | sed -n '2p'
"$ADB" shell "echo ok" >/dev/null 2>&1 || { echo "设备未就绪（未授权或未连接）"; exit 1; }

echo "==> 清日志"
"$ADB" logcat -c 2>/dev/null

echo "==> 冷启动学习通"
"$ADB" shell am force-stop "$PKG"
"$ADB" shell "rm -f /sdcard/cxsp*.png"
"$ADB" shell am start -n "$ACT" >/dev/null 2>&1

echo "==> 连续截屏（${SHOTS} 张，间隔 ${INTERVAL}s）"
mkdir -p "$OUT"
i=1
while [ "$i" -le "$SHOTS" ]; do
  "$ADB" shell screencap -p "/sdcard/cxsp$i.png"
  sleep "$INTERVAL"
  i=$((i + 1))
done
i=1
while [ "$i" -le "$SHOTS" ]; do
  "$ADB" pull "/sdcard/cxsp$i.png" "$OUT/verify$i.png" >/dev/null 2>&1
  i=$((i + 1))
done
ls -1 "$OUT"/verify*.png 2>/dev/null

echo "==> 模块日志（标签 CxSplash）"
LOG=$("$ADB" logcat -d 2>/dev/null | grep -E "CxSplash" | tail -30)
if [ -z "$LOG" ]; then
  echo "（无）模块没有被加载 —— 检查 LSPosed 里是否已启用模块、作用域是否勾了学习通"
else
  echo "$LOG"
fi

echo "==> LSPosed 注入记录"
"$ADB" logcat -d 2>/dev/null \
  | grep -oE "\($PKG\)\[[^]]+\]" \
  | sed -E "s/\($PKG\)\[//; s/,[0-9a-f-]+,[0-9]+,[0-9]+\]$//" \
  | sort -u | head -10

echo "==> 完成，截图在 $OUT/verify*.png"
