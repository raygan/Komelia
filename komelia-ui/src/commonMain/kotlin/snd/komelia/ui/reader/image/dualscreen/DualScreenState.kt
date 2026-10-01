package snd.komelia.ui.reader.image.dualscreen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp

/** How long the Loupe takes to glide to a newly touched spot. */
private const val LOUPE_MOVE_MILLIS = 90

/** How long a step through the page takes. */
private const val STEP_MILLIS = 220

/** Left stick at full tilt moves this many screenfuls per second. */
private const val MOVE_STICK_RATE = 1.5f

/** Right stick at full tilt roughly doubles the zoom level every half second. */
private const val ZOOM_STICK_RATE = 1.4f

private const val MAX_ZOOM_LEVEL = 8f

/** Starting zoom level when held vertically, where a fitted single page is already large. */
private const val VERTICAL_ZOOM_LEVEL = 2f

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

    /**
     * Clockwise quarter turns applied to both screens so they read upright: 0 held normally, 3 held
     * vertically with the main screen on the right, 1 with it on the left.
     */
    val quarterTurns = MutableStateFlow(0)
    private val vertical get() = quarterTurns.value % 2 != 0

    /** Quick Zoom and Loupe levels for the orientation not in use; a single page needs less zoom. */
    private var otherOrientationLevels = VERTICAL_ZOOM_LEVEL to VERTICAL_ZOOM_LEVEL

    private var focus = Offset(0.5f, 0.5f)
    private var touching = false

    /** Stepping back past a spread's first stop lands on the previous spread's last stop. */
    private var startNextSpreadAtEnd = false

    private var stickX = 0f
    private var stickY = 0f
    private var zoomStick = 0f
    private var stickJob: Job? = null
    private var animation: Job? = null

    /** In Quick Zoom: the zoom and offset to return to when the finger lifts. */
    private var rest: Pair<Float, Offset>? = null

    init {
        scaleState.userZoomEvents.onEach { onUserZoom() }.launchIn(scope)
        pagedReaderState.spreadStartOverride = { newScale ->
            if (mode.value == ZoomMode.LOUPE) startZoomedIn(newScale, atEnd = startNextSpreadAtEnd)
            startNextSpreadAtEnd = false
        }
    }

    fun dispose() {
        pagedReaderState.spreadStartOverride = null
        pagedReaderState.forceSinglePage(false)
    }

    // ---- Rotation ----

    /** From the orientation sensor. Held vertically, the reader shows one page at a time. */
    fun setQuarterTurns(turns: Int) {
        if (turns == quarterTurns.value) return
        val wasVertical = vertical
        quarterTurns.value = turns
        if (vertical == wasVertical) return

        animation?.cancel()
        rest = null
        val levels = quickZoomLevel.value to loupeZoomLevel.value
        quickZoomLevel.value = otherOrientationLevels.first
        loupeZoomLevel.value = otherOrientationLevels.second
        otherOrientationLevels = levels
        pagedReaderState.forceSinglePage(vertical)
    }

    /**
     * Turns a direction on the physical controls (D-pad, sticks), which rotate with the device,
     * into the direction it points on the turned screens.
     */
    fun toScreenDirection(x: Float, y: Float): Offset = when (quarterTurns.value) {
        1 -> Offset(y, -x)
        3 -> Offset(-y, x)
        else -> Offset(x, y)
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

    /**
     * New spread in Loupe mode: stay zoomed and start in the top corner where reading starts, or
     * the bottom corner where it ends when stepping backwards.
     */
    private fun startZoomedIn(newScale: ScreenScaleState, atEnd: Boolean) {
        val zoom = loupeZoomLevel.value * fullVisibilityZoom(newScale)
        // Offsets past the edge are clamped, so these land exactly in a corner.
        val far = 1_000_000f
        val readsLeftToRight = pagedReaderState.readingDirection.value != RIGHT_TO_LEFT
        val x = if (readsLeftToRight != atEnd) far else -far
        val y = if (atEnd) -far else far
        newScale.setZoomAndOffset(zoom, Offset(x, y))
    }

    // ---- Page controls ----

    /** L1/R1 and edge taps: in Loupe, step through the spread before turning the page. */
    fun stepNext() = if (mode.value == ZoomMode.LOUPE) step(forward = true) else turnNext()
    fun stepPrevious() = if (mode.value == ZoomMode.LOUPE) step(forward = false) else turnPrevious()

    /** L2/R2 and the D-pad: always turn the whole spread. */
    fun turnNext() {
        if (!touching) pagedReaderState.nextPage()
    }

    fun turnPrevious() {
        if (!touching) pagedReaderState.previousPage()
    }

    fun turnLeft() = if (readsRightToLeft()) turnNext() else turnPrevious()
    fun turnRight() = if (readsRightToLeft()) turnPrevious() else turnNext()

    private fun readsRightToLeft() = pagedReaderState.readingDirection.value == RIGHT_TO_LEFT

    private fun step(forward: Boolean) {
        if (touching) return
        val stops = stops()
        if (stops.isEmpty()) return
        val center = viewCenter()
        val current = stops.indices.minBy { (stops[it] - center).getDistanceSquared() }
        val next = current + if (forward) 1 else -1
        if (next in stops.indices) {
            focus = stops[next]
            animateView(
                toZoom = { zoomFor(loupeZoomLevel.value) },
                toOffset = { offsetFor(focus, zoomFor(loupeZoomLevel.value)) },
                millis = if (animationMillis.value == 0) 0 else STEP_MILLIS,
            )
        } else if (forward) {
            pagedReaderState.nextPage()
        } else {
            startNextSpreadAtEnd = true
            val before = pagedReaderState.currentSpreadIndex.value
            pagedReaderState.previousPage()
            // At the start of the book there's no previous spread to land on.
            if (pagedReaderState.currentSpreadIndex.value == before) startNextSpreadAtEnd = false
        }
    }

    /**
     * Where Loupe stepping stops on the current spread, in reading order: page by page, each page
     * in rows from the top, each row across in reading direction. Neighbouring stops overlap a little.
     */
    private fun stops(): List<Offset> {
        val pages = pagedReaderState.currentSpread.value.pages
        if (pages.isEmpty()) return emptyList()
        val aspects = pages.map { page ->
            val size = page.metadata.size
            if (size == null || size.height == 0) 0.7f else size.width.toFloat() / size.height
        }
        // Page ranges in reading order, laid out left to right; right-to-left spreads put the first
        // page on the right, so mirror them.
        val total = aspects.sum()
        var x = 0f
        val ranges = aspects.map { aspect -> (x / total to (x + aspect) / total).also { x += aspect } }
        val readingOrder = if (readsRightToLeft()) ranges.map { (a, b) -> (1 - b) to (1 - a) } else ranges

        val view = visibleFraction(zoomFor(loupeZoomLevel.value))
        val stops = mutableListOf<Offset>()
        for ((left, right) in readingOrder) {
            val columns = centers(left, right, view.width).let { if (readsRightToLeft()) it.reversed() else it }
            for (row in centers(0f, 1f, view.height)) for (column in columns) {
                val stop = clampFocus(Offset(column, row), view)
                if (stops.lastOrNull()?.let { (it - stop).getDistance() < 0.001f } != true) stops += stop
            }
        }
        return stops
    }

    private fun centers(start: Float, end: Float, view: Float): List<Float> {
        val length = end - start
        if (length <= view) return listOf((start + end) / 2)
        val count = ceil((length - view) / (view * 0.85f)).toInt() + 1
        return (0 until count).map { start + view / 2 + (length - view) * it / (count - 1) }
    }

    // ---- Sticks ----

    /** Left stick moves the Loupe; right stick up and down zooms it. Ignored in Quick Zoom. */
    fun setSticks(x: Float, y: Float, zoom: Float) {
        stickX = x
        stickY = y
        zoomStick = zoom
        val active = mode.value == ZoomMode.LOUPE && (x != 0f || y != 0f || zoom != 0f)
        if (active && stickJob?.isActive != true) stickJob = scope.launch { followSticks() }
    }

    private suspend fun followSticks() {
        animation?.cancel()
        focus = viewCenter()
        var last = withFrameNanos { it }
        while (mode.value == ZoomMode.LOUPE && !touching && (stickX != 0f || stickY != 0f || zoomStick != 0f)) {
            val now = withFrameNanos { it }
            val seconds = ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            // Squared response: small tilts for fine adjustment, full tilt to cross the page.
            if (zoomStick != 0f) {
                loupeZoomLevel.value = (loupeZoomLevel.value * exp(-zoomStick * abs(zoomStick) * ZOOM_STICK_RATE * seconds))
                    .coerceIn(1f, MAX_ZOOM_LEVEL)
            }
            val view = visibleFraction(zoomFor(loupeZoomLevel.value))
            focus = clampFocus(
                Offset(
                    focus.x + stickX * abs(stickX) * view.width * MOVE_STICK_RATE * seconds,
                    focus.y + stickY * abs(stickY) * view.height * MOVE_STICK_RATE * seconds,
                ),
                view,
            )
            showLoupe()
        }
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

    /** How much of the spread the main reader shows at [zoom], as fractions of its width and height. */
    private fun visibleFraction(zoom: Float): Size {
        val target = scaleState.targetSize.value
        val area = scaleState.areaSize.value
        val scale = scaleState.zoomToScale(zoom)
        if (target.width <= 0f || target.height <= 0f || scale <= 0f) return Size(1f, 1f)
        return Size(area.width / (target.width * scale), area.height / (target.height * scale))
    }

    /** Keeps a focus point where the view can actually center, so the stick never feels stuck at an edge. */
    private fun clampFocus(point: Offset, view: Size): Offset = Offset(
        if (view.width >= 1f) 0.5f else point.x.coerceIn(view.width / 2, 1 - view.width / 2),
        if (view.height >= 1f) 0.5f else point.y.coerceIn(view.height / 2, 1 - view.height / 2),
    )

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
