package snd.komelia.ui.reader.image.dualscreen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import kotlin.math.roundToInt
import snd.komelia.image.ReaderImage.PageId
import snd.komelia.image.ReaderImageResult
import snd.komelia.image.toImageBitmap
import snd.komelia.settings.model.PagedReadingDirection.RIGHT_TO_LEFT
import snd.komelia.ui.reader.image.paged.PagedReaderState

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
    val cache = remember { NavigatorImageCache() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val area = IntSize(constraints.maxWidth, constraints.maxHeight)
        val pages = if (readingDirection == RIGHT_TO_LEFT) spread.pages.reversed() else spread.pages
        val layout = remember(pages, area) { layoutSpread(pages, area) }

        val bitmaps by produceState(emptyMap<PageId, ImageBitmap>(), layout) {
            value = layout.mapNotNull { placed -> cache.get(placed.pageId)?.let { placed.pageId to it } }.toMap()
            for (placed in layout) {
                val bitmap = cache.getOrLoad(placed) ?: continue
                value = value + (placed.pageId to bitmap)
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
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dualScreenState.touchDown(toSpread(down.position))
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                dualScreenState.touchMove(toSpread(change.position))
                                change.consume()
                            }
                        } finally {
                            dualScreenState.touchUp()
                        }
                    }
                }
        ) {
            for (placed in layout) {
                val bitmap = bitmaps[placed.pageId]
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
    }
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

internal class PlacedPage(
    val pageId: PageId,
    val imageResult: ReaderImageResult?,
    val offset: IntOffset,
    val size: IntSize,
)

/** Puts the pages side by side at a common height and fits the row inside [area]. */
private fun layoutSpread(pages: List<PagedReaderState.Page>, area: IntSize): List<PlacedPage> {
    if (pages.isEmpty() || area.width == 0 || area.height == 0) return emptyList()
    val aspects = pages.map { page ->
        val size = page.metadata.size
        if (size == null || size.height == 0) 0.7f else size.width.toFloat() / size.height
    }
    val spreadAspect = aspects.sum()
    val height = minOf(area.height.toFloat(), area.width / spreadAspect)
    var x = (area.width - spreadAspect * height) / 2
    val top = ((area.height - height) / 2).roundToInt()
    return pages.mapIndexed { i, page ->
        val width = aspects[i] * height
        PlacedPage(
            pageId = page.metadata.toPageId(),
            imageResult = page.imageResult,
            offset = IntOffset(x.roundToInt(), top),
            size = IntSize(width.roundToInt(), height.roundToInt()),
        ).also { x += width }
    }
}

/**
 * Small copies of pages for the bottom screen. They come from the same processed image the top
 * screen uses (so crop and color correction match), shrunk once to the size they're drawn at.
 */
private class NavigatorImageCache {
    // Insertion-ordered, so the first key is the least recently stored.
    private val bitmaps = LinkedHashMap<PageId, ImageBitmap>()

    fun get(pageId: PageId): ImageBitmap? = bitmaps[pageId]

    private fun put(pageId: PageId, bitmap: ImageBitmap) {
        bitmaps.remove(pageId)
        bitmaps[pageId] = bitmap
        while (bitmaps.size > 8) bitmaps.remove(bitmaps.keys.first())
    }

    suspend fun getOrLoad(placed: PlacedPage): ImageBitmap? {
        bitmaps[placed.pageId]?.let { cached ->
            if (cached.height >= placed.size.height) return cached
        }
        val readerImage = (placed.imageResult as? ReaderImageResult.Success)?.image ?: return null
        // Owned by the ReaderImage: don't close it.
        val original = readerImage.getOriginalImage().getOrNull() ?: return null
        val targetHeight = placed.size.height.coerceAtLeast(1)
        val targetWidth = (original.width.toLong() * targetHeight / original.pageHeight).toInt().coerceAtLeast(1)
        val bitmap = original.resize(targetWidth, targetHeight).use { it.toImageBitmap() }
        put(placed.pageId, bitmap)
        return bitmap
    }
}
