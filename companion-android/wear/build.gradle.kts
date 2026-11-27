plugins {
    id("com.android.application")
}

android {
    namespace = "com.github.macsinspace.bleunlockcompanion.wear"
    compileSdk = 37

    defaultConfig {
        // Same application id as the phone app so a later Data Layer pairing
        // hand-off works; they install on different devices.
        applicationId = "com.github.macsinspace.bleunlockcompanion"
        minSdk = 33 // Wear OS 4+
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
}
