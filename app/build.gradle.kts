plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.tomppi.launchergrid712"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.tomppi.launchergrid712"
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "2.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Compile against local Xposed API stubs without packaging them into the APK.
    compileOnly(project(":xposed-stubs"))
}
