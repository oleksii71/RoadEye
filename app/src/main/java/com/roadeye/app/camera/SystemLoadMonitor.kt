package com.roadeye.app.camera

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log

private const val TAG = "RoadEyeLoad"

// Как часто пересчитываем термостатус — НЕ на каждый кадр камеры (дорого
// и бессмысленно, термостатус не успевает заметно измениться за 400мс),
// а раз в SAMPLE_INTERVAL_MS на отдельном лёгком потоке. Camera2Controller
// на каждом кадре просто читает @Volatile-поле currentSkip — без
// какого-либо I/O на горячем пути.
private const val SAMPLE_INTERVAL_MS = 1500L

// Диапазон "каждый N-й кадр камеры для детекции". Пороги термостатуса
// ниже (в sample()) — ПЕРВОНАЧАЛЬНАЯ ГИПОТЕЗА, не откалибровано на
// реальном вождении (см. NEXT_STEPS.md MEDIUM #5).
private const val MIN_SKIP_INTERVAL = 2
private const val MAX_SKIP_INTERVAL = 10

/**
 * Источник адаптивного шага троттлинга детекции для Camera2Controller.
 *
 * ТОЛЬКО термостатус (PowerManager.getCurrentThermalStatus(), Android
 * 10+, официальный API, здесь гарантированно доступен — minSdk=36).
 *
 * ИЗНАЧАЛЬНО планировалась ещё загрузка CPU через /proc/stat — УБРАНО:
 * подтверждено логом на реальном устройстве, что чтение /proc/stat не
 * работает на этой прошивке (падает сразу, `available=false`), решение
 * пользователя — не пытаться чинить, оставить только термостатус.
 */
class SystemLoadMonitor(context: Context) {

    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val monitorThread = HandlerThread("SystemLoadMonitorThread").apply { start() }
    private val monitorHandler = Handler(monitorThread.looper)

    @Volatile
    private var currentSkip = MIN_SKIP_INTERVAL

    @Volatile
    private var started = false

    private val sampleRunnable = object : Runnable {
        override fun run() {
            sample()
            monitorHandler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        monitorHandler.post(sampleRunnable)
    }

    fun release() {
        monitorHandler.removeCallbacksAndMessages(null)
        monitorThread.quitSafely()
    }

    /** Горячий путь — вызывается на каждом кадре камеры, просто читает поле. */
    fun currentSkipInterval(): Int = currentSkip

    /** Для экрана настроек. */
    fun currentThermalStatus(): Int = powerManager.currentThermalStatus

    private fun sample() {

        val thermalStatus = powerManager.currentThermalStatus

        currentSkip = when (thermalStatus) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> 2
            PowerManager.THERMAL_STATUS_MODERATE -> 4
            PowerManager.THERMAL_STATUS_SEVERE -> 7
            else -> 10 // CRITICAL / EMERGENCY / SHUTDOWN
        }.coerceIn(MIN_SKIP_INTERVAL, MAX_SKIP_INTERVAL)

        Log.d(TAG, "thermal=$thermalStatus -> skip=$currentSkip")
    }
}