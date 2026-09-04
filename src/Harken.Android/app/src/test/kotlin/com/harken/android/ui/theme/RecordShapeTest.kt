package com.harken.android.ui.theme

import androidx.compose.foundation.shape.CircleShape
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `isResting` is what the record button gates its shadow on, and the app ANR'd twice for
 * getting that gate wrong: a concave outline with a nonzero elevation makes HWUI
 * retessellate a spot shadow every frame (SkShadowTessellator::MakeSpot ->
 * computeConcaveShadow), which pegs the RenderThread and blocks the main thread in
 * syncAndDrawFrame. Only progress 0 — the plain circle — may carry a shadow.
 */
class RecordShapeTest {
    private fun at(progress: Float) = RecordShape(CircleShape, progress)

    @Test
    fun `only the resting circle is treated as shadow-safe`() {
        assertTrue(at(0f).isResting)
        assertFalse("mid-morph is concave", at(0.5f).isResting)
        assertFalse("the cookie is concave", at(1f).isResting)
    }

    @Test
    fun `a spring that undershoots zero still counts as resting`() {
        // The morph runs on a spatial spring, so it settles through values like 1e-5 and
        // can dip slightly negative — those are visually a circle and must not flicker the
        // shadow off and on as the animation lands.
        assertTrue(at(0.0001f).isResting)
        assertTrue(at(-0.0005f).isResting)
        assertFalse(at(0.01f).isResting)
    }
}
