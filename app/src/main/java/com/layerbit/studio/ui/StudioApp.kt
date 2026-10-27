@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.layerbit.studio.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.DialogProperties
import com.layerbit.studio.StudioViewModel
import com.layerbit.studio.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*

@Composable fun StudioApp(vm:StudioViewModel,share:(List<Exported>)->Unit) {
    val context=LocalContext.current
    val prefs=remember { context.getSharedPreferences("appearance",0) }
    var theme by rememberSaveable { mutableStateOf(prefs.getString("theme","System") ?: "System") }
    var motion by rememberSaveable { mutableStateOf(prefs.getBoolean("motion",true)) }
    var showLicenses by rememberSaveable {mutableStateOf(false)}
    var clearExports by rememberSaveable {mutableStateOf(false)}
    var settings by rememberSaveable { mutableStateOf(false) }
    var batchDialog by rememberSaveable { mutableStateOf(false) }
    var presetDialog by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable { mutableStateOf("My look") }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var tool by rememberSaveable { mutableStateOf(Tool.CROP) }
    var deleteProject by remember { mutableStateOf<Project?>(null) }
    val state=vm.state
    val photos=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { if(it.isNotEmpty()) vm.import(it) }
    val files=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if(it.isNotEmpty()) vm.import(it.toList()) }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(state.prepared?.mime ?: vm.options.format.mime)) { it?.let(vm::save) }
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::saveBatch) }
    BackHandler(state.current!=null && state.busy==null) { vm.home() }
    StudioTheme(theme) { MotionProvider(motion,tool to (state.current!=null)) {
        Scaffold(containerColor=MaterialTheme.colorScheme.background) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(state.current!=null) TextButton(onClick=vm::home) { Text("Home") }
                    Column(Modifier.weight(1f)) { Text("Layerbit Studio",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                        Text(if(state.current==null) "A little room for your imagination" else state.current.source.name,style=MaterialTheme.typography.labelSmall,maxLines=1) }
                    TextButton(onClick={ settings=true }) { Text("Settings") }
                }
                if(state.current==null) {
                    LazyColumn(Modifier.fillMaxSize().testTag("homeList"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        item {
                            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Text("Make it\nyour image.",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
                                    Text("Thoughtful tools. Free to create. Your pictures stay on your phone.")
                                    Button(onClick={ photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },modifier=Modifier.fillMaxWidth().testTag("openPhotos")) { Text("Open photos") }
                                    OutlinedButton(onClick={ files.launch(arrayOf("image/*")) },modifier=Modifier.fillMaxWidth()) { Text("Browse image files") }
                                    Text("Edits save as private projects. Export always makes a new copy.",style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item { Text("Made for the everyday. Ready for more.",style=MaterialTheme.typography.titleMedium) }
                        item { LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            items(listOf(Tool.CROP,Tool.COMPRESS,Tool.ADJUST,Tool.MARKUP,Tool.MASK,Tool.CLONE)) { t -> ToolCard(t,false) {
                                tool=t; if(state.projects.isNotEmpty()) vm.select(state.projects.first()) else files.launch(arrayOf("image/*"))
                            } }
                        } }
                        item {
                            Text("Your projects",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                            if(state.projects.isEmpty()) Text("Your first image starts here. Open a photo above to try the editor.",Modifier.padding(top=8.dp))
                            else {
                                Text("Select pictures for a batch or a collage. Open a project to edit it.",style=MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick={ selected=if(selected.size==state.projects.size) emptyList() else state.projects.map { it.source.id } }) { Text(if(selected.size==state.projects.size)"Select none" else "Select all") }
                                    OutlinedButton(enabled=selected.isNotEmpty(),onClick={batchDialog=true}) { ToolGlyph(Tool.BATCH,Modifier.size(24.dp)); Text(" Batch (${selected.size})") }
                                    OutlinedButton(enabled=selected.size in 2..9,onClick={vm.collage(selected.toSet(),if(selected.size<=4)2 else 3)}) { ToolGlyph(Tool.COLLAGE,Modifier.size(24.dp)); Text(" Collage") }
                                }
                            }
                        }
                        items(state.projects,key={it.source.id}) { project ->
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Checkbox(checked=project.source.id in selected,onCheckedChange={ checked -> selected=if(checked)selected+project.source.id else selected-project.source.id },modifier=Modifier.semantics { contentDescription="Select ${project.source.name}" })
                                    Thumbnail(project,vm.engine)
                                    Column(Modifier.weight(1f).clickable { vm.select(project) }.padding(12.dp)) {
                                        Text(project.source.name,fontWeight=FontWeight.SemiBold,maxLines=2)
                                        Text("${project.source.width} × ${project.source.height}",style=MaterialTheme.typography.bodySmall)
                                        Text("Open project",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
                                    }
                                    TextButton(onClick={deleteProject=project}) { Text("Remove") }
                                }
                            }
                        }
                        if(state.batch.isNotEmpty()) item {
                            Text("${state.batch.size} prepared exports",style=MaterialTheme.typography.titleMedium)
                            Text(state.batch.joinToString("\n") { "${File(it.path).name}: ${it.width} × ${it.height}, ${it.bytes/1024} KB" },style=MaterialTheme.typography.bodySmall)
                            Row { Button(onClick={folder.launch(null)}) { Text("Save to folder") }; TextButton(onClick={share(state.batch)}) { Text("Share batch") } }
                        }
                    }
                } else Editor(vm,tool,{ tool=it },{presetDialog=true})
            }
        }
        if(showLicenses) AlertDialog(onDismissRequest={showLicenses=false},title={Text("Third-party licenses")},text={Text(remember { "Kotlin and AndroidX components are licensed under Apache-2.0. No Sheaf source or assets are included.\n\n"+context.assets.open("apache-2.0.txt").bufferedReader().use{it.readText()} },Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={showLicenses=false}){Text("Close")}})
        if(clearExports) AlertDialog(onDismissRequest={clearExports=false},title={Text("Clear prepared exports?")},text={Text("Deletes private encoded outputs, including files offered to other apps through Share. Save important results first. Projects and copies saved to your chosen destination are kept.")},confirmButton={TextButton(onClick={vm.clearOutputs();clearExports=false}){Text("Clear outputs")}},dismissButton={TextButton(onClick={clearExports=false}){Text("Cancel")}})
        if(settings) AlertDialog(onDismissRequest={settings=false},title={Text("Your studio")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Appearance"); Choices(listOf("System","Light","Dark"),theme) { theme=it; prefs.edit().putString("theme",it).apply() }
            Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(motion,{motion=it;prefs.edit().putBoolean("motion",it).apply()},modifier=Modifier.testTag("motionSwitch").semantics{contentDescription="Lively tool icons"});Text("Lively tool icons") }
            Text("Animations pause when the app is backgrounded and follow Android's Remove animations setting.",style=MaterialTheme.typography.bodySmall)
            Text("Privacy by construction",fontWeight=FontWeight.Bold)
            Text("No network permission, accounts, ads, or telemetry. Imports are private copies. Exported images remove GPS. Other apps receive an image only when you choose Save or Share.")
            Text("Working limit: ${"%.1f".format(vm.engine.budget/1_000_000f)} megapixels. Large sources use a smaller working image. Check the dimensions in the export preview.")
            TextButton(onClick={clearExports=true}){Text("Clear prepared exports")}
            TextButton(onClick={showLicenses=true}){Text("Third-party licenses")}
            Text("Version 0.1.0 · Layerbit\nAdvanced neural tools are being evaluated. Included editing tools are free.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick={settings=false}) { Text("Done") }})
        if(presetDialog) AlertDialog(onDismissRequest={presetDialog=false},title={Text("Save a reusable recipe")},text={Column {
            OutlinedTextField(presetName,{presetName=it},label={Text("Preset name")},singleLine=true)
            Text("Stores geometry, resize, colour and filter settings. Personal markup layers are excluded.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick={vm.savePreset(presetName);presetDialog=false},enabled=presetName.isNotBlank()) {Text("Save preset")}},dismissButton={TextButton(onClick={presetDialog=false}) {Text("Cancel")}})
        if(batchDialog) BatchDialog(vm,selected.toSet(),{batchDialog=false})
        deleteProject?.let { p -> AlertDialog(onDismissRequest={deleteProject=null},title={Text("Remove private project?")},text={Text("This removes the imported working copy and its edits. Your original and previously saved exports remain in their destinations.")},confirmButton={TextButton(onClick={vm.remove(p);selected=selected-p.source.id;deleteProject=null}) {Text("Remove")}},dismissButton={TextButton(onClick={deleteProject=null}) {Text("Cancel")}}) }
        state.prepared?.let { e -> AlertDialog(onDismissRequest=vm::dismissExport,title={Text("Ready to save")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            state.preparedPreview?.let { Image(it.asImageBitmap(),"Encoded output preview",Modifier.fillMaxWidth().height(180.dp),contentScale=ContentScale.Fit) }
            Text("${e.width} × ${e.height} · ${"%.1f".format(e.bytes/1024.0)} KB",fontWeight=FontWeight.Bold,modifier=Modifier.testTag("exportDimensions"))
            Text(if(e.mime=="image/png") "PNG · lossless encoding of edited pixels" else "${e.mime} · encoder quality ${e.quality}")
            Text(e.note,style=MaterialTheme.typography.bodySmall)
            state.current?.let { Text("Original: ${it.source.width} × ${it.source.height}. Check output dimensions before saving.",style=MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick={share(listOf(e))},modifier=Modifier.fillMaxWidth()) { Text("Share new copy") }
        }},confirmButton={Button(onClick={save.launch(File(e.path).name)}) {Text("Save new copy")}},dismissButton={TextButton(onClick=vm::dismissExport) {Text("Close")}}) }
        state.message?.let { message -> AlertDialog(onDismissRequest={vm.message(null)},title={Text("Studio")},text={Text(message,Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={vm.message(null)}) {Text("OK")}}) }
        state.busy?.let { label -> AlertDialog(onDismissRequest={},properties=DialogProperties(dismissOnBackPress=false,dismissOnClickOutside=false),title={Text(label)},text={LinearProgressIndicator(Modifier.fillMaxWidth())},confirmButton={TextButton(onClick=vm::cancel) {Text("Cancel")}}) }
    } }
}

@Composable private fun Thumbnail(p:Project,engine:ImageEngine) {
    val image by produceState<Bitmap?>(null,p.source.id) { value=withContext(Dispatchers.Default) { runCatching { engine.decode(File(p.source.path),30_000) }.getOrNull() } }
    Box(Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) { image?.let { Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop) } }
}
@Composable private fun ToolCard(tool:Tool,selected:Boolean,onClick:()->Unit) {
    Card(onClick=onClick,modifier=Modifier.width(110.dp),colors=CardDefaults.cardColors(containerColor=if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) { ToolGlyph(tool,Modifier.size(40.dp));Text(tool.title,style=MaterialTheme.typography.labelLarge);Text(tool.hint,style=MaterialTheme.typography.labelSmall,minLines=2) }
    }
}
@Composable fun Choices(labels:List<String>,selected:String,pick:(String)->Unit) {
    LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { items(labels) { label -> FilterChip(selected==label,onClick={pick(label)},label={Text(label)}) } }
}
@Composable private fun Knob(label:String,value:Float,range:ClosedFloatingPointRange<Float>,change:(Float)->Unit,done:()->Unit,reset:()->Unit) {
    Column { Row(verticalAlignment=Alignment.CenterVertically) { Text(label,Modifier.weight(1f));Text("%.2f".format(value),style=MaterialTheme.typography.labelSmall);TextButton(onClick=reset,modifier=Modifier.semantics{contentDescription="Reset $label"}) {Text("Reset")} }
        Slider(value.coerceIn(range.start,range.endInclusive),change,valueRange=range,onValueChangeFinished=done,modifier=Modifier.semantics {contentDescription=label}) }
}

@Composable private fun Editor(vm:StudioViewModel,tool:Tool,pick:(Tool)->Unit,savePreset:()->Unit) {
    val p=vm.state.current ?: return; val r=p.recipe
    var kind by rememberSaveable { mutableStateOf(MarkKind.DRAW) }; var text by rememberSaveable { mutableStateOf("Your words") }
    var brush by rememberSaveable { mutableFloatStateOf(.02f) }; var ink by rememberSaveable { mutableIntStateOf(0xfff39d75.toInt()) }
    var sample by remember { mutableStateOf<Point?>(null) }; var chooseSample by remember { mutableStateOf(false) }
    var navigate by rememberSaveable { mutableStateOf(false) }; var compare by rememberSaveable { mutableStateOf(false) }
    val activeKind=when(tool) {Tool.MASK->MarkKind.ERASE;Tool.CLONE->MarkKind.CLONE;else->kind}
    val draw=tool in listOf(Tool.MARKUP,Tool.MASK,Tool.CLONE) && !navigate && !compare
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            TextButton(onClick=vm::undo,enabled=vm.state.canUndo) {Text("Undo")};TextButton(onClick=vm::redo,enabled=vm.state.canRedo) {Text("Redo")}
            FilterChip(compare,{compare=!compare},label={Text(if(compare)"Original" else "Compare")})
            TextButton(onClick=savePreset) { Text("Save preset") }
            Button(onClick={pick(Tool.EXPORT)},modifier=Modifier.testTag("editorExport")) {Text("Export")}
        }
        LazyRow(contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(Tool.entries.filter { it !in listOf(Tool.BATCH,Tool.COLLAGE) }) { t -> ToolCard(t,tool==t) {vm.commit();pick(t)} }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val panelHeight=(maxHeight*.52f).coerceAtMost(300.dp)
            @Composable fun preview(modifier:Modifier) {
                Column(modifier) {
                    EditorCanvas(if(compare)vm.state.original else vm.state.preview,draw,activeKind,brush,ink,text,sample,chooseSample,
                        {sample=it;chooseSample=false},{mark-> if(r.marks.size<300) vm.change {it.copy(marks=it.marks+mark)} else vm.message("This project has reached 300 layers.")},Modifier.weight(1f))
                    if(vm.state.previewBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if(compare)"Original · unedited private copy" else "Preview · ${vm.state.preview?.width ?: 0} × ${vm.state.preview?.height ?: 0}",Modifier.padding(8.dp),style=MaterialTheme.typography.labelSmall)
                }
            }
            @Composable fun controls(modifier:Modifier) {
                Column(modifier.background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(tool.title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    when(tool) {
                        Tool.CROP -> {
                            Choices(listOf("Free","1:1","4:3","3:2","16:9","9:16"),"Free") { v -> vm.change {it.copy(crop=if(v=="Free")Crop() else {val a=v.split(':');Geometry.aspectCrop(p.source.width,p.source.height,a[0].toFloat()/a[1].toFloat())})} }
                            Text("Free crop: adjust each edge. Crop is applied before rotation and layers.",style=MaterialTheme.typography.bodySmall)
                            Knob("Left",r.crop.left,0f..(r.crop.right-.01f),{vm.draft(r.copy(crop=r.crop.copy(left=it)))},vm::commit,{vm.change{it.copy(crop=it.crop.copy(left=0f))}})
                            Knob("Top",r.crop.top,0f..(r.crop.bottom-.01f),{vm.draft(r.copy(crop=r.crop.copy(top=it)))},vm::commit,{vm.change{it.copy(crop=it.crop.copy(top=0f))}})
                            Knob("Right",r.crop.right,(r.crop.left+.01f)..1f,{vm.draft(r.copy(crop=r.crop.copy(right=it)))},vm::commit,{vm.change{it.copy(crop=it.crop.copy(right=1f))}})
                            Knob("Bottom",r.crop.bottom,(r.crop.top+.01f)..1f,{vm.draft(r.copy(crop=r.crop.copy(bottom=it)))},vm::commit,{vm.change{it.copy(crop=it.crop.copy(bottom=1f))}})
                        }
                        Tool.ROTATE -> {
                            FlowRow { OutlinedButton(onClick={vm.change{it.copy(rotation=(it.rotation+270)%360)}}) {Text("↶ 90°")};OutlinedButton(onClick={vm.change{it.copy(rotation=(it.rotation+90)%360)}}) {Text("↷ 90°")} }
                            Row { FilterChip(r.flipX,{vm.change{it.copy(flipX=!it.flipX)}},label={Text("Flip horizontal")});FilterChip(r.flipY,{vm.change{it.copy(flipY=!it.flipY)}},label={Text("Flip vertical")}) }
                            Knob("Straighten degrees",r.straighten,-45f..45f,{vm.draft(r.copy(straighten=it))},vm::commit,{vm.change{it.copy(straighten=0f)}})
                            Text("Straightening expands the canvas; corners stay transparent. JPEG exports fill transparency with white.",style=MaterialTheme.typography.bodySmall)
                        }
                        Tool.RESIZE -> ResizePanel(p,vm)
                        Tool.ADJUST -> {
                            val names=listOf("Brightness","Contrast","Shadows","Highlights","Saturation","Warmth","Hue","Sharpness")
                            val values=listOf(r.tone.brightness,r.tone.contrast,r.tone.shadows,r.tone.highlights,r.tone.saturation,r.tone.warmth,r.tone.hue,r.tone.sharpness)
                            fun update(index:Int,v:Float,commit:Boolean) { val a=values.toMutableList();a[index]=v;vm.draft(r.copy(tone=Tone(a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7])));if(commit)vm.commit() }
                            names.forEachIndexed { i,n -> Knob(n,values[i],(if(i==7)0f else -1f)..1f,{update(i,it,false)},vm::commit,{update(i,0f,true)}) }
                        }
                        Tool.FILTER -> {
                            Choices(listOf("Original","Mono","Warm","Cool","Fade","Noir"),r.filter) {v->vm.change{it.copy(filter=v)}}
                            Knob("Intensity",r.intensity,0f..1f,{vm.draft(r.copy(intensity=it))},vm::commit,{vm.change{it.copy(intensity=1f)}})
                            Text("Saved recipes",fontWeight=FontWeight.Bold)
                            if(vm.state.presets.isEmpty()) Text("Use Save preset after making a look you like.")
                            vm.state.presets.forEach { (name,recipe) -> OutlinedButton(onClick={vm.change{recipe.copy(marks=it.marks)}}) {Text(name)} }
                        }
                        Tool.MARKUP,Tool.MASK,Tool.CLONE -> {
                            FilterChip(navigate,{navigate=!navigate},label={Text(if(navigate)"Navigate / zoom" else "Paint on image")})
                            if(tool==Tool.MARKUP) Choices(listOf("Draw","Text","Arrow","Box","Ellipse","Cover","Pixelate","Blur"),kind.name.lowercase().replaceFirstChar {it.uppercase()}) {kind=MarkKind.valueOf(it.uppercase())}
                            if(tool==Tool.CLONE) {
                                Text("Choose a source point, then paint where it should be copied. Each stroke samples the canvas below that layer.")
                                Button(onClick={chooseSample=true;navigate=false}) {Text(if(sample==null)"Pick clone source" else "Change clone source")}
                                if(chooseSample) Text("Tap the image to choose the source.",color=MaterialTheme.colorScheme.primary)
                            }
                            if(tool==Tool.MASK) Text("Paint away pixels to reveal transparency. Undo or hide this layer to restore them. Use PNG or WebP to keep transparency.")
                            if(activeKind==MarkKind.TEXT) {
                                OutlinedTextField(text,{text=it.take(300)},label={Text("Text to place")})
                                OutlinedButton(onClick={vm.change{it.copy(marks=it.marks+Mark(MarkKind.TEXT,listOf(Point(.15f,.5f)),ink,brush,text))}}){Text("Place text in centre")}
                            }
                            if(activeKind==MarkKind.COVER) Text("Cover exports as an opaque rectangle. Share the exported copy; the private project still contains the original image.")
                            if(tool==Tool.MARKUP) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(0xfff39d75,0xff83e0c1,0xffffffff,0xff131c22,0xff8bb6ff).forEach { color ->
                                val value=color.toInt(); Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(value)).border(if(ink==value)3.dp else 1.dp,MaterialTheme.colorScheme.primary,RoundedCornerShape(24.dp)).clickable {ink=value}.semantics {contentDescription="Choose colour ${java.lang.Integer.toHexString(value)}"})
                            } }
                            Knob(if(activeKind==MarkKind.TEXT)"Text size" else "Brush / effect size",brush,.004f.. .12f,{brush=it},{},{brush=.02f})
                            Text(if(activeKind==MarkKind.TEXT)"Tap the image to place text." else if(activeKind in listOf(MarkKind.BLUR,MarkKind.PIXELATE))"Drag a rectangle over the area. Use Cover for opaque hiding of sensitive text. Blur and pixelation are visual effects, not secure redaction." else "Drag on the image. Each completed gesture becomes an editable layer.",style=MaterialTheme.typography.bodySmall)
                        }
                        Tool.LAYERS -> {
                            Text("Top row is the last painted layer. Geometry and colour changes apply below these overlays.",style=MaterialTheme.typography.bodySmall)
                            if(r.marks.isEmpty()) Text("Draw, add text, erase, blur or clone to create a layer.")
                            r.marks.indices.reversed().forEach { index -> val mark=r.marks[index]
                                Column { Row(verticalAlignment=Alignment.CenterVertically) {
                                    Checkbox(mark.visible,{v->vm.change{it.copy(marks=it.marks.toMutableList().apply{set(index,mark.copy(visible=v))})}},modifier=Modifier.semantics {contentDescription="Layer ${index+1} visibility"})
                                    Text("${index+1}. ${mark.kind.name.lowercase()}",Modifier.weight(1f))
                                    TextButton(onClick={vm.change{it.copy(marks=it.marks.filterIndexed {i,_->i!=index})}}) {Text("Delete")}
                                }
                                Slider(mark.opacity,{v->vm.draft(r.copy(marks=r.marks.toMutableList().apply{set(index,mark.copy(opacity=v))}))},onValueChangeFinished=vm::commit,enabled=mark.kind!=MarkKind.COVER,modifier=Modifier.semantics{contentDescription="Layer ${index+1} opacity"})
                                Row { TextButton(enabled=index<r.marks.lastIndex,onClick={vm.change{it.copy(marks=it.marks.toMutableList().apply{val m=removeAt(index);add(index+1,m)})}}) {Text("Move up")};TextButton(enabled=index>0,onClick={vm.change{it.copy(marks=it.marks.toMutableList().apply{val m=removeAt(index);add(index-1,m)})}}) {Text("Move down")} }
                                HorizontalDivider()
                                }
                            }
                        }
                        Tool.METADATA -> {Text(p.source.metadata);Text("Exports remove all source metadata by default. You can preserve a limited set of camera/date fields for JPEG in Export. GPS is always removed.");Button(onClick={vm.options=vm.options.copy(keepCamera=false);pick(Tool.EXPORT)}){Text("Export without metadata")}}
                        Tool.COMPRESS,Tool.CONVERT,Tool.EXPORT -> ExportPanel(vm) {vm.prepare()}
                        else -> Unit
                    }
                }
            }
            if(maxWidth>650.dp) Row(Modifier.fillMaxSize()) { preview(Modifier.weight(1.2f).fillMaxHeight());controls(Modifier.weight(1f).fillMaxHeight()) }
            else Column(Modifier.fillMaxSize()) { preview(Modifier.weight(1f).fillMaxWidth()); controls(Modifier.fillMaxWidth().heightIn(max=panelHeight)) }
        }
    }
}

