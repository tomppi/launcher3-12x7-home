plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.tomppi.drawerwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.tomppi.drawerwidget"
        minSdk = 31
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.4"
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
