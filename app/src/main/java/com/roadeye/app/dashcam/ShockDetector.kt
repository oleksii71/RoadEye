package com.roadeye.app.dashcam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

private const val TAG = "RoadEyeDashcam"

/**
 * Детектор удара по TYPE_ACCELEROMETER (сырой, включает гравитацию —
 * НЕ TYPE_LINEAR_ACCELERATION). В покое magnitude ≈ 1g, поэтому порог
 * DashcamConfig.SHOCK_THRESHOLD_G (сейчас 2.5) означает "суммарное
 * ускорение минимум в 2.5 раза больше гравитации", а не "1.5g сверх
 * гравитации в покое". Порог — HYPOTHESIS, требует эмпирической
 * калибровки реальным ударом по планшету (аналогично
 * CameraConfig.FIXED_FOCUS_DISTANCE) — если окажется нечувствительно/
 * сверхчувствительно, менять только константу в DashcamConfig, логику
 * не трогать.
 */
class ShockDetector(
    context: Context,
    private val onShock: () -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastTriggerAtMs = 0L

    fun start() {
        if (accelerometer == null) {
            Log.w(TAG, "No accelerometer on this device, shock detection disabled")
            return
        }
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitudeG = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        if (magnitudeG < DashcamConfig.SHOCK_THRESHOLD_G) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerAtMs < DashcamConfig.SHOCK_DEBOUNCE_MS) return
        lastTriggerAtMs = now

        Log.i(TAG, "Shock detected: magnitude=${"%.2f".format(magnitudeG)}g")
        onShock()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}