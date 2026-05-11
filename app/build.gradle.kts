import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.diode.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.diode.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // APK 檔名加上版號與 build type，例如：diode-ub-v1.1-2-release.apk
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName =
                    "diode-${variant.flavorName}-v${variant.versionName}-${variant.versionCode}-${variant.buildType.name}.apk"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    flavorDimensions += "brand"
    productFlavors {
        create("ub") {
            dimension = "brand"
            applicationId = "com.diode.ub"
            buildConfigField("String", "DEFAULT_URL", "\"https://www.ubet88.io\"")
            buildConfigField("String", "API_KEY", "\"dk_bdb31264eb6942abb34be02ba933a857\"")
            buildConfigField("int", "DIODE_SOCKS_PORT", "9080")
            buildConfigField("int", "WEBVIEW_PROXY_PORT", "8080")
            resValue("string", "app_name", "UB")
        }
        create("k7") {
            dimension = "brand"
            applicationId = "com.diode.k7"
            buildConfigField("String", "DEFAULT_URL", "\"https://m1.zc83641fun.shop\"")
            buildConfigField("String", "API_KEY", "\"dk_2b86022520194ee6aadb20c89554e793\"")
            buildConfigField("int", "DIODE_SOCKS_PORT", "9081")
            buildConfigField("int", "WEBVIEW_PROXY_PORT", "8081")
            resValue("string", "app_name", "K7")
        }
        create("juicycc") {
            dimension = "brand"
            applicationId = "com.diode.juicycc"
            buildConfigField("String", "DEFAULT_URL", "\"https://juicycc.com/\"")
            buildConfigField("String", "API_KEY", "\"dk_2a1a656306ef47dc9af9da92d7b9f7d1\"")
            buildConfigField("int", "DIODE_SOCKS_PORT", "9082")
            buildConfigField("int", "WEBVIEW_PROXY_PORT", "8082")
            resValue("string", "app_name", "juicycc")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.webkit:webkit:1.10.0")

    // Diode Mobile AAR - 編譯 gomobile 後放入 app/libs/
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
