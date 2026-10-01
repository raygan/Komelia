package snd.komelia.ui.reader.image.dualscreen

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

/**
 * Turns the content by [turns] × 90° clockwise and sizes it to fill the space after turning, so a
 * landscape screen can show upright portrait content when the device is held sideways. Content
 * lays out, draws and receives touches as if the screen itself were turned.
 */
fun Modifier.quarterTurns(turns: Int): Modifier =
    if (turns % 4 == 0) this
    else layout { measurable, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val sideways = turns % 2 != 0
        val placeable = measurable.measure(
            if (sideways) Constraints.fixed(height, width) else Constraints.fixed(width, height)
        )
        layout(width, height) {
            placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) {
                rotationZ = turns * 90f
            }
        }
    }
