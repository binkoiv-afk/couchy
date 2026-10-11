plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    // Screenshot tests: CI renders the main screens on the JVM so the design can be checked without a device.
    alias(libs.plugins.paparazzi)
}

// CI passes the run number, so every published build has a higher version than the last.
val buildNumber = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
// The release key lives in the repo, encrypted; its password is a GitHub secret. Without either
// (local builds, or before the owner sets the secret) the build falls back to the debug key.
// Yandex sign-in needs the app registered on oauth.yandex.ru; its client id is public, kept in this file.
val tmdbKey: String = file("tmdb-key.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
val yandexClientId: String = file("yandex-client-id.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() }
    ?.takeIf { file("signing/couchy.jks").exists() }

android {
    namespace = "app.couchy"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.couchy"
        // Android 6: plenty of TV boxes still run it.
        minSdk = 23
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        vectorDrawables.useSupportLibrary = true
        manifestPlaceholders["YANDEX_CLIENT_ID"] = yandexClientId
        buildConfigField("String", "YANDEX_CLIENT_ID", "\"$yandexClientId\"")
        buildConfigField("String", "TMDB_KEY", "\"$tmdbKey\"")
    }

    signingConfigs {
        if (keystorePassword != null) {
            create("release") {
                storeFile = file("signing/couchy.jks")
                storePassword = keystorePassword
                keyAlias = "couchy"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        // NewPipe Extractor uses java.time and java.nio, which old Androids get from desugaring.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // The built-in torrent engine is a TorrServer binary that CI drops into jniLibs as
    // libtorrserver.so. It is started as a process, so it must be unpacked on install and
    // left as built.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libtorrserver.so", "**/libwireproxy.so")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.play.services.auth)
    implementation(libs.yandex.authsdk)
    implementation(libs.newpipe.extractor)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.coil.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)

    testImplementation(libs.junit)
}
