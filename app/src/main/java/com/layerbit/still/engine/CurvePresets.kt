package com.layerbit.still.engine

/**
 * One-tap starting curves. Each is an ordinary [Curves], so after a tap every point can still be
 * dragged; nothing about a preset is hidden.
 */
enum class CurvePreset { BRIGHTEN, DARKEN, CONTRAST, SOFT, MATTE, FADED, CROSS, WARM_LIGHT, COOL_SHADOWS }

object CurvePresets {
    private fun p(vararg xy: Float): List<Pt> = xy.toList().chunked(2).map { Pt(it[0], it[1]) }

    fun curves(preset: CurvePreset): Curves = when (preset) {
        CurvePreset.BRIGHTEN -> Curves(master = p(0f, 0f, 0.45f, 0.56f, 1f, 1f))
        CurvePreset.DARKEN -> Curves(master = p(0f, 0f, 0.55f, 0.44f, 1f, 1f))
        CurvePreset.CONTRAST -> Curves(master = p(0f, 0f, 0.25f, 0.19f, 0.75f, 0.82f, 1f, 1f))
        CurvePreset.SOFT -> Curves(master = p(0f, 0f, 0.25f, 0.30f, 0.75f, 0.71f, 1f, 1f))
        // Blacks lifted and whites held down: the printed, matte look.
        CurvePreset.MATTE -> Curves(master = p(0f, 0.09f, 0.25f, 0.26f, 0.75f, 0.77f, 1f, 0.95f))
        CurvePreset.FADED -> Curves(master = p(0f, 0.16f, 0.5f, 0.52f, 1f, 0.88f))
        // Cross-processed slide film: red and green with contrast, blue flattened and lifted.
        CurvePreset.CROSS -> Curves(
            red = p(0f, 0f, 0.25f, 0.2f, 0.75f, 0.83f, 1f, 1f),
            green = p(0f, 0f, 0.25f, 0.22f, 0.75f, 0.8f, 1f, 1f),
            blue = p(0f, 0.12f, 0.5f, 0.5f, 1f, 0.86f)
        )
        CurvePreset.WARM_LIGHT -> Curves(red = p(0f, 0f, 0.6f, 0.64f, 1f, 1f), blue = p(0f, 0f, 0.6f, 0.56f, 1f, 1f))
        CurvePreset.COOL_SHADOWS -> Curves(blue = p(0f, 0.05f, 0.3f, 0.35f, 1f, 1f), red = p(0f, 0f, 0.3f, 0.27f, 1f, 1f))
    }

    /** Which preset [c] is exactly, if any, so the strip can show it as chosen. */
    fun which(c: Curves): CurvePreset? = CurvePreset.entries.firstOrNull { curves(it) == c }
}
