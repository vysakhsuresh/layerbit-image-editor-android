package com.layerbit.studio.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

data class Point(val x: Float, val y: Float)
data class Crop(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    fun valid() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && right-left >= .01f && bottom-top >= .01f
}
data class Tone(val brightness: Float = 0f, val contrast: Float = 0f, val shadows: Float = 0f,
    val highlights: Float = 0f, val saturation: Float = 0f, val warmth: Float = 0f,
    val hue: Float = 0f, val sharpness: Float = 0f)
enum class MarkKind { DRAW, TEXT, ARROW, BOX, ELLIPSE, COVER, PIXELATE, BLUR, ERASE, CLONE }
data class Mark(val kind: MarkKind, val points: List<Point>, val color: Int = 0xfff39d75.toInt(),
    val width: Float = .018f, val text: String = "", val opacity: Float = 1f, val visible: Boolean = true,
    val source: Point? = null)
data class Recipe(val crop: Crop = Crop(), val rotation: Int = 0, val straighten: Float = 0f,
    val flipX: Boolean = false, val flipY: Boolean = false, val width: Int = 0, val height: Int = 0,
    val tone: Tone = Tone(), val filter: String = "Original", val intensity: Float = 1f,
    val marks: List<Mark> = emptyList()) {
    fun preset() = copy(marks = emptyList())
}
enum class OutputFormat(val extension: String, val mime: String) {
    JPEG("jpg", "image/jpeg"), PNG("png", "image/png"), WEBP("webp", "image/webp")
}
data class ExportOptions(val format: OutputFormat = OutputFormat.JPEG, val quality: Int = 92,
    val targetBytes: Long = 0, val allowShrink: Boolean = false, val keepCamera: Boolean = false)
data class Source(val id: String, val name: String, val path: String, val width: Int, val height: Int,
    val bytes: Long, val mime: String, val metadata: String)
data class Project(val source: Source, val recipe: Recipe = Recipe())
data class Exported(val path: String, val width: Int, val height: Int, val bytes: Long,
    val mime: String, val quality: Int, val note: String)

object Geometry {
    fun fit(w: Int, h: Int, maxPixels: Long, maxEdge: Int = 8192): Pair<Int, Int> {
        require(w > 0 && h > 0 && maxPixels > 0)
        val scale = minOf(1.0, sqrt(maxPixels.toDouble() / w / h), maxEdge.toDouble()/max(w,h))
        val outW=max(1, floor(w*scale).toInt()).coerceAtMost(maxPixels.toInt())
        val outH=max(1, floor(h*scale).toInt()).coerceAtMost((maxPixels/outW).toInt())
        return outW to outH
    }
    fun aspectCrop(w: Int, h: Int, aspect: Float): Crop {
        require(aspect > 0 && aspect.isFinite())
        val current = w.toFloat()/h
        return if (current > aspect) { val f = aspect/current; Crop((1-f)/2,0f,(1+f)/2,1f) }
        else { val f = current/aspect; Crop(0f,(1-f)/2,1f,(1+f)/2) }
    }
    fun safeName(name: String): String = name.substringBeforeLast('.',name).replace(Regex("[^\\p{L}\\p{N}_ -]"), "_").take(60).ifBlank { "image" }
}

object RecipeJson {
    fun write(r: Recipe): String = JSONObject().apply {
        put("version",1); put("crop",JSONArray(listOf(r.crop.left,r.crop.top,r.crop.right,r.crop.bottom)))
        put("rotation",r.rotation); put("straighten",r.straighten); put("flipX",r.flipX); put("flipY",r.flipY)
        put("width",r.width); put("height",r.height); put("filter",r.filter); put("intensity",r.intensity)
        put("tone", JSONArray(listOf(r.tone.brightness,r.tone.contrast,r.tone.shadows,r.tone.highlights,
            r.tone.saturation,r.tone.warmth,r.tone.hue,r.tone.sharpness)))
        put("marks",JSONArray().apply { r.marks.forEach { m -> put(JSONObject().apply {
            put("kind",m.kind.name); put("color",m.color); put("width",m.width); put("text",m.text)
            put("opacity",m.opacity); put("visible",m.visible)
            put("points", JSONArray().apply { m.points.forEach { put(JSONArray(listOf(it.x,it.y))) } })
            m.source?.let { put("source",JSONArray(listOf(it.x,it.y))) }
        }) } })
    }.toString()
    fun read(text: String): Recipe {
        val o=JSONObject(text); require(o.optInt("version")==1) { "Unsupported project version" }
        val c=o.getJSONArray("crop"); val t=o.getJSONArray("tone")
        fun JSONArray.f(i: Int) = getDouble(i).toFloat().also { require(it.isFinite()) }
        val crop=Crop(c.f(0),c.f(1),c.f(2),c.f(3)); require(crop.valid())
        val marks=o.getJSONArray("marks")
        require(marks.length() <= 300)
        return Recipe(crop,o.getInt("rotation"),o.getDouble("straighten").toFloat().coerceIn(-45f,45f),
            o.getBoolean("flipX"),o.getBoolean("flipY"),o.getInt("width").coerceIn(0,8192),o.getInt("height").coerceIn(0,8192),
            Tone(t.f(0),t.f(1),t.f(2),t.f(3),t.f(4),t.f(5),t.f(6),t.f(7)),o.getString("filter"),
            o.getDouble("intensity").toFloat().coerceIn(0f,1f),List(marks.length()) { i ->
                val m=marks.getJSONObject(i); val ps=m.getJSONArray("points"); require(ps.length()<=2000)
                Mark(MarkKind.valueOf(m.getString("kind")),List(ps.length()) { p -> val a=ps.getJSONArray(p); Point(a.f(0).coerceIn(0f,1f),a.f(1).coerceIn(0f,1f)) },
                    m.getInt("color"),m.getDouble("width").toFloat().coerceIn(.002f,.2f),m.getString("text").take(300),
                    m.getDouble("opacity").toFloat().coerceIn(0f,1f),m.getBoolean("visible"),m.optJSONArray("source")?.let { Point(it.f(0),it.f(1)) })
            })
    }
}
