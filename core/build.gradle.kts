plugins { id("org.jetbrains.kotlin.jvm") }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // Provided at runtime by onnxruntime-android(-qnn) on Android and by the JVM artifact in :bench.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
