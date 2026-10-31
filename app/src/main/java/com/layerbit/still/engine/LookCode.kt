package com.layerbit.still.engine

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.roundToLong

/**
 * A look as a short line of text that fits a QR code: what "Share as QR" draws and "Scan look"
 * reads. No server, no link: the whole look travels in the code.
 *
 * Only what a look is goes in - tone, curves, the named look and its strength, the colour mix,
 * scan cleanup and the frame. What belongs to one picture (crop, perspective, turns, size,
 * layers, cut-out, expanded canvas) is left out on the way in and stripped again on the way
 * out, so a code from anyone can only ever change how a picture looks, never what is in it.
 *
 * Format: "STILL1." then base64url (no padding) of raw deflate, with [DICTIONARY] preset, of
 * {"n": name, "r": recipe}. The recipe is the ordinary [RecipeJson] with defaults dropped and
 * numbers rounded to the thousandths the sliders can show, so any version of the app reads it
 * the way it reads a recipe file. The dictionary holds the recipe's own key names, which is
 * what makes a typical look a small, easily scanned code. It is part of the format: a change
 * to it is a new prefix, never an edit.
 */
object LookCode {

    const val PREFIX = "STILL1."
    private const val MAX_INFLATED = 64 * 1024

    private val DICTIONARY = (
        "NONE MONO NOIR SEPIA WARM COOL FADE MATTE VIVID FILM CHROME TEAL_ORANGE PORTRAIT DOCUMENT OFF COLOUR GREY INK" +
            "\"scan\":{\"mode\":\"\",\"strength\":1,\"whiten\":0.5}," +
            "\"mix\":{\"hue\":[0,0,0,0,0,0,0,0],\"saturation\":[0,0,0,0,0,0,0,0],\"lightness\":[0,0,0,0,0,0,0,0]}," +
            "\"frame\":{\"aspect\":0.,\"padding\":0.0,\"border\":0.0,\"borderColor\":-1,\"background\":-1,\"cornerRadius\":0.0,\"blurredFill\":true}," +
            "\"curves\":{\"master\":[[0,0],[1,1]],\"red\":[[0,0],[1,1]],\"green\":[[0,0],[1,1]],\"blue\":[[0,0],[1,1]]}," +
            "\"look\":\"\",\"lookStrength\":0.," +
            "{\"n\":\"\",\"r\":{\"schema\":2,\"tone\":{\"exposure\":0.,\"contrast\":0.,\"highlights\":-0.,\"shadows\":0.,\"whites\":0.,\"blacks\":-0.," +
            "\"saturation\":0.,\"vibrance\":0.,\"temperature\":0.,\"tint\":0.,\"hue\":0.,\"fade\":0.,\"clarity\":0.,\"dehaze\":0.," +
            "\"sharpness\":0.,\"vignette\":0.,\"grain\":0.,\"noise\":0.}"
        ).toByteArray(Charsets.US_ASCII)

    /** What of [r] a shared look keeps. */
    fun lookOf(r: Recipe): Recipe = r.asPreset().copy(geometry = Geometry(), expand = Expand())

    fun encode(name: String, r: Recipe): String {
        val json = jsonOf("n" to name.trim().take(60).json(), "r" to compact(RecipeJson.toJson(lookOf(r))))
        val raw = Json.write(json).toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setDictionary(DICTIONARY)
        deflater.setInput(raw); deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** Whether [code] fits the largest QR code this app writes. */
    fun fits(code: String): Boolean = Qr.versionFor(code.toByteArray(Charsets.US_ASCII).size) != null

    /** The look's name and recipe. @throws IllegalArgumentException when [text] is not a look this app can trust. */
    fun decode(text: String): Pair<String, Recipe> {
        val t = text.trim()
        require(t.startsWith(PREFIX)) { "Not a Still look" }
        val packed = try { Base64.getUrlDecoder().decode(t.substring(PREFIX.length)) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("Damaged look code") }
        val inflater = Inflater(true)
        // A raw stream never asks for its dictionary, so it is set before the first byte.
        inflater.setDictionary(DICTIONARY)
        inflater.setInput(packed)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                require(out.size() <= MAX_INFLATED) { "Look code too large" }
            }
            require(inflater.finished()) { "Damaged look code" }
        } catch (e: DataFormatException) {
            throw IllegalArgumentException("Damaged look code")
        } finally { inflater.end() }
        val o = try { Json.parse(String(out.toByteArray(), Charsets.UTF_8)) } catch (e: RuntimeException) { throw IllegalArgumentException("Damaged look code") }
        val r = o["r"] ?: throw IllegalArgumentException("Not a Still look")
        val name = o["n"]?.str()?.trim()?.take(60).orEmpty()
        return name to lookOf(RecipeJson.read(Json.write(r)))
    }

    /** The recipe JSON without what a reader assumes anyway, numbers to three places. */
    private fun compact(v: JsonValue): JsonValue {
        val o = v as JsonValue.Obj
        val out = LinkedHashMap<String, JsonValue>()
        val identity = Curves.IDENTITY_POINTS.map { listOf(it.x.json(), it.y.json()).json() }.json()
        val white = 0xFFFFFFFF.toInt().json()
        val zero = 0.0.json(); val off = false.json()
        for ((k, value) in o.fields) {
            when (k) {
                "geometry", "marks", "cutout", "expand" -> Unit
                // Every field of these reads as zero when missing.
                "tone" -> keep(out, k, value) { _, it -> it != zero }
                "mix" -> keep(out, k, value) { _, a -> a.arr()?.all { it == zero } != true }
                // Colours read as white when missing; a transparent background (0) must stay.
                "frame" -> keep(out, k, value) { key, it -> if (key == "borderColor" || key == "background") it != white else it != zero && it != off }
                "curves" -> keep(out, k, value) { _, it -> it != identity }
                "look" -> if (value.str() != Look.NONE.name) out[k] = value
                "lookStrength" -> if (value.num() != 1.0) out[k] = round(value)
                else -> out[k] = round(value)
            }
        }
        return JsonValue.Obj(out)
    }

    private inline fun keep(out: MutableMap<String, JsonValue>, key: String, value: JsonValue, wanted: (String, JsonValue) -> Boolean) {
        val kept = (value as? JsonValue.Obj)?.fields?.filter { (k, v) -> wanted(k, v) } ?: return
        if (kept.isNotEmpty()) out[key] = round(JsonValue.Obj(kept))
    }

    private fun round(v: JsonValue): JsonValue = when (v) {
        is JsonValue.Num -> if (v.value == Math.floor(v.value)) v else JsonValue.Num((v.value * 1000).roundToLong() / 1000.0)
        is JsonValue.Arr -> JsonValue.Arr(v.items.map { round(it) })
        is JsonValue.Obj -> JsonValue.Obj(v.fields.mapValues { round(it.value) })
        else -> v
    }
}
