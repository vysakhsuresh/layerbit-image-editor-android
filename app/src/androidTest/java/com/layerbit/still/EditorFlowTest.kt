package com.layerbit.still

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.layerbit.still.prefs.ThemeChoice
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
        val app = ApplicationProvider.getApplicationContext<StillApplication>()
        val folder = File(app.filesDir, "screenshots").apply { mkdirs() }
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    /** A screen's own node, then a settled frame: a click starts a transition the idle check does not cover. */
    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(10_000, condition)
        settle()
    }

    /** Thumbnails and the first render arrive from IO after the composition is idle. */
    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(1_200)
        compose.waitForIdle()
    }

    /**
     * The walk invokes each control's own onClick through semantics. Injected touches right after
     * a recreate or a programmatic scroll were dropped on the emulator; the flow test above keeps
     * real touches as the proof that they work.
     */
    private fun SemanticsNodeInteraction.tap() { performSemanticsAction(SemanticsActions.OnClick) }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun homeEditorAndExportAreConnected() {
        val app = ApplicationProvider.getApplicationContext<StillApplication>()
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

    /**
     * Every screen once, for the listing and for a look at the build: intro, home in both
     * themes, the batch pipeline with steps on and its results, the editor on two panels,
     * settings and about. Nothing is asserted beyond the walk completing.
     */
    @Test
    fun everyScreenOnce() {
        val app = ApplicationProvider.getApplicationContext<StillApplication>()
        val fixtures = (1..3).map { i ->
            File(app.cacheDir, "walk-$i.jpg").also { f ->
                Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply {
                    for (y in 0 until 480) for (x in 0 until 640) setPixel(x, y, Color.rgb((x * 255 / 640 + i * 40) % 256, (y * 255 / 480), (255 - x * 255 / 640 + i * 60) % 256))
                    f.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    recycle()
                }
            }
        }
        val project = runBlocking { app.store.importProject(Uri.fromFile(fixtures[0])) }
        app.settings.update { it.copy(theme = ThemeChoice.DARK) }
        app.prompts.introSeen = false
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        screenshot("01-intro")
        compose.onNodeWithText(app.getString(R.string.intro_skip)).tap()
        waitFor { compose.onAllNodesWithTag("openPhotos").fetchSemanticsNodes().isNotEmpty() }
        screenshot("02-home")

        compose.onNodeWithTag("homeList").performScrollToNode(hasText(app.getString(R.string.home_door_batch)))
        compose.onNodeWithText(app.getString(R.string.home_door_batch)).tap()
        // The run button is the list's last item and not composed until scrolled to; the list itself is the screen.
        waitFor { compose.onAllNodesWithTag("pipelineList").fetchSemanticsNodes().isNotEmpty() }
        screenshot("03-batch-empty")
        // The picker cannot be driven from a test; the handoff is how a share arrives, and it is the same path.
        app.handoff.offer(fixtures.map { Uri.fromFile(it) })
        back()
        compose.onNodeWithTag("homeList").performScrollToNode(hasText(app.getString(R.string.home_door_batch)))
        compose.onNodeWithText(app.getString(R.string.home_door_batch)).tap()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(app.getString(R.string.tools_clear_all)).fetchSemanticsNodes().isNotEmpty() }
        for (step in listOf("resize", "under", "frame")) {
            compose.onNodeWithTag("pipelineList").performScrollToNode(hasTestTag("step-$step"))
            compose.onNodeWithTag("step-$step").tap()
        }
        compose.onNodeWithTag("pipelineList").performScrollToNode(hasTestTag("step-under"))
        compose.waitForIdle()
        screenshot("04-batch-steps")
        // The run button floats over the list, so it needs no scrolling to.
        compose.onNodeWithTag("pipelineRun").tap()
        compose.waitUntil(60_000) { compose.onAllNodesWithText(app.getString(R.string.tools_start_again)).fetchSemanticsNodes().isNotEmpty() }
        settle()
        screenshot("05-batch-done")
        back()
        compose.waitForIdle()

        compose.onNodeWithTag("homeList").performScrollToNode(hasText(project.name))
        compose.onNodeWithText(project.name).tap()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("640 × 480").fetchSemanticsNodes().isNotEmpty() }
        settle()
        screenshot("06-editor-crop")
        compose.onNodeWithTag("panelStrip").performScrollToNode(hasContentDescription(app.getString(R.string.panel_light)))
        compose.onNodeWithContentDescription(app.getString(R.string.panel_light)).tap()
        waitFor { compose.onAllNodesWithText(app.getString(R.string.light_auto)).fetchSemanticsNodes().isNotEmpty() }
        settle()
        screenshot("07-editor-light")
        compose.onNodeWithTag("panelStrip").performScrollToNode(hasContentDescription(app.getString(R.string.panel_looks)))
        compose.onNodeWithContentDescription(app.getString(R.string.panel_looks)).tap()
        waitFor { compose.onAllNodesWithText(app.getString(R.string.look_sepia)).fetchSemanticsNodes().isNotEmpty() }
        // The strip shows every look on the picture itself; the thumbnails are rendered after the panel opens.
        waitFor { compose.onAllNodesWithTag("lookThumb", useUnmergedTree = true).fetchSemanticsNodes().size >= 5 }
        settle()
        screenshot("08-editor-looks")
        compose.onNodeWithTag("panelStrip").performScrollToNode(hasContentDescription(app.getString(R.string.panel_cutout)))
        compose.onNodeWithContentDescription(app.getString(R.string.panel_cutout)).tap()
        waitFor { compose.onAllNodesWithText(app.getString(R.string.cutout_remove_it)).fetchSemanticsNodes().isNotEmpty() }
        settle()
        screenshot("12-editor-cutout")
        compose.onNodeWithTag("panelStrip").performScrollToNode(hasContentDescription(app.getString(R.string.panel_scan)))
        compose.onNodeWithContentDescription(app.getString(R.string.panel_scan)).tap()
        waitFor { compose.onAllNodesWithText(app.getString(R.string.scan_whiten)).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText(app.getString(R.string.scan_off)).fetchSemanticsNodes().isNotEmpty() }
        screenshot("13-editor-scan")
        back()
        compose.waitForIdle()

        compose.onNodeWithTag("homeList").performScrollToNode(hasContentDescription(app.getString(R.string.app_settings)))
        compose.onNodeWithContentDescription(app.getString(R.string.app_settings)).tap()
        waitFor { compose.onAllNodesWithText(app.getString(R.string.settings_appearance)).fetchSemanticsNodes().isNotEmpty() }
        screenshot("09-settings")
        back()
        compose.waitForIdle()
        compose.onNodeWithTag("homeList").performScrollToNode(hasContentDescription(app.getString(R.string.app_about)))
        compose.onNodeWithContentDescription(app.getString(R.string.app_about)).tap()
        waitFor { compose.onAllNodesWithText(app.getString(R.string.about_title)).fetchSemanticsNodes().isNotEmpty() }
        screenshot("10-about")
        back()
        compose.waitForIdle()

        compose.onNodeWithTag("homeList").performScrollToNode(hasTestTag("quickTools"))
        settle()
        screenshot("14-home-quick")
        compose.onNodeWithTag("homeList").performScrollToNode(hasText(app.getString(R.string.home_door_tools)))
        compose.onNodeWithText(app.getString(R.string.home_door_tools)).tap()
        waitFor { compose.onAllNodesWithTag("toolsList").fetchSemanticsNodes().isNotEmpty() }
        screenshot("15-tools")
        back()
        compose.onNodeWithTag("homeList").performScrollToNode(hasText(app.getString(R.string.guide_title)))
        compose.onNodeWithText(app.getString(R.string.guide_title)).tap()
        waitFor { compose.onAllNodesWithTag("guideList").fetchSemanticsNodes().isNotEmpty() }
        screenshot("16-guide")
        back()
        compose.onNodeWithTag("homeList").performScrollToNode(hasText(app.getString(R.string.contact_title)))
        compose.onNodeWithText(app.getString(R.string.contact_title)).tap()
        waitFor { compose.onAllNodesWithTag("contactList").fetchSemanticsNodes().isNotEmpty() }
        screenshot("17-contact")
        back()
        compose.onNodeWithTag("homeList").performScrollToNode(hasTestTag("openPhotos"))
        app.settings.update { it.copy(theme = ThemeChoice.LIGHT) }
        settle()
        screenshot("11-home-light")
        app.settings.update { it.copy(theme = ThemeChoice.DARK) }
    }
}
