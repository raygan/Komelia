package snd.komelia.ui.reader.image.dualscreen

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Composable
actual fun rememberDualScreenSettings(): DualScreenSettingsStore? {
    val activity = LocalContext.current.findActivity() ?: return null
    remember(activity) { findSecondScreen(activity) } ?: return null
    return remember(activity) { AndroidDualScreenSettings.get(activity) }
}

/** Dual-screen settings in their own SharedPreferences file, shared by the whole app process. */
internal class AndroidDualScreenSettings private constructor(context: Context) : DualScreenSettingsStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("dual_screen", Context.MODE_PRIVATE)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSave: Job? = null

    override val enabled = MutableStateFlow(if (prefs.contains(ENABLED)) prefs.getBoolean(ENABLED, true) else null)
    override val preferences: StateFlow<DualScreenPreferences> get() = _preferences
    private val _preferences = MutableStateFlow(load())
    override val heldVertically = MutableStateFlow(false)

    override fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
        prefs.edit { putBoolean(ENABLED, enabled) }
    }

    override fun update(transform: (DualScreenPreferences) -> DualScreenPreferences) {
        _preferences.update(transform)
        // Zoom levels change every frame while pinching or using the stick, so save once it settles.
        pendingSave?.cancel()
        pendingSave = saveScope.launch {
            delay(500)
            save(_preferences.value)
        }
    }

    private fun load(): DualScreenPreferences {
        val defaults = DualScreenPreferences()
        return DualScreenPreferences(
            mode = enumOrDefault(prefs.getString("mode", null), defaults.mode),
            quickZoomLandscape = prefs.getFloat("quickZoomLandscape", defaults.quickZoomLandscape),
            loupeZoomLandscape = prefs.getFloat("loupeZoomLandscape", defaults.loupeZoomLandscape),
            quickZoomVertical = prefs.getFloat("quickZoomVertical", defaults.quickZoomVertical),
            loupeZoomVertical = prefs.getFloat("loupeZoomVertical", defaults.loupeZoomVertical),
            animationMillis = prefs.getInt("animationMillis", defaults.animationMillis),
            stickSpeed = prefs.getFloat("stickSpeed", defaults.stickSpeed),
            orientation = enumOrDefault(prefs.getString("orientation", null), defaults.orientation),
        )
    }

    private fun save(p: DualScreenPreferences) = prefs.edit {
        putString("mode", p.mode.name)
        putFloat("quickZoomLandscape", p.quickZoomLandscape)
        putFloat("loupeZoomLandscape", p.loupeZoomLandscape)
        putFloat("quickZoomVertical", p.quickZoomVertical)
        putFloat("loupeZoomVertical", p.loupeZoomVertical)
        putInt("animationMillis", p.animationMillis)
        putFloat("stickSpeed", p.stickSpeed)
        putString("orientation", p.orientation.name)
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    companion object {
        private const val ENABLED = "enabled"

        @Volatile
        private var instance: AndroidDualScreenSettings? = null

        fun get(context: Context): AndroidDualScreenSettings =
            instance ?: synchronized(this) {
                instance ?: AndroidDualScreenSettings(context.applicationContext).also { instance = it }
            }
    }
}
