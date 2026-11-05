#!/usr/bin/env bash
# 纯命令行构建 APK：aapt2 -> javac -> d8 -> 合并 dex -> zipalign -> apksigner
# 依赖：JDK 17+、Android SDK（platform 与 build-tools 各一份即可）、python3
set -e

PROJ="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJ"

# ---------- 定位 JDK ----------
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JDK="$JAVA_HOME"
elif [ -x "$PROJ/../jdk/bin/javac" ]; then
  JDK="$(cd "$PROJ/../jdk" && pwd)"
elif [ -x "/c/AndroidDev/jdk/bin/javac" ]; then
  JDK="/c/AndroidDev/jdk"
elif command -v javac > /dev/null 2>&1; then
  JDK="$(dirname "$(dirname "$(command -v javac)")")"
else
  echo "找不到 JDK。请安装 JDK 17+ 并设置 JAVA_HOME。" >&2
  exit 1
fi

# ---------- 定位 Android SDK ----------
for cand in "$ANDROID_SDK_ROOT" "$ANDROID_HOME" \
            "$LOCALAPPDATA/Android/Sdk" \
            "$HOME/AppData/Local/Android/Sdk" \
            "$HOME/Library/Android/sdk" \
            "/c/AndroidDev/sdk" \
            "/usr/lib/android-sdk"; do
  if [ -n "$cand" ] && [ -d "$cand/platforms" ]; then SDKDIR="$cand"; break; fi
done
[ -n "$SDKDIR" ] || { echo "找不到 Android SDK。请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT。" >&2; exit 1; }

# 自动选最新的 platform 与 build-tools
PLATFORM="$(ls "$SDKDIR/platforms" | grep -E '^android-[0-9]+$' | sort -t- -k2 -n | tail -1)"
BTVER="$(ls "$SDKDIR/build-tools" 2>/dev/null | sort -V | tail -1)"
[ -n "$PLATFORM" ] || { echo "SDK 里没有 platforms/android-XX，请用 sdkmanager 安装。" >&2; exit 1; }
[ -n "$BTVER" ] || { echo "SDK 里没有 build-tools，请用 sdkmanager 安装。" >&2; exit 1; }

BT="$SDKDIR/build-tools/$BTVER"
JAR="$SDKDIR/platforms/$PLATFORM/android.jar"
JAVA="$JDK/bin/java"
JAVAC="$JDK/bin/javac"
KEYTOOL="$JDK/bin/keytool"

# aapt2 / zipalign 在 Windows 上带 .exe
exe() { if [ -x "$1.exe" ]; then echo "$1.exe"; else echo "$1"; fi; }
AAPT2="$(exe "$BT/aapt2")"
ZIPALIGN="$(exe "$BT/zipalign")"

echo "JDK        : $JDK"
echo "SDK        : $SDKDIR"
echo "platform   : $PLATFORM"
echo "build-tools: $BTVER"

[ -f "$JAR" ] || { echo "缺少 android.jar: $JAR" >&2; exit 1; }

# ---------- 版本号（可用环境变量覆盖） ----------
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"
MIN_SDK="${MIN_SDK:-26}"
TARGET_SDK="${TARGET_SDK:-${PLATFORM#android-}}"

rm -rf build
mkdir -p build/gen build/classes build/dex

echo "[1/6] aapt2 compile"
"$AAPT2" compile --dir res -o build/res.zip

echo "[2/6] aapt2 link"
"$AAPT2" link \
  -o build/unsigned.apk \
  -I "$JAR" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  build/res.zip

echo "[3/6] javac"
find src build/gen -name "*.java" > build/sources.txt
"$JAVAC" -encoding UTF-8 --release 11 -cp "$JAR" -d build/classes @build/sources.txt

echo "[4/6] d8"
find build/classes -name "*.class" > build/classes.txt
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api "$MIN_SDK" --lib "$JAR" --output build/dex @build/classes.txt

echo "[5/6] 合并 dex + zipalign"
python3 tools/add_dex.py build/unsigned.apk build/dex/classes.dex build/merged.apk 2>/dev/null \
  || python tools/add_dex.py build/unsigned.apk build/dex/classes.dex build/merged.apk
"$ZIPALIGN" -f 4 build/merged.apk build/aligned.apk

echo "[6/6] 签名"
if [ ! -f debug.keystore ]; then
  echo "  未找到 debug.keystore，自动生成一个"
  "$KEYTOOL" -genkeypair -v -keystore debug.keystore \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" > /dev/null 2>&1
fi

rm -f build/HotspotAuto.apk
SIGNED=0
for attempt in 1 2 3; do
  if "$JAVA" -jar "$BT/lib/apksigner.jar" sign \
      --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
      --ks-key-alias androiddebugkey \
      --out build/HotspotAuto.apk build/aligned.apk 2>/dev/null; then
    SIGNED=1
    break
  fi
  echo "  签名第 $attempt 次失败（文件锁），重试…"
  sleep 1
done
[ "$SIGNED" = "1" ] || { echo "签名失败" >&2; exit 1; }

"$JAVA" -jar "$BT/lib/apksigner.jar" verify --print-certs build/HotspotAuto.apk | head -4

ls -lh build/HotspotAuto.apk
echo "OK -> $PROJ/build/HotspotAuto.apk"
