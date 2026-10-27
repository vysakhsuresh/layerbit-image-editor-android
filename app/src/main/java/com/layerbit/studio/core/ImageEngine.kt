package com.layerbit.studio.core

import android.graphics.*
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

/** Bounded, software, 8-bit sRGB pipeline. All coordinates in marks refer to the transformed canvas. */
class ImageEngine(val budget: Long = (Runtime.getRuntime().maxMemory()/48).coerceIn(1_000_000L,8_000_000L)) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    fun decode(file: File, maxPixels: Long, check: () -> Unit = {}): Bitmap {
        require(maxPixels>0)
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) { "This file is not a supported still image." }
        check()
        var sample=1
        while(((bounds.outWidth.toLong()+sample-1)/sample)*((bounds.outHeight.toLong()+sample-1)/sample)>maxPixels || (max(bounds.outWidth,bounds.outHeight).toLong()+sample-1)/sample>8192) sample*=2
        val opts=BitmapFactory.Options().apply { inSampleSize=sample; inPreferredConfig=Bitmap.Config.ARGB_8888
            inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB); inMutable=true }
        val raw=BitmapFactory.decodeFile(file.path,opts) ?: error("The image could not be decoded.")
        val exif=runCatching { ExifInterface(file) }.getOrNull()
        val m=Matrix()
        when(exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION,1)) {
            2 -> m.setScale(-1f,1f); 3 -> m.setRotate(180f); 4 -> m.setScale(1f,-1f)
            5 -> { m.setRotate(90f); m.postScale(-1f,1f) }; 6 -> m.setRotate(90f)
            7 -> { m.setRotate(-90f); m.postScale(-1f,1f) }; 8 -> m.setRotate(-90f)
        }
        if(m.isIdentity) return raw
        val result=Bitmap.createBitmap(raw,0,0,raw.width,raw.height,m,true)
        if(result!==raw) raw.recycle()
        return result
    }
    fun render(source: File, r: Recipe, preview: Boolean = false, check: () -> Unit = {}): Bitmap {
        require(r.crop.valid()) { "Crop must contain at least 1% of the image in each direction." }
        val maxPixels=if(preview) minOf(budget,900_000L) else budget
        // A diagonal rotation can double the bounding box. Reserve space before decoding.
        var b=decode(source,if(abs(r.straighten)>.01f) maxPixels/2 else maxPixels,check)
        fun replace(next: Bitmap) { if(next!==b) { b.recycle(); b=next } }
        try {
            check()
            val x=(r.crop.left*b.width).toInt().coerceIn(0,b.width-1)
            val y=(r.crop.top*b.height).toInt().coerceIn(0,b.height-1)
            val w=((r.crop.right-r.crop.left)*b.width).roundToInt().coerceIn(1,b.width-x)
            val h=((r.crop.bottom-r.crop.top)*b.height).roundToInt().coerceIn(1,b.height-y)
            replace(Bitmap.createBitmap(b,x,y,w,h))
            val matrix=Matrix().apply { postScale(if(r.flipX)-1f else 1f,if(r.flipY)-1f else 1f); postRotate(r.rotation+r.straighten) }
            if(!matrix.isIdentity) {
                // Thin panoramas can grow far beyond twice their area when rotated.
                // Check the actual bounding box before allocating the transformed bitmap.
                fun transformedSize():Pair<Int,Int> {
                    val bounds=RectF(0f,0f,b.width.toFloat(),b.height.toFloat())
                    matrix.mapRect(bounds)
                    return ceil(bounds.width().toDouble()).toInt() to ceil(bounds.height().toDouble()).toInt()
                }
                var dims=transformedSize()
                while(dims.first.toLong()*dims.second>maxPixels || max(dims.first,dims.second)>8192) {
                    val fit=Geometry.fit(dims.first,dims.second,maxPixels)
                    val factor=min(fit.first.toDouble()/dims.first,fit.second.toDouble()/dims.second)*.98
                    replace(Bitmap.createScaledBitmap(b,max(1,(b.width*factor).toInt()),max(1,(b.height*factor).toInt()),true))
                    check();dims=transformedSize()
                }
                replace(Bitmap.createBitmap(b,0,0,b.width,b.height,matrix,true))
            }
            if(r.width>0 && r.height>0) {
                val dims=if(preview) Geometry.fit(r.width,r.height,maxPixels) else {
                    require(r.width.toLong()*r.height<=budget && max(r.width,r.height)<=8192) { "Requested dimensions exceed this device's ${budget/1_000_000f} MP working limit. Choose a smaller size." }
                    r.width to r.height
                }
                replace(Bitmap.createScaledBitmap(b,dims.first,dims.second,true))
            }
            // createBitmap may retain immutable/shared storage. Every following stage writes only to a new mutable bitmap.
            if(!b.isMutable || b.config!=Bitmap.Config.ARGB_8888) replace(b.copy(Bitmap.Config.ARGB_8888,true))
            b.setHasAlpha(true)
            adjust(b,r,check)
            for(mark in r.marks) { check(); if(mark.visible && mark.points.isNotEmpty()) drawMark(b,mark,check) }
            return b
        } catch(t: Throwable) { b.recycle(); throw t }
    }
    private fun adjust(b: Bitmap,r: Recipe,check: () -> Unit) {
        val t=r.tone
        if(t==Tone() && (r.filter=="Original" || r.intensity==0f)) return
        val a=IntArray(b.width*b.height); b.getPixels(a,0,b.width,0,0,b.width,b.height)
        val hsv=FloatArray(3)
        for(i in a.indices) {
            if(i%b.width==0) check()
            val p=a[i]; val alpha=Color.alpha(p)
            var red=Color.red(p)/255f; var green=Color.green(p)/255f; var blue=Color.blue(p)/255f
            val lum=.2126f*red+.7152f*green+.0722f*blue
            val lift=t.brightness*.35f + t.shadows*(1-lum).pow(2)*.35f + t.highlights*lum.pow(2)*.35f
            fun curve(v:Float)=((v-.5f)*(1+t.contrast)+.5f+lift).coerceIn(0f,1f)
            red=(curve(red)+t.warmth*.12f).coerceIn(0f,1f); green=curve(green); blue=(curve(blue)-t.warmth*.12f).coerceIn(0f,1f)
            Color.RGBToHSV((red*255).toInt(),(green*255).toInt(),(blue*255).toInt(),hsv)
            hsv[0]=(hsv[0]+t.hue*180+360)%360; hsv[1]=(hsv[1]*(1+t.saturation)).coerceIn(0f,1f)
            val col=Color.HSVToColor(hsv); red=Color.red(col)/255f; green=Color.green(col)/255f; blue=Color.blue(col)/255f
            val mono=.2126f*red+.7152f*green+.0722f*blue
            var fr=red; var fg=green; var fb=blue
            when(r.filter) {
                "Mono" -> { fr=mono;fg=mono;fb=mono }
                "Warm" -> { fr=red*1.08f+.025f;fg=green*1.02f;fb=blue*.90f }
                "Cool" -> { fr=red*.92f;fg=green*1.01f;fb=blue*1.08f }
                "Fade" -> { fr=red*.82f+.10f;fg=green*.82f+.10f;fb=blue*.82f+.10f }
                "Noir" -> { val v=(mono-.5f)*1.5f+.5f;fr=v;fg=v;fb=v }
            }
            fun mix(v:Float,f:Float)=((v+(f-v)*r.intensity).coerceIn(0f,1f)*255).roundToInt()
            a[i]=Color.argb(alpha,mix(red,fr),mix(green,fg),mix(blue,fb))
        }
        if(t.sharpness>0) {
            val original=a.copyOf(); val width=b.width
            for(y in 1 until b.height-1) { check(); for(x in 1 until width-1) {
                val i=y*width+x; val p=original[i]
                fun sharpen(shift:Int):Int {
                    val c=(p shr shift) and 255
                    val mean=((original[i-1] shr shift and 255)+(original[i+1] shr shift and 255)+
                        (original[i-width] shr shift and 255)+(original[i+width] shr shift and 255))/4f
                    return (c+(c-mean)*t.sharpness).roundToInt().coerceIn(0,255)
                }
                a[i]=Color.argb(Color.alpha(p),sharpen(16),sharpen(8),sharpen(0))
            } }
        }
        b.setPixels(a,0,b.width,0,0,b.width,b.height)
    }
    private fun drawMark(b: Bitmap,m: Mark,check: () -> Unit) {
        val c=Canvas(b); val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color=m.color; alpha=(m.opacity*255).roundToInt(); strokeWidth=max(1f,m.width*min(b.width,b.height))
            strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND; style=Paint.Style.STROKE
        }
        val points=m.points.map { PointF(it.x*b.width,it.y*b.height) }; val start=points.first(); val end=points.last()
        val path=Path().apply { moveTo(start.x,start.y); points.drop(1).forEach { lineTo(it.x,it.y) }; if(points.size==1) lineTo(start.x+.1f,start.y+.1f) }
        val rect=RectF(min(start.x,end.x),min(start.y,end.y),max(start.x,end.x),max(start.y,end.y))
        when(m.kind) {
            MarkKind.DRAW -> c.drawPath(path,p)
            MarkKind.TEXT -> { p.style=Paint.Style.FILL; p.textSize=max(12f,m.width*min(b.width,b.height)*4); p.typeface=Typeface.create("sans-serif",Typeface.BOLD); c.drawText(m.text,start.x,start.y.coerceAtLeast(p.textSize),p) }
            MarkKind.BOX -> c.drawRect(rect,p)
            MarkKind.ELLIPSE -> c.drawOval(rect,p)
            MarkKind.COVER -> { p.style=Paint.Style.FILL;p.alpha=255;c.drawRect(rect,p) }
            MarkKind.ARROW -> {
                c.drawLine(start.x,start.y,end.x,end.y,p)
                val angle=atan2(end.y-start.y,end.x-start.x); val length=p.strokeWidth*5
                for(offset in listOf(-.55f,.55f)) c.drawLine(end.x,end.y,end.x-length*cos(angle+offset),end.y-length*sin(angle+offset),p)
            }
            MarkKind.ERASE -> { p.xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT); c.drawPath(path,p) }
            MarkKind.CLONE -> {
                val src=m.source ?: return
                val snap=b.copy(Bitmap.Config.ARGB_8888,false)
                p.shader=BitmapShader(snap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setTranslate(start.x-src.x*b.width,start.y-src.y*b.height) })
                }
                c.drawPath(path,p); p.shader=null; snap.recycle()
            }
            MarkKind.PIXELATE, MarkKind.BLUR -> {
                val x=rect.left.toInt().coerceIn(0,b.width-1); val y=rect.top.toInt().coerceIn(0,b.height-1)
                val w=rect.width().toInt().coerceIn(1,b.width-x); val h=rect.height().toInt().coerceIn(1,b.height-y)
                val area=Bitmap.createBitmap(b,x,y,w,h)
                val divisor=if(m.kind==MarkKind.PIXELATE) max(5,(min(b.width,b.height)*m.width).toInt()) else 1
                if(m.kind==MarkKind.PIXELATE) {
                    val small=Bitmap.createScaledBitmap(area,max(1,w/divisor),max(1,h/divisor),false)
                    val pixelPaint=Paint().apply { alpha=p.alpha; isFilterBitmap=false }
                    c.drawBitmap(small,null,Rect(x,y,x+w,y+h),pixelPaint)
                    if(small!==area) small.recycle()
                } else {
                    val blurred=boxBlur(area,max(2,(min(w,h)*m.width*2).toInt()).coerceAtMost(40),check)
                    c.drawBitmap(blurred,x.toFloat(),y.toFloat(),Paint().apply { alpha=p.alpha })
                    blurred.recycle()
                }
                if(area!==b) area.recycle()
            }
        }
    }
    private fun boxBlur(src: Bitmap,radius: Int,check: () -> Unit): Bitmap {
        val w=src.width; val h=src.height; val a=IntArray(w*h); val tmp=IntArray(w*h); src.getPixels(a,0,w,0,0,w,h)
        // Two separable sliding-window passes; running time is independent of radius.
        fun pass(input:IntArray,output:IntArray,horizontal:Boolean) {
            val outer=if(horizontal) h else w; val inner=if(horizontal) w else h
            fun at(o:Int,i:Int)=if(horizontal)o*w+i else i*w+o
            for(o in 0 until outer) { check(); val sums=LongArray(4)
                fun add(i:Int,sign:Int) { val p=input[at(o,i.coerceIn(0,inner-1))]; for(k in 0..3) sums[k]+=((p ushr (k*8)) and 255)*sign }
                for(i in -radius..radius) add(i,1)
                for(i in 0 until inner) {
                    var p=0; for(k in 0..3) p=p or ((sums[k]/(radius*2+1)).toInt() shl (k*8)); output[at(o,i)]=p
                    add(i-radius,-1); add(i+radius+1,1)
                }
            }
        }
        pass(a,tmp,true); pass(tmp,a,false)
        return Bitmap.createBitmap(a,w,h,Bitmap.Config.ARGB_8888)
    }
    fun export(project: Project,options: ExportOptions,destination: File,check: () -> Unit = {}): Exported {
        require(options.quality in 1..100 && options.targetBytes>=0)
        require(!(options.format==OutputFormat.PNG && options.targetBytes>0)) { "PNG is lossless and has no quality control. Choose JPEG or WebP for a target file size." }
        var b=render(File(project.source.path),project.recipe,false,check)
        var quality=options.quality
        val codec=when(options.format) { OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG; OutputFormat.PNG -> Bitmap.CompressFormat.PNG
            OutputFormat.WEBP -> if(Build.VERSION.SDK_INT>=30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP }
        try {
            if(options.format==OutputFormat.JPEG) {
                val flat=Bitmap.createBitmap(b.width,b.height,Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(b,0f,0f,paint) }; b.recycle(); b=flat
            }
            fun encode(q: Int) {
                check(); FileOutputStream(destination).use { require(b.compress(codec,q,it)) { "Encoder failed" } }
                if(options.keepCamera && options.format==OutputFormat.JPEG) {
                    val source=runCatching { ExifInterface(File(project.source.path)) }.getOrNull()
                    val target=ExifInterface(destination)
                    listOf(ExifInterface.TAG_MAKE,ExifInterface.TAG_MODEL,ExifInterface.TAG_DATETIME_ORIGINAL,
                        ExifInterface.TAG_EXPOSURE_TIME,ExifInterface.TAG_F_NUMBER,ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY).forEach { tag ->
                        source?.getAttribute(tag)?.let { target.setAttribute(tag,it) }
                    }; target.saveAttributes()
                }
            }
            encode(quality)
            if(options.targetBytes>0 && destination.length()>options.targetBytes) {
                while(true) {
                    var low=5; var high=options.quality; var best=-1
                    while(low<=high) { val q=(low+high)/2; encode(q); if(destination.length()<=options.targetBytes) { best=q; low=q+1 } else high=q-1 }
                    if(best>=0) { quality=best; encode(best); break }
                    require(options.allowShrink && min(b.width,b.height)>32) { "Target cannot be met at these dimensions. Allow dimension reduction or increase the size limit." }
                    encode(5)
                    val factor=min(.85,sqrt(options.targetBytes.toDouble()/destination.length())*.9).coerceAtLeast(.1)
                    val smaller=Bitmap.createScaledBitmap(b,max(1,(b.width*factor).toInt()),max(1,(b.height*factor).toInt()),true)
                    b.recycle(); b=smaller
                }
                require(destination.length()<=options.targetBytes) { "Encoded output exceeds the requested limit." }
            }
            check()
            val note=buildList {
                add("8-bit sRGB export")
                if(options.format==OutputFormat.JPEG) add("transparency becomes white")
                if(options.format!=OutputFormat.PNG) add("lossy encoding")
                add(if(options.keepCamera && options.format==OutputFormat.JPEG) "camera/date kept; GPS removed" else "source metadata removed")
                if(project.recipe.width==0 && b.width.toLong()*b.height<project.source.width.toLong()*project.source.height && project.recipe.crop==Crop()) add("dimensions reduced by the device working limit or target-size setting")
            }.joinToString(" · ")
            return Exported(destination.path,b.width,b.height,destination.length(),options.format.mime,quality,note)
        } catch(t: Throwable) { destination.delete(); throw t } finally { b.recycle() }
    }
}
