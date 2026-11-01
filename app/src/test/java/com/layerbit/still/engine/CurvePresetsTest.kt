package com.layerbit.still.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurvePresetsTest {
    private fun through(c: Curves, v: Int): IntArray {
        val px = intArrayOf(Argb.pack(255, v, v, v))
        TonePipeline.build(Tone(), c, Look.NONE, 1f).apply(px, 1, 0, 1)
        return intArrayOf(Argb.red(px[0]), Argb.green(px[0]), Argb.blue(px[0]))
    }

    @Test
    fun everyPresetIsValidAndDoesWhatItsNameSays() {
        for (p in CurvePreset.entries) {
            val c = CurvePresets.curves(p)
            assertTrue(p.name, c.valid && !c.isIdentity)
            assertEquals(p, CurvePresets.which(c))
        }
        assertTrue(through(CurvePresets.curves(CurvePreset.BRIGHTEN), 110)[1] > 125)
        assertTrue(through(CurvePresets.curves(CurvePreset.DARKEN), 140)[1] < 125)
        assertTrue(through(CurvePresets.curves(CurvePreset.MATTE), 0)[1] > 15)
        assertTrue(through(CurvePresets.curves(CurvePreset.CONTRAST), 50)[1] < 45)
        val warm = through(CurvePresets.curves(CurvePreset.WARM_LIGHT), 160)
        assertTrue(warm[0] > warm[2])
        assertEquals(null, CurvePresets.which(Curves()))
    }
}
