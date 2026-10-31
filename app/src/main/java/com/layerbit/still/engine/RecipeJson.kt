package com.layerbit.still.engine

/**
 * Recipes and presets on disk.
 *
 * Written by hand and read by hand, with a default for every missing field, so a recipe saved
 * by this version opens in the next one and a recipe saved by the next one opens in this. The
 * schema number only moves when a field changes meaning, never when one is added. Values are
 * validated on the way in: a file is not trusted to hold a sensible slider position.
 *
 * Schema 1 is what the 0.1 preview wrote (crop before rotation, eight tone sliders, filter by
 * name). It is read here and translated, so no project from the preview is lost.
 */
object RecipeJson {

    fun write(r: Recipe): String = Json.write(toJson(r))

    fun toJson(r: Recipe): JsonValue = jsonOf(
        "schema" to Recipe.SCHEMA.json(),
        "geometry" to geometry(r.geometry),
        "tone" to tone(r.tone),
        "curves" to if (r.curves.isIdentity) null else jsonOf(
            "master" to points(r.curves.master), "red" to points(r.curves.red),
            "green" to points(r.curves.green), "blue" to points(r.curves.blue)
        ),
        "look" to r.look.name.json(),
        "lookStrength" to r.lookStrength.json(),
        "marks" to r.marks.map { mark(it) }.json(),
        "frame" to if (r.frame.isIdentity) null else jsonOf(
            "aspect" to r.frame.aspect.json(), "padding" to r.frame.padding.json(), "border" to r.frame.border.json(),
            "borderColor" to r.frame.borderColor.json(), "background" to r.frame.background.json(),
            "cornerRadius" to r.frame.cornerRadius.json(), "blurredFill" to r.frame.blurredFill.json()
        ),
        "mix" to if (r.mix.isIdentity) null else jsonOf(
            "hue" to JsonValue.Arr(r.mix.hue.map { it.json() }), "saturation" to JsonValue.Arr(r.mix.saturation.map { it.json() }),
            "lightness" to JsonValue.Arr(r.mix.lightness.map { it.json() })
        ),
        "scan" to if (r.scan.isIdentity) null else jsonOf("mode" to r.scan.mode.name.json(), "strength" to r.scan.strength.json(), "whiten" to r.scan.whiten.json()),
        "cutout" to if (r.cutout.isIdentity) null else jsonOf(
            "enabled" to r.cutout.enabled.json(), "mode" to r.cutout.mode.name.json(),
            "scribbles" to r.cutout.scribbles.map { jsonOf("points" to points(it.points), "width" to it.width.json(), "keep" to it.keep.json()) }.json(),
            "feather" to r.cutout.feather.json(), "threshold" to r.cutout.threshold.json(), "invert" to r.cutout.invert.json(),
            "backdrop" to r.cutout.backdrop.name.json(), "color" to r.cutout.color.json(), "blur" to r.cutout.blur.json(),
            "grow" to r.cutout.grow.json(), "refine" to r.cutout.refine.json(), "outline" to r.cutout.outline.json(), "shadow" to r.cutout.shadow.json(),
            "outlineColor" to r.cutout.outlineColor.json(), "trim" to r.cutout.trim.json()
        ),
        "locals" to if (r.locals.isEmpty()) null else r.locals.map { local(it) }.json(),
        "portrait" to if (r.portrait.isIdentity) null else portrait(r.portrait),
        "double" to if (r.double.isIdentity) null else double(r.double),
        "effects" to if (r.effects.isEmpty()) null else r.effects.map { effect(it) }.json()
    )

    private fun local(l: LocalAdjust) = jsonOf(
        "mask" to l.mask.let { m ->
            jsonOf(
                "kind" to m.kind.name.json(),
                "strokes" to if (m.strokes.isEmpty()) null else m.strokes.map { s -> jsonOf("points" to points(s.points), "width" to s.width.json(), "softness" to s.softness.json(), "erase" to s.erase.json()) }.json(),
                "a" to listOf(m.a.x.json(), m.a.y.json()).json(), "b" to listOf(m.b.x.json(), m.b.y.json()).json(),
                "radiusX" to m.radiusX.json(), "radiusY" to m.radiusY.json(), "angle" to m.angle.json(),
                "feather" to m.feather.json(), "range" to m.range.json(), "invert" to m.invert.json()
            )
        },
        "exposure" to l.exposure.json(), "contrast" to l.contrast.json(), "highlights" to l.highlights.json(), "shadows" to l.shadows.json(),
        "temperature" to l.temperature.json(), "tint" to l.tint.json(), "saturation" to l.saturation.json(),
        "clarity" to l.clarity.json(), "sharpness" to l.sharpness.json(), "visible" to l.visible.json()
    )

