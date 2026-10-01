package snd.komelia.ui.reader.image.dualscreen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import snd.komelia.ui.reader.image.ScreenScaleState
import kotlin.math.abs

/**
 * Zoom state shared by both screens. Positions on the spread are fractions (0..1) of the
 * spread's width and height, so the bottom screen and the main reader agree regardless of size.
 *
 * Quick Zoom: the main reader zooms to the touched spot while a finger is on the bottom screen,
 * then returns to exactly where it was.
 */
class DualScreenState(
    private val scaleState: ScreenScaleState,
    private val scope: CoroutineScope,
) {
    /** How far to zoom, relative to the whole spread fitting on screen. */
    val zoomLevel = MutableStateFlow(2.5f)
    val animationMillis = MutableStateFlow(120)

    private var focus = Offset(0.5f, 0.5f)
    private val progress = Animatable(0f)
    private var touching = false

    /** Zoom and offset to return to when the finger lifts. */
    private var rest: Pair<Float, Offset>? = null

    fun touchDown(position: Offset) {
        touching = true
        if (rest == null) rest = scaleState.zoom.value to scaleState.transformation.value.offset
        focus = position
        animateTo(1f)
    }

    fun touchMove(position: Offset) {
        if (!touching) return
        focus = position
        apply()
    }

    fun touchUp() {
        if (!touching) return
        touching = false
        animateTo(0f)
    }

    private fun animateTo(target: Float) {
        scope.launch {
            val millis = (animationMillis.value * abs(target - progress.value)).toInt()
            progress.animateTo(target, tween(millis, easing = FastOutSlowInEasing)) { apply() }
            apply()
            if (target == 0f && !touching) rest = null
        }
    }

    private fun apply() {
        val (restZoom, restOffset) = rest ?: return
        val target = scaleState.targetSize.value
        val area = scaleState.areaSize.value
        if (target.width <= 0f || area.width == 0) return

        val fullVisibilityZoom = scaleState.scaleForFullVisibility() / scaleState.scaleFor100PercentZoom()
        val zoom = zoomLevel.value * fullVisibilityZoom
        val scale = scaleState.zoomToScale(zoom)
        // Center the focus point, but never past the spread's edges.
        val limitX = ((target.width * scale - area.width) / 2).coerceAtLeast(0f)
        val limitY = ((target.height * scale - area.height) / 2).coerceAtLeast(0f)
        val offset = Offset(
            (-(focus.x - 0.5f) * target.width * scale).coerceIn(-limitX, limitX),
            (-(focus.y - 0.5f) * target.height * scale).coerceIn(-limitY, limitY),
        )

        val p = progress.value
        scaleState.setZoomAndOffset(
            zoom = restZoom + (zoom - restZoom) * p,
            offset = restOffset + (offset - restOffset) * p,
        )
    }

    /**
     * The part of the spread the main reader currently shows, as fractions of the spread, or null
     * when the whole spread is visible.
     */
    fun visibleArea(): Rect? {
        val target = scaleState.targetSize.value
        val area = scaleState.areaSize.value
        val transform = scaleState.transformation.value
        if (target.width <= 0f || area.width == 0) return null
        if (transform.scale <= scaleState.scaleForFullVisibility() * 1.01f) return null
        fun toSpread(screenX: Float, screenY: Float): Offset {
            // Komelia's transformation is relative to the screen center.
            val point = transform.pointOf(Offset(screenX - area.width / 2f, screenY - area.height / 2f))
            return Offset(point.x / target.width + 0.5f, point.y / target.height + 0.5f)
        }
        val topLeft = toSpread(0f, 0f)
        val bottomRight = toSpread(area.width.toFloat(), area.height.toFloat())
        return Rect(topLeft, bottomRight)
    }
}
