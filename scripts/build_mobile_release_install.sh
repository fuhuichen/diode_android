#!/bin/bash
# 完整流程：編譯 Go Mobile AAR → 建置 Android Release APK → 安裝到裝置
# 使用方式: ./scripts/build_mobile_release_install.sh

set -e

cd "$(dirname "$0")/.."
PROJECT_ROOT="$(pwd)"

echo "=========================================="
echo "DiodeProxy - Mobile + Release APK + Install"
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
# 步驟 2: 複製 OpenSSL .so 到 assets
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
# 步驟 3: 建置 Android Release APK
# ============================================
echo "🔨 [3/4] 建置 Android Release APK..."
echo "------------------------------------------"

if [ ! -f "${PROJECT_ROOT}/gradlew" ]; then
    echo "❌ 錯誤: 找不到 gradlew"
    exit 1
fi

chmod +x "${PROJECT_ROOT}/gradlew"

# 若無 keystore 會建出未簽名 APK；有 keystore.properties 則會簽名
echo "執行 assembleRelease..."
"${PROJECT_ROOT}/gradlew" assembleRelease

APK_PATH="${PROJECT_ROOT}/app/build/outputs/apk/release/app-release.apk"

if [ ! -f "$APK_PATH" ]; then
    echo "❌ 錯誤: Release APK 建置失敗"
    exit 1
fi

echo "✅ Release APK 建置完成"
echo "   位置: $APK_PATH"
echo ""

# ============================================
# 步驟 4: 安裝到裝置
# ============================================
echo "📱 [4/4] 安裝到 Android 裝置..."
echo "------------------------------------------"

if ! command -v adb &> /dev/null; then
    echo "❌ 錯誤: 找不到 adb"
    echo "   請確認 Android SDK Platform-Tools 已安裝並加入 PATH"
    exit 1
fi

DEVICES=$(adb devices | grep -v "List" | grep "device$" | wc -l)
if [ "$DEVICES" -eq 0 ]; then
    echo "❌ 錯誤: 沒有偵測到已連接的 Android 裝置"
    echo ""
    echo "請確認："
    echo "  1. 裝置已透過 USB 連接"
    echo "  2. 已啟用 USB 偵錯"
    echo "  3. 已授權此電腦偵錯"
    echo ""
    echo "執行 'adb devices' 檢查"
    exit 1
fi

echo "已偵測到 $DEVICES 個裝置"
adb devices

echo ""
echo "安裝 Release APK（覆蓋舊版）..."
adb install -r "$APK_PATH"

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "✅ 建置與安裝完成！"
    echo "=========================================="
    echo ""
    echo "📱 應用程式已安裝"
    echo "   套件: com.diode.android"
    echo ""
    echo "🚀 啟動："
    echo "   adb shell am start -n com.diode.android/.LoadingActivity"
    echo ""
    echo "📋 日誌："
    echo "   adb logcat -s Diode:* GoLog:*"
    echo ""
else
    echo ""
    echo "❌ 安裝失敗"
    exit 1
fi
