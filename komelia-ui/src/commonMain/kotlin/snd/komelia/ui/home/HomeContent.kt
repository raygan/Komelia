package snd.komelia.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType.Companion.KeyDown
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.Res
import io.github.snd_r.komelia.ui.komelia_ui.generated.resources.home_filter_all
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import snd.komelia.komga.api.model.KomeliaBook
import snd.komelia.komga.api.model.KomeliaSeries
import snd.komelia.ui.LocalKeyEvents
import snd.komelia.ui.LocalPlatform
import snd.komelia.ui.common.cards.BookImageCard
import snd.komelia.ui.common.cards.SeriesImageCard
import snd.komelia.ui.common.controllerFocusOutline
import snd.komelia.ui.common.menus.BookMenuActions
import snd.komelia.ui.common.menus.SeriesMenuActions
import snd.komelia.ui.platform.PlatformType

@Composable
fun HomeContent(
    filters: List<HomeFilterData>,
    onEditStart: () -> Unit,

    activeFilterNumber: Int,
    onFilterChange: (Int) -> Unit,

    cardWidth: Dp,
    onSeriesClick: (KomeliaSeries) -> Unit,
    seriesMenuActions: SeriesMenuActions,
    bookMenuActions: BookMenuActions,
    onBookClick: (KomeliaBook) -> Unit,
    onBookReadClick: (KomeliaBook, Boolean) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    // Controller shoulder buttons L1/R1 move between the views in the toolbar, in on-screen order.
    val viewNumbers = remember(filters) {
        val shown = filters.filter {
            when (it) {
                is BookFilterData -> it.books.isNotEmpty()
                is SeriesFilterData -> it.series.isNotEmpty()
            }
        }.map { it.filter.order }
        if (filters.size > 1) listOf(0) + shown else shown
    }
    val currentActiveFilter by rememberUpdatedState(activeFilterNumber)
    val keyEvents = LocalKeyEvents.current
    LaunchedEffect(viewNumbers) {
        keyEvents.collect { event ->
            if (event.type != KeyDown || viewNumbers.isEmpty()) return@collect
            val step = when (event.key) {
                Key.ButtonL1 -> -1
                Key.ButtonR1 -> 1
                else -> return@collect
            }
            val index = viewNumbers.indexOf(currentActiveFilter).coerceAtLeast(0)
            val next = viewNumbers[(index + step).coerceIn(viewNumbers.indices)]
            if (next != currentActiveFilter) {
                onFilterChange(next)
                gridState.animateScrollToItem(0)
            }
        }
    }

    Column {
        Toolbar(
            filters = filters,
            currentFilterNumber = activeFilterNumber,
            onEditStart = onEditStart,
            onFilterChange = {
                onFilterChange(it)
                coroutineScope.launch { gridState.animateScrollToItem(0) }
            },
        )
        DisplayContent(
            filters = filters,
            activeFilterNumber = activeFilterNumber,

            gridState = gridState,
            cardWidth = cardWidth,
            onSeriesClick = onSeriesClick,
            seriesMenuActions = seriesMenuActions,
            bookMenuActions = bookMenuActions,
            onBookClick = onBookClick,
            onBookReadClick = onBookReadClick,
        )
    }
}

