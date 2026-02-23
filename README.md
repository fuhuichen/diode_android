# Diode Android

將 [diode_client](https://github.com/diodechain/diode_client) 封裝為 Android 應用，透過 Go Mobile 提供 SOCKS5 代理，讓裝置上的 App 可存取 `.diode` 網域。

## 架構

```
diode_android/
├── diode_client/          # Diode Go 客戶端（含 mobile wrapper）
│   └── mobile/
│       └── mobile.go      # Go Mobile 封裝
├── app/                   # Android 應用
│   ├── libs/              # 放置編譯後的 diode_mobile.aar
│   └── src/main/java/.../
│       ├── MainActivity.kt
│       └── DiodeForegroundService.kt
└── scripts/
    └── build_mobile.sh    # 編譯 AAR 腳本
```

## 建置步驟

### 前置需求

1. **Go 1.25+** 與 **gomobile**
   ```bash
   go install golang.org/x/mobile/cmd/gomobile@latest
   gomobile init
   ```

2. **Android SDK** 與 **NDK**
   - Android SDK Platform-Tools（含 `adb`）
   - NDK r21+ (API 21-35)

3. **OpenSSL 原始碼**
   - 專案已包含 `openssl/` 完整原始碼（用於編譯時標頭檔）
   - `openssl-1.1.1k-clang/` 提供預編譯的 Android 函式庫

### 快速建置（推薦）

**一鍵建置 SDK + App + 安裝到裝置：**

```bash
./scripts/build_and_install.sh
```

此腳本會自動：
1. 編譯 Go Mobile AAR (`diode_mobile.aar`)
2. 建置 Android Debug APK
3. 解除安裝舊版本
4. 安裝到已連接的 Android 裝置

### 分步建置

#### 1. 編譯 Go Mobile AAR

```bash
./scripts/build_mobile.sh
```

編譯完成後，`app/libs/diode_mobile.aar` 會自動產生（約 23MB）。

**技術細節：**
- 使用 `openssl/` 原始碼提供內部標頭（如 `ssl/ssl_local.h`）
- 連結 `openssl-1.1.1k-clang/` 的預編譯函式庫（各 ABI）
- 支援 arm64-v8a, armeabi-v7a, x86, x86_64

#### 2. 建置 Android APK（不重新編譯 AAR）

```bash
./scripts/build_app.sh
```

或使用 Gradle：

```bash
./gradlew clean assembleDebug
```

#### 3. 手動安裝到裝置

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 使用方式

### 應用程式操作

1. **輸入設定**
   - RPC 位址：留空使用預設節點
   - SOCKS5 埠位：預設 `1080`
   - 私鑰：留空自動生成

2. **啟動 Diode**：點擊「啟動 Diode」，會以前台服務方式運行

3. **開啟 WebView**：點擊「開啟 WebView 瀏覽 .diode」
   - 預設載入 `https://whatismyipaddress.com/` 檢查代理狀態
   - 可輸入任何 `.diode` 網域或一般網址

4. **電池最佳化**：建議點擊「忽略電池最佳化」以提升背景穩定性

### 命令列操作

```bash
# 啟動應用程式
adb shell am start -n com.diode.android/.MainActivity

# 查看日誌
adb logcat -s DiodeForegroundService:* MainActivity:*

# 解除安裝
adb uninstall com.diode.android
```

## WebView 設定範例

```kotlin
val proxyConfig = ProxyConfig.Builder()
    .addProxyRule("127.0.0.1:1234")
    .addDirectRule("localhost")
    .build()

ProxyController.getInstance().setProxyOverride(proxyConfig, {
    webView.loadUrl("https://example.0x667788...diode")
}, { executor -> /* 錯誤處理 */ })
```

## Go Mobile API

- `StartDiode(rpcAddrs, socksPort, socksHost, privateKeyHex, dataDir)`：完整版啟動
- `StartDiodeSimple(rpcServer, portInfo, privateKey, dataDir)`：簡化版，portInfo 格式如 `"1234:socks"`
- `StopDiode()`：停止服務
- `GetAddress()`：取得 Diode 地址
- `GetLastError()`：取得最後錯誤

## 前台服務

為避免 Android 電池管理 (Low Memory Killer) 終止背景連線，本 App 使用 **Foreground Service** 執行 Diode，系統會顯示常駐通知。此為維持 P2P 連線穩定的必要設計。

## 授權

與 diode_client 相同，採用 Diode License, Version 1.1。
