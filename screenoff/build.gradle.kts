plugins {
    id("com.android.application")
}
android {
    namespace = "com.tile.screenoff"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.tile.screenoff.a17"
        minSdk = 21
        targetSdk = 35
        versionCode = 23
        versionName = "23-a17-preview2"
    }
    buildFeatures { aidl = true }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false }
}
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.0")
    implementation("dev.rikka.shizuku:provider:13.1.0")
}
