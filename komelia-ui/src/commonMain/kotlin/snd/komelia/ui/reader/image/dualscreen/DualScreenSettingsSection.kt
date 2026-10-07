package snd.komelia.ui.reader.image.dualscreen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import snd.komelia.ui.common.components.DropdownChoiceMenu
import snd.komelia.ui.common.components.LabeledEntry
import snd.komelia.ui.common.components.SwitchWithLabel
import kotlin.math.roundToInt

private val modes = listOf(
    LabeledEntry(ZoomMode.QUICK_ZOOM, "Quick Zoom: zoom while touching the second screen"),
    LabeledEntry(ZoomMode.LOUPE, "Loupe: stay zoomed and steer it"),
)
private val animations = listOf(0, 80, 120, 200, 350).map { LabeledEntry(it, if (it == 0) "Off" else "$it ms") }
private val stickSpeeds = listOf(LabeledEntry(0.8f, "Slow"), LabeledEntry(1.5f, "Medium"), LabeledEntry(2.5f, "Fast"))
private val tapDelays = listOf(60 to "60 ms", 90 to "90 ms", 120 to "120 ms")
private val orientations = listOf(
    LabeledEntry(DualScreenOrientation.AUTO, "Follow how it's held"),
    LabeledEntry(DualScreenOrientation.LANDSCAPE, "Always landscape"),
    LabeledEntry(DualScreenOrientation.VERTICAL_MAIN_SCREEN_RIGHT, "Vertical, main screen on the right"),
    LabeledEntry(DualScreenOrientation.VERTICAL_MAIN_SCREEN_LEFT, "Vertical, main screen on the left"),
)

/** "Dual screen" section of the paged reader's settings, shown only on devices with a second screen. */
@Composable
fun DualScreenSettingsSection() {
    val settings = rememberDualScreenSettings() ?: return
    val preferences by settings.preferences.collectAsState()
    val vertical by settings.heldVertically.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Dual screen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 10.dp))
        Choice("Zoom mode (L3/R3 or double-tap switches)", preferences.mode, modes) { mode ->
            settings.update { it.copy(mode = mode) }
        }

        val heldHow = if (vertical) " (held vertically)" else ""
        ZoomLevelSlider("Quick Zoom level$heldHow", if (vertical) preferences.quickZoomVertical else preferences.quickZoomLandscape) { level ->
            settings.update { if (vertical) it.copy(quickZoomVertical = level) else it.copy(quickZoomLandscape = level) }
        }
        ZoomLevelSlider("Loupe level$heldHow (also pinch, or right stick)", if (vertical) preferences.loupeZoomVertical else preferences.loupeZoomLandscape) { level ->
            settings.update { if (vertical) it.copy(loupeZoomVertical = level) else it.copy(loupeZoomLandscape = level) }
        }

        Choice("Zoom animation", preferences.animationMillis, animations) { millis ->
            settings.update { it.copy(animationMillis = millis) }
        }
        Choice("Left stick speed (Loupe)", preferences.stickSpeed, stickSpeeds) { speed ->
            settings.update { it.copy(stickSpeed = speed) }
        }
        Choice("Orientation", preferences.orientation, orientations) { orientation ->
            settings.update { it.copy(orientation = orientation) }
        }

        SwitchWithLabel(
            checked = preferences.doubleTapSwitchesMode,
            onCheckedChange = { on -> settings.update { it.copy(doubleTapSwitchesMode = on) } },
            label = { Text("Double-tap switches zoom mode") },
            supportingText = {
                Text("On either screen. Turn off for instant zooming, and an instant menu tap in the middle of the main screen")
            },
            contentPadding = PaddingValues(horizontal = 10.dp),
        )
        AnimatedVisibility(visible = preferences.doubleTapSwitchesMode) {
            Column(Modifier.padding(horizontal = 10.dp)) {
                Text("Delay before Quick Zoom starts", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "So a quick tap can start a double-tap without zooming. Shorter feels snappier; longer avoids a zoom flicker when double-tapping.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.selectableGroup()) {
                    for ((millis, label) in tapDelays) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .selectable(
                                    selected = preferences.tapDelayMillis == millis,
                                    onClick = { settings.update { it.copy(tapDelayMillis = millis) } },
                                    role = Role.RadioButton,
                                )
                                .padding(end = 16.dp),
                        ) {
                            RadioButton(selected = preferences.tapDelayMillis == millis, onClick = null)
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> Choice(label: String, selected: T, options: List<LabeledEntry<T>>, onChange: (T) -> Unit) {
    DropdownChoiceMenu(
        selectedOption = options.firstOrNull { it.value == selected },
        options = options,
        onOptionChange = { onChange(it.value) },
        inputFieldModifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        inputFieldColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
private fun ZoomLevelSlider(label: String, level: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 10.dp)) {
        Text("$label: ${(level * 100).roundToInt() / 100f}×", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = level,
            onValueChange = { onChange((it * 4).roundToInt() / 4f) },
            valueRange = 1f..8f,
        )
    }
}
