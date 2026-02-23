# Diode Android

Android 應用程式，將 Diode Go 客戶端封裝為移動應用，提供 SOCKS5 代理功能。

## 專案結構

```
diode_android/
├── app/                    # Android 應用
│   ├── src/main/java/com/diode/android/
│   │   ├── MainActivity.kt           # 主界面
│   │   ├── DiodeForegroundService.kt # 前台服務
│   │   ├── WebViewActivity.kt        # WebView 瀏覽器
│   │   └── DiodeApplication.kt       # 應用初始化
│   └── libs/
│       └── diode_mobile.aar          # Go Mobile 編譯的庫
├── diode_client/           # Diode Go 客戶端源碼
│   └── mobile/mobile.go    # Go Mobile API 封裝
├── openssl-1.1.1k-clang/   # 預編譯的 OpenSSL 庫
└── scripts/                # 構建腳本
    ├── build_mobile.sh     # 編譯 Go Mobile AAR
    ├── build_app.sh        # 構建 APK
    └── build_and_install.sh
```

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

## 主要功能

1. SOCKS5 代理服務（默認端口 1080）
2. 內建 WebView 瀏覽器（通過代理訪問 .diode 域名）
3. Foreground Service 後台運行
