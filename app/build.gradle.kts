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
        versionCode = 1
        versionName = "1.0.0"
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
    // Minimal legacy Xposed API stubs. compileOnly means they are not bundled in the APK.
    compileOnly(files("libs/xposed-api-stubs.jar"))
}
