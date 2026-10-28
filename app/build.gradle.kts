plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bodmannferdinand.apexrush"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bodmannferdinand.apexrush"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Intentionally empty: the game has zero external dependencies —
// all graphics are procedural (android.graphics.Canvas) and
// persistence uses the built-in SharedPreferences + org.json.
dependencies {
}
