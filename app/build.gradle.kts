import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// =============================================================================================
// Living Room Games - Android application module.
//
// This module is built WITHOUT the Android Gradle Plugin: Kotlin is compiled against android.jar and
// the APK is assembled with the standard SDK tools (aapt2, D8, zipalign, apksigner). See BUILDING.md
// for why (the build sandbox cannot reach Google's Maven repository) and how to configure tool paths.
//
//   ./gradlew :app:assembleDebugApk   -> app/build/outputs/apk/LivingRoomGames-debug.apk
// =============================================================================================

plugins {
    kotlin("jvm")
}

val appId = "com.clrg.christmasgames"
val minSdk = (findProperty("clrg.minSdk") as String).toInt()
val targetSdk = (findProperty("clrg.targetSdk") as String).toInt()
val versionCode = (findProperty("clrg.versionCode") as String).toInt()
val versionName = findProperty("clrg.versionName") as String
val abis = (findProperty("clrg.abis") as String).split(',').map { it.trim() }.filter { it.isNotEmpty() }

fun requiredTool(key: String): File {
    val path = rootProject.extra[key] as String?
        ?: throw GradleException("Android SDK not configured: set sdk.dir in local.properties or ANDROID_HOME (missing $key)")
    val f = file(path)
    if (!f.exists()) throw GradleException("Required Android SDK file not found for $key: $f (see BUILDING.md)")
    return f
}

val androidJarPath = rootProject.extra["clrg.androidJar"] as String?
val androidJarFile = androidJarPath?.let { file(it) }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        noJdk.set(true) // java.* comes from android.jar, exactly like on the device
    }
}

// --------------------------------------------------------------------------------------------
// Dependencies. TensorFlow Lite is published as AARs on Maven Central; we unpack them ourselves.
// --------------------------------------------------------------------------------------------
val tfliteVersion = "2.16.1"
val aar by configurations.creating { isTransitive = false }

dependencies {
    implementation(project(":core"))
    aar("org.tensorflow:tensorflow-lite:$tfliteVersion@aar")
    aar("org.tensorflow:tensorflow-lite-api:$tfliteVersion@aar")
    aar("org.tensorflow:tensorflow-lite-gpu:$tfliteVersion@aar")
    aar("org.tensorflow:tensorflow-lite-gpu-api:$tfliteVersion@aar")
}

val aarDir = layout.buildDirectory.dir("intermediates/aars")
val extractAars by tasks.registering(Sync::class) {
    description = "Unpacks AAR dependencies (classes.jar + native libraries)."
    into(aarDir)
    aar.resolve().forEach { f ->
        from(zipTree(f)) {
            include("classes.jar", "jni/**", "AndroidManifest.xml", "proguard.txt")
            into(f.nameWithoutExtension)
        }
    }
}

val aarClassJars = files(provider {
    aar.resolve().map { aarDir.get().file("${it.nameWithoutExtension}/classes.jar").asFile }
}).builtBy(extractAars)

dependencies {
    if (androidJarFile != null) compileOnly(files(androidJarFile))
    compileOnly(aarClassJars)
}

tasks.withType<KotlinCompile>().configureEach { dependsOn(extractAars) }

// --------------------------------------------------------------------------------------------
// Pose models (MediaPipe BlazePose, Apache-2.0). Downloaded once with checksum verification and
// bundled into the APK so gameplay works offline after installation.
// --------------------------------------------------------------------------------------------
data class ModelSpec(val variant: String, val sha256: String)
val modelSpecs = listOf(
    ModelSpec("lite", "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a"),
    ModelSpec("full", "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"),
)
/** Finger tracking: MediaPipe hand landmark model (lite, Apache-2.0), a single .tflite file. */
val handModelUrl = "https://storage.googleapis.com/mediapipe-assets/hand_landmark_lite.tflite"
val handModelSha256 = "d7fde8ac11f8ce03f8663775bfc323f4fc9f2a38062b4f4efa142874ef5b2a48"
val modelCache = rootProject.layout.projectDirectory.dir(".model-cache")
val generatedAssets = layout.buildDirectory.dir("generated/assets")

fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

val fetchPoseModels by tasks.registering {
    description = "Downloads and verifies the MediaPipe pose landmarker models, then unpacks the .tflite files."
    val outDir = generatedAssets.map { it.dir("models") }
    outputs.dir(outDir)
    inputs.property("models", modelSpecs.map { it.variant + ":" + it.sha256 } + handModelSha256)
    doLast {
        val cache = modelCache.asFile.apply { mkdirs() }
        val out = outDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        for (m in modelSpecs) {
            val task = File(cache, "pose_landmarker_${m.variant}.task")
            if (!task.exists() || sha256(task) != m.sha256) {
                val url = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_${m.variant}/float16/1/pose_landmarker_${m.variant}.task"
                logger.lifecycle("Downloading $url")
                uri(url).toURL().openStream().use { input -> task.outputStream().use { input.copyTo(it) } }
            }
            val actual = sha256(task)
            if (actual != m.sha256) throw GradleException("Checksum mismatch for ${task.name}: $actual")
            ZipFile(task).use { zip ->
                for (name in listOf("pose_detector.tflite", "pose_landmarks_detector.tflite")) {
                    val e = zip.getEntry(name) ?: throw GradleException("$name missing in ${task.name}")
                    File(out, "${m.variant}_$name").outputStream().use { o -> zip.getInputStream(e).use { it.copyTo(o) } }
                }
            }
        }
        val hand = File(cache, "hand_landmark_lite.tflite")
        if (!hand.exists() || sha256(hand) != handModelSha256) {
            logger.lifecycle("Downloading $handModelUrl")
            uri(handModelUrl).toURL().openStream().use { input -> hand.outputStream().use { input.copyTo(it) } }
        }
        val handSum = sha256(hand)
        if (handSum != handModelSha256) throw GradleException("Checksum mismatch for ${hand.name}: $handSum")
        hand.copyTo(File(out, "hand_landmark_lite.tflite"), overwrite = true)
    }
}

// --------------------------------------------------------------------------------------------
// Manifest processing (inject package / version / sdk levels - the job AGP's manifest merger does).
// --------------------------------------------------------------------------------------------
val processManifest by tasks.registering {
    val src = file("src/main/AndroidManifest.xml")
    val out = layout.buildDirectory.file("intermediates/manifest/AndroidManifest.xml")
    inputs.file(src)
    inputs.property("v", listOf(appId, versionCode, versionName, minSdk, targetSdk).joinToString())
    outputs.file(out)
    doLast {
        var text = src.readText()
        text = text.replaceFirst(
            "<manifest ",
            "<manifest package=\"$appId\" android:versionCode=\"$versionCode\" android:versionName=\"$versionName\" "
        )
        text = text.replaceFirst(
            Regex("(<manifest[^>]*>)"),
            "$1\n    <uses-sdk android:minSdkVersion=\"$minSdk\" android:targetSdkVersion=\"$targetSdk\" />"
        )
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
    }
}

// --------------------------------------------------------------------------------------------
// Resources: aapt2 compile + link.
// --------------------------------------------------------------------------------------------
val resDir = file("src/main/res")
val compiledResZip = layout.buildDirectory.file("intermediates/res/compiled.zip")
val aapt2Compile by tasks.registering(Exec::class) {
    inputs.dir(resDir)
    outputs.file(compiledResZip)
    doFirst { compiledResZip.get().asFile.parentFile.mkdirs() }
    executable = requiredTool("clrg.aapt2").absolutePath
    args("compile", "--dir", resDir.absolutePath, "-o", compiledResZip.get().asFile.absolutePath)
}

val linkedResApk = layout.buildDirectory.file("intermediates/res/resources.ap_")
val aapt2Link by tasks.registering(Exec::class) {
    dependsOn(aapt2Compile, processManifest)
    inputs.file(compiledResZip)
    inputs.files(processManifest)
    outputs.file(linkedResApk)
    executable = requiredTool("clrg.aapt2").absolutePath
    args(
        "link",
        "-I", requiredTool("clrg.resourceLinkJar").absolutePath,
        "--manifest", layout.buildDirectory.file("intermediates/manifest/AndroidManifest.xml").get().asFile.absolutePath,
        "--min-sdk-version", minSdk.toString(),
        "--target-sdk-version", targetSdk.toString(),
        "--version-code", versionCode.toString(),
        "--version-name", versionName,
        "--auto-add-overlay",
        "-o", linkedResApk.get().asFile.absolutePath,
        compiledResZip.get().asFile.absolutePath,
    )
}

