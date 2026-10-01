package snd.komelia.ui.reader.image.dualscreen

import androidx.compose.runtime.Composable
import snd.komelia.ui.reader.image.paged.PagedReaderState

/**
 * Dual-screen state for the paged reader when the device has a second screen (the AYN Thor's
 * bottom screen), or null on devices and platforms without one.
 */
@Composable
expect fun rememberDualScreenState(pagedReaderState: PagedReaderState): DualScreenState?

/** Shows the navigator on the second screen. */
@Composable
expect fun DualScreenHost(pagedReaderState: PagedReaderState, dualScreenState: DualScreenState)
