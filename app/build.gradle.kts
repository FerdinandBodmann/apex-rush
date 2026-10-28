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
        versionCode = 3
        versionName = "1.2"
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
// the 3D engine is raw OpenGL ES 2.0 with hand-written GLSL shaders,
// UI is drawn with android.graphics.Canvas, and persistence uses
// built-in SharedPreferences + org.json.
dependencies {
}
