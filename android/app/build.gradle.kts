plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.breakthebarriers.unbar"
    compileSdk = 35

    // NDK for the tunnel-bridge native library (see
    // src/main/cpp/). Must match the version installed by CI
    // (.github/workflows/build.yml).
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.breakthebarriers.unbar"
        minSdk = 21
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        multiDexEnabled = true

        // F-Droid builds pass -Pfdroid: the in-app GitHub update check
        // is hidden there, since F-Droid delivers updates itself.
        buildConfigField(
            "boolean",
            "UPDATE_CHECK",
            (!project.hasProperty("fdroid")).toString()
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Per-ABI release APKs (1.0.0): one APK per ABI plus a universal APK,
    // so each device downloads only its own native libraries (Go core +
    // hev bridge) instead of all of them. The release job renames them
    // Unbar-<version>-<abi>.apk. The debug job uploads the universal
    // debug APK. No plain 32-bit x86: practically no such devices left.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // A fixed debug keystore, so every CI build signs with the SAME
    // key. Without it each fresh CI runner auto-generates a new debug
    // keystore, and Android then refuses to install a new APK over the
    // old one (signature mismatch) — the install silently fails and the
    // old app stays. The keystore lives in GitHub Actions secrets, never
    // in the repo; locally the env vars below are unset and Gradle keeps
    // its auto-generated debug keystore. This is a debug keystore only
    // (well-known android/android credentials); it is never used for a
    // release build.
    signingConfigs {
        // Reconfigure the auto-created "debug" signing config (it
        // already exists, so create() would fail). Point it at the
        // fixed keystore when CI restores it from secrets.
        getByName("debug") {
            val debugKeystorePath = System.getenv("UNBAR_DEBUG_KEYSTORE")
            if (debugKeystorePath != null && file(debugKeystorePath).exists()) {
                storeFile = file(debugKeystorePath)
                storePassword = System.getenv("UNBAR_DEBUG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UNBAR_DEBUG_KEY_ALIAS")
                keyPassword = System.getenv("UNBAR_DEBUG_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }

        // Release signing (CI only): the keystore lives in GitHub
        // Actions secrets, never in the repo. The release job restores
        // it from the UNBAR_RELEASE_KEYSTORE secret and sets the four
        // UNBAR_KEYSTORE_* env vars below. The key password is the same
        // as the store password (openssl PKCS12). Without the env vars
        // the config is unused — see the release build type.
        create("release") {
            storeFile = file(System.getenv("UNBAR_RELEASE_KEYSTORE") ?: "release.keystore")
            storePassword = System.getenv("UNBAR_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("UNBAR_KEY_ALIAS")
            keyPassword = System.getenv("UNBAR_KEY_PASSWORD")
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign only when the release keystore is present (CI restores
            // it from secrets); local release builds stay unsigned.
            val releaseKeystorePath = System.getenv("UNBAR_RELEASE_KEYSTORE")
            if (releaseKeystorePath != null && file(releaseKeystorePath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Native build for the tunnel bridge (the VPN data plane, see
    // src/main/cpp/Android.mk). ndk-build compiles the vendored C
    // library and the JNI wrapper into the APK's lib/ folder.
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Prebuilt DNS-tunnel Go core (gomobile .aar)
    implementation(files("libs/unbar-core.aar"))

    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.multidex)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)

    // QR code scanning for config import (CameraX + ZXing; no
    // proprietary ML Kit, so the app builds fully from source).
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Home-screen widget (1.0.15, C7): Glance app widget.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    debugImplementation(libs.androidx.ui.tooling)
}