// --------------------------------------------------------------------------------------------
// Dex: D8 over app classes + runtime classpath (core, kotlin-stdlib) + AAR class jars.
// --------------------------------------------------------------------------------------------
val appClassesJar by tasks.registering(Jar::class) {
    archiveFileName.set("app-classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/classes"))
    from(sourceSets.main.get().output)
}

val dexDir = layout.buildDirectory.dir("intermediates/dex")
val dex by tasks.registering(JavaExec::class) {
    dependsOn(appClassesJar, extractAars)
    val runtimeJars = configurations.runtimeClasspath.get()
    inputs.files(appClassesJar, runtimeJars, aarClassJars)
    outputs.dir(dexDir)
    classpath = files(requiredTool("clrg.d8Jar"))
    mainClass.set("com.android.tools.r8.D8")
    maxHeapSize = "2g"
    doFirst {
        val out = dexDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        args = listOf(
            "--debug",
            "--min-api", minSdk.toString(),
            "--lib", requiredTool("clrg.androidJar").absolutePath,
            "--output", out.absolutePath,
        ) + appClassesJar.get().archiveFile.get().asFile.absolutePath +
            runtimeJars.files.map { it.absolutePath } +
            aarClassJars.files.map { it.absolutePath }
    }
}

// --------------------------------------------------------------------------------------------
// Package: merge linked resources + dex + assets + native libs into an unsigned APK.
// resources.arsc and .tflite files are STORED (uncompressed) so they can be memory-mapped.
// --------------------------------------------------------------------------------------------
val assetsDir = file("src/main/assets")
val unsignedApk = layout.buildDirectory.file("intermediates/apk/unsigned.apk")
val packageApk by tasks.registering {
    dependsOn(aapt2Link, dex, fetchPoseModels, extractAars)
    inputs.files(linkedResApk, dexDir, generatedAssets)
    if (assetsDir.exists()) inputs.dir(assetsDir)
    inputs.dir(aarDir)
    inputs.property("abis", abis)
    outputs.file(unsignedApk)
    doLast {
        val out = unsignedApk.get().asFile.apply { parentFile.mkdirs(); delete() }
        val written = HashSet<String>()
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            fun put(name: String, bytes: ByteArray, store: Boolean) {
                if (!written.add(name)) return
                val e = ZipEntry(name)
                e.time = 315532800000L // fixed timestamp for reproducible APKs
                if (store) {
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong(); e.compressedSize = bytes.size.toLong()
                    e.crc = CRC32().apply { update(bytes) }.value
                } else e.method = ZipEntry.DEFLATED
                zos.putNextEntry(e); zos.write(bytes); zos.closeEntry()
            }
            ZipFile(linkedResApk.get().asFile).use { z ->
                // AndroidManifest.xml first, then everything else from aapt2.
                val entries = z.entries().toList().sortedBy { if (it.name == "AndroidManifest.xml") "" else it.name }
                for (e in entries) {
                    val bytes = z.getInputStream(e).use { it.readBytes() }
                    put(e.name, bytes, store = e.name == "resources.arsc" || e.method == ZipEntry.STORED)
                }
            }
            dexDir.get().asFile.listFiles { f -> f.name.endsWith(".dex") }!!.sortedBy { it.name }.forEach {
                put(it.name, it.readBytes(), store = false)
            }
            val assetRoots = listOfNotNull(assetsDir.takeIf { it.exists() }, generatedAssets.get().asFile)
            for (root in assetRoots) root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                put("assets/$rel", f.readBytes(), store = f.name.endsWith(".tflite"))
            }
            aarDir.get().asFile.walkTopDown().filter { it.isFile && it.path.contains("/jni/") && it.name.endsWith(".so") }
                .sortedBy { it.path }.forEach { so ->
                    val abi = so.parentFile.name
                    if (abi in abis) put("lib/$abi/${so.name}", so.readBytes(), store = false)
                }
        }
    }
}

