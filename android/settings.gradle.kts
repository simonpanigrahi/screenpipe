// Names the Gradle project and includes the single :app module.
rootProject.name = "UsbDisplayClient"   // the overall Gradle project name (shown in IDEs; placeholder branding, predates the "screenpipe" rename)
include(":app")                         // tell Gradle the build contains one module, ":app" (the android/app/ directory)
