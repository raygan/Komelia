package snd.komelia.ui.common

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Highlight for the focused item when navigating with a game controller or keyboard. It only
 * shows in keyboard input mode, which starts with the first navigation key press and ends with
 * the next touch, so touch users never see it.
 */
val ControllerFocusColor = Color(0xFFFF9800)
private val outlineWidth = 3.dp
private val outlineCornerRadius = 6.dp

/**
 * Wraps the app's indication (Material's ripple) so everything that uses it, e.g. any
 * `Modifier.clickable`, also draws an orange outline while focused by a controller.
 */
class ControllerFocusIndication(private val base: Indication) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        ControllerFocusNode(interactionSource, (base as? IndicationNodeFactory)?.create(interactionSource))

    override fun equals(other: Any?) = other is ControllerFocusIndication && other.base == base
    override fun hashCode() = base.hashCode()
}

private class ControllerFocusNode(
    private val interactionSource: InteractionSource,
    base: DelegatableNode?,
) : DelegatingNode(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val focuses = mutableListOf<FocusInteraction.Focus>()

    init {
        base?.let { delegate(it) }
    }

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focuses += interaction
                    is FocusInteraction.Unfocus -> focuses -= interaction.focus
                    else -> return@collect
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        // Reading the input mode here redraws the outline away as soon as the screen is touched.
        if (focuses.isEmpty() || currentValueOf(LocalInputModeManager).inputMode != InputMode.Keyboard) return
        val inset = outlineWidth.toPx() / 2
        drawRoundRect(
            color = ControllerFocusColor,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = CornerRadius(outlineCornerRadius.toPx()),
            style = Stroke(width = outlineWidth.toPx()),
        )
    }
}

/**
 * The same outline for Material components (chips, buttons) that draw their own ripple instead
 * of the app's indication. Put it on the component's modifier.
 */
fun Modifier.controllerFocusOutline(shape: Shape = RoundedCornerShape(8.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    onFocusChanged { focused = it.hasFocus }
        .then(if (focused && keyboard) Modifier.border(outlineWidth, ControllerFocusColor, shape) else Modifier)
}
