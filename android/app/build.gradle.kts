plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNo = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.evkamera.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.evkamera.app"
        minSdk = 24
        targetSdk = 34
        versionCode = buildNo
        versionName = "0.1.$buildNo"
    }

    // Sabit imza anahtarı: yeni sürümler eskisinin üzerine kurulabilsin diye
    signingConfigs {
        create("evk") {
            storeFile = file("evkamera.jks")
            storePassword = "evkamera"
            keyAlias = "evkamera"
            keyPassword = "evkamera"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("evk")
        }
    }

    // Telefon tipine göre ayrı, daha küçük APK'lar
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("io.getstream:stream-webrtc-android:1.1.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
}
