package com.layerbit.still.engine

/** Filenames: safe on every filesystem, readable by a person, and patterned for a batch. */
object Names {

    /** The name without its extension, or "image" when there is nothing left. */
    fun stem(name: String): String = name.substringBeforeLast('.', name).ifBlank { "image" }

    /**
     * Strips anything that cannot appear in a filename and anything that could walk out of a
     * directory. A display name comes from a content provider and is arbitrary text.
     */
    fun safe(name: String, max: Int = 80): String {
        val cleaned = buildString {
            for (c in name) append(if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' || c == ' ' || c == '(' || c == ')') c else '_')
        }.trim().trim('.')
        return cleaned.take(max).ifEmpty { "image" }
    }

    /**
     * A batch pattern with tokens: {name} the source stem, {n} the 1-based index, {w}x{h} the
     * output pixels, {date} YYYY-MM-DD. "{name}-web" is the default; a pattern without {name}
     * or {n} gets "-{n}" appended so two outputs never want the same name.
     */
    fun pattern(template: String, name: String, index: Int, width: Int, height: Int, date: String): String {
        var t = template.ifBlank { "{name}" }
        if (!t.contains("{name}") && !t.contains("{n}")) t += "-{n}"
        val out = t.replace("{name}", stem(name)).replace("{n}", index.toString())
            .replace("{w}", width.toString()).replace("{h}", height.toString()).replace("{date}", date)
        return safe(out)
    }
}
