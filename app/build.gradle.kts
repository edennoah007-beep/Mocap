plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.standin.gamer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.standin.gamer"
        minSdk = 29          // Android 10+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK installs without extra setup.
            // Replace with your own keystore before publishing to the Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("html") }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