@Composable private fun ResizePanel(p:Project,vm:StudioViewModel) {
    val r=p.recipe
    val cw=max(1f,p.source.width*(r.crop.right-r.crop.left)).toDouble();val ch=max(1f,p.source.height*(r.crop.bottom-r.crop.top)).toDouble()
    val angle=(r.rotation+r.straighten)*PI/180;val naturalW=(abs(cw*cos(angle))+abs(ch*sin(angle))).roundToInt();val naturalH=(abs(cw*sin(angle))+abs(ch*cos(angle))).roundToInt()
    var width by rememberSaveable(p.source.id,r.width) {mutableStateOf((if(r.width>0)r.width else naturalW).toString())}
    var height by rememberSaveable(p.source.id,r.height) {mutableStateOf((if(r.height>0)r.height else naturalH).toString())}
    var lock by rememberSaveable {mutableStateOf(true)}
    val aspect=naturalW.toDouble()/naturalH
    OutlinedTextField(width,{v->width=v;if(lock)v.toIntOrNull()?.let{height=(it/aspect).roundToInt().toString()}},label={Text("Width in pixels")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(height,{v->height=v;if(lock)v.toIntOrNull()?.let{width=(it*aspect).roundToInt().toString()}},label={Text("Height in pixels")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(lock,{lock=it},modifier=Modifier.semantics{contentDescription="Lock aspect ratio"});Text("Lock aspect ratio")}
    Choices(listOf("25%","50%","75%","100%","150%"),"") {v->val f=v.removeSuffix("%").toFloat()/100;width=max(1,(naturalW*f).roundToInt()).toString();height=max(1,(naturalH*f).roundToInt()).toString()}
    Text("Requested output: $width × $height. Larger than the source uses standard interpolation; it adds no new detail.",style=MaterialTheme.typography.bodySmall)
    Button(onClick={val w=width.toIntOrNull();val h=height.toIntOrNull();if(w!=null&&h!=null&&w in 1..8192&&h in 1..8192&&w.toLong()*h<=vm.engine.budget)vm.change{it.copy(width=w,height=h)} else vm.message("Enter positive dimensions up to 8192 pixels and within this device's ${"%.1f".format(vm.engine.budget/1_000_000f)} MP limit.")}){Text("Apply dimensions")}
    TextButton(onClick={vm.change{it.copy(width=0,height=0)}}){Text("Reset resize")}
}

@Composable private fun ExportPanel(vm:StudioViewModel,prepare:()->Unit) {
    val o=vm.options
    var target by rememberSaveable {mutableStateOf(if(o.targetBytes>0)(o.targetBytes/1024).toString() else "")}
    var unit by rememberSaveable {mutableStateOf("KB")}
    val parsed=target.toDoubleOrNull();val valid=target.isBlank() || (parsed!=null&&parsed.isFinite()&&parsed>0&&parsed<=100000)
    Choices(OutputFormat.entries.map{it.name},o.format.name){format->vm.options=o.copy(format=OutputFormat.valueOf(format),targetBytes=if(format!="PNG"&&valid&&parsed!=null)(parsed*(if(unit=="KB")1024 else 1024*1024)).toLong() else 0)}
    Text(when(o.format){OutputFormat.JPEG->"JPEG: lossy, transparent areas become white.";OutputFormat.PNG->"PNG: lossless encoding of the edited pixels, with transparency.";OutputFormat.WEBP->"WebP: lossy with transparency."},style=MaterialTheme.typography.bodySmall)
    if(o.format!=OutputFormat.PNG) {
        Text("Maximum encoder quality: ${o.quality}")
        Slider(o.quality.toFloat(),{vm.options=vm.options.copy(quality=it.roundToInt())},valueRange=5f..100f,modifier=Modifier.semantics{contentDescription="Export quality"})
        OutlinedTextField(target,{v->target=v;val n=v.toDoubleOrNull();vm.options=vm.options.copy(targetBytes=if(n!=null&&n.isFinite()&&n>0&&n<=100000)(n*(if(unit=="KB")1024 else 1024*1024)).toLong() else 0)},label={Text("Maximum file size (optional)")},supportingText={Text(if(valid)"Blank keeps the selected quality." else "Enter a positive size.")},isError=!valid,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
        Choices(listOf("KB","MB"),unit){u->unit=u;vm.options=vm.options.copy(targetBytes=if(parsed!=null&&parsed.isFinite()&&parsed>0&&parsed<=100000)(parsed*(if(u=="KB")1024 else 1024*1024)).toLong() else 0)}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(o.allowShrink,{vm.options=vm.options.copy(allowShrink=it)},modifier=Modifier.semantics{contentDescription="Allow smaller dimensions to meet the limit"});Text("Allow smaller dimensions to meet the limit")}
    }
    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(o.keepCamera,{vm.options=vm.options.copy(keepCamera=it)},enabled=o.format==OutputFormat.JPEG,modifier=Modifier.semantics{contentDescription="Keep camera and date fields, JPEG only"});Text("Keep camera/date fields (JPEG only)")}
    Text("GPS is removed. Exact size and dimensions appear in the encoded preview before saving. PNG has no target-size control.",style=MaterialTheme.typography.bodySmall)
    Button(onClick=prepare,enabled=valid||o.format==OutputFormat.PNG,modifier=Modifier.fillMaxWidth().testTag("prepareOutput")){ToolGlyph(Tool.EXPORT,Modifier.size(24.dp));Text(" Prepare output")}
}

@Composable private fun BatchDialog(vm:StudioViewModel,ids:Set<String>,close:()->Unit) {
    var preset by remember {mutableStateOf("Each project's edits")};var prefix by rememberSaveable{mutableStateOf("Studio")}
    var w by rememberSaveable{mutableStateOf("")};var h by rememberSaveable{mutableStateOf("")}
    AlertDialog(onDismissRequest=close,title={Text("Batch · ${ids.size} images")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Process sequentially. A failed image does not discard successful outputs. Keep the app open while processing.")
        Choices(listOf("Each project's edits")+vm.state.presets.map{it.first},preset){preset=it}
        OutlinedTextField(prefix,{prefix=it},label={Text("Output filename prefix")},singleLine=true)
        Text("Optional exact dimensions for every image. Leave both blank to use the selected recipe. Exact dimensions may change aspect ratio.",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(w,{w=it},label={Text("Width")},modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number));OutlinedTextField(h,{h=it},label={Text("Height")},modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))}
        ExportPanel(vm){
            val recipe=vm.state.presets.firstOrNull{it.first==preset}?.second
            var dimensions:Pair<Int,Int>?=null
            if(w.isNotBlank()||h.isNotBlank()) {
                val width=w.toIntOrNull();val height=h.toIntOrNull()
                if(width==null||height==null||width !in 1..8192||height !in 1..8192||width.toLong()*height>vm.engine.budget){vm.message("Enter both dimensions within the device working limit.");return@ExportPanel}
                dimensions=width to height
            }
            vm.batch(ids,recipe,prefix,dimensions);close()
        }
    }},confirmButton={TextButton(onClick=close){Text("Close")}})
}

