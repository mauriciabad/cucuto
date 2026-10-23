package app.mauri.cucuto.ui.theme

import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/**
 * Which Material 3 Expressive shape each Cucuto element uses (brand/README.md, "Shape"). Cards, sheets and dialogs
 * use the standard `MaterialTheme.shapes` scale; buttons keep the expressive defaults (`ButtonDefaults.shapes()`).
 */
object CucutoShapes {
    /** Contact and character avatars at rest. Never a plain circle: the cookie tells characters from WhatsApp photos. */
    val Avatar: RoundedPolygon get() = MaterialShapes.Cookie9Sided

    /** Avatars while pressed or speaking. */
    val AvatarActive: RoundedPolygon get() = MaterialShapes.SoftBurst

    /** Character picker tiles when pressed (at rest they use `MaterialTheme.shapes.extraLarge`). */
    val TilePressed: RoundedPolygon get() = MaterialShapes.Cookie4Sided

    /** "New sender" and "update" badges. */
    val Badge: RoundedPolygon get() = MaterialShapes.Sunny

    /** The cuckoo door and its frame. */
    val Door: RoundedPolygon get() = MaterialShapes.Circle
    val DoorFrame: RoundedPolygon get() = MaterialShapes.Arch

    /** Particle burst shapes, in emission order. */
    val Particles: List<RoundedPolygon>
        get() = listOf(MaterialShapes.Circle, MaterialShapes.Sunny, MaterialShapes.Clover4Leaf, MaterialShapes.Pill)
}

/**
 * A [Shape] in the middle of a [Morph] between two normalized polygons (`MaterialShapes` are normalized to the unit
 * square). [progress] 0 is the start shape, 1 the end; spatial springs may overshoot it slightly, which is fine.
 */
class MorphShape(
    private val morph: Morph,
    private val progress: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = morph.toPath(progress, Path())
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}
