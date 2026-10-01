package snd.komelia.ui.reader.image.dualscreen

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import kotlin.math.abs

/**
 * Works out from gravity how a dual-screen device is held, as clockwise quarter turns for the
 * screens' content: 0 held normally, 3 held vertically with the main screen on the right, 1 with
 * it on the left. The activity stays locked to landscape; the reader turns its own content.
 */
internal class OrientationSensor(
    private val activity: Activity,
    private val onTurns: (Int) -> Unit,
) : SensorEventListener {
    private val sensorManager = activity.getSystemService(SensorManager::class.java)
    private var candidate = -1
    private var candidateSince = 0L

    fun start() {
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val ax = event.values[0]
        val ay = event.values[1]
        @Suppress("DEPRECATION")
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display?.rotation
        else activity.windowManager.defaultDisplay.rotation
        // Into the main screen's frame: x toward its right edge, y toward its top edge.
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> -ay to ax
            Surface.ROTATION_180 -> -ax to -ay
            Surface.ROTATION_270 -> ay to -ax
            else -> ax to ay
        }
        val turns = when {
            y > 6.5f && y > abs(x) + 2 -> 0
            // Left edge pointing up: main screen on the right.
            x < -6.5f && -x > abs(y) + 2 -> 3
            x > 6.5f && x > abs(y) + 2 -> 1
            else -> return // lying flat, upside down, or in between: keep what we have
        }
        // Wait until it's been held that way a moment, so a passing tilt doesn't flip the screens.
        if (turns != candidate) {
            candidate = turns
            candidateSince = event.timestamp
        } else if (event.timestamp - candidateSince > 300_000_000L) {
            onTurns(turns)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