@Composable private fun EditorCanvas(bitmap:Bitmap?,drawing:Boolean,kind:MarkKind,brush:Float,color:Int,text:String,
    sample:Point?,picking:Boolean,setSample:(Point)->Unit,add:(Mark)->Unit,modifier:Modifier) {
    var zoom by rememberSaveable {mutableFloatStateOf(1f)};var pan by remember {mutableStateOf(Offset.Zero)}
    var points by remember {mutableStateOf(listOf<Point>())}
    val latestAdd by rememberUpdatedState(add);val latestSample by rememberUpdatedState(setSample)
    Column(modifier) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
            TextButton(onClick={zoom=(zoom/1.25f).coerceAtLeast(1f);if(zoom==1f)pan=Offset.Zero},modifier=Modifier.semantics{contentDescription="Zoom out"}){Text("−")}
            TextButton(onClick={zoom=1f;pan=Offset.Zero}){Text("${(zoom*100).roundToInt()}% · Fit")}
            TextButton(onClick={zoom=(zoom*1.25f).coerceAtMost(6f)},modifier=Modifier.semantics{contentDescription="Zoom in"}){Text("+")}
        }
        if(bitmap==null){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("Preparing preview…")};return}
        val image=remember(bitmap){bitmap.asImageBitmap()}
        fun bounds(size:IntSize):Rect {
            val fit=min(size.width.toFloat()/bitmap.width,size.height.toFloat()/bitmap.height)*zoom
            val w=bitmap.width*fit;val h=bitmap.height*fit
            return Rect((size.width-w)/2+pan.x,(size.height-h)/2+pan.y,(size.width+w)/2+pan.x,(size.height+h)/2+pan.y)
        }
        fun normal(at:Offset,size:IntSize):Point? {
            val rect=bounds(size);if(!rect.contains(at))return null
            return Point(((at.x-rect.left)/rect.width).coerceIn(0f,1f),((at.y-rect.top)/rect.height).coerceIn(0f,1f))
        }
        val gestures=when {
            drawing && (picking || kind==MarkKind.TEXT) -> Modifier.pointerInput(bitmap.width,bitmap.height,zoom,pan,kind,picking,text,color,brush){detectTapGestures { at->normal(at,size)?.let {p-> if(picking)latestSample(p) else latestAdd(Mark(kind,listOf(p),color,brush,text))} }}
            drawing -> Modifier.pointerInput(bitmap.width,bitmap.height,zoom,pan,kind,sample,color,brush){detectDragGestures(
                onDragStart={at->points=normal(at,size)?.let{listOf(it)}?:emptyList()},
                onDragCancel={points=emptyList()},
                onDragEnd={if(points.isNotEmpty() && (kind!=MarkKind.CLONE || sample!=null))latestAdd(Mark(kind,points,color,brush,text,source=sample));points=emptyList()},
                onDrag={change,_->change.consume();normal(change.position,size)?.let{p->if(points.size<2000)points=points+p}}
            )}
            else -> Modifier.pointerInput(bitmap.width,bitmap.height){detectTransformGestures{_,delta,factor,_->zoom=(zoom*factor).coerceIn(1f,6f);pan=if(zoom>1f)pan+delta else Offset.Zero}}
        }
        Canvas(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xff1a232b)).then(gestures).semantics {
            contentDescription=if(drawing)"Image editing canvas. ${if(picking)"Tap clone source" else kind.name.lowercase()} tool. Touch interaction required." else "Image preview. Pinch to zoom or use zoom buttons."
        }) {
            val cell=18.dp.toPx()
            for(y in 0..(size.height/cell).toInt())for(x in 0..(size.width/cell).toInt())if((x+y)%2==0)drawRect(Color.White.copy(alpha=.05f),Offset(x*cell,y*cell),Size(cell,cell))
            val rect=bounds(IntSize(size.width.roundToInt(),size.height.roundToInt()))
            drawImage(image,dstOffset=IntOffset(rect.left.roundToInt(),rect.top.roundToInt()),dstSize=IntSize(max(1,rect.width.roundToInt()),max(1,rect.height.roundToInt())))
            if(points.isNotEmpty()){
                fun pos(p:Point)=Offset(rect.left+p.x*rect.width,rect.top+p.y*rect.height)
                val start=pos(points.first());val end=pos(points.last())
                if(kind in listOf(MarkKind.BOX,MarkKind.COVER,MarkKind.BLUR,MarkKind.PIXELATE,MarkKind.ELLIPSE))drawRect(Color(color),Offset(min(start.x,end.x),min(start.y,end.y)),Size(abs(start.x-end.x),abs(start.y-end.y)),style=Stroke(2.dp.toPx()))
                else {val path=Path().apply{moveTo(start.x,start.y);points.drop(1).forEach{val p=pos(it);lineTo(p.x,p.y)}};drawPath(path,Color(color),style=Stroke(max(2f,brush*min(rect.width,rect.height)),cap=StrokeCap.Round))}
            }
            sample?.takeIf{kind==MarkKind.CLONE&&drawing}?.let{val at=Offset(rect.left+it.x*rect.width,rect.top+it.y*rect.height);drawCircle(Color.White,8.dp.toPx(),at,style=Stroke(2.dp.toPx()))}
        }
    }
}
