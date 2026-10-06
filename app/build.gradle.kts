plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.input_ds"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.input_ds"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // The rehabilitation tablet and the embedded Godot game use ARM64.
        // Restricting the APK avoids packaging unused Godot and Rime runtimes.
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // Godot needs random access to its pack; bundled songs are already compressed.
        noCompress += listOf("pck", "mp3")
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Coroutines
    implementation(libs.kotlinx.coroutines)

    // ONNX Runtime for EEG model inference
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.work.runtime.ktx)

    // Entertainment playback (music playlists and HLS television streams)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp.dnsoverhttps)

    // Godot 4.7.1 runtime used by the embedded wizard game module.
    implementation("org.godotengine:godot:4.7.1.stable")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
