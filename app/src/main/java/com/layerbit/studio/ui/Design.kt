package com.layerbit.studio.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.*

enum class Tool(val title:String,val hint:String) {
    CROP("Crop","Find your frame"), ROTATE("Rotate","Straighten & flip"), RESIZE("Resize","Pixels & percent"),
    COMPRESS("Compress","Choose a size limit"), CONVERT("Convert","JPEG · PNG · WebP"),
    ADJUST("Adjust","Light & colour"), FILTER("Filters","A little atmosphere"),
    MARKUP("Markup","Draw, text & shapes"), MASK("Erase mask","Paint transparency"), CLONE("Clone","Copy a sampled area"),
    LAYERS("Layers","Order & opacity"), METADATA("Details","Know your image"), BATCH("Batch","One recipe, many images"),
    COLLAGE("Collage","Bring pictures together"), EXPORT("Export","Preview your new copy")
}
val LocalMotion=staticCompositionLocalOf<State<Float>?> { null }
@Composable fun StudioTheme(choice:String,content:@Composable ()->Unit) {
    val dark=choice=="Dark" || (choice=="System" && isSystemInDarkTheme())
    val colors=if(dark) darkColorScheme(primary=Color(0xff83e0c1),onPrimary=Color(0xff083c30),
        background=Color(0xff101820),surface=Color(0xff17232d),surfaceVariant=Color(0xff26343f),
        onSurface=Color(0xffeff4f2),onSurfaceVariant=Color(0xffb3c3c9),secondary=Color(0xfff4ac85))
    else lightColorScheme(primary=Color(0xff17664f),onPrimary=Color.White,background=Color(0xfff5f4ee),
        surface=Color(0xfffffef9),surfaceVariant=Color(0xffe4e9e1),onSurface=Color(0xff182b31),
        onSurfaceVariant=Color(0xff4f6369),secondary=Color(0xff925031))
    MaterialTheme(colorScheme=colors,content=content)
}
@Composable fun MotionProvider(enabled:Boolean,content:@Composable ()->Unit) {
    val context=LocalContext.current; val lifecycle=LocalLifecycleOwner.current.lifecycle
    fun allowed()=Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f
    var systemMotion by remember { mutableStateOf(allowed()) }; var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(context,lifecycle) {
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange:Boolean) { systemMotion=allowed() } }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        val events=LifecycleEventObserver { _,_ -> active=lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED); systemMotion=allowed() }
        lifecycle.addObserver(events)
        onDispose { context.contentResolver.unregisterContentObserver(observer); lifecycle.removeObserver(events) }
    }
    if(enabled && systemMotion && active) {
        val clock=rememberInfiniteTransition(label="tool previews").animateFloat(0f,1f,infiniteRepeatable(tween(6000,easing=LinearEasing)),label="motion clock")
        CompositionLocalProvider(LocalMotion provides clock,content=content)
    } else CompositionLocalProvider(LocalMotion provides null,content=content)
}
@Composable fun ToolGlyph(tool:Tool,modifier:Modifier=Modifier) {
    val clock=LocalMotion.current; val ink=MaterialTheme.colorScheme.primary; val warm=MaterialTheme.colorScheme.secondary
    Canvas(modifier.clearAndSetSemantics { }) {
        // Read in draw phase: the clock invalidates drawings, not entire cards.
        val phase=clock?.value ?: .25f
        val t=sin(phase*2*PI).toFloat(); val k=(t+1)/2
        val unit=min(size.width,size.height)/48f
        scale(unit,unit,pivot=Offset.Zero) {
            val stroke=Stroke(2.5f,cap=StrokeCap.Round,join=StrokeJoin.Round)
            fun line(a:Offset,b:Offset,color:Color=ink)=drawLine(color,a,b,2.5f,StrokeCap.Round)
            fun frame(x:Float=10f,y:Float=12f,w:Float=28f,h:Float=24f,color:Color=ink)=drawRoundRect(color,Offset(x,y),Size(w,h),CornerRadius(3f,3f),style=stroke)
            fun picture(x:Float=10f,y:Float=12f,w:Float=28f,h:Float=24f) {
                frame(x,y,w,h); drawCircle(warm,2.5f,Offset(x+w*.75f,y+h*.25f))
                val p=Path().apply { moveTo(x+3,y+h-5); lineTo(x+w*.4f,y+h*.45f); lineTo(x+w*.6f,y+h*.65f); lineTo(x+w-3,y+h*.4f) }; drawPath(p,ink,style=stroke)
            }
            when(tool) {
                Tool.CROP -> { val inset=3*k; picture(); line(Offset(6+inset,4+inset),Offset(6+inset,40-inset)); line(Offset(6+inset,40-inset),Offset(43-inset,40-inset)); line(Offset(4+inset,8+inset),Offset(40-inset,8+inset)); line(Offset(40-inset,8+inset),Offset(40-inset,44-inset)) }
                Tool.ROTATE -> rotate(90*k,Offset(24f,24f)) { picture(13f,12f,22f,24f); drawArc(warm,200f,110f,false,Offset(3f,3f),Size(42f,42f),style=stroke) }
                Tool.RESIZE -> { picture(14-5*k,16-5*k,20+10*k,16+10*k); line(Offset(4f,44f),Offset(13f,35f),warm); line(Offset(35f,13f),Offset(44f,4f),warm) }
                Tool.COMPRESS -> { picture(10f,12+4*k,28f,24-8*k); line(Offset(14f,4+4*k),Offset(34f,4+4*k),warm); line(Offset(14f,44-4*k),Offset(34f,44-4*k),warm) }
                Tool.CONVERT -> { frame(); drawLine(warm,Offset(8+30*k,9f),Offset(8+30*k,39f),3f); drawCircle(ink,4f,Offset(19f,24f),style=stroke); drawRect(ink,Offset(28f,20f),Size(7f,8f),style=stroke) }
                Tool.ADJUST -> for(i in 0..2) { val x=12+i*12f; line(Offset(x,6f),Offset(x,42f)); drawCircle(warm,4f,Offset(x,18+i*6f+t*5)) }
                Tool.FILTER -> { drawCircle(ink.copy(alpha=.5f),11f,Offset(18f,24f)); drawCircle(warm.copy(alpha=.65f),11f,Offset(29+3*t,24f)); drawCircle(ink,17f,Offset(24f,24f),style=stroke) }
                Tool.MARKUP -> { val p=Path().apply { moveTo(5f,34f); cubicTo(15f,4f,27f,44f,40f,12f) }; drawPath(p,ink,style=Stroke(3f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(45f,70f),-k*50))); drawCircle(warm,3f,Offset(9+30*k,29-12*k)) }
                Tool.MASK -> { for(y in 0..3) for(x in 0..3) drawRect(ink.copy(alpha=if((x+y)%2==0).1f else .35f),Offset(8+x*8f,8+y*8f),Size(8f,8f)); drawLine(warm,Offset(9+29*k,9f),Offset(9+29*k,39f),7f,StrokeCap.Round) }
                Tool.CLONE -> { drawCircle(ink,8f,Offset(14f,24f),style=stroke); drawCircle(warm.copy(alpha=.4f+.6f*k),8f,Offset(34f,24f),style=stroke); line(Offset(23f,24f),Offset(26f,24f)) }
                Tool.LAYERS -> for(i in 2 downTo 0) { val y=7+i*8f+(if(i==0)t*2 else 0f); drawRoundRect(if(i==0)warm else ink.copy(alpha=.5f),Offset(8f,y),Size(32f,19f),CornerRadius(3f),style=stroke) }
                Tool.METADATA -> { frame(9f,6f,30f,36f); drawCircle(warm,2f,Offset(24f,15f)); line(Offset(24f,21f),Offset(24f,34f)); drawLine(ink.copy(alpha=.5f),Offset(13f,38f),Offset(13+21*k,38f),2f) }
                Tool.BATCH -> for(i in 0..2) { val x=4+i*11f; frame(x,8+i*5f,19f,25f,if(i==1)warm else ink); drawCircle(warm,2f,Offset(10+i*12f,43f-k*2)) }
                Tool.COLLAGE -> { frame(6f,6f,36f,36f); line(Offset(24f,6f),Offset(24f,42f)); line(Offset(6f,24f),Offset(42f,24f)); drawCircle(warm,3f,Offset(15+18*k,15f)) }
                Tool.EXPORT -> { line(Offset(7f,28f),Offset(7f,40f)); line(Offset(7f,40f),Offset(41f,40f)); line(Offset(41f,40f),Offset(41f,28f)); val y=10+5*k; line(Offset(24f,32f),Offset(24f,y),warm); line(Offset(15f,y+9),Offset(24f,y),warm); line(Offset(24f,y),Offset(33f,y+9),warm) }
            }
        }
    }
}
