package snd.komelia.ui.reader.image.dualscreen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/** Fades a screen to black while the device is being turned, hiding the re-layout steps. */
@Composable
fun ScreenCover(dualScreenState: DualScreenState) {
    val covered by dualScreenState.coverScreens.collectAsState()
    val alpha by animateFloatAsState(
        targetValue = if (covered) 1f else 0f,
        animationSpec = tween(if (covered) COVER_FADE_IN_MILLIS.toInt() else COVER_FADE_OUT_MILLIS),
    )
    if (alpha > 0f) {
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(Color.Black))
    }
}
