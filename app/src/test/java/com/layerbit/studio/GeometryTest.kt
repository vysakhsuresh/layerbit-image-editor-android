package com.layerbit.studio
import com.layerbit.studio.core.*
import org.junit.Assert.*
import org.junit.Test
class GeometryTest {
    @Test fun extremeDimensionsStayInsideBudgetAndPreserveAspect() {
        val (w,h)=Geometry.fit(30000,20000,4_000_000);assertTrue(w.toLong()*h<=4_000_000);assertEquals(1.5,w.toDouble()/h,.002)
        val tiny=Geometry.fit(1,100000,100);assertEquals(1,tiny.first);assertTrue(tiny.second<=100)
    }
    @Test fun presetCropMatchesDesiredAspect() {
        for(ratio in listOf(1f,16f/9,9f/16)) {val c=Geometry.aspectCrop(4000,3000,ratio);assertTrue(c.valid());assertEquals(ratio,4000*(c.right-c.left)/(3000*(c.bottom-c.top)),.0001f)}
    }
    @Test fun exportNamesCannotEscapeDestination(){assertFalse(Geometry.safeName("../../photo.jpg").contains('/'));assertTrue(Geometry.safeName(".jpg").isNotBlank())}
}
