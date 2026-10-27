package com.layerbit.studio

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.emoji2.text.EmojiCompat
import com.layerbit.studio.core.ImageEngine
import com.layerbit.studio.core.ProjectStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditorFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun screenshot(name:String) {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val folder=File(app.filesDir,"screenshots").apply{mkdirs()}
        val image=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(folder,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    @Test fun settingsHomeAndExportAreConnected() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        assertFalse("Automatic downloadable emoji fonts must be disabled",EmojiCompat.isConfigured())
        app.getSharedPreferences("appearance",0).edit().putBoolean("motion",true).putString("theme","System").commit()
        val f=File(app.cacheDir,"ui-fixture.png")
        Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(41,120,100));f.outputStream().use{compress(Bitmap.CompressFormat.PNG,100,it)};recycle()}
        val original=f.readBytes()
        // Exercise the actual import flow. Activity recreation keeps this ViewModel.
        lateinit var vm:StudioViewModel
        compose.runOnIdle { vm=ViewModelProvider(compose.activity)[StudioViewModel::class.java];vm.import(listOf(Uri.fromFile(f))) }
        compose.waitUntil(20000){vm.state.current!=null && vm.state.projects.isNotEmpty() && vm.state.busy==null}
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithTag("openPhotos").assertIsDisplayed().assertHasClickAction()
        screenshot("home")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("motionSwitch").performClick().assertIsOff()
        compose.onNodeWithText("Light",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("homeList").performScrollToNode(hasText("Open project"))
        compose.onNodeWithText("Open project").performClick()
        compose.waitUntil(20000){compose.onAllNodesWithTag("editorExport").fetchSemanticsNodes().isNotEmpty()}
        screenshot("editor")
        compose.onNodeWithTag("editorExport").performClick()
        compose.onNodeWithTag("prepareOutput").performScrollTo().performClick()
        compose.waitUntil(30000){compose.onAllNodesWithTag("exportDimensions").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("exportDimensions").assertTextContains("320 × 240",substring=true)
        compose.onNodeWithText("Save new copy").assertHasClickAction()
        screenshot("export")
        assertArrayEquals(original,f.readBytes())
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithTag("openPhotos").assertIsDisplayed()
        assertFalse("Opening the editor must not enable downloadable fonts",EmojiCompat.isConfigured())
    }
}
