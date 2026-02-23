#!/bin/bash
# 編譯 Diode Go Mobile xcframework for iOS
#
# 前提條件：
#   1. go install golang.org/x/mobile/cmd/gomobile@latest
#   2. gomobile init
#   3. 準備好 OpenSSL iOS 庫（見下方說明）
#
# OpenSSL iOS 庫的獲取：
#   方式一（推薦）：使用 CocoaPods
#     cd ios && pod install
#     將 Pods 中 OpenSSL-Universal 的庫路徑填入 OPENSSL_ROOT
#
#   方式二：手動放置預編譯庫
#     mkdir -p openssl-ios/{lib,include}
#     cp libssl.a libcrypto.a   openssl-ios/lib/
#     cp -r headers/*           openssl-ios/include/

set -e
cd "$(dirname "$0")/.."

SCRIPT_DIR="$(pwd)"
DIODE_CLIENT="${SCRIPT_DIR}/diode_client"
MOBILE_DIR="${DIODE_CLIENT}/mobile"
OPENSSL_ROOT="${SCRIPT_DIR}/openssl-ios"
OUTPUT="${SCRIPT_DIR}/ios/Frameworks/DiodeMobile.xcframework"

# --- 檢查 gomobile ---
export PATH="${PATH}:$(go env GOPATH)/bin"
if ! command -v gomobile &> /dev/null; then
    echo "Error: gomobile not found"
    echo "  go install golang.org/x/mobile/cmd/gomobile@latest"
    echo "  gomobile init"
    exit 1
fi

# --- 檢查 OpenSSL ---
if [ ! -f "${OPENSSL_ROOT}/lib/libssl.a" ]; then
    echo "Error: OpenSSL iOS libs not found at ${OPENSSL_ROOT}/lib/"
    echo ""
    echo "方式一 (CocoaPods):"
    echo "  cd ios && pod install"
    echo "  然後將 Pods/OpenSSL-Universal 的路徑填入此腳本的 OPENSSL_ROOT"
    echo ""
    echo "方式二 (手動放置):"
    echo "  mkdir -p ${OPENSSL_ROOT}/lib"
    echo "  cp <your-openssl>/libssl.a    ${OPENSSL_ROOT}/lib/"
    echo "  cp <your-openssl>/libcrypto.a ${OPENSSL_ROOT}/lib/"
    echo "  mkdir -p ${OPENSSL_ROOT}/include"
    echo "  cp -r <your-openssl>/include/* ${OPENSSL_ROOT}/include/"
    exit 1
fi

echo "=== Building Diode Mobile xcframework for iOS ==="
echo "OpenSSL: ${OPENSSL_ROOT}"
echo "Mobile:  ${MOBILE_DIR}"
echo "Output:  ${OUTPUT}"

export CGO_ENABLED=1
export CGO_CFLAGS="-I${OPENSSL_ROOT}/include"
export CGO_LDFLAGS="-L${OPENSSL_ROOT}/lib -lssl -lcrypto"

mkdir -p "$(dirname "$OUTPUT")"
cd "$MOBILE_DIR"
"$GOMOBILE" bind -target=ios -o "$OUTPUT" -v .

echo ""
echo "=== Build complete ==="
ls -la "$OUTPUT"
