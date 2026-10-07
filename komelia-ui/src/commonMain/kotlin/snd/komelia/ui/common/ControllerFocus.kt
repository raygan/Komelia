package snd.komelia.ui.common

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.RippleThemeConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Highlight for the focused item when navigating with a game controller or keyboard, using
 * Material's own inset focus ring. Focus only happens when navigating that way, so touch users
 * never see it. Provided app-wide in MainView through LocalRippleThemeConfiguration and
 * LocalRippleConfiguration, so every component that uses Material's ripple gets it.
 */
private val ControllerFocusColor = Color(0xFFFF9800)

/**
 * Material's inset focus ring style, thicker than its 2 dp default so it stands out against cover
 * art on the book and series cards.
 */
val controllerFocusRingStyle = RippleThemeConfiguration(
    focus = RippleThemeConfiguration.Focus.InsetRing(
        outerStrokeInset = 0.dp,
        outerStrokeWidth = 4.dp,
        innerStrokeInset = 4.dp,
        innerStrokeWidth = 1.dp,
    )
)

/** The focus ring's color. */
@OptIn(ExperimentalMaterial3Api::class)
val controllerFocusRipple = RippleConfiguration(
    focus = RippleConfiguration.Focus.InsetRing(
        outerStrokeColor = ControllerFocusColor,
        innerStrokeColor = Color.Transparent,
    )
)
