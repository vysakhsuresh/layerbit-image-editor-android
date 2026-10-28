import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Named here and again in :baselineprofile, because the root build file puts AGP and
    // Kotlin on every module's classpath and this plugin is not one of them.
    id("androidx.baselineprofile") version "1.5.0"
}

// Release signing credentials live only in a local, git-ignored keystore.properties (see
// keystore.properties.example) - never hardcoded here and never committed.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystoreProperties = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasKeystoreProperties) load(keystorePropertiesFile.inputStream())
}
// Release candidates from CI are signed with release/rc-testing.jks, a key that exists only so a
// tester can install an optimised build and update it. It is not the Play key: the Play build
// is signed by the owner with keystore.properties (see docs/RELEASE.md). On only when asked.
val rcSigning = !hasKeystoreProperties && System.getenv("STILL_RC_SIGNING") == "1"

android {
    namespace = "com.layerbit.still"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.layerbit.still"
        // The family floor Deja, Abhyas and Sheaf established. Nothing in the editor needs a
        // newer platform: the photo picker is backported through androidx.activity, WebP
        // lossless is offered only from 30, and everything else is android.graphics.
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.0-rc.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasKeystoreProperties) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
        if (rcSigning) {
            create("rc") {
                storeFile = rootProject.file("release/rc-testing.jks")
                storePassword = "still-rc-testing"
                keyAlias = "rc"
                keyPassword = "still-rc-testing"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // No Resources.getIdentifier anywhere, so nothing in res/ is reached by a name the
            // shrinker cannot see.
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystoreProperties) signingConfig = signingConfigs.getByName("release")
            else if (rcSigning) signingConfig = signingConfigs.getByName("rc")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // The About screen shows the version it is actually running.
        buildConfig = true
    }

    // Writes the locale list Android 13+ shows under Settings > Apps > Still >
    // Language, from the values-xx folders that exist. English is the unqualified default.
    androidResources {
        generateLocaleConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Warnings that exist today are recorded in lint-baseline.xml, so lint reports only what
    // is new. Fatal issues still fail lintVitalRelease before every release build.
    lint {
        baseline = file("lint-baseline.xml")
        checkReleaseBuilds = true
        abortOnError = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The baseline profile plugin derives nonMinifiedRelease and benchmarkRelease from release
// with initWith, which copies the signing config across - and release has none unless
// keystore.properties exists. Anyone without the release key would get two variants that build
// an APK no device will install, and generating a profile is the one thing they are for.
androidComponents {
    finalizeDsl { app ->
        val debugSigning = app.signingConfigs.getByName("debug")
        app.buildTypes.forEach { buildType ->
            val generated = buildType.name.startsWith("nonMinified") || buildType.name.startsWith("benchmark")
            if (!generated) return@forEach
            buildType.signingConfig = debugSigning
            if (!buildType.isMinifyEnabled) buildType.isShrinkResources = false
        }
    }
}

// The profile is a text file checked in beside the sources and packaged into the APK. It is
// regenerated by a deliberate device run and never by a release build.
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    mergeIntoMain = true
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")

    // Carries the baseline profile into ART's own profile shortly after the first launch.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Orientation, and the handful of camera fields a user may choose to keep. GPS never.
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    // Display names and folder writes through a SAF tree the user granted.
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Already present through Compose; explicit so the manifest override that stops Emoji2
    // asking an external font provider for downloads has a declared target.
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("androidx.emoji2:emoji2:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    baselineProfile(project(":baselineprofile"))
}

// DEPENDENCIES DELIBERATELY NOT HERE
//
// No image-loading library: Coil and Glide are built around fetching, and this app has nothing
// to fetch. Thumbnails come from BitmapFactory with a sample size, through ImageStore's cache.
// No processing SDK and no ML model: every tool is android.graphics plus the pure engine in
// engine/. A neural tool arrives, if it ever does, with its licence, size, memory and accuracy
// measured on a fixed corpus first - see docs/ARCHITECTURE.md.