    /** A local adjustment, or null when its mask is a kind this app does not know: a newer app's, skipped rather than refused. */
    private fun readLocal(v: JsonValue): LocalAdjust? {
        val m = v["mask"] ?: throw IllegalArgumentException("A local adjustment has no mask")
        val kind = m["kind"]?.str()?.let { name -> MaskKind.entries.firstOrNull { it.name == name } } ?: return null
        val d = LocalMask.create(kind)
        val mask = LocalMask(
            kind = kind,
            strokes = m["strokes"]?.arr()?.map { s -> BrushStroke(s["points"]?.let { pts(it) } ?: emptyList(), s["width"]?.num()?.toFloat() ?: 0.08f, s["softness"]?.num()?.toFloat() ?: 0.6f, s["erase"]?.bool() ?: false) } ?: emptyList(),
            a = m["a"]?.let { pt(it) } ?: d.a, b = m["b"]?.let { pt(it) } ?: d.b,
            radiusX = m["radiusX"]?.num()?.toFloat() ?: d.radiusX, radiusY = m["radiusY"]?.num()?.toFloat() ?: d.radiusY,
            angle = m.f("angle"), feather = m["feather"]?.num()?.toFloat() ?: d.feather, range = m["range"]?.num()?.toFloat() ?: d.range,
            invert = m["invert"]?.bool() ?: false
        )
        return LocalAdjust(
            mask, exposure = v.f("exposure"), contrast = v.f("contrast"), highlights = v.f("highlights"), shadows = v.f("shadows"),
            temperature = v.f("temperature"), tint = v.f("tint"), saturation = v.f("saturation"), clarity = v.f("clarity"),
            sharpness = v.f("sharpness"), visible = v["visible"]?.bool() ?: true
        )
    }

    private fun portrait(p: PortraitEdit) = jsonOf(
        "smooth" to p.smooth.json(), "texture" to p.texture.json(), "even" to p.even.json(), "light" to p.light.json(),
        "eyes" to p.eyes.json(), "teeth" to p.teeth.json(), "subjectOnly" to p.subjectOnly.json(),
        "strokes" to p.strokes.map { jsonOf("points" to points(it.points), "width" to it.width.json(), "add" to it.add.json()) }.json(),
        "taps" to p.taps.map { jsonOf("at" to listOf(it.at.x.json(), it.at.y.json()).json(), "kind" to it.kind.name.json(), "radius" to it.radius.json()) }.json()
    )

    private fun double(d: DoubleExposure) = jsonOf(
        "file" to d.file.json(), "x" to d.x.json(), "y" to d.y.json(), "scale" to d.scale.json(), "angle" to d.angle.json(),
        "mode" to d.mode.name.json(), "opacity" to d.opacity.json(), "insideSubject" to d.insideSubject.json()
    )

    /** Absent is off: a recipe from before portraits reads as no portrait. */
    private fun readPortrait(v: JsonValue?): PortraitEdit {
        if (v == null || v == JsonValue.Null) return PortraitEdit()
        val d = PortraitEdit()
        return PortraitEdit(
            smooth = v.f("smooth"), texture = v["texture"]?.num()?.toFloat() ?: d.texture, even = v.f("even"), light = v.f("light"),
            eyes = v["eyes"]?.num()?.toFloat() ?: d.eyes, teeth = v["teeth"]?.num()?.toFloat() ?: d.teeth,
            subjectOnly = v["subjectOnly"]?.bool() ?: false,
            strokes = v["strokes"]?.arr()?.map { s -> SkinStroke(s["points"]?.let { pts(it) } ?: emptyList(), s["width"]?.num()?.toFloat() ?: 0.05f, s["add"]?.bool() ?: true) } ?: emptyList(),
            taps = v["taps"]?.arr()?.mapNotNull { t ->
                val kind = t["kind"]?.str()?.let { name -> PortraitTapKind.entries.firstOrNull { it.name == name } } ?: return@mapNotNull null
                PortraitTap(t["at"]?.let { pt(it) } ?: throw IllegalArgumentException("A tap has a place"), kind, t["radius"]?.num()?.toFloat() ?: PortraitTap.DEFAULT_RADIUS)
            } ?: emptyList()
        )
    }

