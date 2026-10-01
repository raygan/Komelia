package snd.komelia.ui.reader.image.dualscreen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import snd.komelia.image.ReaderImageResult
import snd.komelia.settings.model.PagedReadingDirection.RIGHT_TO_LEFT
import snd.komelia.ui.reader.image.ScreenScaleState
import snd.komelia.ui.reader.image.paged.PagedReaderState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp

/** How long the Loupe takes to glide to a newly touched spot. */
private const val LOUPE_MOVE_MILLIS = 90

/** How long the screens take to fade out before turning. */
const val COVER_FADE_IN_MILLIS = 90L

/** How long the screens take to fade back in after turning. */
const val COVER_FADE_OUT_MILLIS = 160

/** How often to check whether the reader has finished re-laying out after turning. */
private const val SETTLE_CHECK_MILLIS = 50L

/** Never leave the screens covered longer than this, even if the page is slow to load. */
private const val MAX_COVERED_MILLIS = 900L

/** How long a step through the page takes. */
private const val STEP_MILLIS = 220

/** Right stick at full tilt roughly doubles the zoom level every half second. */
private const val ZOOM_STICK_RATE = 1.4f

private const val MAX_ZOOM_LEVEL = 8f

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
    private val settings: DualScreenSettingsStore,
) {
    private val scaleState: ScreenScaleState get() = pagedReaderState.screenScaleState
    private val preferences get() = settings.preferences.value

    val mode = MutableStateFlow(preferences.mode)

    /** Emits when the mode changes, so it can be announced. */
    val modeChanges = MutableSharedFlow<ZoomMode>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Zoom levels for the way the device is held right now; a fitted single page needs less zoom. */
    private var quickZoomLevel: Float
        get() = if (vertical) preferences.quickZoomVertical else preferences.quickZoomLandscape
        set(level) = settings.update { if (vertical) it.copy(quickZoomVertical = level) else it.copy(quickZoomLandscape = level) }

    private var loupeZoomLevel: Float
        get() = if (vertical) preferences.loupeZoomVertical else preferences.loupeZoomLandscape
        set(level) = settings.update { if (vertical) it.copy(loupeZoomVertical = level) else it.copy(loupeZoomLandscape = level) }

    private val animationMillis get() = preferences.animationMillis

    val doubleTapSwitchesMode get() = preferences.doubleTapSwitchesMode

    /** How long a touch must last before Quick Zoom starts: none unless taps need telling apart. */
    val quickZoomHoldMillis get() = if (preferences.doubleTapSwitchesMode) preferences.tapDelayMillis.toLong() else 0L

    /**
     * Clockwise quarter turns applied to both screens so they read upright: 0 held normally, 3 held
     * vertically with the main screen on the right, 1 with it on the left.
     */
    val quarterTurns = MutableStateFlow(0)
    private val vertical get() = quarterTurns.value % 2 != 0
    private var sensedQuarterTurns = 0
    private var targetQuarterTurns = 0
    private var rotation: Job? = null

    /**
     * True while both screens should be covered: turning the device re-lays out the reader in
     * several visible steps, so they happen behind a brief fade instead.
     */
    val coverScreens = MutableStateFlow(false)

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
        // Changes made in the settings menu.
        settings.preferences.map { it.mode }.distinctUntilChanged().onEach { setMode(it) }.launchIn(scope)
        settings.preferences.map { it.orientation }.distinctUntilChanged().onEach { applyQuarterTurns() }.launchIn(scope)

        // Opening the reader in Loupe mode: zoom in once the first spread is laid out.
        if (mode.value == ZoomMode.LOUPE) scope.launch {
            pagedReaderState.currentSpread.first { it.pages.isNotEmpty() }
            scaleState.areaSize.first { it.width > 0 }
            startZoomedIn(scaleState, atEnd = false)
        }
    }

    fun dispose() {
        pagedReaderState.spreadStartOverride = null
        pagedReaderState.forceSinglePage(false)
    }

    // ---- Rotation ----

    /** From the orientation sensor; used unless the orientation is fixed in settings. */
    fun setSensedQuarterTurns(turns: Int) {
        sensedQuarterTurns = turns
        applyQuarterTurns()
    }

    /**
     * Held vertically, the reader shows one page at a time. The screens fade out, turn and re-lay
     * out, then fade back in once the page has loaded at its new size.
     */
    private fun applyQuarterTurns() {
        val turns = preferences.orientation.quarterTurns ?: sensedQuarterTurns
        if (turns == targetQuarterTurns) return
        targetQuarterTurns = turns
        rotation?.cancel()
        rotation = scope.launch {
            coverScreens.value = true
            delay(COVER_FADE_IN_MILLIS)

            val wasVertical = vertical
            quarterTurns.value = turns
            settings.heldVertically.value = vertical
            if (vertical != wasVertical) {
                animation?.cancel()
                rest = null
                pagedReaderState.forceSinglePage(vertical)
            }

            withTimeoutOrNull(MAX_COVERED_MILLIS) {
                // The reader picks up its turned size and reloads the page, sometimes more than once
                // (layout change, then size change); each page image then re-renders in the
                // background, and its size on screen follows the rendered picture. Wait for that.
                scaleState.areaSize.first { it.width > 0 && (it.width < it.height) == vertical }
                while (!pagesRenderedForScreen()) delay(SETTLE_CHECK_MILLIS)
                // One more frame so the final picture is on screen before fading in.
                delay(SETTLE_CHECK_MILLIS)
            }
            coverScreens.value = false
        }
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
                animateView(toZoom = { zoomFor(quickZoomLevel) }, toOffset = { offsetFor(focus, zoomFor(quickZoomLevel)) })
            }
            // Glide to the touched spot rather than jumping there.
            ZoomMode.LOUPE -> animateView(
                toZoom = { zoomFor(loupeZoomLevel) },
                toOffset = { offsetFor(focus, zoomFor(loupeZoomLevel)) },
                millis = minOf(animationMillis, LOUPE_MOVE_MILLIS),
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
                val zoom = zoomFor(quickZoomLevel)
                scaleState.setZoomAndOffset(zoom, offsetFor(focus, zoom))
            }
            ZoomMode.LOUPE -> showLoupe()
        }
    }

    /**
     * Pinching the second screen sets the zoom level of the current mode. It resizes the outline:
     * spreading the fingers makes the outline bigger, which zooms out. The view follows the point
     * between the fingers.
     */
    fun pinch(factor: Float, center: Offset) {
        if (!touching) return
        focus = center
        when (mode.value) {
            ZoomMode.QUICK_ZOOM -> quickZoomLevel = (quickZoomLevel / factor).coerceIn(1f, MAX_ZOOM_LEVEL)
            ZoomMode.LOUPE -> loupeZoomLevel = (loupeZoomLevel / factor).coerceIn(1f, MAX_ZOOM_LEVEL)
        }
        // A running zoom-in animation picks up the new level and focus by itself.
        if (animation?.isActive == true) return
        when (mode.value) {
            ZoomMode.QUICK_ZOOM -> if (rest != null) {
                val zoom = zoomFor(quickZoomLevel)
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

    /** [at]: where to put the loupe, as a spread position; defaults to where reading starts on the spread. */
    fun toggleMode(at: Offset? = null) {
        setMode(if (mode.value == ZoomMode.LOUPE) ZoomMode.QUICK_ZOOM else ZoomMode.LOUPE, at)
    }

    fun setMode(newMode: ZoomMode, at: Offset? = null) {
        if (mode.value == newMode) return
        changeMode(newMode)
        rest = null
        when (newMode) {
            ZoomMode.LOUPE -> {
                // Without a touched spot (a button or the settings menu), start where reading
                // starts: the top left, or the top right for right-to-left. Edges clamp, so a
                // corner lands exactly there.
                focus = at ?: Offset(if (readsRightToLeft()) 1f else 0f, 0f)
                animateView(toZoom = { zoomFor(loupeZoomLevel) }, toOffset = { offsetFor(focus, zoomFor(loupeZoomLevel)) })
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
                loupeZoomLevel = level
                changeMode(ZoomMode.LOUPE)
            }

            ZoomMode.LOUPE -> if (level <= 1.02f) {
                changeMode(ZoomMode.QUICK_ZOOM)
            } else {
                loupeZoomLevel = level
            }
        }
    }

    /** Records a mode change: announced on the second screen and remembered for next time. */
    private fun changeMode(newMode: ZoomMode) {
        mode.value = newMode
        modeChanges.tryEmit(newMode)
        if (preferences.mode != newMode) settings.update { it.copy(mode = newMode) }
    }

    // ---- Main reader view ----

    private fun showLoupe() {
        val zoom = zoomFor(loupeZoomLevel)
        scaleState.setZoomAndOffset(zoom, offsetFor(focus, zoom))
    }

    /**
     * New spread in Loupe mode: stay zoomed and start in the top corner where reading starts, or
     * the bottom corner where it ends when stepping backwards.
     */
    private fun startZoomedIn(newScale: ScreenScaleState, atEnd: Boolean) {
        val zoom = loupeZoomLevel * fullVisibilityZoom(newScale)
        // Offsets past the edge are clamped, so these land exactly in a corner.
        val far = 1_000_000f
        val readsLeftToRight = pagedReaderState.readingDirection.value != RIGHT_TO_LEFT
        val x = if (readsLeftToRight != atEnd) far else -far
        val y = if (atEnd) -far else far
        newScale.setZoomAndOffset(zoom, Offset(x, y))
    }

    /**
     * Whether every page of the current spread has been rendered at the size the reader lays it
     * out at for the current screen: each page gets an equal share of the width (Komelia's
     * layout) and is fitted inside it, so its rendered size touches one side of that share.
     */
    private fun pagesRenderedForScreen(): Boolean {
        val pages = pagedReaderState.currentSpread.value.pages
        val area = scaleState.areaSize.value
        if (pages.isEmpty() || area.width == 0) return false
        val share = IntSize(area.width / pages.size, area.height)
        return pages.all { page ->
            val image = (page.imageResult as? ReaderImageResult.Success)?.image ?: return@all page.imageResult != null
            val rendered = image.displaySize.value ?: return@all false
            val painter = image.painter.value ?: return@all false
            painter.intrinsicSize == rendered.toSize() &&
                rendered.width <= share.width + 2 && rendered.height <= share.height + 2 &&
                (abs(rendered.width - share.width) <= 2 || abs(rendered.height - share.height) <= 2)
        }
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

    /** Left and right shoulder buttons follow the page: for right-to-left reading, left goes forward. */
    fun stepLeft() = if (readsRightToLeft()) stepNext() else stepPrevious()
    fun stepRight() = if (readsRightToLeft()) stepPrevious() else stepNext()

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
                toZoom = { zoomFor(loupeZoomLevel) },
                toOffset = { offsetFor(focus, zoomFor(loupeZoomLevel)) },
                millis = if (animationMillis == 0) 0 else STEP_MILLIS,
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

        val view = visibleFraction(zoomFor(loupeZoomLevel))
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
                loupeZoomLevel = (loupeZoomLevel * exp(-zoomStick * abs(zoomStick) * ZOOM_STICK_RATE * seconds))
                    .coerceIn(1f, MAX_ZOOM_LEVEL)
            }
            val view = visibleFraction(zoomFor(loupeZoomLevel))
            focus = clampFocus(
                Offset(
                    focus.x + stickX * abs(stickX) * view.width * preferences.stickSpeed * seconds,
                    focus.y + stickY * abs(stickY) * view.height * preferences.stickSpeed * seconds,
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
        millis: Int = animationMillis,
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
