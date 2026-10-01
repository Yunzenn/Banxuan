plugins {
    id("com.android.application")
    kotlin("android")
}

// Explicit local-only build. Public/CI builds neither resolve Core nor package a user's model.
val localLive2d = providers.gradleProperty("localLive2d").orNull == "true"
val modelRoot = if (localLive2d) file(providers.gradleProperty("live2dModelDir").get()) else null
val modelEntry = modelRoot?.listFiles()?.filter { it.name.endsWith(".model3.json") }?.singleOrNull()
if (localLive2d) {
    check(modelEntry != null) { "live2dModelDir must contain exactly one model3.json" }
    check(project.findProject(":core-live2d") != null) { "Official local Cubism R5 SDK required" }
}

android {
    namespace = "com.aiwatch.probe"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.aiwatch.probe"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (localLive2d) applicationId = "com.aiwatch.probe.live2ddev"
    }

    buildFeatures { buildConfig = true }
    defaultConfig.buildConfigField("String", "LOCAL_MODEL_JSON", "\"${modelEntry?.name ?: ""}\"")
    sourceSets.getByName("main").java.srcDir(if (localLive2d) "src/localLive2d/kotlin" else "src/staticAvatar/kotlin")
    if (localLive2d) {
        sourceSets.getByName("androidTest").java.srcDir("src/localLive2dTest/kotlin")
        sourceSets.getByName("debug").manifest.srcFile("src/localLive2d/AndroidManifest.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.all {
        it.systemProperty("w4.classesDir", layout.buildDirectory.dir("tmp/kotlin-classes/debug").get().asFile.absolutePath)
    }
}

androidComponents {
    beforeVariants(selector().all()) { if (localLive2d && it.buildType != "debug") it.enable = false }
}

if (localLive2d) {
    val stageLocalModel by tasks.registering(Sync::class) {
        from(requireNotNull(modelRoot)) { into("local-model") }
        into(layout.buildDirectory.dir("generated/localLive2dAssets"))
        // No preview PNG, unrelated archives, or sample models. This directory is build output only.
        include("**/*.model3.json", "**/*.moc3", "**/*.physics3.json", "**/*.pose3.json",
            "**/*.motion3.json", "**/*.exp3.json", "**/texture*.png")
    }
    android.sourceSets.getByName("main").assets.srcDir(stageLocalModel.map { it.destinationDir })
    tasks.named("preBuild") { dependsOn(stageLocalModel) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    if (localLive2d) {
        implementation(project(":core-live2d"))
        implementation(files(rootProject.file("third_party/live2d/sdk-r5/CubismSdkForJava-5-r.5/Core/android/Live2DCubismCore.aar")))
    }
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