val alignedApk = layout.buildDirectory.file("intermediates/apk/aligned.apk")
val zipalignApk by tasks.registering(Exec::class) {
    dependsOn(packageApk)
    inputs.file(unsignedApk)
    outputs.file(alignedApk)
    executable = requiredTool("clrg.zipalign").absolutePath
    args("-f", "-p", "4", unsignedApk.get().asFile.absolutePath, alignedApk.get().asFile.absolutePath)
}

// Debug signing only. The debug keystore lives in the user's ~/.android directory (never in the project).
val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
val ensureDebugKeystore by tasks.registering(Exec::class) {
    onlyIf { !debugKeystore.exists() }
    doFirst { debugKeystore.parentFile.mkdirs() }
    executable = File(System.getProperty("java.home"), "bin/keytool").absolutePath
    args(
        "-genkeypair", "-v", "-keystore", debugKeystore.absolutePath, "-storepass", "android",
        "-alias", "androiddebugkey", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US"
    )
}

val finalApk = layout.buildDirectory.file("outputs/apk/LivingRoomGames-debug.apk")
val signApk by tasks.registering(Exec::class) {
    dependsOn(zipalignApk, ensureDebugKeystore)
    inputs.file(alignedApk)
    outputs.file(finalApk)
    doFirst { finalApk.get().asFile.parentFile.mkdirs() }
    executable = requiredTool("clrg.apksigner").absolutePath
    args(
        "sign", "--v1-signing-enabled", "false", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true", "--ks", debugKeystore.absolutePath, "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--ks-key-alias", "androiddebugkey", "--min-sdk-version", minSdk.toString(),
        "--out", finalApk.get().asFile.absolutePath, alignedApk.get().asFile.absolutePath
    )
}

val verifyApk by tasks.registering(Exec::class) {
    dependsOn(signApk)
    executable = requiredTool("clrg.apksigner").absolutePath
    args("verify", "--verbose", "--min-sdk-version", minSdk.toString(), finalApk.get().asFile.absolutePath)
}

/** Offline/privacy checks on the final APK: bundled models and native libraries present, no network permission. */
val verifyApkContents by tasks.registering {
    dependsOn(signApk)
    doLast {
        val apk = finalApk.get().asFile
        val names = ZipFile(apk).use { z -> z.entries().toList().map { it.name } }
        val required = listOf("classes.dex", "AndroidManifest.xml", "resources.arsc") +
            modelSpecs.flatMap { listOf("assets/models/${it.variant}_pose_detector.tflite", "assets/models/${it.variant}_pose_landmarks_detector.tflite") } +
            listOf("assets/models/hand_landmark_lite.tflite") +
            listOf("lib/arm64-v8a/libtensorflowlite_jni.so", "lib/arm64-v8a/libtensorflowlite_gpu_jni.so")
        val missing = required.filter { it !in names }
        if (missing.isNotEmpty()) throw GradleException("APK is missing: $missing")
        val proc = ProcessBuilder(requiredTool("clrg.aapt2").absolutePath, "dump", "badging", apk.absolutePath).redirectErrorStream(true).start()
        val badging = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) throw GradleException("aapt2 dump badging failed: $badging")
        val perms = badging.lines().filter { it.startsWith("uses-permission:") }
        if (perms.any { it.contains("INTERNET") }) throw GradleException("APK must not request INTERNET: $perms")
        logger.lifecycle("APK contents OK: models + native libs bundled; permissions: ${perms.joinToString { it.substringAfter("name='").substringBefore("'") }}")
    }
}

tasks.register("assembleDebugApk") {
    group = "build"
    description = "Builds, aligns, signs (debug key) and verifies the installable APK."
    dependsOn(verifyApk, verifyApkContents)
    doLast { logger.lifecycle("APK: ${finalApk.get().asFile}") }
}
