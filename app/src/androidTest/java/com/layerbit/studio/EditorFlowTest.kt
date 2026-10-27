package com.layerbit.studio

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.emoji2.text.EmojiCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real app on an emulator: a picture imported through the store appears on the home
 * board, opens in the editor, exports with the measured dimensions, and the source is
 * untouched. Screenshots of each screen are collected by tools/device_checks.sh.
 */
@RunWith(AndroidJUnit4::class)
class EditorFlowTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        val app = ApplicationProvider.getApplicationContext<StudioApplication>()
        val folder = File(app.filesDir, "screenshots").apply { mkdirs() }
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test
    fun homeEditorAndExportAreConnected() {
        val app = ApplicationProvider.getApplicationContext<StudioApplication>()
        assertFalse("Automatic downloadable emoji fonts must be disabled", EmojiCompat.isConfigured())
        app.prompts.introSeen = true
        val fixture = File(app.cacheDir, "ui-fixture.png")
        Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 240) for (x in 0 until 320) setPixel(x, y, Color.rgb(x * 255 / 320, 120, 255 - y))
            fixture.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val original = fixture.readBytes()
        val project = runBlocking { app.store.importProject(Uri.fromFile(fixture)) }

        // Recreate so the home board reads the project list fresh, as a relaunch would.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("openPhotos").assertIsDisplayed().assertHasClickAction()
        screenshot("home")

        compose.onNodeWithTag("homeList").performScrollToNode(hasText(project.name))
        compose.onNodeWithText(project.name).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("editorExport").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        screenshot("editor")

        compose.onNodeWithTag("editorExport").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(app.getString(R.string.export_prepare)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(app.getString(R.string.export_prepare)).performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("exportDimensions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("exportDimensions").assertTextContains("320 × 240", substring = true)
        compose.onNodeWithText(app.getString(R.string.export_save_copy)).assertHasClickAction()
        screenshot("export")

        assertArrayEquals(original, fixture.readBytes())
        assertFalse("Opening the editor must not enable downloadable fonts", EmojiCompat.isConfigured())
    }
}
