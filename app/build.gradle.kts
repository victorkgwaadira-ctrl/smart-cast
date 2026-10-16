plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vikaranet.smartcast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vikaranet.smartcast"
        minSdk = 26          // Android 8.0+ (phones, tablets, Android TV, Google TV, Fire TV)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    // No third-party libraries: everything uses the Android framework only.
    testImplementation("junit:junit:4.13.2")
}
