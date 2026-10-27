package com.layerbit.still.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes the baseline profile that ships inside the release APK.
 *
 * Run with `./gradlew :app:generateBaselineProfile` against a connected device on API 28 or
 * newer, then commit what lands in `app/src/main/generated/baselineProfiles`. Never part of a
 * release build: a build that needs a phone attached is a build nobody can make.
 *
 * The journey stops at the first idle frame of the home board, which is the cold path every
 * launch pays. Whether the profile measurably helps is answered by [StartupBenchmark], not
 * assumed; an unmeasured performance claim is a hope.
 */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = APP_PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
    }

    private companion object {
        const val APP_PACKAGE = "com.layerbit.still"
    }
}

/**
 * Cold start with and without the profile, ten iterations each. The difference between the two
 * medians of timeToInitialDisplayMs is the profile's worth on that device, in milliseconds, and
 * belongs in VALIDATION.md.
 *
 *     ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
 *         -P android.testInstrumentationRunnerArguments.class=com.layerbit.still.baselineprofile.StartupBenchmark
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupNoCompilation() = measure(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() = measure(CompilationMode.Partial())

    private fun measure(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = "com.layerbit.still",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = 10
    ) {
        pressHome()
        startActivityAndWait()
    }
}
