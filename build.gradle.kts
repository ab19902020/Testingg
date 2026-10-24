import java.util.Properties

plugins {
    kotlin("jvm") version "2.2.21" apply false
}

// ---------------------------------------------------------------------------------------------
// Android SDK discovery shared by :core (API-surface check) and :app (APK pipeline).
// Order: -P / gradle.properties  ->  local.properties  ->  ANDROID_HOME / ANDROID_SDK_ROOT.
// ---------------------------------------------------------------------------------------------
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun setting(name: String): String? =
    (findProperty(name) as String?) ?: localProps.getProperty(name)

val sdkDir: String? = setting("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
val compileSdk = setting("clrg.compileSdk") ?: "35"
val buildToolsVersion = setting("clrg.buildToolsVersion") ?: "35.0.0"
val buildToolsDir = sdkDir?.let { "$it/build-tools/$buildToolsVersion" }

extra["clrg.androidJar"] = setting("clrg.androidJar") ?: sdkDir?.let { "$it/platforms/android-$compileSdk/android.jar" }
// aapt2 links against this jar's resource table. Normally the same as clrg.androidJar; an older platform
// jar can be used when the available aapt2 predates the compileSdk resource-table format.
extra["clrg.resourceLinkJar"] = setting("clrg.resourceLinkJar") ?: extra["clrg.androidJar"]
extra["clrg.aapt2"] = setting("clrg.aapt2") ?: buildToolsDir?.let { "$it/aapt2" }
extra["clrg.d8Jar"] = setting("clrg.d8Jar") ?: buildToolsDir?.let { "$it/lib/d8.jar" }
extra["clrg.zipalign"] = setting("clrg.zipalign") ?: buildToolsDir?.let { "$it/zipalign" }
extra["clrg.apksigner"] = setting("clrg.apksigner") ?: buildToolsDir?.let { "$it/apksigner" }
