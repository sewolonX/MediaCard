plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "module.sewolon.mediacard"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "module.sewolon.mediacard"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // The standalone module has no release keystore; use the local debug key as requested.
            signingConfig = signingConfigs.getByName("debug")
            // AGP requires code shrinking for resource shrinking; names stay unchanged via -dontobfuscate.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures { compose = true }

    androidResources {
        localeFilters += listOf("en", "zh-rCN")
    }
}

dependencies {
    // The Xposed framework supplies API 102 at runtime in hooked processes.
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.mediacard.activity.compose)
    implementation(platform(libs.mediacard.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.miuix.ui)
}