    /** Absent is off. An unknown blend mode reads as screen, the classic double exposure. */
    private fun readDouble(v: JsonValue?): DoubleExposure {
        if (v == null || v == JsonValue.Null) return DoubleExposure()
        val d = DoubleExposure()
        return DoubleExposure(
            file = v["file"]?.str() ?: "", x = v["x"]?.num()?.toFloat() ?: d.x, y = v["y"]?.num()?.toFloat() ?: d.y,
            scale = v["scale"]?.num()?.toFloat() ?: d.scale, angle = v.f("angle"),
            mode = v["mode"]?.str()?.let { name -> BlendMode.entries.firstOrNull { it.name == name } } ?: d.mode,
            opacity = v["opacity"]?.num()?.toFloat() ?: d.opacity, insideSubject = v["insideSubject"]?.bool() ?: false
        )
    }

    private fun effect(e: Effect) = jsonOf(
        "kind" to e.kind.name.json(), "style" to e.style.json(), "strength" to e.strength.json(), "extra" to e.extra.json(),
        "seed" to e.seed.json(), "center" to listOf(e.center.x.json(), e.center.y.json()).json(), "size" to e.size.json(),
        "angle" to e.angle.json(), "aspect" to e.aspect.json()
    )

    /** An effect this app does not know, from a newer one, is left out rather than refusing the recipe. */
    private fun readEffect(v: JsonValue): Effect? {
        val kind = v["kind"]?.str()?.let { name -> EffectKind.entries.firstOrNull { it.name == name } } ?: return null
        val d = Effect(kind)
        return Effect(
            kind = kind, style = v["style"]?.num()?.toInt() ?: 0, strength = v["strength"]?.num()?.toFloat() ?: d.strength,
            extra = v["extra"]?.num()?.toFloat() ?: d.extra, seed = v["seed"]?.num()?.toInt() ?: d.seed,
            center = v["center"]?.let { pt(it) } ?: d.center, size = v["size"]?.num()?.toFloat() ?: d.size,
            angle = v["angle"]?.num()?.toFloat() ?: d.angle, aspect = v["aspect"]?.num()?.toFloat() ?: d.aspect
        )
    }

    private fun geometry(g: Geometry) = jsonOf(
        "quad" to if (g.quad.isIdentity) null else points(g.quad.points),
        "rotation" to g.rotation.json(), "flipX" to g.flipX.json(), "flipY" to g.flipY.json(),
        "straighten" to g.straighten.json(),
        "crop" to listOf(g.crop.left.json(), g.crop.top.json(), g.crop.right.json(), g.crop.bottom.json()).json(),
        "width" to g.width.json(), "height" to g.height.json()
    )

    private fun tone(t: Tone) = jsonOf(
        "exposure" to t.exposure.json(), "contrast" to t.contrast.json(), "highlights" to t.highlights.json(),
        "shadows" to t.shadows.json(), "whites" to t.whites.json(), "blacks" to t.blacks.json(),
        "saturation" to t.saturation.json(), "vibrance" to t.vibrance.json(), "temperature" to t.temperature.json(),
        "tint" to t.tint.json(), "hue" to t.hue.json(), "fade" to t.fade.json(), "clarity" to t.clarity.json(),
        "dehaze" to t.dehaze.json(), "sharpness" to t.sharpness.json(), "vignette" to t.vignette.json(), "grain" to t.grain.json(), "noise" to t.noise.json()
    )

    private fun mark(m: Mark) = jsonOf(
        "kind" to m.kind.name.json(), "points" to points(m.points), "color" to m.color.json(), "width" to m.width.json(),
        "text" to m.text.json(), "opacity" to m.opacity.json(), "visible" to m.visible.json(),
        "source" to m.source?.let { listOf(it.x.json(), it.y.json()).json() }, "angle" to m.angle.json(),
        "place" to m.place.takeIf { it != WatermarkPlace.TILED }?.name?.json()
    )

    private fun points(ps: List<Pt>) = ps.map { listOf(it.x.json(), it.y.json()).json() }.json()

    /** @throws IllegalArgumentException when the text is not a recipe this app can trust. */
    fun read(text: String): Recipe {
        val o = Json.parse(text)
        require(o is JsonValue.Obj) { "Not a recipe" }
        val schema = o["schema"]?.num()?.toInt() ?: o["version"]?.num()?.toInt() ?: Recipe.SCHEMA
        val recipe = if (schema <= 1) readSchema1(o) else readCurrent(o)
        require(recipe.valid) { "Recipe holds values outside what the editor allows" }
        return recipe
    }

