package com.harken.android.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

// Material 3 Expressive shape morphing, on androidx.graphics.shapes.
//
// The record button is ONE shape whose silhouette reports state: a calm circle at rest,
// a twelve-lobed cookie while capturing. Nothing blinks for attention — see
// docs/adr/0010-expressive-redesign.md.

/** Circle at rest. */
val ShapeCircle: RoundedPolygon = RoundedPolygon.circle(numVertices = 12)

/** The cookie the record button blooms into while capturing. */
val ShapeCookie: RoundedPolygon = RoundedPolygon.star(
    numVerticesPerRadius = 12,
    innerRadius = 0.86f,
    rounding = CornerRounding(0.28f),
    innerRounding = CornerRounding(0.28f),
)

/** Second beat of the loading sequence. */
val ShapeClover: RoundedPolygon = RoundedPolygon.star(
    numVerticesPerRadius = 4,
    innerRadius = 0.42f,
    rounding = CornerRounding(0.42f),
    innerRounding = CornerRounding(0.42f),
)

/**
 * A [Shape] that draws a [Morph] at [progress], optionally rotated.
 *
 * Kept as a Shape rather than a Canvas draw so the morphing surface can still clip its
 * content, take a shadow, and receive ripple — a Canvas would give up all three.
 */
class MorphShape(
    private val morph: Morph,
    private val progress: Float,
    private val rotationDegrees: Float = 0f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val matrix = Matrix()
        matrix.scale(size.minDimension / 2f, size.minDimension / 2f)
        matrix.translate(1f, 1f)
        if (rotationDegrees != 0f) matrix.rotateZ(rotationDegrees)
        val path = morph.toPath(progress = progress).asComposePath()
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

/**
 * The record button's shape. [recording] drives a spatial spring between circle and
 * cookie; while recording the cookie turns slowly so the state reads as ongoing rather
 * than as a static decoration.
 */
@Composable
fun rememberRecordShape(recording: Boolean): Shape {
    val morph = remember { Morph(ShapeCircle, ShapeCookie) }
    val progress by animateFloatAsState(
        targetValue = if (recording) 1f else 0f,
        animationSpec = HarkenMotion.spatialFast(),
        label = "recordShapeMorph",
    )
    val spin = rememberInfiniteTransition(label = "recordShapeSpin")
    val rotation by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 14_000)),
        label = "recordShapeRotation",
    )
    // The spin is an infinite transition, so it cannot be snapped by a motion token —
    // under reduced motion the cookie holds still and the shape change alone carries
    // "recording".
    val reduced = LocalReducedMotion.current
    return MorphShape(morph, progress, if (recording && !reduced) rotation else 0f)
}
