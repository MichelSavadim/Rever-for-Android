plugins {
    id("com.android.application")
}

val releaseKeystore = System.getenv("REVER_RELEASE_KEYSTORE")

android {
    namespace = "com.rever.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rever.reader"
        minSdk = 34
        targetSdk = 34
        versionCode = 10000
        versionName = "1.0.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (!releaseKeystore.isNullOrBlank()) create("reverRelease") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("REVER_RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("REVER_RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("REVER_RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!releaseKeystore.isNullOrBlank()) signingConfig = signingConfigs.getByName("reverRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("com.github.axet:lame:1.0.9")
}
