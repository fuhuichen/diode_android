#!/bin/bash
# 完整建置與安裝流程：編譯 Go Mobile AAR -> 建置 Android APK -> 安裝到裝置
# 使用方式: ./scripts/build_and_install.sh

set -e  # 遇到錯誤立即停止

cd "$(dirname "$0")/.."
PROJECT_ROOT="$(pwd)"

echo "=========================================="
echo "Diode Android - 完整建置與安裝流程"
echo "=========================================="
echo ""

# ============================================
# 步驟 1: 編譯 Go Mobile AAR
# ============================================
echo "📦 [1/4] 編譯 Go Mobile AAR..."
echo "------------------------------------------"

if [ ! -f "${PROJECT_ROOT}/scripts/build_mobile.sh" ]; then
    echo "❌ 錯誤: 找不到 build_mobile.sh"
    exit 1
fi

"${PROJECT_ROOT}/scripts/build_mobile.sh"

if [ ! -f "${PROJECT_ROOT}/app/libs/diode_mobile.aar" ]; then
    echo "❌ 錯誤: AAR 編譯失敗"
    exit 1
fi

echo "✅ Go Mobile AAR 編譯完成"
echo ""

# ============================================
# 步驟 2: 複製 OpenSSL .so 到 assets（Gradle 不打包 versioned .so，需從 assets 解壓載入）
# ============================================
echo "📋 [2/4] 複製 OpenSSL 共享庫到 assets..."
echo "------------------------------------------"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    SRC="${PROJECT_ROOT}/openssl-1.1.1k-clang/${abi}/lib"
    DST="${PROJECT_ROOT}/app/src/main/assets/openssl/${abi}"
    if [ -d "$SRC" ]; then
        mkdir -p "$DST"
        cp -f "$SRC"/libssl.so.1.1 "$SRC"/libcrypto.so.1.1 "$DST/" 2>/dev/null || true
        echo "  已複製 ${abi} 的 libssl.so.1.1, libcrypto.so.1.1"
    fi
done
echo "✅ OpenSSL assets 已就緒"
echo ""

# ============================================
# 步驟 3: 建置 Android APK
# ============================================
echo "🔨 [3/4] 建置 Android APK..."
echo "------------------------------------------"

# 檢查是否有 gradlew
if [ ! -f "${PROJECT_ROOT}/gradlew" ]; then
    echo "❌ 錯誤: 找不到 gradlew，請確認專案結構"
    exit 1
fi

# 賦予執行權限
chmod +x "${PROJECT_ROOT}/gradlew"

# 清理舊的建置
echo "清理舊的建置..."
"${PROJECT_ROOT}/gradlew" clean

# 建置 Debug APK
echo "建置 Debug APK..."
"${PROJECT_ROOT}/gradlew" assembleDebug

APK_PATH="${PROJECT_ROOT}/app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK_PATH" ]; then
    echo "❌ 錯誤: APK 建置失敗"
    exit 1
fi

echo "✅ Android APK 建置完成"
echo "   位置: $APK_PATH"
echo ""

# ============================================
# 步驟 4: 安裝到裝置
# ============================================
echo "📱 [4/4] 安裝到 Android 裝置..."
echo "------------------------------------------"

# 檢查 adb 是否可用
if ! command -v adb &> /dev/null; then
    echo "❌ 錯誤: 找不到 adb 指令"
    echo "   請確認 Android SDK Platform-Tools 已安裝並加入 PATH"
    exit 1
fi

# 檢查裝置連接
echo "檢查已連接的裝置..."
DEVICES=$(adb devices | grep -v "List" | grep "device$" | wc -l)

if [ "$DEVICES" -eq 0 ]; then
    echo "❌ 錯誤: 沒有偵測到已連接的 Android 裝置"
    echo ""
    echo "請確認："
    echo "  1. 裝置已透過 USB 連接"
    echo "  2. 已啟用 USB 偵錯模式"
    echo "  3. 已授權此電腦進行偵錯"
    echo ""
    echo "執行 'adb devices' 檢查裝置狀態"
    exit 1
fi

echo "已偵測到 $DEVICES 個裝置"
adb devices

# 解除安裝舊版本（如果存在）
echo ""
echo "解除安裝舊版本..."
adb uninstall com.diode.android 2>/dev/null || echo "（沒有舊版本）"

# 安裝新版本
echo ""
echo "安裝 APK 到裝置..."
adb install "$APK_PATH"

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "✅ 建置與安裝完成！"
    echo "=========================================="
    echo ""
    echo "📱 應用程式已安裝到裝置"
    echo "   套件名稱: com.diode.android"
    echo ""
    echo "🚀 啟動應用程式："
    echo "   adb shell am start -n com.diode.android/.MainActivity"
    echo ""
    echo "📋 查看日誌："
    echo "   adb logcat -s DiodeForegroundService:* MainActivity:*"
    echo ""
else
    echo ""
    echo "❌ 安裝失敗"
    exit 1
fi
