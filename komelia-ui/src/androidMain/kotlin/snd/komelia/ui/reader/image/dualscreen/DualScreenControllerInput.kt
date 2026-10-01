package snd.komelia.ui.reader.image.dualscreen

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.sign

/**
 * Game controller input for dual-screen reading. Android delivers controller events to whichever
 * screen was touched last, so both the activity and the second-screen window forward them here.
 * Only active while a dual-screen reader is open; otherwise every event passes through untouched.
 *
 * Handles the AYN Thor's quirks: its D-pad reports as a hat axis, its right stick as Z/RZ, and its
 * triggers as BRAKE/GAS axes that may also send key presses.
 */
object DualScreenControllerInput {
    @Volatile
    var target: DualScreenState? = null

    private var hatX = 0
    private var leftTriggerDown = false
    private var rightTriggerDown = false
    private var triggersSendKeys = false
    private var lastTriggerAxisTurn = 0L

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val state = target ?: return false
        val action: (() -> Unit) = when (event.keyCode) {
            // Shoulder buttons point left and right, so for right-to-left reading they swap roles.
            KeyEvent.KEYCODE_BUTTON_R1 -> state::stepRight
            KeyEvent.KEYCODE_BUTTON_L1 -> state::stepLeft
            KeyEvent.KEYCODE_BUTTON_R2 -> state::turnRight
            KeyEvent.KEYCODE_BUTTON_L2 -> state::turnLeft
            KeyEvent.KEYCODE_DPAD_LEFT -> dpadAction(state, -1f, 0f) ?: return false
            KeyEvent.KEYCODE_DPAD_RIGHT -> dpadAction(state, 1f, 0f) ?: return false
            KeyEvent.KEYCODE_DPAD_UP -> dpadAction(state, 0f, -1f) ?: return false
            KeyEvent.KEYCODE_DPAD_DOWN -> dpadAction(state, 0f, 1f) ?: return false
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> { { state.toggleMode() } }
            else -> return false
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            triggersSendKeys = true
            // The trigger's axis may already have turned the page for this same pull.
            if (event.eventTime - lastTriggerAxisTurn < 150) return true
        }
        // Consume key-up too, so the reader's own key handling doesn't act on it as well.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) action()
        return true
    }

    /**
     * The D-pad turns pages left and right on screen, so which button does it depends on how the
     * device is held: held vertically with the main screen on the right, up turns right.
     */
    private fun dpadAction(state: DualScreenState, x: Float, y: Float): (() -> Unit)? {
        val direction = state.toScreenDirection(x, y)
        return when {
            direction.x < -0.5f -> state::turnLeft
            direction.x > 0.5f -> state::turnRight
            else -> null
        }
    }

    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val state = target ?: return false
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE) return false

        fun axis(axis: Int): Float {
            val flat = event.device?.getMotionRange(axis, event.source)?.flat ?: 0f
            val value = event.getAxisValue(axis)
            return if (abs(value) > maxOf(flat, 0.15f)) value else 0f
        }
        // Sticks and D-pad rotate with the device, so turn them to match the screens.
        val left = state.toScreenDirection(axis(MotionEvent.AXIS_X), axis(MotionEvent.AXIS_Y))
        val right = state.toScreenDirection(axis(MotionEvent.AXIS_Z), axis(MotionEvent.AXIS_RZ))
        state.setSticks(left.x, left.y, right.y)

        // Handling joystick events ourselves stops Android turning the hat into D-pad key presses.
        val hatDirection = state.toScreenDirection(
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )
        val hat = hatDirection.x.let { if (abs(it) > 0.5f) it.sign.toInt() else 0 }
        if (hat != hatX) {
            if (hat < 0) state.turnLeft()
            if (hat > 0) state.turnRight()
        }
        hatX = hat

        if (!triggersSendKeys) {
            val left = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f
            val right = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f
            if (left && !leftTriggerDown) {
                state.turnLeft()
                lastTriggerAxisTurn = event.eventTime
            }
            if (right && !rightTriggerDown) {
                state.turnRight()
                lastTriggerAxisTurn = event.eventTime
            }
            leftTriggerDown = left
            rightTriggerDown = right
        }
        return true
    }
}
