plugins {                                          // declares the Gradle plugins THIS module uses
    id("com.android.application")                  // the Android Application plugin -> this module builds an installable APK
    id("org.jetbrains.kotlin.android")             // the Kotlin-for-Android plugin -> lets us write the app in Kotlin
}

android {                                          // all Android-specific build configuration lives in this block
    namespace = "com.example.usbdisplay"           // the package used for the generated R class + BuildConfig; must match the source package
    compileSdk = 34                                // compile against the Android 14 (API 34) SDK (newest APIs available at build time)

    defaultConfig {                                // settings applied to every build variant
        applicationId = "com.example.usbdisplay"   // the unique app ID on the device / Play Store (can differ from namespace; here it's the same)
        minSdk = 29            // 29 = Android 10. KEY_LOW_LATENCY needs 30+, which   // lowest Android version the app will install on
                               // we set conditionally; the S9+ is well above this.
        targetSdk = 34                             // the API level the app is tested against / declares behavior compatibility with
        versionCode = 1                            // internal integer version, must increase with each release
        versionName = "0.1"                        // human-readable version string shown to users
    }

    buildTypes {                                   // configuration per build type (debug/release)
        release {                                  // the release (production) build
            isMinifyEnabled = false                // don't run R8/ProGuard code shrinking yet (keeps the build simple for now)
        }
    }

    compileOptions {                               // Java language settings
        sourceCompatibility = JavaVersion.VERSION_17   // treat the source as Java 17
        targetCompatibility = JavaVersion.VERSION_17   // produce Java 17-compatible bytecode
    }
    kotlinOptions {                                // Kotlin compiler settings
        jvmTarget = "17"                           // emit JVM 17 bytecode from Kotlin too (must match the Java level above)
    }
}

dependencies {                                     // third-party libraries this module needs
    implementation("androidx.core:core-ktx:1.13.1")        // AndroidX core + Kotlin extensions (used for WindowCompat / insets)
    implementation("androidx.appcompat:appcompat:1.7.0")   // AppCompat -> provides AppCompatActivity and the theme we use
}
