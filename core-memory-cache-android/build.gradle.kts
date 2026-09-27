plugins {
    id("com.android.library")
    kotlin("android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.aiwatch.memory.cache"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        minSdk = 28
        // DAO, partition and transaction behaviour needs a real SQLite, so it is verified on an
        // emulator. Fresh-clone CI only assembles this module and does not pretend otherwise.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Room's schema is exported rather than left implicit. A persistence projection that changes without a
// record of the change is how a cache silently starts answering a question the new code did not ask.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // The canonical schema only. This module must not depend on :core-memory-remote: a durable cache
    // that is bound to the HTTP wire format would have to change whenever the transport does, and the
    // two are not the same contract.
    api(project(":core-memory"))
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // kotlin.test's assertions, which take the message last - the opposite of JUnit4's, whose three
    // argument form takes it first. Mixing the two silently compares the wrong pair of values.
    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation("junit:junit:4.13.2")
}
