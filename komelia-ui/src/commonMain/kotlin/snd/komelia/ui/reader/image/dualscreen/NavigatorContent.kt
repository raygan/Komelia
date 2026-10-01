package snd.komelia.ui.reader.image.dualscreen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import snd.komelia.image.ReaderImage.PageId
import snd.komelia.image.ReaderImageResult
import snd.komelia.image.toImageBitmap
import snd.komelia.settings.model.PagedReadingDirection.RIGHT_TO_LEFT
import snd.komelia.ui.reader.image.PageMetadata
import snd.komelia.ui.reader.image.paged.PagedReaderState
import kotlin.math.roundToInt

private val placeholder = Color(0xFF1C1C1C)
private val dim = Color.Black.copy(alpha = 0.55f)

/**
 * Bottom screen: the whole current spread, fitted to the screen, in reading order. While the main
 * reader is zoomed in, the part it shows is outlined. Touch zooms the main reader to that spot.
 */
@Composable
fun NavigatorContent(pagedReaderState: PagedReaderState, dualScreenState: DualScreenState) {
    val spread by pagedReaderState.currentSpread.collectAsState()
    val readingDirection by pagedReaderState.readingDirection.collectAsState()
    // Redraw the outline whenever the main reader's zoom or position changes.
    pagedReaderState.screenScaleState.transformation.collectAsState().value
    val scope = rememberCoroutineScope()
    val cache = remember { NavigatorImageCache(scope, pagedReaderState) }

    val turns by dualScreenState.quarterTurns.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).quarterTurns(turns)) {
        val area = IntSize(constraints.maxWidth, constraints.maxHeight)
        fun visualOrder(pages: List<PageMetadata>) = if (readingDirection == RIGHT_TO_LEFT) pages.reversed() else pages
        val pages = visualOrder(spread.pages.map { it.metadata })
        val layout = remember(pages, area) { layoutSpread(pages, area) }

        // Keep showing the previous spread until the new one is ready (briefly), then switch in one
        // step, rather than showing placeholders and pages popping in one at a time.
        var shown by remember { mutableStateOf(ShownSpread(emptyList(), emptyMap())) }
        LaunchedEffect(layout) {
            val jobs = layout.associateWith { cache.request(it) }
            withTimeoutOrNull(SWAP_WAIT_MILLIS) { jobs.values.awaitAll() }
            shown = ShownSpread(layout, jobs.readyBitmaps())
            jobs.values.joinAll()
            shown = ShownSpread(layout, jobs.readyBitmaps())

            // Prepare the neighbouring spreads so turning the page is instant here too.
            val index = pagedReaderState.currentSpreadIndex.value
            for (neighbour in listOf(index + 1, index - 1)) {
                val metadata = pagedReaderState.pageSpreads.value.getOrNull(neighbour) ?: continue
                layoutSpread(visualOrder(metadata), area).forEach { cache.request(it) }
            }
        }

        val spreadBox = remember(layout) { spreadBounds(layout) }
        val visible = dualScreenState.visibleArea()

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(spreadBox) {
                    if (spreadBox == null) return@pointerInput
                    fun toSpread(position: Offset) = Offset(
                        ((position.x - spreadBox.left) / spreadBox.width).coerceIn(0f, 1f),
                        ((position.y - spreadBox.top) / spreadBox.height).coerceIn(0f, 1f),
                    )
                    var lastTap: PointerInputChange? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val previous = lastTap
                        val doubleTap = previous != null &&
                            down.uptimeMillis - previous.uptimeMillis < viewConfiguration.doubleTapTimeoutMillis &&
                            (down.position - previous.position).getDistance() < viewConfiguration.touchSlop * 4
                        if (doubleTap) {
                            // Switch modes and ignore the rest of this touch.
                            lastTap = null
                            dualScreenState.toggleMode(at = toSpread(down.position))
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }

                        // In Quick Zoom, wait a moment before zooming so a quick tap (the first half
                        // of a double-tap) doesn't zoom in and straight back out. Dragging or a
                        // second finger starts the zoom right away.
                        var position = down.position
                        var liftedEarly: PointerInputChange? = null
                        if (dualScreenState.mode.value == ZoomMode.QUICK_ZOOM) {
                            withTimeoutOrNull(QUICK_ZOOM_HOLD_MILLIS) {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        liftedEarly = change
                                        break
                                    }
                                    position = change.position
                                    val moved = (position - down.position).getDistance() > viewConfiguration.touchSlop
                                    if (moved || event.changes.count { it.pressed } > 1) break
                                }
                            }
                        }
                        liftedEarly?.let { tap ->
                            lastTap = tap
                            return@awaitEachGesture
                        }

                        dualScreenState.touchDown(toSpread(position))
                        var up: PointerInputChange? = null
                        // A second finger pinches the zoom level; after a pinch the remaining finger
                        // doesn't move the view (it would jump), until all fingers lift.
                        var pinchDistance: Float? = null
                        var pinched = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    up = event.changes.firstOrNull { it.id == down.id }
                                    break
                                }
                                if (pressed.size >= 2) {
                                    val a = pressed[0].position
                                    val b = pressed[1].position
                                    val distance = (a - b).getDistance()
                                    val previous = pinchDistance
                                    if (previous != null && previous > 0f) {
                                        dualScreenState.pinch(distance / previous, toSpread((a + b) / 2f))
                                    }
                                    pinchDistance = distance
                                    pinched = true
                                } else {
                                    pinchDistance = null
                                    if (!pinched) dualScreenState.touchMove(toSpread(pressed[0].position))
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } finally {
                            dualScreenState.touchUp()
                        }
                        val isTap = !pinched && up != null &&
                            up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis &&
                            (up.position - down.position).getDistance() < viewConfiguration.touchSlop
                        lastTap = if (isTap) up else null
                    }
                }
        ) {
            for (placed in shown.layout) {
                val bitmap = shown.bitmaps[placed]
                if (bitmap == null) {
                    drawRect(placeholder, placed.offset.toOffset(), placed.size.toSize())
                } else {
                    drawImage(
                        image = bitmap,
                        dstOffset = placed.offset,
                        dstSize = placed.size,
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
            if (visible != null && spreadBox != null) drawOutline(spreadBox, visible)
        }

        ModeAnnouncement(dualScreenState)
        ScreenCover(dualScreenState)
    }
}

/** Briefly names the zoom mode after it changes. */
@Composable
private fun BoxScope.ModeAnnouncement(dualScreenState: DualScreenState) {
    var shown by remember { mutableStateOf<ZoomMode?>(null) }
    LaunchedEffect(dualScreenState) {
        dualScreenState.modeChanges.collectLatest { mode ->
            shown = mode
            delay(1500)
            shown = null
        }
    }
    val mode = shown ?: return
    Text(
        text = when (mode) {
            ZoomMode.QUICK_ZOOM -> "Quick Zoom"
            ZoomMode.LOUPE -> "Loupe"
        },
        color = Color.White,
        fontSize = 22.sp,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 16.dp)
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun spreadBounds(layout: List<PlacedPage>): Rect? {
    if (layout.isEmpty()) return null
    val first = layout.first()
    val last = layout.last()
    return Rect(
        left = first.offset.x.toFloat(),
        top = first.offset.y.toFloat(),
        right = (last.offset.x + last.size.width).toFloat(),
        bottom = (first.offset.y + first.size.height).toFloat(),
    )
}

/** Dims everything outside what the main reader shows, and outlines it. */
private fun DrawScope.drawOutline(spreadBox: Rect, visible: Rect) {
    val outline = Rect(
        left = spreadBox.left + visible.left.coerceIn(0f, 1f) * spreadBox.width,
        top = spreadBox.top + visible.top.coerceIn(0f, 1f) * spreadBox.height,
        right = spreadBox.left + visible.right.coerceIn(0f, 1f) * spreadBox.width,
        bottom = spreadBox.top + visible.bottom.coerceIn(0f, 1f) * spreadBox.height,
    )
    val w = size.width
    val h = size.height
    drawRect(dim, Offset(0f, 0f), Size(w, outline.top))
    drawRect(dim, Offset(0f, outline.bottom), Size(w, h - outline.bottom))
    drawRect(dim, Offset(0f, outline.top), Size(outline.left, outline.height))
    drawRect(dim, Offset(outline.right, outline.top), Size(w - outline.right, outline.height))
    drawRect(Color.White, outline.topLeft, outline.size, style = Stroke(width = 4f))
}

internal data class PlacedPage(
    val metadata: PageMetadata,
    val offset: IntOffset,
    val size: IntSize,
)

private class ShownSpread(val layout: List<PlacedPage>, val bitmaps: Map<PlacedPage, ImageBitmap>)

@OptIn(ExperimentalCoroutinesApi::class)
private fun Map<PlacedPage, Deferred<ImageBitmap?>>.readyBitmaps(): Map<PlacedPage, ImageBitmap> =
    mapNotNull { (placed, job) -> if (job.isCompleted) job.getCompleted()?.let { placed to it } else null }.toMap()

/** Puts the pages side by side at a common height and fits the row inside [area]. */
private fun layoutSpread(pages: List<PageMetadata>, area: IntSize): List<PlacedPage> {
    if (pages.isEmpty() || area.width == 0 || area.height == 0) return emptyList()
    val aspects = pages.map { page ->
        val size = page.size
        if (size == null || size.height == 0) 0.7f else size.width.toFloat() / size.height
    }
    val spreadAspect = aspects.sum()
    val height = minOf(area.height.toFloat(), area.width / spreadAspect)
    var x = (area.width - spreadAspect * height) / 2
    val top = ((area.height - height) / 2).roundToInt()
    return pages.mapIndexed { i, page ->
        val width = aspects[i] * height
        PlacedPage(
            metadata = page,
            offset = IntOffset(x.roundToInt(), top),
            size = IntSize(width.roundToInt(), height.roundToInt()),
        ).also { x += width }
    }
}

/**
 * Small copies of pages for the bottom screen. They come from the same processed images the main
 * reader uses (so crop and color correction match), shrunk once to the size they're drawn at.
 * Pages are prepared in parallel, and a request for one that's already prepared or in progress
 * shares the same job.
 */
private class NavigatorImageCache(
    private val scope: CoroutineScope,
    private val pagedReaderState: PagedReaderState,
) {
    private data class Key(val pageId: PageId, val height: Int)

    // Insertion-ordered, so the first key is the least recently requested.
    private val jobs = LinkedHashMap<Key, Deferred<ImageBitmap?>>()

    fun request(placed: PlacedPage): Deferred<ImageBitmap?> {
        val key = Key(placed.metadata.toPageId(), placed.size.height)
        jobs.remove(key)?.let { existing ->
            // Keep it unless it failed (the page wasn't loaded yet), so it can be retried.
            @OptIn(ExperimentalCoroutinesApi::class)
            val failed = existing.isCompleted && existing.getCompleted() == null
            if (!failed) {
                jobs[key] = existing
                return existing
            }
        }
        val job = scope.async(Dispatchers.Default) { shrink(placed) }
        jobs[key] = job
        while (jobs.size > MAX_CACHED_PAGES) jobs.remove(jobs.keys.first())
        return job
    }

    private suspend fun shrink(placed: PlacedPage): ImageBitmap? {
        val imageResult = pagedReaderState.cachedPage(placed.metadata)?.await()?.imageResult
            ?: pagedReaderState.currentSpread.value.pages.firstOrNull { it.metadata == placed.metadata }?.imageResult
        val readerImage = (imageResult as? ReaderImageResult.Success)?.image ?: return null
        // Owned by the ReaderImage: don't close it.
        val original = readerImage.getOriginalImage().getOrNull() ?: return null
        val targetHeight = placed.size.height.coerceAtLeast(1)
        val targetWidth = (original.width.toLong() * targetHeight / original.pageHeight).toInt().coerceAtLeast(1)
        return original.resize(targetWidth, targetHeight).use { it.toImageBitmap() }
    }
}

/** In Quick Zoom, how long a touch has to last before zooming, so quick taps don't zoom. */
private const val QUICK_ZOOM_HOLD_MILLIS = 60L

/** How long to keep showing the previous spread while the new one is prepared. */
private const val SWAP_WAIT_MILLIS = 250L

/** The current spread, its neighbours, and a little history. */
private const val MAX_CACHED_PAGES = 12
