package com.layerbit.studio

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import com.layerbit.studio.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagePipelineTest {
    private lateinit var dir:File
    private val engine=ImageEngine(2_000_000)
    @Before fun setup(){dir=Files.createTempDirectory("studio-images").toFile()}
    @After fun cleanup(){dir.deleteRecursively()}
    private fun fixture(w:Int=160,h:Int=100,jpeg:Boolean=false):File {
        val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val rng=Random(17);for(y in 0 until h)for(x in 0 until w)b.setPixel(x,y,Color.rgb(rng.nextInt(256),rng.nextInt(256),rng.nextInt(256)))
        val f=File(dir,if(jpeg)"original.jpg" else "original.png")
        f.outputStream().use{b.compress(if(jpeg)Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,95,it)};b.recycle();return f
    }
    private fun project(f:File,r:Recipe=Recipe())=Project(Source("test","Original",f.path,160,100,f.length(),"image/png",""),r)
    private fun hash(f:File)=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).toList()
    @Test fun transformedExportKeepsOriginalByteIdentical() {
        val src=fixture();val before=hash(src)
        val r=Recipe(crop=Crop(.25f,0f,.75f,1f),rotation=90,width=50,height=40,tone=Tone(brightness=.2f))
        val e=engine.export(project(src,r),ExportOptions(OutputFormat.PNG),File(dir,"out.png"))
        assertEquals(50,e.width);assertEquals(40,e.height);assertEquals(before,hash(src));assertTrue(e.bytes>0)
    }
    @Test fun orientationIsAppliedOnceAndGpsNeverCopied() {
        val src=fixture(jpeg=true)
        ExifInterface(src).apply{setAttribute(ExifInterface.TAG_ORIENTATION,"6");setAttribute(ExifInterface.TAG_MAKE,"Test camera");setLatLong(25.2,55.3);saveAttributes()}
        val b=engine.decode(src,2_000_000);assertEquals(100,b.width);assertEquals(160,b.height);b.recycle()
        val out=File(dir,"export.jpg");engine.export(project(src),ExportOptions(keepCamera=true),out)
        val metadata=ExifInterface(out);assertNull(metadata.getAttribute(ExifInterface.TAG_GPS_LATITUDE));assertEquals("Test camera",metadata.getAttribute(ExifInterface.TAG_MAKE))
        val rotated=engine.decode(out,2_000_000);assertEquals(100,rotated.width);assertEquals(160,rotated.height);rotated.recycle()
    }
    @Test fun maskIsReversibleInRecipeAndPngKeepsTransparency() {
        val src=fixture();val mask=Mark(MarkKind.ERASE,listOf(Point(.2f,.5f),Point(.8f,.5f)),width=.2f)
        val out=File(dir,"mask.png");engine.export(project(src,Recipe(marks=listOf(mask))),ExportOptions(OutputFormat.PNG),out)
        val b=BitmapFactory.decodeFile(out.path);assertEquals(0,Color.alpha(b.getPixel(80,50)));assertEquals(255,Color.alpha(b.getPixel(0,0)));b.recycle()
        val restored=engine.render(src,Recipe(marks=listOf(mask.copy(visible=false))));assertEquals(255,Color.alpha(restored.getPixel(80,50)));restored.recycle()
    }
    @Test fun targetSizeIsMeasuredAfterMetadataAndIsAHardUpperLimit() {
        val src=fixture(400,300,true);val out=File(dir,"small.jpg")
        ExifInterface(src).apply{setAttribute(ExifInterface.TAG_MAKE,"Camera");saveAttributes()}
        val e=engine.export(project(src),ExportOptions(targetBytes=12_000,allowShrink=true,keepCamera=true),out)
        assertTrue(e.bytes<=12_000);assertEquals(out.length(),e.bytes);assertTrue(e.quality in 5..92)
    }
    @Test fun impossibleTargetLeavesNoPartialOutputAndKeepsSource() {
        val src=fixture();val before=hash(src);val out=File(dir,"impossible.jpg")
        try{engine.export(project(src),ExportOptions(targetBytes=10),out);fail("Expected honest target failure")}catch(_:IllegalArgumentException){}
        assertFalse(out.exists());assertEquals(before,hash(src))
    }
    @Test fun cancellationDeletesPartialExport() {
        val src=fixture();val out=File(dir,"cancelled.png");var ticks=0
        try{engine.export(project(src,Recipe(tone=Tone(contrast=.2f))),ExportOptions(OutputFormat.PNG),out){if(++ticks>4)throw CancellationException()};fail("Expected cancellation")}catch(_:CancellationException){}
        assertFalse(out.exists());assertTrue(src.exists())
    }
    @Test fun unknownOrCorruptInputsFailClearly() {
        val src=File(dir,"bad").apply{writeText("this is not an image")}
        try{engine.decode(src,1000);fail("Expected unsupported file error")}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("supported"))}
    }
    @Test fun boundedDecodeDoesNotAllocateFullLargeImage() {
        val src=fixture(2400,1600);val b=engine.decode(src,150_000)
        assertTrue(b.width.toLong()*b.height<=150_000);b.recycle()
    }
    @Test fun cloneAndLocalBlurAreRealPixelOperations() {
        val src=fixture();val base=engine.render(src,Recipe())
        val clone=Mark(MarkKind.CLONE,listOf(Point(.7f,.5f),Point(.72f,.5f)),width=.15f,source=Point(.2f,.5f))
        val b=engine.render(src,Recipe(marks=listOf(clone)));assertEquals(base.getPixel(32,50),b.getPixel(112,50));b.recycle()
        val blurred=engine.render(src,Recipe(marks=listOf(Mark(MarkKind.BLUR,listOf(Point(.1f,.1f),Point(.9f,.9f)),width=.1f))))
        assertNotEquals(base.getPixel(80,50),blurred.getPixel(80,50));assertEquals(base.getPixel(0,0),blurred.getPixel(0,0));base.recycle();blurred.recycle()
    }
    @Test fun recipesRoundTripLayersAndRejectInvalidGeometry() {
        val r=Recipe(tone=Tone(warmth=.3f),marks=listOf(Mark(MarkKind.TEXT,listOf(Point(.1f,.2f)),text="Layerbit",opacity=.5f)))
        assertEquals(r,RecipeJson.read(RecipeJson.write(r)))
        try{RecipeJson.read(RecipeJson.write(Recipe(crop=Crop(.9f,0f,.1f,1f))));fail("Expected invalid crop rejection")}catch(_:IllegalArgumentException){}
    }
    @Test fun explicitOversizeOutputRequiresSmallerDimensions() {
        val src=fixture();try{engine.render(src,Recipe(width=8000,height=8000));fail("Expected budget failure")}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("working limit"))}
    }
}
