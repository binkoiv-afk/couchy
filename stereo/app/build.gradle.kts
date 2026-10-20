plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes the run number, so every published build has a higher version than the last.
val buildNumber = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
// Same scheme as Couchy: the key is committed encrypted, its password is a GitHub secret.
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() }
    ?.takeIf { file("signing/stereo.jks").exists() }

android {
    namespace = "app.stereoplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.stereoplay"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        // Phones, tablets and TV boxes are ARM; the neural network runtime is large per ABI.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (keystorePassword != null) {
            create("release") {
                storeFile = file("signing/stereo.jks")
                storePassword = keystorePassword
                keyAlias = "stereo"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
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
    // The depth models are copied out of the APK once; stored, they copy fast.
    // Media3 marks parts of its API unstable; that check is not worth failing a build over.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    // AC3, DTS and the like, common in films, decoded in software.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    // Depth Anything V2 runs on ONNX Runtime.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
