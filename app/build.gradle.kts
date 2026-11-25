plugins {
    id("com.android.application")
}

android {
    buildFeatures {
        aidl = true
    }

    namespace = "com.gamerturbo.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gamerturbo.mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = 13
        versionName = "2.3.5"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
