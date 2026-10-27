package com.layerbit.still

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import com.layerbit.still.engine.Crop
import com.layerbit.still.engine.Curves
import com.layerbit.still.engine.Backdrop
import com.layerbit.still.engine.Cutout
import com.layerbit.still.engine.CutoutMode
import com.layerbit.still.engine.Frame
import com.layerbit.still.engine.Scan
import com.layerbit.still.engine.ScanMode
import com.layerbit.still.engine.Scribble
import com.layerbit.still.engine.Geometry
import com.layerbit.still.engine.JpegSegments
import com.layerbit.still.engine.Look
import com.layerbit.still.engine.Mark
import com.layerbit.still.engine.MarkKind
import com.layerbit.still.engine.Pt
import com.layerbit.still.engine.Quad
import com.layerbit.still.engine.Recipe
import com.layerbit.still.engine.Tone
import com.layerbit.still.image.Codecs
import com.layerbit.still.image.ExportOptions
import com.layerbit.still.image.OutputFormat
import com.layerbit.still.image.Renderer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.security.MessageDigest
import java.util.Random
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Android half of the pipeline against real codecs. The engine's arithmetic is covered on
 * the JVM; these prove the glue: decode, EXIF, geometry, encode, measure, strip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = StillApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RendererTest {

    private lateinit var dir: File
    private val renderer = Renderer(budget = 2_000_000)
    private val codecs = Codecs(renderer)

    @Before fun setup() { dir = Files.createTempDirectory("still").toFile() }
    @After fun cleanup() { dir.deleteRecursively() }

    /** A smooth scene with an edge and a gradient, so tone and detail have something to act on. */
    private fun scene(w: Int, h: Int, jpeg: Boolean = false, name: String = "scene"): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rng = Random(11)
        for (y in 0 until h) for (x in 0 until w) {
            val gx = x * 255 / w; val gy = y * 255 / h
            val edge = if (x > w / 2) 60 else 0
            val n = rng.nextInt(12)
            b.setPixel(x, y, Color.rgb((gx + n).coerceIn(0, 255), (gy + edge).coerceIn(0, 255), ((255 - gx) / 2 + edge + n).coerceIn(0, 255)))
        }
        val f = File(dir, if (jpeg) "$name.jpg" else "$name.png")
        f.outputStream().use { b.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, it) }
        b.recycle()
        return f
    }

    private fun hash(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).toList()

    private fun export(src: File, r: Recipe, o: ExportOptions, out: File) = runBlocking {
        val bitmap = renderer.render(src, r)
        try { codecs.export(bitmap, o, out, src, scaledFromSource = false) } finally { bitmap.recycle() }
    }

    @Test
    fun exportLeavesTheSourceByteForByteAndHonoursGeometry() {
        val src = scene(160, 100)
        val before = hash(src)
        val r = Recipe(geometry = Geometry(rotation = 90, crop = Crop(0f, 0f, 1f, 0.5f), width = 40, height = 50))
        val e = export(src, r, ExportOptions(OutputFormat.PNG), File(dir, "out.png"))
        assertEquals(40, e.width); assertEquals(50, e.height)
        assertEquals(before, hash(src))
        assertTrue(e.bytes > 0 && e.file.length() == e.bytes)
    }

    @Test
    fun orientationIsAppliedOnceAndLocationIsNeverCopied() {
        val src = scene(160, 100, jpeg = true)
        ExifInterface(src).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); setAttribute(ExifInterface.TAG_MAKE, "Test camera"); setLatLong(25.2, 55.3); saveAttributes() }
        val upright = renderer.decode(src, 2_000_000)
        assertEquals(100, upright.width); assertEquals(160, upright.height); upright.recycle()
        val out = File(dir, "export.jpg")
        export(src, Recipe(), ExportOptions(OutputFormat.JPEG, keepCameraFields = true), out)
        val exif = ExifInterface(out)
        assertNull(exif.latLong)
        assertEquals("Test camera", exif.getAttribute(ExifInterface.TAG_MAKE))
        val again = renderer.decode(out, 2_000_000)
        assertEquals(100, again.width); assertEquals(160, again.height); again.recycle()
    }

    @Test
    fun previewPredictsExport() = runBlocking {
        // The same recipe at preview size and at export size must agree once the export is
        // downsampled: the property every slider in the editor depends on.
        val src = scene(1200, 800)
        val r = Recipe(
            tone = Tone(exposure = 0.3f, contrast = 0.4f, shadows = 0.3f, saturation = 0.2f, temperature = 0.3f, sharpness = 0.8f, clarity = 0.5f, vignette = 0.6f, fade = 0.2f),
            curves = Curves(master = listOf(Pt(0f, 0f), Pt(0.4f, 0.5f), Pt(1f, 1f))),
            look = Look.TEAL_ORANGE, lookStrength = 0.7f
        )
        val small = renderer.render(src, r, maxPixels = 300 * 200)
        val large = renderer.render(src, r, maxPixels = 1200 * 800)
        assertEquals(300, small.width); assertEquals(1200, large.width)
        val shrunk = renderer.scale(large, small.width, small.height)
        val diff = renderer.difference(small, shrunk)
        assertTrue("mean difference $diff", diff < 6.0)
        small.recycle(); large.recycle(); shrunk.recycle()
    }

    @Test
    fun eraseKeepsTransparencyInPngAndJpegFlattensToWhite() {
        val src = scene(160, 100)
        val mask = Mark(MarkKind.ERASE, listOf(Pt(0.2f, 0.5f), Pt(0.8f, 0.5f)), width = 0.25f)
        val png = File(dir, "mask.png")
        export(src, Recipe(marks = listOf(mask)), ExportOptions(OutputFormat.PNG), png)
        val b = BitmapFactory.decodeFile(png.path)
        assertEquals(0, Color.alpha(b.getPixel(80, 50))); assertEquals(255, Color.alpha(b.getPixel(2, 2))); b.recycle()
        val jpg = File(dir, "mask.jpg")
        export(src, Recipe(marks = listOf(mask)), ExportOptions(OutputFormat.JPEG), jpg)
        val j = BitmapFactory.decodeFile(jpg.path)
        assertTrue(Color.red(j.getPixel(80, 50)) > 240 && Color.blue(j.getPixel(80, 50)) > 240); j.recycle()
    }

    @Test
    fun targetSizeIsAHardLimitMeasuredAfterMetadata() {
        val src = scene(600, 400, jpeg = true)
        ExifInterface(src).apply { setAttribute(ExifInterface.TAG_MAKE, "Camera"); saveAttributes() }
        val out = File(dir, "small.jpg")
        val e = export(src, Recipe(), ExportOptions(OutputFormat.JPEG, targetBytes = 15_000, allowShrink = true, keepCameraFields = true), out)
        assertTrue(e.bytes <= 15_000)
        assertEquals(out.length(), e.bytes)
        assertEquals("Camera", ExifInterface(out).getAttribute(ExifInterface.TAG_MAKE))
    }

    @Test
    fun impossibleTargetLeavesNoPartialFile() {
        val src = scene(160, 100)
        val out = File(dir, "impossible.jpg")
        try { export(src, Recipe(), ExportOptions(OutputFormat.JPEG, targetBytes = 10, allowShrink = false), out); fail() } catch (_: Codecs.TargetUnreachable) {}
        assertFalse(out.exists())
    }

    @Test
    fun cancellationDeletesThePartialExport() = runBlocking {
        val src = scene(400, 300)
        val out = File(dir, "cancelled.png")
        var ticks = 0
        val bitmap = renderer.render(src, Recipe())
        try {
            codecs.export(bitmap, ExportOptions(OutputFormat.PNG), out, src, false) { if (++ticks > 1) throw CancellationException() }
            fail()
        } catch (_: CancellationException) {} finally { bitmap.recycle() }
        assertFalse(out.exists())
    }

    @Test
    fun losslessStripRemovesLocationAndKeepsThePicture() {
        val src = scene(200, 120, jpeg = true)
        ExifInterface(src).apply { setLatLong(12.9, 77.6); setAttribute(ExifInterface.TAG_MAKE, "Phone"); saveAttributes() }
        val bytes = src.readBytes()
        assertTrue(JpegSegments.hasGps(bytes))
        val out = File(dir, "clean.jpg")
        val result = codecs.losslessStrip(src, out)!!
        assertTrue(result.hadGps && result.after < result.before)
        val cleaned = out.readBytes()
        assertFalse(JpegSegments.hasGps(cleaned))
        assertNull(ExifInterface(out).getAttribute(ExifInterface.TAG_MAKE))
        // The entropy-coded picture is identical, so the decoded pixels are identical.
        val a = BitmapFactory.decodeFile(src.path); val b = BitmapFactory.decodeFile(out.path)
        val pa = IntArray(a.width * a.height); val pb = IntArray(pa.size)
        a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height); b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
        assertArrayEquals(pa, pb)
        val sosA = JpegSegments.segments(bytes)!!.last(); val sosB = JpegSegments.segments(cleaned)!!.last()
        assertArrayEquals(bytes.copyOfRange(sosA.offset, bytes.size), cleaned.copyOfRange(sosB.offset, cleaned.size))
    }

    @Test
    fun perspectiveStraightenAndFrameProduceTheSizesTheyPromise() = runBlocking {
        val src = scene(400, 300)
        val quad = Quad(Pt(0.1f, 0.1f), Pt(0.9f, 0.05f), Pt(0.95f, 0.95f), Pt(0.05f, 0.9f))
        val p = renderer.render(src, Recipe(geometry = Geometry(quad = quad)))
        assertTrue(p.width in 300..360 && p.height in 240..300); p.recycle()

        val thin = scene(4000, 6)
        val s = renderer.render(thin, Recipe(geometry = Geometry(straighten = 45f)))
        assertTrue(s.width.toLong() * s.height <= 2_000_000 && s.width <= 8192 && s.height <= 8192); s.recycle()

        val f = renderer.render(src, Recipe(frame = Frame(aspect = 1f, padding = 0.05f, border = 0.02f, cornerRadius = 0.1f)))
        assertEquals(f.width, f.height)
        assertTrue(f.width > 400); f.recycle()
    }

    @Test
    fun layersDrawWhereTheyAreToldAtAnySize() = runBlocking {
        val src = scene(400, 300)
        val cover = Mark(MarkKind.COVER, listOf(Pt(0.1f, 0.1f), Pt(0.4f, 0.4f)), color = Color.BLACK)
        val text = Mark(MarkKind.TEXT, listOf(Pt(0.7f, 0.7f)), text = "Layerbit", color = Color.WHITE, width = 0.06f)
        val watermark = Mark(MarkKind.WATERMARK, listOf(Pt(0.5f, 0.5f)), text = "©", opacity = 0.5f, angle = -30f)
        for (pixels in listOf(100_000L, 120_000L)) {
            // The watermark tiles the whole canvas, so the cover is checked under the text alone.
            val b = renderer.render(src, Recipe(marks = listOf(cover, text)), maxPixels = pixels)
            val inside = b.getPixel((b.width * 0.25f).toInt(), (b.height * 0.25f).toInt())
            assertEquals(Color.BLACK, inside)
            val marked = renderer.render(src, Recipe(marks = listOf(cover, text, watermark)), maxPixels = pixels)
            assertTrue("A watermark changes the picture", renderer.difference(b, marked) > 0.0)
            b.recycle(); marked.recycle()
        }
        val blur = Mark(MarkKind.BLUR, listOf(Pt(0.1f, 0.1f), Pt(0.9f, 0.9f)), width = 0.1f)
        val plain = renderer.render(src, Recipe())
        val blurred = renderer.render(src, Recipe(marks = listOf(blur)))
        assertTrue(renderer.difference(plain, blurred) > 1.0)
        assertEquals(plain.getPixel(2, 2), blurred.getPixel(2, 2))
        plain.recycle(); blurred.recycle()
    }

    @Test
    fun corruptInputsFailWithAnIllegalArgument() {
        val bad = File(dir, "bad.jpg").apply { writeText("not a picture") }
        try { renderer.decode(bad, 1000); fail() } catch (_: IllegalArgumentException) {}
        assertNull(renderer.bounds(bad))
    }

    @Test
    fun webpLosslessIsOfferedOnlyWhereItExists() {
        // SDK 28 in this test: lossless WebP is not available, and the format list says so.
        assertFalse(OutputFormat.WEBP_LOSSLESS.available)
        assertNotNull(OutputFormat.fromMime("image/webp"))
    }

    @Test
    fun cutoutRemovesTheSceneEdgeKeepsTheMiddleAndSurvivesAPngExport() = runBlocking {
        // A dark square in the middle of a light field: the subject every guess should find.
        val w = 300; val h = 220
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) b.setPixel(x, y, if (x in 100..200 && y in 60..160) Color.rgb(30, 40, 120) else Color.rgb(225 - (x % 5), 220, 205))
        val src = File(dir, "square.png"); src.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
        val cut = Recipe(cutout = Cutout(enabled = true))
        val out = renderer.render(src, cut)
        assertEquals(0, Color.alpha(out.getPixel(10, 10)))
        assertEquals(255, Color.alpha(out.getPixel(150, 110)))
        // The export keeps the transparency in PNG and fills it in JPEG.
        val png = export(src, cut, ExportOptions(OutputFormat.PNG), File(dir, "cut.png"))
        val back = renderer.decode(png.file, 2_000_000)
        assertEquals(0, Color.alpha(back.getPixel(10, 10))); back.recycle()
        val jpg = export(src, cut, ExportOptions(OutputFormat.JPEG), File(dir, "cut.jpg"))
        val flat = renderer.decode(jpg.file, 2_000_000)
        assertTrue(Color.red(flat.getPixel(10, 10)) > 240); flat.recycle()
        // Preview and export decide the same edge: the mask is one decision scaled.
        val small = renderer.render(src, cut, maxPixels = 150 * 110)
        assertEquals(0, Color.alpha(small.getPixel(5, 5))); assertEquals(255, Color.alpha(small.getPixel(75, 55)))
        // A colour behind, a blur behind, and the inverse.
        val coloured = renderer.render(src, Recipe(cutout = Cutout(enabled = true, backdrop = Backdrop.COLOUR, color = Color.rgb(10, 200, 10))))
        assertEquals(Color.rgb(10, 200, 10), coloured.getPixel(10, 10))
        val blurred = renderer.render(src, Recipe(cutout = Cutout(enabled = true, backdrop = Backdrop.BLUR)))
        assertEquals(255, Color.alpha(blurred.getPixel(10, 10)))
        val inverted = renderer.render(src, Recipe(cutout = Cutout(enabled = true, invert = true)))
        assertEquals(255, Color.alpha(inverted.getPixel(10, 10))); assertEquals(0, Color.alpha(inverted.getPixel(150, 110)))
        // A remove stroke across the square takes it out.
        val struck = renderer.render(src, Recipe(cutout = Cutout(enabled = true, scribbles = listOf(Scribble(listOf(Pt(0.4f, 0.5f), Pt(0.6f, 0.5f)), 0.2f, keep = false)))))
        assertEquals(0, Color.alpha(struck.getPixel(150, 110)))
        out.recycle(); small.recycle(); coloured.recycle(); blurred.recycle(); inverted.recycle(); struck.recycle()
    }

    @Test
    fun inkCutoutScanAndStickerExport() = runBlocking {
        val w = 240; val h = 180
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val light = 230 - x * 100 / w
            b.setPixel(x, y, if (y in 80..90 && x in 20..200) Color.rgb(20, 20, 30) else Color.rgb(light, light - 4, light - 12))
        }
        val src = File(dir, "page.png"); src.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
        val ink = renderer.render(src, Recipe(cutout = Cutout(enabled = true, mode = CutoutMode.INK)))
        assertEquals("ink kept", 255, Color.alpha(ink.getPixel(100, 85))); assertEquals("paper gone", 0, Color.alpha(ink.getPixel(100, 30))); ink.recycle()
        val scanned = renderer.render(src, Recipe(scan = Scan(ScanMode.COLOUR)))
        assertTrue("paper white: ${Color.red(scanned.getPixel(230, 30))}", Color.red(scanned.getPixel(230, 30)) > 235)
        assertTrue("ink dark: ${Color.red(scanned.getPixel(230, 85))}", Color.red(scanned.getPixel(230, 85)) < 90); scanned.recycle()
        val sticker = export(src, Recipe(cutout = Cutout(enabled = true, mode = CutoutMode.INK)), ExportOptions(OutputFormat.JPEG, sticker = true), File(dir, "sticker.webp"))
        assertEquals("sticker width", 512, sticker.width); assertEquals("sticker height", 512, sticker.height)
        assertEquals(OutputFormat.WEBP, sticker.format)
        assertTrue("sticker bytes ${sticker.bytes}", sticker.bytes <= ExportOptions.STICKER_BYTES)
        val decoded = renderer.decode(sticker.file, 2_000_000)
        assertEquals("sticker corner transparent", 0, Color.alpha(decoded.getPixel(2, 2))); decoded.recycle()
    }
}
