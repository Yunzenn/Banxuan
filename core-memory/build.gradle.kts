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

// The shared cross-language contract lives with the evidence rather than being copied into each
// language's test tree, so both sides read one file and cannot drift apart.

sourceSets["test"].resources.srcDir(rootProject.file("evidence/contracts"))

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation(kotlin("test"))
    // Test-only JSON parser for the shared contract file. Gson is already resolvable offline because
    // :core-protocol depends on it. Deliberately testImplementation and never api/implementation: the
    // module's published API and its production dependency set are unchanged.
    testImplementation("com.google.code.gson:gson:2.10.1")
}

tasks.test {
    useJUnitPlatform()
}
