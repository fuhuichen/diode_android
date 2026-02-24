# Diode Android

Android 應用程式，將 Diode Go 客戶端封裝為移動應用，提供 SOCKS5 代理功能。

## 專案結構

```
diode_android/
├── app/                    # Android 應用
│   ├── src/main/java/com/diode/android/
│   │   ├── LoadingActivity.kt          # 啟動畫面，初始化 Diode、等待連線就緒
│   │   ├── DiodeForegroundService.kt   # 前台服務，管理 SOCKS5 代理生命週期
│   │   ├── NodeConnectionManager.kt    # 節點發現、連線、keepalive 管理
│   │   ├── DiodeApiClient.kt           # HTTP 客戶端（getNodes/connect/keepalive/disconnect）
│   │   ├── WebViewActivity.kt          # WebView 瀏覽器，設定 proxy override
│   │   ├── MainActivity.kt             # 主界面（啟動/停止按鈕）
│   │   └── DiodeApplication.kt         # Application 初始化（載入 OpenSSL）
│   ├── build.gradle.kts                # Gradle 配置（含 flavor 設定）
│   └── libs/
│       └── diode_mobile.aar            # Go Mobile 編譯的庫
├── diode_client/           # Diode Go 客戶端源碼
│   ├── mobile/mobile.go    # Go Mobile API 封裝（含 GetTrafficAndReset / GetLastBytesDown）
│   └── rpc/traffic.go      # 原子計數器（trafficBytesUp / trafficBytesDown）
├── openssl-1.1.1k-clang/   # 預編譯的 OpenSSL 庫
└── scripts/                # 構建腳本
    ├── build_mobile.sh     # 編譯 Go Mobile AAR
    ├── build_app.sh        # 構建 APK
    └── build_and_install.sh
```

## Flavor 設定

| Flavor | Application ID | SOCKS Port | Proxy Port | 預設 URL |
|--------|---------------|------------|------------|----------|
| UB     | `com.diode.ub` | 9080       | 8080       | ubet88.io |
| K7     | `com.diode.k7` | 9081       | 8081       | zc83641fun.shop |

每個 flavor 有獨立的 port，避免同時安裝時衝突。

## 連線流程

```
LoadingActivity
  → 啟動 DiodeForegroundService（啟動 Diode SOCKS5 代理）
  → 等待 Diode ready
  → NodeConnectionManager.connectToNode()
      → DiodeApiClient.getNodes()（取得節點清單）
      → DiodeApiClient.connect(nodeId, sessionId)
      → Mobile.setBinds("<PROXY_PORT>:<client_address>:1080:tcp")
  → 連線成功 → WebViewActivity
```

## 流量回報

- **間隔**：keepalive 每 **25 秒**回報一次
- **數據**：`bytes_up` / `bytes_down`（透過 Go 原子計數器收集）
- **流程**：`Mobile.getTrafficAndReset()` → `Mobile.getLastBytesDown()` → `DiodeApiClient.keepalive(sessionId, bytesUp, bytesDown)`

## 構建指令

```bash
# 編譯 Go Mobile AAR
./scripts/build_mobile.sh

# 構建 APK
./scripts/build_app.sh

# 完整構建並安裝到設備
./scripts/build_and_install.sh
```

## 技術棧

- **Android**: Kotlin, Material Components, WebView
- **Go Mobile**: gomobile bind
- **加密**: secp256k1, OpenSSL 1.1.1k
- **構建**: Gradle, CMake/NDK
