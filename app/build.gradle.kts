plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pe.muhu.kds"
    compileSdk = 34

    defaultConfig {
        applicationId = "pe.muhu.kds"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Firma fija: permite actualizar la app sin desinstalarla
    signingConfigs {
        create("kds") {
            storeFile = file("kds-muhu.keystore")
            storePassword = "kdsmuhu2026"
            keyAlias = "kdsmuhu"
            keyPassword = "kdsmuhu2026"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("kds") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("kds")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
}