    private fun readCurrent(o: JsonValue): Recipe {
        val g = o["geometry"]
        val t = o["tone"]
        val c = o["curves"]
        val f = o["frame"]
        val sc = o["scan"]
        val cu = o["cutout"]
        val d = Recipe()
        return Recipe(
            geometry = Geometry(
                quad = g?.get("quad")?.let { quad(it) } ?: Quad.IDENTITY,
                rotation = g?.get("rotation")?.num()?.toInt() ?: 0,
                flipX = g?.get("flipX")?.bool() ?: false,
                flipY = g?.get("flipY")?.bool() ?: false,
                straighten = g?.get("straighten")?.num()?.toFloat() ?: 0f,
                crop = g?.get("crop")?.let { crop(it) } ?: Crop.FULL,
                width = g?.get("width")?.num()?.toInt() ?: 0,
                height = g?.get("height")?.num()?.toInt() ?: 0
            ),
            tone = Tone(
                exposure = t.f("exposure"), contrast = t.f("contrast"), highlights = t.f("highlights"), shadows = t.f("shadows"),
                whites = t.f("whites"), blacks = t.f("blacks"), saturation = t.f("saturation"), vibrance = t.f("vibrance"),
                temperature = t.f("temperature"), tint = t.f("tint"), hue = t.f("hue"), fade = t.f("fade"),
                clarity = t.f("clarity"), dehaze = t.f("dehaze"), sharpness = t.f("sharpness"), vignette = t.f("vignette"), grain = t.f("grain"), noise = t.f("noise").coerceIn(0f, 1f)
            ),
            curves = if (c == null) Curves() else Curves(
                master = c["master"]?.let { pts(it) } ?: Curves.IDENTITY_POINTS,
                red = c["red"]?.let { pts(it) } ?: Curves.IDENTITY_POINTS,
                green = c["green"]?.let { pts(it) } ?: Curves.IDENTITY_POINTS,
                blue = c["blue"]?.let { pts(it) } ?: Curves.IDENTITY_POINTS
            ),
            look = o["look"]?.str()?.let { name -> Look.entries.firstOrNull { it.name == name } } ?: Look.NONE,
            lookStrength = o["lookStrength"]?.num()?.toFloat() ?: 1f,
            marks = o["marks"]?.arr()?.map { readMark(it) } ?: emptyList(),
            frame = if (f == null) d.frame else Frame(
                aspect = f.f("aspect"), padding = f.f("padding"), border = f.f("border"),
                borderColor = f["borderColor"]?.num()?.toLong()?.toInt() ?: d.frame.borderColor,
                background = f["background"]?.num()?.toLong()?.toInt() ?: d.frame.background,
                cornerRadius = f.f("cornerRadius"), blurredFill = f["blurredFill"]?.bool() ?: false
            ),
            mix = o["mix"]?.let { m ->
                fun band(key: String) = m[key]?.arr()?.map { (it.num()?.toFloat() ?: 0f).coerceIn(-1f, 1f) }?.takeIf { it.size == ColourMix.BANDS } ?: ColourMix.ZERO
                ColourMix(band("hue"), band("saturation"), band("lightness"))
            } ?: ColourMix(),
            scan = if (sc == null) Scan() else Scan(
                mode = sc["mode"]?.str()?.let { name -> ScanMode.entries.firstOrNull { it.name == name } } ?: ScanMode.OFF,
                strength = sc["strength"]?.num()?.toFloat() ?: 1f, whiten = sc["whiten"]?.num()?.toFloat() ?: 0.5f
            ),
            cutout = if (cu == null) Cutout() else Cutout(
                enabled = cu["enabled"]?.bool() ?: false,
                mode = cu["mode"]?.str()?.let { name -> CutoutMode.entries.firstOrNull { it.name == name } } ?: CutoutMode.SUBJECT,
                scribbles = cu["scribbles"]?.arr()?.map { sb -> Scribble(sb["points"]?.let { pts(it) } ?: emptyList(), sb["width"]?.num()?.toFloat() ?: 0.03f, sb["keep"]?.bool() ?: true) } ?: emptyList(),
                feather = cu["feather"]?.num()?.toFloat() ?: 0.004f, threshold = cu.f("threshold"), invert = cu["invert"]?.bool() ?: false,
                backdrop = cu["backdrop"]?.str()?.let { name -> Backdrop.entries.firstOrNull { it.name == name } } ?: Backdrop.TRANSPARENT,
                color = cu["color"]?.num()?.toLong()?.toInt() ?: d.cutout.color, blur = cu["blur"]?.num()?.toFloat() ?: 0.03f,
                grow = cu.f("grow"), refine = cu["refine"]?.bool() ?: true, outline = cu.f("outline"), shadow = cu.f("shadow").coerceIn(0f, 1f),
                outlineColor = cu["outlineColor"]?.num()?.toLong()?.toInt() ?: d.cutout.outlineColor, trim = cu["trim"]?.bool() ?: false
            ),
            locals = o["locals"]?.arr()?.mapNotNull { readLocal(it) } ?: emptyList(),
            portrait = readPortrait(o["portrait"]),
            double = readDouble(o["double"]),
            effects = o["effects"]?.arr()?.mapNotNull { readEffect(it) } ?: emptyList()
        )
    }

