// Top-level build file. Declares the plugins the :app module applies.
// NOTE: Android Studio may prompt to update these versions on first sync -
// accepting that is fine and expected.
plugins {                                                            // plugins declared here are made AVAILABLE to sub-modules but not applied to the root
    id("com.android.application") version "8.5.2" apply false        // Android Gradle Plugin 8.5.2; apply false = define the version here, actually apply it in app/
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false  // Kotlin Gradle plugin 1.9.24; same pattern — version pinned here, applied in the app module
}
