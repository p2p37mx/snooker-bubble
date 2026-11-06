plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alwan.accessbubble"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alwan.accessbubble"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // مفتاح توقيع ثابت: حتى التحديثات تنزل فوق القديم بدون حذف (والصلاحية تبقى)
    signingConfigs {
        getByName("debug") {
            storeFile = file("fixed.keystore")
            storePassword = "android"
            keyAlias = "key"
            keyPassword = "android"
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
        checkReleaseBuilds = false
    }
}
