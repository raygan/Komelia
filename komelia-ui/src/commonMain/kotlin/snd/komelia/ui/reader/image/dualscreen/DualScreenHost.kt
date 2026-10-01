package snd.komelia.ui.reader.image.dualscreen

import androidx.compose.runtime.Composable
import snd.komelia.ui.reader.image.paged.PagedReaderState

/**
 * Shows the dual-screen navigator on a second screen when the device has one (the AYN Thor's
 * bottom screen). Does nothing on devices and platforms without a second screen.
 */
@Composable
expect fun DualScreenHost(pagedReaderState: PagedReaderState)
