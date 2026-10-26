package com.layerbit.studio.core

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Original URIs are read once. The pipeline only receives private file paths. */
class ProjectStore(private val context: Context) {
    private val root=File(context.filesDir,"projects").apply { mkdirs() }
    val exports=File(context.filesDir,"exports").apply { mkdirs() }
    private val presetDir=File(context.filesDir,"presets").apply { mkdirs() }
    fun outputs():List<Exported> = exports.listFiles().orEmpty().filter { it.isFile }.sortedByDescending {it.lastModified()}.take(50).mapNotNull { f ->
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(f.path,options)
        if(options.outWidth>0) Exported(f.path,options.outWidth,options.outHeight,f.length(),options.outMimeType ?: "image/*",-1,"Recovered private output") else null
    }
    fun clearOutputs() { exports.listFiles().orEmpty().forEach { it.delete() } }
    fun import(uri: Uri,check: () -> Unit): Project {
        val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "Image"
        val folder=File(root,UUID.randomUUID().toString()).apply { mkdirs() }; val file=File(folder,"source")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                val buffer=ByteArray(64*1024); var total=0L
                while(true) { check(); val n=input.read(buffer); if(n<0) break; total+=n; require(total<=100L*1024*1024) { "Import limit is 100 MB per image." }; output.write(buffer,0,n) }
            } } ?: error("This document could not be read.")
            return register(folder,file,name)
        } catch(t:Throwable) { folder.deleteRecursively(); throw t }
    }
    private fun register(folder:File,file:File,name:String): Project {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) { "Unsupported or damaged image." }
        val exif=runCatching { ExifInterface(file) }.getOrNull()
        val rotated=(exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION,1) ?: 1) in 5..8
        val width=if(rotated) bounds.outHeight else bounds.outWidth; val height=if(rotated) bounds.outWidth else bounds.outHeight
        val metadata=buildList {
            add("${bounds.outMimeType ?: "Image"} · $width × $height pixels")
            add("Source ${file.length()/1024} KB")
            add("Editing/export: 8-bit sRGB; HDR and wide-gamut images are converted")
            listOf(ExifInterface.TAG_MAKE,ExifInterface.TAG_MODEL,ExifInterface.TAG_DATETIME_ORIGINAL).forEach { tag -> exif?.getAttribute(tag)?.let { add("$tag: $it") } }
            add(if(exif?.getAttribute(ExifInterface.TAG_GPS_LATITUDE)!=null) "Location metadata found. Export removes it." else "No GPS location found.")
        }.joinToString("\n")
        val project=Project(Source(folder.name,name,file.path,width,height,file.length(),bounds.outMimeType ?: "image/*",metadata))
        save(project); return project
    }
    @Synchronized fun save(p:Project) {
        val s=p.source; val folder=File(root,s.id); folder.mkdirs()
        val json=JSONObject().apply { put("id",s.id);put("name",s.name);put("width",s.width);put("height",s.height)
            put("bytes",s.bytes);put("mime",s.mime);put("metadata",s.metadata);put("recipe",JSONObject(RecipeJson.write(p.recipe))) }
        val tmp=File(folder,"project.tmp"); tmp.writeText(json.toString()); check(tmp.renameTo(File(folder,"project.json")))
    }
    fun projects(): List<Project> = root.listFiles().orEmpty().sortedByDescending { File(it,"project.json").lastModified() }.mapNotNull { dir -> runCatching {
        val o=JSONObject(File(dir,"project.json").readText()); val source=File(dir,"source"); require(source.exists())
        Project(Source(dir.name,o.getString("name"),source.path,o.getInt("width"),o.getInt("height"),o.getLong("bytes"),o.getString("mime"),o.getString("metadata")),RecipeJson.read(o.getJSONObject("recipe").toString()))
    }.getOrNull() }
    fun remove(p:Project) { File(root,p.source.id).deleteRecursively() }
    fun savePreset(name:String,r:Recipe) { File(presetDir,Geometry.safeName(name)+".json").writeText(RecipeJson.write(r.preset())) }
    fun presets(): List<Pair<String,Recipe>> = presetDir.listFiles().orEmpty().sortedBy { it.name }.mapNotNull { runCatching { it.nameWithoutExtension to RecipeJson.read(it.readText()) }.getOrNull() }
    fun saveExport(export:Exported,uri:Uri) {
        // The only caller supplies a URI returned by CreateDocument, never an imported URI.
        context.contentResolver.openOutputStream(uri,"w")?.use { out -> File(export.path).inputStream().use { it.copyTo(out) } } ?: error("Destination is not writable.")
    }
    fun copyBatch(exports:List<Exported>,tree:Uri,check:()->Unit):Int {
        val dir=DocumentFile.fromTreeUri(context,tree) ?: error("Choose a writable folder.")
        var completed=0
        for(e in exports) {
            check(); val name=File(e.path).name
            val target=dir.createFile(e.mime,name) ?: error("Could not create a new document after $completed saved files.")
            try { saveExport(e,target.uri); completed++ } catch(t:Throwable) { target.delete(); throw t }
        }
        return completed
    }
    fun collage(projects:List<Project>,columns:Int,engine:ImageEngine,check:()->Unit):Project {
        require(projects.size in 2..9); val rows=(projects.size+columns-1)/columns
        val cell=600; val gap=24; val width=columns*cell+(columns+1)*gap; val height=rows*cell+(rows+1)*gap
        require(width.toLong()*height<=engine.budget) { "Collage exceeds this device's working limit. Select fewer pictures." }
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); val canvas=Canvas(bitmap); canvas.drawColor(Color.WHITE)
        try {
            projects.forEachIndexed { i,p -> check(); val b=engine.render(File(p.source.path),p.recipe,true,check)
                val scale=minOf(cell.toFloat()/b.width,cell.toFloat()/b.height); val w=b.width*scale; val h=b.height*scale
                val x=gap+(i%columns)*(cell+gap)+(cell-w)/2; val y=gap+(i/columns)*(cell+gap)+(cell-h)/2
                canvas.drawBitmap(b,null,RectF(x,y,x+w,y+h),Paint(Paint.FILTER_BITMAP_FLAG)); b.recycle()
            }
            val folder=File(root,UUID.randomUUID().toString()).apply { mkdirs() }; val file=File(folder,"source")
            try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; return register(folder,file,"Collage.png") }
            catch(t:Throwable) { folder.deleteRecursively(); throw t }
        } finally { bitmap.recycle() }
    }
}
