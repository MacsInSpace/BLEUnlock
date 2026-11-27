plugins {
    id("com.android.application")
}

android {
    namespace = "com.github.macsinspace.bleunlockcompanion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.github.macsinspace.bleunlockcompanion"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
