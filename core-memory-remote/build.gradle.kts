plugins {
    kotlin("jvm")
    `java-library`
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // The canonical schema, not a copy of it. A remote gateway that redefined memory types would be a
    // second authority by accident.
    api(project(":core-memory"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// The shared golden payloads are mounted as a test resource root, so the boundary is read from the
// repository rather than copied into the module. Loaded through the classloader, never a relative path.
sourceSets["test"].resources.srcDir(rootProject.file("evidence/contracts"))
