plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pocketchat.app"
    compileSdk = 34
    // Pinned to the version llama.cpp's own CI currently builds against
    // (.github/workflows/build-android.yml in the submodule) — keeps us on a
    // version this exact vendored commit is actually verified to compile with.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.pocketchat.app"
        // API 26 (Android 8.0, 2017) covers our ~3GB-RAM-floor / last-6-7-years target
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // arm64-v8a covers virtually all target devices; armeabi-v7a keeps
            // the floor-spec end covered; x86_64 is for the emulator. CI passes
            // -Ppocketchat.abi=<abi> to restrict this to one ABI for fast
            // push/PR builds — a real Gradle property our own script reads,
            // unlike AGP's Studio-deploy-only android.injected.build.abi flag,
            // which produces no packaged APK when used outside Studio's deploy
            // task graph.
            val abiOverride = project.findProperty("pocketchat.abi") as String?
            abiFilters += abiOverride?.let { listOf(it) } ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // Single .so, statically linking llama/ggml (see core/CMakeLists.txt) —
                // c++_static avoids packaging a separate libc++_shared.so.
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")

    // ChatViewModel: viewModelScope, the viewModel() composable, and
    // collectAsStateWithLifecycle() for the streaming chat UI state.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
