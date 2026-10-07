package snd.komelia.ui.platform

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The app-wide key event stream (LocalKeyEvents) on Android. The activity feeds it every key
 * press it sees, whether or not anything has focus, so screens can react to controller buttons.
 *
 * Some controllers (the AYN Thor's among them) report L2/R2 only as analog trigger axes, so a
 * firm pull is turned into an L2/R2 press here, unless the triggers also send key presses.
 */
class ControllerKeyEvents {
    private val events = MutableSharedFlow<ComposeKeyEvent>(extraBufferCapacity = 32)
    val flow: SharedFlow<ComposeKeyEvent> get() = events

    private var triggersSendKeys = false
    private var leftTriggerDown = false
    private var rightTriggerDown = false

    fun onKeyEvent(event: KeyEvent) {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            triggersSendKeys = true
        }
        // Held buttons repeat; screens want one press.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return
        events.tryEmit(ComposeKeyEvent(event))
    }

    fun onMotionEvent(event: MotionEvent) {
        if (triggersSendKeys || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return
        if (event.action != MotionEvent.ACTION_MOVE) return
        val left = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f
        val right = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f
        if (left != leftTriggerDown) emitButton(KeyEvent.KEYCODE_BUTTON_L2, left)
        if (right != rightTriggerDown) emitButton(KeyEvent.KEYCODE_BUTTON_R2, right)
        leftTriggerDown = left
        rightTriggerDown = right
    }

    private fun emitButton(keyCode: Int, down: Boolean) {
        val now = SystemClock.uptimeMillis()
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        events.tryEmit(ComposeKeyEvent(KeyEvent(now, now, action, keyCode, 0)))
    }
}
