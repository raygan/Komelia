package snd.komelia.ui.reader.image.dualscreen

import androidx.compose.runtime.Composable
import snd.komelia.ui.reader.image.paged.PagedReaderState

@Composable
actual fun rememberDualScreenState(pagedReaderState: PagedReaderState): DualScreenState? = null

@Composable
actual fun DualScreenHost(pagedReaderState: PagedReaderState, dualScreenState: DualScreenState) = Unit

@Composable
actual fun rememberDualScreenSettings(): DualScreenSettingsStore? = null
