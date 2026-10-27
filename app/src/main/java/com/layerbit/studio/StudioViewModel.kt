package com.layerbit.studio

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.studio.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

data class StudioState(val projects:List<Project> = emptyList(),val current:Project?=null,val preview:Bitmap?=null,
    val original:Bitmap?=null,val prepared:Exported?=null,val preparedPreview:Bitmap?=null,
    val busy:String?=null,val previewBusy:Boolean=false,val message:String?=null,
    val batch:List<Exported> = emptyList(),val presets:List<Pair<String,Recipe>> = emptyList(),
    val canUndo:Boolean=false,val canRedo:Boolean=false)
class StudioViewModel(app:Application):AndroidViewModel(app) {
    val store=ProjectStore(app); val engine=ImageEngine()
    var state by mutableStateOf(StudioState()); private set
    var options by mutableStateOf(ExportOptions())
    private val undo=ArrayDeque<Recipe>(); private val redo=ArrayDeque<Recipe>()
    private val persistence=Mutex(); private var saveJob:Job?=null
    private fun persist(p:Project) {
        saveJob?.cancel()
        saveJob=viewModelScope.launch {
            try { persistence.withLock { withContext(Dispatchers.IO) { ensureActive();store.save(p) } } }
            catch(c:CancellationException) { throw c }
            catch(e:Exception) { message("Could not save the private project: ${e.message}. Keep the editor open and export a copy.") }
        }
    }
    private var baseline=Recipe(); private var previewJob:Job?=null; private var work:Job?=null
    init { viewModelScope.launch { refreshProjects(); val outputs=withContext(Dispatchers.IO){store.outputs()};state=state.copy(batch=outputs) } }
    private suspend fun refreshProjects() { val all=withContext(Dispatchers.IO) { store.projects() }; val presets=withContext(Dispatchers.IO) { store.presets() }; state=state.copy(projects=all,presets=presets) }
    fun message(text:String?) { state=state.copy(message=text) }
    fun select(p:Project) {
        previewJob?.cancel(); undo.clear(); redo.clear(); baseline=p.recipe
        state=state.copy(current=p,preview=null,original=null,prepared=null,preparedPreview=null,canUndo=false,canRedo=false)
        render()
    }
    fun home() { commit(); val pendingSave=saveJob; state=state.copy(current=null,preview=null,original=null,prepared=null,preparedPreview=null); previewJob?.cancel(); viewModelScope.launch { pendingSave?.join(); refreshProjects() } }
    fun draft(recipe:Recipe) { state=state.copy(current=state.current?.copy(recipe=recipe),prepared=null); render() }
    fun commit() {
        val recipe=state.current?.recipe ?: return
        if(recipe!=baseline) { undo.addLast(baseline); if(undo.size>60) undo.removeFirst(); redo.clear(); baseline=recipe }
        state.current?.let(::persist)
        state=state.copy(canUndo=undo.isNotEmpty(),canRedo=redo.isNotEmpty())
    }
    fun change(block:(Recipe)->Recipe) { state.current?.let {
        val next=block(it.recipe)
        if(!next.crop.valid()) { message("This crop is too narrow. Keep at least 1% of each image dimension.");return }
        draft(next); commit()
    } }
    fun undo() { if(undo.isNotEmpty()) { val r=undo.removeLast(); redo.addLast(baseline); baseline=r; draft(r); state.current?.let(::persist); state=state.copy(canUndo=undo.isNotEmpty(),canRedo=true) } }
    fun redo() { if(redo.isNotEmpty()) { val r=redo.removeLast(); undo.addLast(baseline); baseline=r; draft(r); state.current?.let(::persist); state=state.copy(canUndo=true,canRedo=redo.isNotEmpty()) } }
    private fun render() {
        previewJob?.cancel(); val p=state.current ?: return
        state=state.copy(previewBusy=true)
        previewJob=viewModelScope.launch {
            delay(150)
            try {
                val context=currentCoroutineContext()
                val bitmap=withContext(Dispatchers.Default) { context.ensureActive(); engine.render(File(p.source.path),p.recipe,true) { context.ensureActive() } }
                val original=state.original ?: withContext(Dispatchers.Default) { engine.decode(File(p.source.path),minOf(engine.budget,900_000L)) { context.ensureActive() } }
                if(state.current==p) state=state.copy(preview=bitmap,original=original,previewBusy=false)
            } catch(c:CancellationException) { throw c } catch(e:Exception) { state=state.copy(previewBusy=false,message=e.message ?: "Preview failed. Your original image is safe.") }
        }
    }
    private fun runTask(label:String,block:suspend ()->Unit) {
        if(work?.isActive==true) return
        state=state.copy(busy=label)
        work=viewModelScope.launch {
            try { block() } catch(c:CancellationException) { state=state.copy(message="Cancelled. Completed files and projects are kept.") }
            catch(e:Exception) { state=state.copy(message=e.message ?: "Operation failed. Your source is safe.") }
            finally { state=state.copy(busy=null); refreshProjects() }
        }
    }
    fun cancel() { work?.cancel() }
    fun import(uris:List<Uri>) = runTask("Importing private copies…") {
        val ctx=currentCoroutineContext(); var first:Project?=null
        withContext(Dispatchers.IO) { for(uri in uris.take(30)) { ctx.ensureActive(); val p=store.import(uri) { ctx.ensureActive() }; if(first==null) first=p } }
        first?.let { select(it) }
        if(uris.size>30) message("Imported the first 30 images. Import another batch to add more.")
    }
    fun prepare() = runTask("Rendering and measuring export…") {
        commit(); previewJob?.cancelAndJoin(); val p=state.current ?: return@runTask; val settings=options; val ctx=currentCoroutineContext()
        val output=File(store.exports,"${Geometry.safeName(p.source.name)}-${UUID.randomUUID().toString().take(8)}.${settings.format.extension}")
        val e=withContext(Dispatchers.Default) { store.save(p); engine.export(p,settings,output) { ctx.ensureActive() } }
        val preview=withContext(Dispatchers.Default) { engine.decode(File(e.path),600_000) { ctx.ensureActive() } }
        state=state.copy(prepared=e,preparedPreview=preview,previewBusy=false)
    }
    fun dismissExport() { state=state.copy(prepared=null,preparedPreview=null) }
    fun save(uri:Uri) { val e=state.prepared ?: return; runTask("Saving new copy…") { withContext(Dispatchers.IO) { store.saveExport(e,uri) }; message("Saved a new copy.") } }
    fun saveBatch(uri:Uri) = runTask("Saving batch…") {
        val ctx=currentCoroutineContext(); val exports=state.batch
        val count=withContext(Dispatchers.IO) { store.copyBatch(exports,uri) { ctx.ensureActive() } }; message("Saved $count new images.")
    }
    fun batch(ids:Set<String>,preset:Recipe?,prefix:String,resize:Pair<Int,Int>?=null) = runTask("Starting batch…") {
        val selected=state.projects.filter { it.source.id in ids }; require(selected.isNotEmpty()) { "Select images first." }
        val settings=options; val ctx=currentCoroutineContext(); state=state.copy(batch=emptyList()); val failures=mutableListOf<String>()
        for((i,p) in selected.withIndex()) {
            ctx.ensureActive(); state=state.copy(busy="Processing ${i+1} of ${selected.size}: ${p.source.name}")
            val name="${Geometry.safeName(prefix.ifBlank { p.source.name })}-${i+1}-${UUID.randomUUID().toString().take(6)}.${settings.format.extension}"
            try {
                val e=withContext(Dispatchers.Default) { engine.export(p.copy(recipe=(preset ?: p.recipe).let { if(resize==null)it else it.copy(width=resize.first,height=resize.second) }),settings,File(store.exports,name)) { ctx.ensureActive() } }
                state=state.copy(batch=state.batch+e)
            } catch(c:CancellationException) { throw c } catch(e:Exception) { failures.add("${p.source.name}: ${e.message}") }
        }
        message("${state.batch.size} images prepared. ${failures.size} failed."+if(failures.isNotEmpty()) "\n"+failures.joinToString("\n") else "")
    }
    fun savePreset(name:String) { val r=state.current?.recipe ?: return; runTask("Saving preset…") { withContext(Dispatchers.IO) { store.savePreset(name,r) }; message("Preset saved without markup layers.") } }
    fun collage(ids:Set<String>,columns:Int) = runTask("Building collage…") {
        val ps=state.projects.filter { it.source.id in ids }; val ctx=currentCoroutineContext()
        val project=withContext(Dispatchers.Default) { store.collage(ps,columns,engine) { ctx.ensureActive() } }; select(project)
    }
    fun clearOutputs()=runTask("Clearing prepared exports…") { withContext(Dispatchers.IO){store.clearOutputs()};state=state.copy(batch=emptyList(),prepared=null,preparedPreview=null) }
    fun remove(p:Project) = runTask("Removing private project…") { withContext(Dispatchers.IO) { store.remove(p) } }
}
