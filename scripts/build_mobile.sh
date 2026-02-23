#!/bin/bash
# 編譯 Diode Go Mobile AAR for Android
# 需先安裝: go install golang.org/x/mobile/cmd/gomobile@latest
# 並執行: gomobile init
#
# 使用專案內預編譯的 openssl-1.1.1k-clang

set -e
cd "$(dirname "$0")/.."

SCRIPT_DIR="$(pwd)"
OPENSSL_ROOT="${SCRIPT_DIR}/openssl-1.1.1k-clang"
OPENSSL_SRC="${SCRIPT_DIR}/openssl"
DIODE_CLIENT="${SCRIPT_DIR}/diode_client"
MOBILE_DIR="${DIODE_CLIENT}/mobile"
OUTPUT_AAR="${SCRIPT_DIR}/app/libs/diode_mobile.aar"

if [ ! -d "$OPENSSL_ROOT" ]; then
    echo "Error: OpenSSL libs not found at $OPENSSL_ROOT"
    exit 1
fi
if [ ! -d "$OPENSSL_SRC" ] || [ ! -f "$OPENSSL_SRC/ssl/ssl_local.h" ]; then
    echo "Error: OpenSSL source (for ssl_local.h) not found at $OPENSSL_SRC"
    exit 1
fi

# 確保 gomobile 在 PATH（go install 會裝到 GOPATH/bin）
export PATH="${PATH}:$(go env GOPATH)/bin"
GOMOBILE="$(command -v gomobile 2>/dev/null || true)"
if [ -z "$GOMOBILE" ]; then
    echo "Error: gomobile not found. Run: go install golang.org/x/mobile/cmd/gomobile@latest"
    echo "Then ensure \$(go env GOPATH)/bin is in your PATH"
    exit 1
fi

echo "=== Building Diode Mobile AAR ==="
echo "OpenSSL libs: ${OPENSSL_ROOT}"
echo "OpenSSL src:  ${OPENSSL_SRC}"
echo "Mobile package: ${MOBILE_DIR}"
echo "Output: ${OUTPUT_AAR}"

# CGO 設定：openssl-1.1.1k-clang 提供 lib，openssl/ 提供 ssl_local.h 等內部標頭
export CGO_ENABLED=1
export CGO_CFLAGS="-I${OPENSSL_ROOT}/include -I${OPENSSL_SRC}"

# gomobile 會依序為各 ABI 編譯，需根據 GOARCH 選對應的 lib 路徑
# 建立 go wrapper 以在每次編譯時動態設定 LDFLAGS
REAL_GO=$(command -v go)

# 建立 go wrapper（依 GOARCH 對應 Android ABI 目錄）
# gomobile 會為 arm64/arm/386/amd64 各編譯一次，需依 arch 選正確的 lib
mkdir -p "${SCRIPT_DIR}/scripts"
GO_WRAPPER="${SCRIPT_DIR}/scripts/go-wrapper.sh"
cat > "$GO_WRAPPER" << WRAPPER_EOF
#!/bin/bash
case "\${GOARCH}" in
    arm64)  ARCH_LIB="arm64-v8a" ;;
    arm)    ARCH_LIB="armeabi-v7a" ;;
    386)    ARCH_LIB="x86" ;;
    amd64)  ARCH_LIB="x86_64" ;;
    *)      ARCH_LIB="" ;;
esac
if [ -n "\${ARCH_LIB}" ] && [ -d "${OPENSSL_ROOT}/\${ARCH_LIB}/lib" ]; then
    export CGO_LDFLAGS="-L${OPENSSL_ROOT}/\${ARCH_LIB}/lib -lssl -lcrypto"
fi
exec "${REAL_GO}" "\$@"
WRAPPER_EOF
chmod +x "$GO_WRAPPER"

# 建立 go 軟連結（gomobile 會呼叫 "go"）
( cd "${SCRIPT_DIR}/scripts" && ln -sf go-wrapper.sh go )
export PATH="${SCRIPT_DIR}/scripts:${PATH}"

mkdir -p "$(dirname "$OUTPUT_AAR")"

cd "$MOBILE_DIR"
# 較新 NDK (24+) 僅支援 API 21–35，需明確指定（gomobile 預設 16 已不支援）
"$GOMOBILE" bind -target=android -androidapi 21 -o "$OUTPUT_AAR" -v .

# 清理 wrapper
rm -f "${SCRIPT_DIR}/scripts/go" "$GO_WRAPPER"

echo ""
echo "=== Build complete ==="
ls -la "$OUTPUT_AAR"
