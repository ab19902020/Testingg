plugins {
    id("com.android.application")
}

// The game itself lives in ../../castle-chaos. It's copied into the APK's assets at build time,
// so there's only one copy of the game to edit.
val gameDir = rootProject.projectDir.resolve("../castle-chaos")
val generatedAssets = layout.buildDirectory.dir("generated/gameAssets")

val copyGameAssets by tasks.registering(Copy::class) {
    from(gameDir) {
        exclude("README.md")
    }
    into(generatedAssets.map { it.dir("game") })
}

// Optional release signing from environment variables (set as GitHub secrets).
// Without them the APK is signed with the standard debug key, which is fine for sideloading.
val keystorePath: String? = System.getenv("CC_KEYSTORE")

android {
    namespace = "com.castlechaos.game"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.castlechaos.game"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("CC_VERSION_CODE") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("CC_VERSION_CODE") ?: "0")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("CC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CC_KEY_ALIAS")
                keyPassword = System.getenv("CC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].assets.srcDir(generatedAssets)

    // keep the game's files uncompressed-friendly and readable by the WebView
    androidResources {
        noCompress += listOf("woff2")
    }
}

tasks.named("preBuild") {
    dependsOn(copyGameAssets)
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