    private fun readMark(m: JsonValue): Mark = Mark(
        kind = m["kind"]?.str()?.let { name -> MarkKind.entries.firstOrNull { it.name == name } }
            ?: throw IllegalArgumentException("Unknown layer kind"),
        points = m["points"]?.let { pts(it) } ?: emptyList(),
        color = m["color"]?.num()?.toLong()?.toInt() ?: Mark.DEFAULT_COLOR,
        width = m["width"]?.num()?.toFloat() ?: 0.018f,
        text = m["text"]?.str()?.take(Mark.MAX_TEXT) ?: "",
        opacity = m["opacity"]?.num()?.toFloat() ?: 1f,
        visible = m["visible"]?.bool() ?: true,
        source = m["source"]?.let { pt(it) },
        angle = m["angle"]?.num()?.toFloat() ?: 0f,
        // Older apps never wrote a place, and a name this app does not know falls back to tiled.
        place = m["place"]?.str()?.let { name -> WatermarkPlace.entries.firstOrNull { it.name == name } } ?: WatermarkPlace.TILED
    )

    /**
     * The 0.1 preview's format. Its crop was measured on the source before rotation and its
     * eight sliders had different scales; both are translated so the picture looks as it did.
     */
    private fun readSchema1(o: JsonValue): Recipe {
        val c = o["crop"]?.let { crop(it) } ?: Crop.FULL
        val rotation = o["rotation"]?.num()?.toInt() ?: 0
        val t = o["tone"]?.arr()
        fun old(i: Int) = t?.getOrNull(i)?.num()?.toFloat() ?: 0f
        val filter = o["filter"]?.str() ?: "Original"
        return Recipe(
            geometry = Geometry(
                rotation = rotation, flipX = o["flipX"]?.bool() ?: false, flipY = o["flipY"]?.bool() ?: false,
                straighten = o["straighten"]?.num()?.toFloat() ?: 0f,
                // Rotate the source-frame crop into the rotated frame the new model uses.
                crop = Geometry2D.rotateCrop(c, rotation, o["flipX"]?.bool() ?: false, o["flipY"]?.bool() ?: false),
                width = o["width"]?.num()?.toInt() ?: 0, height = o["height"]?.num()?.toInt() ?: 0
            ),
            tone = Tone(
                exposure = (old(0) * 0.7f).coerceIn(-2f, 2f), contrast = old(1), shadows = old(2), highlights = old(3),
                saturation = old(4), temperature = old(5), hue = old(6), sharpness = old(7).coerceIn(0f, 1f)
            ),
            look = when (filter) { "Mono" -> Look.MONO; "Warm" -> Look.WARM; "Cool" -> Look.COOL; "Fade" -> Look.FADE; "Noir" -> Look.NOIR; else -> Look.NONE },
            lookStrength = o["intensity"]?.num()?.toFloat() ?: 1f,
            marks = o["marks"]?.arr()?.map { readMark(it) } ?: emptyList()
        )
    }

    /** A missing number is its default; a present one that is not a number is a corrupt file. */
    private fun JsonValue?.f(key: String): Float {
        val v = this?.get(key) ?: return 0f
        if (v == JsonValue.Null) return 0f
        return v.num()?.toFloat() ?: throw IllegalArgumentException("'$key' is not a number")
    }
    private fun pt(v: JsonValue): Pt {
        val a = v.arr() ?: throw IllegalArgumentException("Point is not a pair")
        require(a.size == 2) { "Point is not a pair" }
        return Pt(a[0].num()?.toFloat() ?: Float.NaN, a[1].num()?.toFloat() ?: Float.NaN)
    }
    private fun pts(v: JsonValue): List<Pt> = (v.arr() ?: throw IllegalArgumentException("Points are not a list")).map { pt(it) }
    private fun quad(v: JsonValue): Quad { val p = pts(v); require(p.size == 4) { "A quad has four corners" }; return Quad(p[0], p[1], p[2], p[3]) }
    private fun crop(v: JsonValue): Crop {
        val a = v.arr() ?: throw IllegalArgumentException("Crop is not a list")
        require(a.size == 4) { "Crop has four edges" }
        return Crop(a[0].num()?.toFloat() ?: Float.NaN, a[1].num()?.toFloat() ?: Float.NaN, a[2].num()?.toFloat() ?: Float.NaN, a[3].num()?.toFloat() ?: Float.NaN)
    }
}
