package snd.komelia.ui.reader.image.dualscreen

import android.app.Presentation
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.first
import snd.komelia.settings.model.PageDisplayLayout
import snd.komelia.ui.reader.image.paged.PagedReaderState

@Composable
actual fun rememberDualScreenState(pagedReaderState: PagedReaderState): DualScreenState? {
    val activity = LocalContext.current.findActivity() ?: return null
    remember(activity) { findSecondScreen(activity) } ?: return null
    val settings = remember(activity) { AndroidDualScreenSettings.get(activity) }
    val enabled by settings.enabled.collectAsState()

    // The first time the reader opens on a dual-screen device, switch the mode on and say where to
    // turn it off. Once turned off, it stays off.
    if (enabled == null) {
        LaunchedEffect(Unit) {
            settings.setEnabled(true)
            Toast.makeText(activity, FIRST_RUN_MESSAGE, Toast.LENGTH_LONG).show()
            // Dual-screen reading is built around spreads; Komelia defaults to single pages.
            pagedReaderState.pageSpreads.first { it.isNotEmpty() }
            if (pagedReaderState.layout.value == PageDisplayLayout.SINGLE_PAGE) {
                pagedReaderState.onLayoutChange(PageDisplayLayout.DOUBLE_PAGES)
            }
        }
    }
    if (enabled != true) return null

    val scope = rememberCoroutineScope()
    val state = remember(pagedReaderState) { DualScreenState(pagedReaderState, scope, settings) }
    DisposableEffect(activity, state) {
        // Held vertically, the reader turns its own content on both screens instead of letting
        // Android rotate (and recreate) the activity.
        val previousOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val sensor = OrientationSensor(activity) { turns -> state.setSensedQuarterTurns(turns) }
        // Only while the app is on screen; no need to read the accelerometer in the background.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> sensor.start()
                Lifecycle.Event.ON_STOP -> sensor.stop()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            sensor.stop()
            activity.requestedOrientation = previousOrientation
            state.dispose()
        }
    }
    return state
}

@Composable
actual fun DualScreenHost(pagedReaderState: PagedReaderState, dualScreenState: DualScreenState) {
    // The main screen's half of the fade while turning; the second screen draws its own.
    ScreenCover(dualScreenState)
    val activity = LocalContext.current.findActivity() ?: return
    val display = remember(activity) { findSecondScreen(activity) } ?: return

    // The second screen shows the navigator only while the app is on screen: leaving with the home
    // button (or switching apps) keeps the reader open in the background, but gives the second
    // screen back to the system.
    DisposableEffect(activity, display, dualScreenState) {
        var presentation: Presentation? = null
        fun show() {
            if (presentation != null) return
            presentation = NavigatorPresentation(activity, display) {
                NavigatorContent(pagedReaderState, dualScreenState)
            }.also { it.show() }
            DualScreenControllerInput.target = dualScreenState
        }
        fun hide() {
            if (DualScreenControllerInput.target === dualScreenState) DualScreenControllerInput.target = null
            presentation?.dismiss()
            presentation = null
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> show()
                Lifecycle.Event.ON_STOP -> hide()
                else -> Unit
            }
        }
        // Adding the observer replays the events up to the current state, so this also shows the
        // navigator right away when the app is already on screen.
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            hide()
        }
    }
}

// Android cuts toasts off after two lines, so keep this short.
private const val FIRST_RUN_MESSAGE = "Dual-screen reading on. Turn off in reader settings → Dual screen"

/** The AYN Thor's bottom screen shows up as a presentation display separate from the activity's own. */
internal fun findSecondScreen(activity: ComponentActivity): Display? {
    val displayManager = activity.getSystemService(DisplayManager::class.java) ?: return null
    @Suppress("DEPRECATION")
    val ownDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
    else activity.windowManager.defaultDisplay
    return displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        .firstOrNull { it.displayId != ownDisplay?.displayId }
}

internal fun Context.findActivity(): ComponentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    return null
}

/**
 * A window on the second screen hosting Compose content. Compose needs lifecycle, saved-state and
 * view-model owners on the window's view tree; a Presentation has none of its own, so it borrows
 * the activity's.
 */
private class NavigatorPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val content: @Composable () -> Unit,
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val window = requireNotNull(window)
        window.decorView.setViewTreeLifecycleOwner(activity)
        window.decorView.setViewTreeViewModelStoreOwner(activity)
        window.decorView.setViewTreeSavedStateRegistryOwner(activity)
        setContentView(ComposeView(context).apply { setContent(content) })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    // Controller input goes to whichever screen was touched last, so this window forwards it too.
    override fun dispatchKeyEvent(event: KeyEvent) =
        DualScreenControllerInput.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent) =
        DualScreenControllerInput.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)
}
