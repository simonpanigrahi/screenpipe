// settings.gradle.kts — the FIRST file Gradle evaluates. It names the build,
// lists the modules, and declares WHERE plugins and dependencies are downloaded
// from. Without the repositories below, a clean clone can't resolve the Android
// Gradle Plugin (it lives in Google's Maven, not the default plugin portal).

pluginManagement {                  // controls where build PLUGINS (e.g. the Android/Kotlin plugins) are fetched from
    repositories {                  // search these, in order, for plugin artifacts:
        google()                    // Google's Maven — hosts com.android.application (AGP)
        mavenCentral()              // Maven Central — hosts the Kotlin Gradle plugin and most libraries
        gradlePluginPortal()        // Gradle's own plugin portal — fallback for community plugins
    }
}

dependencyResolutionManagement {    // controls where app DEPENDENCIES (the libraries in app/build.gradle.kts) are fetched from
    repositories {                  // search these for androidx.* and other dependencies:
        google()                    // AndroidX / Android libraries live here
        mavenCentral()              // everything else
    }
}

rootProject.name = "UsbDisplayClient"   // the overall Gradle project name (shown in IDEs; placeholder branding, predates the "screenpipe" rename)
include(":app")                         // tell Gradle the build contains one module, ":app" (the android/app/ directory)
