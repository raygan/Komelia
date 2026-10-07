package snd.komelia.ui.reader.image.dualscreen

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Dual-screen settings. Kept in their own small store rather than Komelia's settings database, so
 * this fork never adds database migrations that conflict with upstream's.
 */
data class DualScreenPreferences(
    val mode: ZoomMode = ZoomMode.QUICK_ZOOM,
    /** Zoom levels relative to the whole spread (or page, held vertically) fitting the screen. */
    val quickZoomLandscape: Float = 2.5f,
    val loupeZoomLandscape: Float = 2.5f,
    val quickZoomVertical: Float = 2f,
    val loupeZoomVertical: Float = 2f,
    val animationMillis: Int = 120,
    /** Screenfuls per second the left stick moves the Loupe at full tilt. */
    val stickSpeed: Float = 1.5f,
    val orientation: DualScreenOrientation = DualScreenOrientation.AUTO,
    /** Double-tapping either screen switches between Quick Zoom and Loupe. */
    val doubleTapSwitchesMode: Boolean = true,
    /**
     * With double-tap on, how long a touch on the second screen must last before Quick Zoom starts,
     * so the first tap of a double-tap doesn't zoom. Shorter is snappier; longer flickers less.
     */
    val tapDelayMillis: Int = 60,
)

enum class DualScreenOrientation(
    /** Fixed clockwise quarter turns for the screens' content, or null to follow how it's held. */
    val quarterTurns: Int?,
) {
    AUTO(null),
    LANDSCAPE(0),
    VERTICAL_MAIN_SCREEN_RIGHT(3),
    VERTICAL_MAIN_SCREEN_LEFT(1),
}

interface DualScreenSettingsStore {
    val preferences: StateFlow<DualScreenPreferences>

    /** Whether the device is currently held vertically, so settings can show the right zoom levels. */
    val heldVertically: MutableStateFlow<Boolean>

    fun update(transform: (DualScreenPreferences) -> DualScreenPreferences)
}

/** The settings store when the device has a second screen, or null without one. */
@Composable
expect fun rememberDualScreenSettings(): DualScreenSettingsStore?

/**
 * The first time an image reader opens on a device with a second screen, switches to the
 * dual-screen reader ([onSelectDualScreen]) and says where to change it. Only ever once, so
 * choosing another reader type sticks.
 */
@Composable
expect fun DualScreenFirstRun(onSelectDualScreen: () -> Unit)