@Composable
private fun Toolbar(
    filters: List<HomeFilterData>,
    currentFilterNumber: Int,
    onFilterChange: (Int) -> Unit,
    onEditStart: () -> Unit
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
    )
    val nonEmptyFilters = remember(filters) {
        filters.filter {
            when (it) {
                is BookFilterData -> it.books.isNotEmpty()
                is SeriesFilterData -> it.series.isNotEmpty()
            }
        }
    }
    Box {
        val lazyRowState = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()

        // Keep the selected view's chip in sight when it changes (e.g. with L1/R1). Row items are:
        // spacer, edit chip, "All" (with more than one view), then the views.
        LaunchedEffect(currentFilterNumber) {
            val chipIndex = when {
                currentFilterNumber == 0 -> 2
                else -> nonEmptyFilters.indexOfFirst { it.filter.order == currentFilterNumber }
                    .takeIf { it >= 0 }
                    ?.let { it + if (filters.size > 1) 3 else 2 }
            } ?: return@LaunchedEffect
            val visible = lazyRowState.layoutInfo.visibleItemsInfo
            val shown = visible.firstOrNull { it.index == chipIndex }
            val fullyShown = shown != null && shown.offset >= 0 &&
                shown.offset + shown.size <= lazyRowState.layoutInfo.viewportEndOffset
            if (!fullyShown) lazyRowState.animateScrollToItem((chipIndex - 1).coerceAtLeast(0))
        }

        LazyRow(
            state = lazyRowState,
            modifier = Modifier.animateContentSize(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                Spacer(Modifier.width(20.dp))
            }

            item {
                FilterChip(
                    onClick = onEditStart,
                    selected = false,
                    label = {
                        Icon(Icons.Default.Tune, null)
                    },
                    colors = chipColors,
                    border = null,
                    modifier = Modifier.controllerFocusOutline(),
                )
            }

            if (filters.size > 1) {
                item {
                    FilterChip(
                        onClick = { onFilterChange(0) },
                        selected = currentFilterNumber == 0,
                        label = { Text(stringResource(Res.string.home_filter_all)) },
                        colors = chipColors,
                        border = null,
                        modifier = Modifier.controllerFocusOutline(),
                    )
                }
            }
            items(nonEmptyFilters) { data ->
                val display = remember(data.filter) {
                    when (data) {
                        is BookFilterData -> data.books.isNotEmpty()
                        is SeriesFilterData -> data.series.isNotEmpty()
                    }
                }
                if (display) {
                    FilterChip(
                        onClick = { onFilterChange(data.filter.order) },
                        selected = currentFilterNumber == data.filter.order || filters.size == 1,
                        label = { Text(data.filter.label) },
                        colors = chipColors,
                        border = null,
                        modifier = Modifier.controllerFocusOutline(),
                    )
                }
            }
            item {
                Spacer(Modifier.width(40.dp))
            }
        }

        if (LocalPlatform.current != PlatformType.MOBILE) {
            Row {
                if (lazyRowState.canScrollBackward) {
                    IconButton(
                        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surface),
                        onClick = { coroutineScope.launch { lazyRowState.animateScrollBy(-200.0f) } },
                    ) {
                        Icon(Icons.Default.ChevronLeft, null)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (lazyRowState.canScrollForward) {
                    IconButton(
                        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surface),
                        onClick = { coroutineScope.launch { lazyRowState.animateScrollBy(200.0f) } },
                    ) {
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplayContent(
    filters: List<HomeFilterData>,
    activeFilterNumber: Int,
    gridState: LazyGridState,
    cardWidth: Dp,
    onSeriesClick: (KomeliaSeries) -> Unit,
    seriesMenuActions: SeriesMenuActions,
    bookMenuActions: BookMenuActions,
    onBookClick: (KomeliaBook) -> Unit,
    onBookReadClick: (KomeliaBook, Boolean) -> Unit,
) {
    LazyVerticalGrid(
        modifier = Modifier.padding(horizontal = 20.dp),
        state = gridState,
        columns = GridCells.Adaptive(cardWidth),
        horizontalArrangement = Arrangement.spacedBy(15.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
        contentPadding = PaddingValues(bottom = 50.dp)
    ) {
        for (data in filters) {
            if (activeFilterNumber == 0 || data.filter.order == activeFilterNumber) {
                when (data) {
                    is BookFilterData -> BookFilterEntry(
                        label = data.filter.label,
                        books = data.books,
                        bookMenuActions = bookMenuActions,
                        onBookClick = onBookClick,
                        onBookReadClick = onBookReadClick,
                    )

                    is SeriesFilterData -> SeriesFilterEntries(
                        label = data.filter.label,
                        series = data.series,
                        onSeriesClick = onSeriesClick,
                        seriesMenuActions = seriesMenuActions,
                    )

                }
            }
        }
    }
}

private fun LazyGridScope.BookFilterEntry(
    label: String,
    books: List<KomeliaBook>,
    bookMenuActions: BookMenuActions,
    onBookClick: (KomeliaBook) -> Unit,
    onBookReadClick: (KomeliaBook, Boolean) -> Unit,
) {
    if (books.isEmpty()) return

    item(span = { GridItemSpan(maxLineSpan) }) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            HorizontalDivider()
        }
    }
    items(books) { book ->
        BookImageCard(
            book = book,
            onBookClick = { onBookClick(book) },
            onBookReadClick = { onBookReadClick(book, it) },
            bookMenuActions = bookMenuActions,
            showSeriesTitle = true,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun LazyGridScope.SeriesFilterEntries(
    label: String,
    series: List<KomeliaSeries>,
    onSeriesClick: (KomeliaSeries) -> Unit,
    seriesMenuActions: SeriesMenuActions,
) {
    if (series.isEmpty()) return
    item(span = { GridItemSpan(maxLineSpan) }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            HorizontalDivider()
        }
    }

    items(series) {
        SeriesImageCard(
            series = it,
            onSeriesClick = { onSeriesClick(it) },
            seriesMenuActions = seriesMenuActions,
            modifier = Modifier.fillMaxSize()
        )
    }
}
