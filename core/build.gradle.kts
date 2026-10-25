import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

// Pure-Kotlin game, tracking and gesture logic. No Android dependencies, so it is unit-tested on the JVM.
// When an Android SDK is available, main sources compile against android.jar *instead of* the JDK so that
// only APIs that exist on Android can be used (tests still compile and run on the normal JDK).
val androidJar = (rootProject.extra["clrg.androidJar"] as String?)?.let { file(it) }?.takeIf { it.exists() }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

if (androidJar != null) {
    tasks.named<KotlinCompile>("compileKotlin") {
        compilerOptions.noJdk.set(true)
        libraries.from(androidJar)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    // Optional real-model validation (see tools/pose-validation/run_real_model_tests.sh)
    for (k in listOf("clrg.validation.python", "clrg.validation.models", "clrg.validation.scenes", "clrg.cpudiag", "clrg.rallydiag", "clrg.sfxdump", "clrg.uishots")) {
        (project.findProperty(k) as String?)?.let { systemProperty(k, it) }
    }
    testLogging.showStandardStreams = (project.findProperty("clrg.validation.python") != null || project.findProperty("clrg.sfxdump") != null)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
