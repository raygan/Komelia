package snd.komelia.ui.reader.image.dualscreen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import snd.komelia.settings.model.PagedReadingDirection.RIGHT_TO_LEFT
import snd.komelia.ui.reader.image.ScreenScaleState
import snd.komelia.ui.reader.image.paged.PagedReaderState

/** How long the Loupe takes to glide to a newly touched spot. */
private const val LOUPE_MOVE_MILLIS = 90

/** Set while the paged reader runs in dual-screen mode, so reader controls can offer its gestures. */
val LocalDualScreenState = staticCompositionLocalOf<DualScreenState?> { null }

enum class ZoomMode {
    /** The main reader zooms only while a finger is on the second screen. */
    QUICK_ZOOM,

    /** The main reader stays zoomed; touch on the second screen moves it. */
    LOUPE,
}

/**
 * Zoom state shared by both screens. Positions on the spread are fractions (0..1) of the
 * spread's width and height, so the second screen and the main reader agree regardless of size.
 *
 * Zoom levels are relative to the whole spread fitting on the main screen (1 = whole spread).
 */
class DualScreenState(
    private val pagedReaderState: PagedReaderState,
    private val scope: CoroutineScope,
) {
    private val scaleState: ScreenScaleState get() = pagedReaderState.screenScaleState

    val mode = MutableStateFlow(ZoomMode.QUICK_ZOOM)

    /** Emits when the mode changes, so it can be announced. */
    val modeChanges = MutableSharedFlow<ZoomMode>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val quickZoomLevel = MutableStateFlow(2.5f)
    val loupeZoomLevel = MutableStateFlow(2.5f)
    val animationMillis = MutableStateFlow(120)

    private var focus = Offset(0.5f, 0.5f)
    private var touching = false
    private var animation: Job? = null

    /** In Quick Zoom: the zoom and offset to return to when the finger lifts. */
    private var rest: Pair<Float, Offset>? = null

    init {
        scaleState.userZoomEvents.onEach { onUserZoom() }.launchIn(scope)
        pagedReaderState.spreadStartOverride = { newScale ->
            if (mode.value == ZoomMode.LOUPE) startZoomedIn(newScale)
        }
    }

    fun dispose() {
        pagedReaderState.spreadStartOverride = null
    }

    // ---- Touch on the second screen ----

    fun touchDown(position: Offset) {
        touching = true
        focus = position
        when (mode.value) {
            ZoomMode.QUICK_ZOOM -> {
                if (rest == null) rest = currentZoom() to currentOffset()
                animateView(toZoom = { zoomFor(quickZoomLevel.value) }, toOffset = { offsetFor(focus, zoomFor(quickZoomLevel.value)) })
            }
            // Glide to the touched spot rather than jumping there.
            ZoomMode.LOUPE -> animateView(
                toZoom = { zoomFor(loupeZoomLevel.value) },
                toOffset = { offsetFor(focus, zoomFor(loupeZoomLevel.value)) },
                millis = minOf(animationMillis.value, LOUPE_MOVE_MILLIS),
            )
        }
    }

    fun touchMove(position: Offset) {
        if (!touching) return
        focus = position
        // A running animation re-reads the focus every frame.
        if (animation?.isActive == true) return
        when (mode.value) {
            ZoomMode.QUICK_ZOOM -> if (rest != null) {
                val zoom = zoomFor(quickZoomLevel.value)
                scaleState.setZoomAndOffset(zoom, offsetFor(focus, zoom))
            }
            ZoomMode.LOUPE -> showLoupe()
        }
    }

    fun touchUp() {
        if (!touching) return
        touching = false
        if (mode.value != ZoomMode.QUICK_ZOOM) return
        val (restZoom, restOffset) = rest ?: return
        animateView(toZoom = { restZoom }, toOffset = { restOffset }, onEnd = { if (!touching) rest = null })
    }

    // ---- Modes ----

    /** [at]: where to put the loupe, as a spread position; defaults to where the main reader is looking. */
    fun toggleMode(at: Offset? = null) {
        setMode(if (mode.value == ZoomMode.LOUPE) ZoomMode.QUICK_ZOOM else ZoomMode.LOUPE, at)
    }

    fun setMode(newMode: ZoomMode, at: Offset? = null) {
        if (mode.value == newMode) return
        mode.value = newMode
        modeChanges.tryEmit(newMode)
        rest = null
        when (newMode) {
            ZoomMode.LOUPE -> {
                focus = at ?: viewCenter()
                animateView(toZoom = { zoomFor(loupeZoomLevel.value) }, toOffset = { offsetFor(focus, zoomFor(loupeZoomLevel.value)) })
            }
            ZoomMode.QUICK_ZOOM -> animateView(toZoom = { zoomFor(1f) }, toOffset = { Offset.Zero })
        }
    }

    /**
     * Pinching the main reader: zooming in from Quick Zoom switches to Loupe at that level;
     * pinching back out to the whole spread switches back to Quick Zoom.
     */
    private fun onUserZoom() {
        if (touching) return
        val level = currentLevel()
        when (mode.value) {
            ZoomMode.QUICK_ZOOM -> if (level > 1.05f) {
                animation?.cancel()
                rest = null
                loupeZoomLevel.value = level
                mode.value = ZoomMode.LOUPE
                modeChanges.tryEmit(ZoomMode.LOUPE)
            }

            ZoomMode.LOUPE -> if (level <= 1.02f) {
                mode.value = ZoomMode.QUICK_ZOOM
                modeChanges.tryEmit(ZoomMode.QUICK_ZOOM)
            } else {
                loupeZoomLevel.value = level
            }
        }
    }

    // ---- Main reader view ----

    private fun showLoupe() {
        val zoom = zoomFor(loupeZoomLevel.value)
        scaleState.setZoomAndOffset(zoom, offsetFor(focus, zoom))
    }

    /** New spread in Loupe mode: stay zoomed and start at the top of the first page. */
    private fun startZoomedIn(newScale: ScreenScaleState) {
        val zoom = loupeZoomLevel.value * fullVisibilityZoom(newScale)
        // Offsets past the edge are clamped, so this lands in the top corner where reading starts.
        val far = 1_000_000f
        val x = if (pagedReaderState.readingDirection.value == RIGHT_TO_LEFT) -far else far
        newScale.setZoomAndOffset(zoom, Offset(x, far))
    }

    /**
     * Animates the main reader from where it is now to a target that's re-read every frame,
     * so a finger moving during the animation is followed.
     */
    private fun animateView(
        toZoom: () -> Float,
        toOffset: () -> Offset,
        millis: Int = animationMillis.value,
        onEnd: () -> Unit = {},
    ) {
        animation?.cancel()
        val fromZoom = currentZoom()
        val fromOffset = currentOffset()
        animation = scope.launch {
            val progress = Animatable(0f)
            if (millis > 0) {
                progress.animateTo(1f, tween(millis, easing = FastOutSlowInEasing)) {
                    val t = value
                    scaleState.setZoomAndOffset(
                        zoom = fromZoom + (toZoom() - fromZoom) * t,
                        offset = fromOffset + (toOffset() - fromOffset) * t,
                    )
                }
            }
            scaleState.setZoomAndOffset(toZoom(), toOffset())
            onEnd()
        }
    }

    private fun currentZoom() = scaleState.zoom.value
    private fun currentOffset() = scaleState.transformation.value.offset

    private fun fullVisibilityZoom(state: ScreenScaleState = scaleState) =
        state.scaleForFullVisibility() / state.scaleFor100PercentZoom()

    /** Komelia's zoom value for a zoom level relative to the whole spread. */
    private fun zoomFor(level: Float) = level * fullVisibilityZoom()

    private fun currentLevel() = scaleState.transformation.value.scale / scaleState.scaleForFullVisibility()

    /** The offset that centers [focus] at [zoom], without showing past the spread's edges. */
    private fun offsetFor(focus: Offset, zoom: Float): Offset {
        val target = scaleState.targetSize.value
        val area = scaleState.areaSize.value
        val scale = scaleState.zoomToScale(zoom)
        val limitX = ((target.width * scale - area.width) / 2).coerceAtLeast(0f)
        val limitY = ((target.height * scale - area.height) / 2).coerceAtLeast(0f)
        return Offset(
            (-(focus.x - 0.5f) * target.width * scale).coerceIn(-limitX, limitX),
            (-(focus.y - 0.5f) * target.height * scale).coerceIn(-limitY, limitY),
        )
    }

    /** Spread position at a point on the main screen (pixels from its top left). */
    fun spreadPositionAt(screenX: Float, screenY: Float): Offset {
        val target = scaleState.targetSize.value
        val area = scaleState.areaSize.value
        if (target.width <= 0f || area.width == 0) return Offset(0.5f, 0.5f)
        // Komelia's transformation is relative to the screen center.
        val point = scaleState.transformation.value.pointOf(Offset(screenX - area.width / 2f, screenY - area.height / 2f))
        return Offset(point.x / target.width + 0.5f, point.y / target.height + 0.5f)
    }

    private fun viewCenter(): Offset {
        val area = scaleState.areaSize.value
        return spreadPositionAt(area.width / 2f, area.height / 2f)
    }

    /**
     * The part of the spread the main reader currently shows, as fractions of the spread, or null
     * when the whole spread is visible.
     */
    fun visibleArea(): Rect? {
        val target = scaleState.targetSize.value
        val area = scaleState.areaSize.value
        if (target.width <= 0f || area.width == 0) return null
        if (scaleState.transformation.value.scale <= scaleState.scaleForFullVisibility() * 1.01f) return null
        return Rect(spreadPositionAt(0f, 0f), spreadPositionAt(area.width.toFloat(), area.height.toFloat()))
    }
}
