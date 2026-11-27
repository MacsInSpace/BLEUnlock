plugins {
    id("com.android.library")
}

android {
    namespace = "com.github.macsinspace.bleunlockcompanion.core"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
