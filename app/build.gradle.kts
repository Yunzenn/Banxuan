plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.aiwatch.probe"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.aiwatch.probe"
        minSdk = 28
        targetSdk = 35
        versionCode = providers.gradleProperty("VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.all {
        it.systemProperty("w4.classesDir", layout.buildDirectory.dir("tmp/kotlin-classes/debug").get().asFile.absolutePath)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    // Declared explicitly rather than relying on androidTestImplementation extending implementation:
    // the memory trust instrumentation test drives the real :core-memory types.
    androidTestImplementation(project(":core-memory"))
    // The composition layer is pure logic over two interfaces, so its contract is verified in fast JVM
    // unit tests rather than on an emulator. The cache module is an `implementation` dependency, so Room
    // itself stays off this module's compile classpath.
    testImplementation(project(":core-memory-cache-android"))
    testImplementation(kotlin("test"))
    // W4 structural guards inspect compiled production bytecode, including coroutine classes.
    // Test-only; BSD-3-Clause. Never packaged in the APK.
    testImplementation("org.ow2.asm:asm:9.6")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(project(":core-protocol"))
    implementation(project(":core-audio"))
    implementation(project(":core-memory"))
    implementation(project(":core-memory-cache-android"))
    // Live2D adapter: official Cubism framework as a Gradle module + local Core AAR. DEV-ONLY (P2B-1A).
}
