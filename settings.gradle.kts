pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AIWatchProbe"
include(":app", ":core-protocol", ":core-audio", ":core-memory", ":core-memory-remote")

// The Cubism SDK may not be redistributed, so :core-live2d only exists where the SDK does. Test the SDK
// root itself rather than its parent: a leftover empty third_party/live2d would otherwise include a
// module that cannot compile.
val cubismSdkRoot = file("third_party/live2d/sdk-r5/CubismSdkForJava-5-r.5")
if (cubismSdkRoot.isDirectory) {
    include(":core-live2d")
}
