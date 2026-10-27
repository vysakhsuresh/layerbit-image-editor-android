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

// One module of app code, like Sheaf, Deja and Abhyas. The boundaries that matter - engine/
// (pure Kotlin, tested off-device), image/ (the Android renderer), ops/, files/, jobs/, ui/ -
// are enforced by package discipline and by tools/jvm-check.sh, which compiles engine/ with
// no Android classes on the path and fails the moment anything in it imports one.
//
// :baselineprofile holds no app code. It drives the app on a device and writes one text file
// that :app ships; it is a module because it has to start and kill the app as a separate
// process, which is what com.android.test is for.
rootProject.name = "LayerbitStudio"
include(":app")
include(":baselineprofile")
