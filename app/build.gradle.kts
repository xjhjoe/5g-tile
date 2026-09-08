plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.fivegtile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.fivegtile"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.1-final"
    }

    buildFeatures {
        aidl = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
