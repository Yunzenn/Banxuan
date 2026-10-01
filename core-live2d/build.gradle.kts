// Live2D adapter module for the product.
//
// The official Cubism SDK for Java is distributed only as a local archive: there is no Maven Central or
// JitPack artifact for it (verified 2026-09-25 by checking the repositories directly, and by JitPack
// failing to build every Live2D tag). This module therefore consumes the UNMODIFIED SDK tree under
// third_party/live2d/sdk-r5/, which is git-ignored because it contains proprietary binaries and the
// sample models. Nothing proprietary is copied into version control by this file.
//
// The SDK's own Framework/framework/build.gradle cannot be used directly here: it requires compileSdk 36
// (only android-35 is installed) and a Java 17 toolchain (only JDK 21 is present), and neither can be
// fetched offline. The verification harness hit the same wall; see
// evidence/tests/cubism_runtime_smoke/README.md.
plugins {
    id("com.android.library")
    kotlin("android")
}

val cubismSdk = rootProject.file("third_party/live2d/sdk-r5/CubismSdkForJava-5-r.5")
val frameworkMain = File(cubismSdk, "Framework/framework/src/main")
val shaderDir = File(frameworkMain, "assets/com/live2d/sdk/cubism/framework/shaders/standardES")
val expectedShaderCount = 36

android {
    namespace = "com.aiwatch.live2d"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        minSdk = 28
        // This module owns its own Live2D regression instrumentation, so it needs a runner of its own.
        // A library module with androidTest produces a self-instrumenting test APK, which is what keeps
        // the Live2D regression host out of the product app entirely.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs(frameworkMain.resolve("java"))
            // The framework's shaders are loaded AT RUNTIME by CubismShaderAndroid. Shipping only
            // java.srcDirs compiles and packages cleanly and then fails on the device, so these assets are
            // part of the contract, not an optimisation. Official sample models belong to androidTest
            // only; a local product preview receives its model from the app's opt-in build input.
            assets.srcDirs(
                frameworkMain.resolve("assets"),
            )
        }
        getByName("androidTest").assets.srcDir(File(cubismSdk, "Sample/src/main/assets"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val coreAar = File(cubismSdk, "Core/android/Live2DCubismCore.aar")

    // Mirrors Live2D's official CubismJavaFramework: compile against Core, but do not embed the
    // proprietary Core in the AAR this module produces. AGP rejects a library AAR that has a direct
    // local .aar file dependency, which is exactly the constraint upstream works within. The final
    // application packages Core again (CubismJavaSamples does this at the application layer).
    compileOnly(files(coreAar))

    // Instrumentation runs as an APK, so the regression test APK carries Core itself at runtime.
    androidTestImplementation(files(coreAar))

    // Reuse the exact versions the app module already resolves, both of which are in the local cache.
    // Deliberately NOT androidx.test:core / ActivityScenario: that artifact is not available offline and
    // this migration must not introduce a testing dependency just to move a host Activity.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
    // Offline constraint: androidx.test:runner:1.6.2 transitively requests androidx.annotation
    // 1.7.0-beta01, which is not in the local cache. 1.7.0 is, and outranks the beta, so pin it
    // explicitly instead of letting the build depend on network resolution.
    androidTestImplementation("androidx.annotation:annotation:1.7.0")
}

// Shader assertion layer 1 (build-time input): the framework's runtime-loaded shaders must all be
// present before anything is packaged. Layers 2 and 3 are asserted on-device by Phase2B1AAvatarTest.
val verifyCubismShaderAssets by tasks.registering {
    description = "Asserts the official Cubism runtime shader set is complete before packaging."
    val dir = shaderDir
    val expected = expectedShaderCount
    inputs.dir(dir).withPropertyName("cubismStandardESShaders").optional()
    doLast {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        check(files.size == expected) {
            "Cubism shader assertion failed: expected $expected shader files in $dir but found " +
                "${files.size}. An incomplete set compiles and installs fine and then fails at runtime."
        }
        val byExtension = files.groupingBy { it.extension }.eachCount()
        logger.lifecycle("Cubism shaders verified: $expected files $byExtension")
    }
}

tasks.named("preBuild") { dependsOn(verifyCubismShaderAssets) }
