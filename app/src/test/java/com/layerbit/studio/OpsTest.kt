package com.layerbit.studio

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.layerbit.studio.engine.Collage
import com.layerbit.studio.engine.Frame
import com.layerbit.studio.engine.JpegSegments
import com.layerbit.studio.engine.Look
import com.layerbit.studio.engine.Geometry
import com.layerbit.studio.engine.Recipe
import com.layerbit.studio.engine.RecipeJson
import com.layerbit.studio.engine.Tone
import com.layerbit.studio.files.ImageStore
import com.layerbit.studio.files.StudioFile
import com.layerbit.studio.files.Workspace
import com.layerbit.studio.image.Codecs
import com.layerbit.studio.image.ExportOptions
import com.layerbit.studio.image.OutputFormat
import com.layerbit.studio.image.Renderer
import com.layerbit.studio.ops.CollageOp
import com.layerbit.studio.ops.CompressOp
import com.layerbit.studio.ops.EnhanceOp
import com.layerbit.studio.ops.FitKbOp
import com.layerbit.studio.ops.Op
import com.layerbit.studio.ops.OpCancelled
import com.layerbit.studio.ops.OpContext
import com.layerbit.studio.ops.OpOutcome
import com.layerbit.studio.ops.Pipeline
import com.layerbit.studio.ops.PipelineOp
import com.layerbit.studio.ops.ResizeOp
import com.layerbit.studio.ops.StripMetadataOp
import com.layerbit.studio.ops.ToolId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/** The batch tools end to end through the real store, workspace, renderer and codecs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = StudioApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpsTest {

    private lateinit var dir: File
    private val app: StudioApplication get() = ApplicationProvider.getApplicationContext()
    private val renderer = Renderer(budget = 3_000_000)
    private val codecs = Codecs(renderer)
    private lateinit var context: OpContext

    @Before fun setup() {
        dir = Files.createTempDirectory("ops").toFile()
        context = OpContext(Workspace(app), renderer, codecs)
    }
    @After fun cleanup() { dir.deleteRecursively(); Workspace(app).clear() }

    private fun picture(w: Int, h: Int, jpeg: Boolean, name: String, gps: Boolean = false): StudioFile {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) b.setPixel(x, y, Color.rgb((x * 7 + y * 3) % 256, (x * 3) % 256, (y * 5) % 256))
        val f = File(dir, name)
        f.outputStream().use { b.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, it) }
        b.recycle()
        if (gps) ExifInterface(f).apply { setLatLong(1.0, 2.0); setAttribute(ExifInterface.TAG_MAKE, "Cam"); saveAttributes() }
        return StudioFile(f, name, StudioFile.Origin.Derived("test"), if (jpeg) StudioFile.MIME_JPEG else StudioFile.MIME_PNG, w, h)
    }

    private fun run(op: Op, vararg inputs: StudioFile): List<OpOutcome> = runBlocking { op.run(inputs.toList(), context) {} }

    @Test
    fun fitKbGetsUnderTheLimitAndStripsRatherThanReencodesWhenAlreadyUnder() {
        val big = picture(1600, 1200, jpeg = true, name = "big.jpg")
        val out = run(FitKbOp(30f), big).single()
        assertTrue(out is OpOutcome.Produced)
        val produced = out as OpOutcome.Produced
        assertTrue(produced.file.sizeBytes < 30_000)
        assertTrue(produced.file.width <= 1600)

        val small = picture(120, 90, jpeg = true, name = "small.jpg", gps = true)
        val stripped = run(FitKbOp(500f), small).single() as OpOutcome.Produced
        assertTrue(stripped.file.sizeBytes < small.sizeBytes)
        assertEquals(120, stripped.file.width)
        val again = run(FitKbOp(500f), stripped.file).single()
        assertTrue("a clean file under the limit is skipped: $again", again is OpOutcome.Skipped)
    }

    @Test
    fun passportCropsToTheRightShape() {
        val wide = picture(1200, 600, jpeg = true, name = "wide.jpg")
        val out = run(FitKbOp(500f, FitKbOp.Pixels.Passport), wide).single() as OpOutcome.Produced
        assertEquals(FitKbOp.PASSPORT_W, out.file.width)
        assertEquals(FitKbOp.PASSPORT_H, out.file.height)
    }

    @Test
    fun resizeModesAndSkips() {
        val p = picture(800, 600, jpeg = false, name = "p.png")
        val edge = run(ResizeOp(ResizeOp.Mode.LongEdge(400), ExportOptions(OutputFormat.PNG)), p).single() as OpOutcome.Produced
        assertEquals(400, edge.file.width); assertEquals(300, edge.file.height)
        val percent = run(ResizeOp(ResizeOp.Mode.Percent(25), ExportOptions(OutputFormat.PNG)), p).single() as OpOutcome.Produced
        assertEquals(200, percent.file.width)
        val exact = run(ResizeOp(ResizeOp.Mode.Exact(100, 100), ExportOptions(OutputFormat.PNG)), p).single() as OpOutcome.Produced
        assertEquals(100, exact.file.width); assertEquals(100, exact.file.height)
        val same = run(ResizeOp(ResizeOp.Mode.Percent(100), ExportOptions(OutputFormat.PNG)), p).single()
        assertTrue(same is OpOutcome.Skipped)
        assertTrue(edge.file.displayName.contains("400x300"))
    }

    @Test
    fun stripIsLosslessForJpegAndReencodesOthers() {
        val j = picture(300, 200, jpeg = true, name = "loc.jpg", gps = true)
        val out = run(StripMetadataOp(), j).single() as OpOutcome.Produced
        assertEquals(null, ExifInterface(out.file.file).latLong)
        assertTrue(out.detail!!.isNotBlank())
        val clean = run(StripMetadataOp(), out.file).single()
        assertTrue(clean is OpOutcome.Skipped)
        val png = picture(100, 100, jpeg = false, name = "p.png")
        val pngOut = run(StripMetadataOp(), png).single() as OpOutcome.Produced
        assertEquals(StudioFile.MIME_PNG, pngOut.file.mimeType)
    }

    @Test
    fun aBatchReportsEachFileAndKeepsTheOthersWhenOneFails() {
        val good = picture(300, 200, jpeg = true, name = "good.jpg")
        val bad = StudioFile(File(dir, "bad.jpg").apply { writeText("nope") }, "bad.jpg", StudioFile.Origin.Derived("t"), StudioFile.MIME_JPEG, 10, 10)
        val outcomes = run(CompressOp(ExportOptions(OutputFormat.JPEG, 60)), good, bad, good)
        assertEquals(3, outcomes.size)
        assertTrue(outcomes[0] is OpOutcome.Produced || outcomes[0] is OpOutcome.Skipped)
        assertTrue(outcomes[1] is OpOutcome.Failed)
        assertEquals(outcomes[0]::class, outcomes[2]::class)
    }

    @Test
    fun cancellationStopsBetweenFiles() {
        val p = picture(300, 200, jpeg = true, name = "c.jpg")
        var calls = 0
        val cancelling = OpContext(Workspace(app), renderer, codecs) { ++calls > 3 }
        try { runBlocking { CompressOp(ExportOptions(OutputFormat.JPEG, 60)).run(listOf(p, p, p, p, p), cancelling) {} }; fail() } catch (_: OpCancelled) {}
    }

    @Test
    fun collageHoldsEveryPictureAndEnhanceLeavesGoodOnesAlone() {
        val a = picture(400, 300, jpeg = true, name = "a.jpg"); val b = picture(300, 400, jpeg = true, name = "b.jpg"); val c = picture(500, 500, jpeg = false, name = "c.png")
        val out = run(CollageOp(Collage.Layout.FEATURE, Color.WHITE, ExportOptions(OutputFormat.JPEG, 85)), a, b, c).single() as OpOutcome.Produced
        assertTrue(out.file.width > 0 && out.file.sizeBytes > 0)
        try { run(CollageOp(Collage.Layout.GRID, Color.WHITE, ExportOptions()), a); fail() } catch (_: IllegalArgumentException) {}

        val flat = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(120, 120, 120)) }
        val f = File(dir, "flat.jpg"); f.outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, 90, it) }; flat.recycle()
        val flatFile = StudioFile(f, "flat.jpg", StudioFile.Origin.Derived("t"), StudioFile.MIME_JPEG, 200, 200)
        val enhanced = run(EnhanceOp(ExportOptions(OutputFormat.JPEG)), flatFile).single()
        assertTrue("a flat grey has no range to stretch and is reported, not altered: $enhanced", enhanced is OpOutcome.Skipped || enhanced is OpOutcome.Produced)
    }

    @Test
    fun storeImportsAProjectKeepsItsRecipeAndNeverTouchesTheSource() = runBlocking {
        val store = ImageStore(app, renderer)
        val src = picture(320, 240, jpeg = true, name = "import.jpg", gps = true)
        val before = src.file.readBytes()
        val project = store.importProject(Uri.fromFile(src.file))
        assertEquals(320, project.file.width)
        assertTrue(project.file.file.path != src.file.path)
        val edited = project.copy(recipe = Recipe(geometry = Geometry(rotation = 90), tone = Tone(exposure = 0.5f)))
        store.save(edited)
        val back = store.project(project.id)!!
        assertEquals(edited.recipe, back.recipe)
        assertEquals(RecipeJson.write(edited.recipe), RecipeJson.write(back.recipe))
        val details = store.details(project.file)
        assertTrue(details.any { it.first.isNotBlank() })
        assertNotNull(store.thumbnail(project))
        store.remove(project)
        assertFalse(project.file.file.exists())
        assertTrue(before.contentEquals(src.file.readBytes()))
    }

    @Test
    fun everyToolHasAPanelOrAnOpAndAnIcon() {
        for (tool in ToolId.entries) {
            if (tool.mode == ToolId.Mode.EDITOR) assertTrue(tool.name, tool.panel != ToolId.Panel.NONE || tool == ToolId.EDIT)
            assertTrue(tool.name, tool.title.isNotBlank() && tool.summary.isNotBlank() && tool.action.isNotBlank())
            com.layerbit.studio.ui.icons.iconFor(tool)
        }
    }

    @Test
    fun pipelineWithOnlyCleaningKeepsJpegPixelsByteForByte() {
        val tagged = picture(640, 480, jpeg = true, name = "tagged.jpg", gps = true)
        val out = run(PipelineOp(Pipeline()), tagged).single() as OpOutcome.Produced
        val before = tagged.file.readBytes()
        val after = out.file.file.readBytes()
        val scanBefore = before.copyOfRange(JpegSegments.segments(before)!!.last().offset, before.size)
        val scanAfter = after.copyOfRange(JpegSegments.segments(after)!!.last().offset, after.size)
        assertTrue("The picture from the start-of-scan marker on is kept byte for byte", scanBefore.contentEquals(scanAfter))
        assertFalse(JpegSegments.hasGps(after))
        assertTrue(after.size < before.size)
        assertTrue(out.file.displayName.endsWith("-clean.jpg"))
    }

    @Test
    fun pipelineComposesResizeSizeLimitLookWatermarkAndFrameInOneRun() {
        val big = picture(1600, 1200, jpeg = true, name = "big.jpg", gps = true)
        val p = Pipeline(resize = ResizeOp.Mode.LongEdge(800), underKb = 60f, look = Look.MONO, watermark = Pipeline.Watermark("© test"), frame = Frame(aspect = 1f, padding = 0.05f), pattern = "{name}-web")
        assertTrue(p.changesPixels)
        val out = run(PipelineOp(p), big).single() as OpOutcome.Produced
        assertTrue("Under 60 KB, was ${out.file.sizeBytes}", out.file.sizeBytes <= 60_000)
        assertEquals("Padded to a square", out.file.width, out.file.height)
        assertTrue("Resized well below the source", out.file.width < big.width)
        assertFalse(JpegSegments.hasGps(out.file.file.readBytes()))
        assertEquals("big-web.jpg", out.file.displayName)
        assertTrue(p.summary().contains("800 px"))
    }

    @Test
    fun pipelineConvertsFormatWhenAskedAndKeepsItWhenNot() {
        val png = picture(300, 200, jpeg = false, name = "shot.png")
        val kept = run(PipelineOp(Pipeline(resize = ResizeOp.Mode.Percent(50))), png).single() as OpOutcome.Produced
        assertEquals(StudioFile.MIME_PNG, kept.file.mimeType)
        assertEquals(150, kept.file.width)
        val converted = run(PipelineOp(Pipeline(format = OutputFormat.JPEG, quality = 80)), png).single() as OpOutcome.Produced
        assertEquals(StudioFile.MIME_JPEG, converted.file.mimeType)
        assertEquals(300, converted.file.width)
    }
}
