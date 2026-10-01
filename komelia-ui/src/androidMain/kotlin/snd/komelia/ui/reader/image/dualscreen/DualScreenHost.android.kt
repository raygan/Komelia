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
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import snd.komelia.ui.reader.image.paged.PagedReaderState

@Composable
actual fun rememberDualScreenState(pagedReaderState: PagedReaderState): DualScreenState? {
    val activity = LocalContext.current.findActivity() ?: return null
    remember(activity) { findSecondScreen(activity) } ?: return null
    val scope = rememberCoroutineScope()
    val state = remember(pagedReaderState) { DualScreenState(pagedReaderState, scope) }
    DisposableEffect(activity, state) {
        // Held vertically, the reader turns its own content on both screens instead of letting
        // Android rotate (and recreate) the activity.
        val previousOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val sensor = OrientationSensor(activity) { turns -> state.setQuarterTurns(turns) }
        sensor.start()
        onDispose {
            sensor.stop()
            activity.requestedOrientation = previousOrientation
            state.dispose()
        }
    }
    return state
}

@Composable
actual fun DualScreenHost(pagedReaderState: PagedReaderState, dualScreenState: DualScreenState) {
    val activity = LocalContext.current.findActivity() ?: return
    val display = remember(activity) { findSecondScreen(activity) } ?: return

    DisposableEffect(activity, display, dualScreenState) {
        val presentation = NavigatorPresentation(activity, display) {
            NavigatorContent(pagedReaderState, dualScreenState)
        }
        presentation.show()
        DualScreenControllerInput.target = dualScreenState
        onDispose {
            if (DualScreenControllerInput.target === dualScreenState) DualScreenControllerInput.target = null
            presentation.dismiss()
        }
    }
}

/** The AYN Thor's bottom screen shows up as a presentation display separate from the activity's own. */
private fun findSecondScreen(activity: ComponentActivity): Display? {
    val displayManager = activity.getSystemService(DisplayManager::class.java) ?: return null
    @Suppress("DEPRECATION")
    val ownDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
    else activity.windowManager.defaultDisplay
    return displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        .firstOrNull { it.displayId != ownDisplay?.displayId }
}

private fun Context.findActivity(): ComponentActivity? {
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
