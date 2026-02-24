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
        versionCode = 1
        versionName = "1.0"
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
            buildConfigField("String", "API_SECRET", "\"ds_6bd6fae49c42416394f14fbd3baa86623fda6ae9df984a35\"")
            resValue("string", "app_name", "UB")
        }
        create("k7") {
            dimension = "brand"
            applicationId = "com.diode.k7"
            buildConfigField("String", "DEFAULT_URL", "\"https://m1.zc83641fun.shop\"")
            buildConfigField("String", "API_KEY", "\"dk_2b86022520194ee6aadb20c89554e793\"")
            buildConfigField("String", "API_SECRET", "\"ds_98419284f0144e98a065d85617e4cfd38c6841037e794fce\"")
            resValue("string", "app_name", "K7")
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
