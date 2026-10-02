plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.ikko.aura"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ikko.aura"
        minSdk = 31
        // ONNX Runtime ships a ~30MB native library per ABI, and it carries four: filtering to
        // the one this app is for takes the APK from 138MB to about 42MB. The Mind One Pro is
        // arm64 (MediaTek MT6789), as is every device this is built for. Add "armeabi-v7a" or
        // "x86_64" here if it ever has to run on a 32-bit phone or an emulator.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_shared" }
        }
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            // Signed with the debug key so a release build can simply be installed on the device
            // for testing — performance can only be judged here (the debug build's Compose
            // instrumentation makes it 5-6x slower and no build flag closes that gap).
            // Replace with a real keystore before distributing anything.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        // QA: exactly the release build (R8, shrunk, not debuggable, same code), under its own application id so it installs
        // beside the daily app and shares nothing with it: the id decides the app's private storage (queue, history,
        // favourites, downloads, settings and databases), and nothing here writes outside it. Built with
        // `./gradlew :app:assembleQa`; its name on the launcher is "Aura QA" (src/qa/res).
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Debug Compose builds carry instrumentation that release does not; source information is
    // the part we can drop without losing debuggability, and it is pure overhead on device.
    composeCompiler {
        includeSourceInformation = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    // The Automix analyser's DSP front end — mel spectrogram, STFT, tempo and structure — is
    // C++, as it is in BitChord. The Kotlin side degrades to "no evidence" (a plain crossfade)
    // if the library is missing, so a build without the NDK still runs; it just never
    // beat-matches.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "27.0.12077973"

    androidResources {
        // The two Automix models are already int8-quantised and compress by almost nothing;
        // deflating them only costs time inflating them again at first inference.
        noCompress += listOf("onnx")
        // Ships only the two languages the app is actually written in, instead of every locale
        // AndroidX and Material carry. `resourceConfigurations` said the same thing and is on its
        // way out.
        localeFilters += listOf("fr", "en")
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    compileOnly(libs.errorprone.annotations)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource)
    implementation(libs.media3.extractor)
    // FFmpeg audio decoders for what the phone's own codecs do not play (AC3, DTS, ALAC, WMA, APE…), used as a fallback.
    implementation(libs.nextlib.media3ext)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")

    implementation(libs.kotlinx.coroutines.android)

    // The full android artifact, not onnxruntime-mobile: mobile only loads .ort models and the
    // two Automix models are plain .onnx. Same choice BitChord makes, for the same reason.
    implementation(libs.onnxruntime.android)
    // One file's worth: the Automix analysis store, whose on-disk records are
    // @Serializable DTOs in BitChord and stay that way here.
    implementation(libs.kotlinx.serialization.json)

    // JVM unit tests only: nothing here ships in the app. The first tests of the project, for the
    // pure logic that decides what gets played (see source/ and playback/).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
