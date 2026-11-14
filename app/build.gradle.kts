plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "colourfilm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "colourfilm.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "runtime"
    productFlavors {
        // Snapdragon build: ONNX Runtime with the Qualcomm QNN execution provider (NPU) plus CPU fallback.
        create("snapdragon") {
            dimension = "runtime"
            ndk { abiFilters += "arm64-v8a" }
            buildConfigField("boolean", "HAS_QNN", "true")
        }
        // Universal build: ONNX Runtime CPU (arm64, armv7, x86_64) for any Android 10+ device / emulator.
        create("universal") {
            dimension = "runtime"
            applicationIdSuffix = ".universal"
            // e.g. -PonlyAbi=x86_64 for a lean emulator test build
            (project.findProperty("onlyAbi") as String?)?.let { ndk { abiFilters += it } }
            buildConfigField("boolean", "HAS_QNN", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the APK installs directly; see BUILDING.md for real signing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Hexagon libraries must be extracted to disk for the DSP loader.
            useLegacyPackaging = true
            // Not needed for HTP (NPU) inference on Snapdragon 8 Gen 1 and newer + 7/6-series V69+.
            excludes += listOf(
                "**/libQnnDsp.so", "**/libQnnDspV66Stub.so", "**/libQnnDspV66Skel.so",
                "**/libQnnHtpV68Stub.so", "**/libQnnHtpV68Skel.so", "**/libQnnGpu.so",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    "snapdragonImplementation"("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
    "universalImplementation"("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
