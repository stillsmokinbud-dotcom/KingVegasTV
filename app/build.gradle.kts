plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    // App identity: King Vegas TV. (The Kotlin package name stays com.novatv.app; users never see it.)
    namespace = "com.novatv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kingvegas.tv"
        minSdk = 23          // Fire TV Stick (2nd gen+) and Android TV 6.0+
        targetSdk = 35
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // Address of your license server (the /server folder). Can also be changed in
        // Settings › Premium account › Account server.
        val serverUrl = System.getenv("NOVATV_SERVER_URL")?.takeIf { it.isNotBlank() } ?: "https://kingvegastv-server.onrender.com"
        buildConfigField("String", "LICENSE_SERVER_URL", "\"$serverUrl\"")
    }

    // Release signing: GitHub Actions passes the keystore through these environment variables
    // (see SETUP.md). Without them, release builds are signed with the debug key so they still install.
    val keystorePath = System.getenv("NOVATV_KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("NOVATV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NOVATV_KEY_ALIAS")
                keyPassword = System.getenv("NOVATV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Media3 marks many player APIs @UnstableApi; we use them on purpose.
        disable += "UnsafeOptInUsageError"
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Player (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3") // QR codes for "Get Premium"
}
