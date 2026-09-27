plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    kotlin("android") version "2.1.0" apply false
    kotlin("jvm") version "2.1.0" apply false
    // Room's annotation processor. KSP rather than kapt: it is the supported path for Kotlin 2.x, and
    // the version is pinned to the Kotlin version rather than chosen freely.
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}
