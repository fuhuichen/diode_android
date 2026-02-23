#!/bin/bash
# 快速建置與安裝 Android APK（不重新編譯 Go Mobile AAR）
# 使用方式: ./scripts/build_app.sh

set -e

cd "$(dirname "$0")/.."
PROJECT_ROOT="$(pwd)"

echo "=========================================="
echo "Diode Android - 快速建置與安裝"
echo "=========================================="
echo ""

# 檢查 AAR 是否存在
if [ ! -f "${PROJECT_ROOT}/app/libs/diode_mobile.aar" ]; then
    echo "⚠️  警告: 找不到 diode_mobile.aar"
    echo "   請先執行 ./scripts/build_mobile.sh 編譯 Go Mobile AAR"
    echo "   或執行 ./scripts/build_and_install.sh 完整建置"
    exit 1
fi

# 建置 APK
echo "🔨 建置 Android APK..."
echo "------------------------------------------"

chmod +x "${PROJECT_ROOT}/gradlew"

echo "清理舊的建置..."
"${PROJECT_ROOT}/gradlew" clean

echo "建置 Debug APK..."
"${PROJECT_ROOT}/gradlew" assembleDebug

APK_PATH="${PROJECT_ROOT}/app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo "❌ 錯誤: APK 建置失敗"
    exit 1
fi

echo "✅ APK 建置完成: $APK_PATH"
echo ""

# 安裝到裝置
echo "📱 安裝到裝置..."
echo "------------------------------------------"

if ! command -v adb &> /dev/null; then
    echo "❌ 找不到 adb，請手動安裝 APK"
    echo "   APK 位置: $APK_PATH"
    exit 1
fi

DEVICES=$(adb devices | grep -v "List" | grep "device$" | wc -l)

if [ "$DEVICES" -eq 0 ]; then
    echo "❌ 沒有偵測到已連接的裝置"
    echo "   APK 已建置，位置: $APK_PATH"
    echo "   請連接裝置後手動安裝或執行: adb install $APK_PATH"
    exit 1
fi

echo "解除安裝舊版本..."
adb uninstall com.diode.android 2>/dev/null || true

echo "安裝新版本..."
adb install "$APK_PATH"

echo ""
echo "✅ 完成！"
echo ""
echo "啟動應用程式: adb shell am start -n com.diode.android/.MainActivity"
