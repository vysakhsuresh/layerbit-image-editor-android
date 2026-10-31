package com.layerbit.still.image

import android.graphics.Bitmap
import com.layerbit.still.engine.Expand
import com.layerbit.still.engine.ExpandFill
import com.layerbit.still.engine.Expansion

/**
 * The bitmap side of [Expansion]: pixels out of the geometry stage, the grown canvas back in.
 *
 * Smart fill's choices are remembered under the caller's key (picture and geometry) and the
 * expand settings, so the export reuses the plan the preview showed instead of choosing again
 * on slightly different pixels: the preview predicts the export.
 */
class ExpandStage {

    private class Entry(val key: String, val plan: Expansion.Plan)
    private val plans = ArrayDeque<Entry>()

    fun apply(picture: Bitmap, e: Expand, key: String?, check: () -> Unit): Bitmap {
        val w = picture.width; val h = picture.height
        val px = IntArray(w * h)
        picture.getPixels(px, 0, w, 0, 0, w, h)
        check()
        val plan = if (e.fill == ExpandFill.SMART) planFor(px, w, h, e, key, check) else null
        val out = Expansion.apply(px, w, h, e, plan, check)
        val lay = e.layout(w, h)
        val b = Bitmap.createBitmap(lay.width, lay.height, Bitmap.Config.ARGB_8888)
        b.setPixels(out, 0, lay.width, 0, 0, lay.width, lay.height)
        return b
    }

    private fun planFor(px: IntArray, w: Int, h: Int, e: Expand, key: String?, check: () -> Unit): Expansion.Plan {
        val k = key?.let { "$it|$e" }
        if (k != null) synchronized(plans) { plans.firstOrNull { it.key == k }?.let { return it.plan } }
        val plan = Expansion.plan(px, w, h, e, check)
        if (k != null) synchronized(plans) {
            plans.removeAll { it.key == k }
            plans.addFirst(Entry(k, plan))
            while (plans.size > 4) plans.removeLast()
        }
        return plan
    }
}
