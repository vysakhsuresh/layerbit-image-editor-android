package com.layerbit.still.engine

/**
 * The smallest JSON that recipes need, with no dependency.
 *
 * org.json is an Android class and the engine must not import one; kotlinx.serialization is a
 * compiler plugin for a format that is six value types. Reading is tolerant of order and of
 * unknown keys, which is the property that lets a newer app add a field without breaking what
 * an older one wrote, and vice versa.
 */
sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue

    fun obj(): Obj? = this as? Obj
    fun arr(): List<JsonValue>? = (this as? Arr)?.items
    fun str(): String? = (this as? Str)?.value
    fun num(): Double? = (this as? Num)?.value
    fun bool(): Boolean? = (this as? Bool)?.value
    operator fun get(key: String): JsonValue? = (this as? Obj)?.fields?.get(key)
}

object Json {

    fun parse(text: String): JsonValue = Parser(text).run {
        val v = value()
        skipSpace()
        require(at >= text.length) { "Trailing characters at $at" }
        v
    }

    fun write(value: JsonValue): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(v: JsonValue, out: StringBuilder) {
        when (v) {
            is JsonValue.Obj -> {
                out.append('{')
                var first = true
                for ((k, item) in v.fields) {
                    if (!first) out.append(',')
                    first = false
                    quote(k, out); out.append(':'); write(item, out)
                }
                out.append('}')
            }
            is JsonValue.Arr -> {
                out.append('[')
                v.items.forEachIndexed { i, item -> if (i > 0) out.append(','); write(item, out) }
                out.append(']')
            }
            is JsonValue.Str -> quote(v.value, out)
            is JsonValue.Num -> out.append(number(v.value))
            is JsonValue.Bool -> out.append(v.value)
            JsonValue.Null -> out.append("null")
        }
    }

    /** Whole numbers without a fraction, everything else as Kotlin prints it. */
    private fun number(d: Double): String {
        require(d.isFinite()) { "JSON cannot hold $d" }
        return if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }

    private fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(val s: String) {
        var at = 0

        fun skipSpace() { while (at < s.length && s[at].isWhitespace()) at++ }

        fun value(): JsonValue {
            skipSpace()
            require(at < s.length) { "Unexpected end of JSON" }
            return when (val c = s[at]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("Unexpected '$c' at $at")
            }
        }

        private fun literal(word: String, v: JsonValue): JsonValue {
            require(s.startsWith(word, at)) { "Bad literal at $at" }
            at += word.length
            return v
        }

        private fun obj(): JsonValue {
            at++ // {
            val fields = LinkedHashMap<String, JsonValue>()
            skipSpace()
            if (at < s.length && s[at] == '}') { at++; return JsonValue.Obj(fields) }
            while (true) {
                skipSpace()
                require(at < s.length && s[at] == '"') { "Expected key at $at" }
                val key = string()
                skipSpace()
                require(at < s.length && s[at] == ':') { "Expected ':' at $at" }
                at++
                fields[key] = value()
                skipSpace()
                require(at < s.length) { "Unterminated object" }
                when (s[at]) {
                    ',' -> at++
                    '}' -> { at++; return JsonValue.Obj(fields) }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $at")
                }
            }
        }

        private fun arr(): JsonValue {
            at++ // [
            val items = ArrayList<JsonValue>()
            skipSpace()
            if (at < s.length && s[at] == ']') { at++; return JsonValue.Arr(items) }
            while (true) {
                items += value()
                skipSpace()
                require(at < s.length) { "Unterminated array" }
                when (s[at]) {
                    ',' -> at++
                    ']' -> { at++; return JsonValue.Arr(items) }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $at")
                }
            }
        }

        private fun string(): String {
            at++ // opening quote
            val out = StringBuilder()
            while (true) {
                require(at < s.length) { "Unterminated string" }
                val c = s[at++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(at < s.length) { "Unterminated escape" }
                        when (val e = s[at++]) {
                            '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/')
                            'b' -> out.append('\b'); 'f' -> out.append('\u000C'); 'n' -> out.append('\n')
                            'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> {
                                require(at + 4 <= s.length) { "Bad unicode escape" }
                                out.append(s.substring(at, at + 4).toInt(16).toChar()); at += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape \\$e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): JsonValue {
            val start = at
            if (s[at] == '-') at++
            while (at < s.length && (s[at].isDigit() || s[at] in ".eE+-")) at++
            val d = s.substring(start, at).toDoubleOrNull() ?: throw IllegalArgumentException("Bad number at $start")
            require(d.isFinite()) { "Number out of range at $start" }
            return JsonValue.Num(d)
        }
    }
}

/** Builders, so writing a recipe reads like the recipe. */
fun jsonOf(vararg fields: Pair<String, JsonValue?>): JsonValue.Obj =
    JsonValue.Obj(LinkedHashMap<String, JsonValue>().apply { for ((k, v) in fields) if (v != null) put(k, v) })

fun Number.json(): JsonValue = JsonValue.Num(this.toDouble())
fun String.json(): JsonValue = JsonValue.Str(this)
fun Boolean.json(): JsonValue = JsonValue.Bool(this)
fun List<JsonValue>.json(): JsonValue = JsonValue.Arr(this)
