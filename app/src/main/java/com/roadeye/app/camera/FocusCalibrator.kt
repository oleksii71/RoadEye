package com.roadeye.app.camera

import android.os.Handler
import android.util.Log

private const val TAG = "RoadEyeFocusCalib"

// Сколько ждать после смены фокуса, прежде чем начать засчитывать детекции для этой
// позиции — объективу нужно физически перестроиться (не мгновенно).
private const val LENS_SETTLE_MS = 600L

// Сколько СОБИРАТЬ детекции для каждой позиции после settle-паузы. С учётом адаптивного
// троттлинга детекции (skip=2..10 кадров) этого достаточно для нескольких попыток.
private const val SAMPLE_WINDOW_MS = 2000L

/**
 * Ассистированная проверка фокуса: по очереди ставит три позиции из CameraSettings.FOCUS_OPTIONS
 * (∞ / 25 м / 50 м) "на лету" (Camera2Controller.setManualFocusDistance, без пересоздания
 * камеры) и для каждой собирает максимальный confidence среди сырых детекций за окно
 * SAMPLE_WINDOW_MS (через TrafficSignAnalyzer.onRawDetection).
 *
 * Использует тот же реальный CONFIDENCE_THRESHOLD, что и в проде: на совсем размытой позиции
 * детекций может не быть вообще (confidence=0) — это ожидаемо и само по себе информативно.
 *
 * Результат НЕ применяется автоматически: по окончании возвращается к сохранённому фокусу, а
 * пользователь выбирает позицию сам (см. RoadeyeOverlayService.showFocusCalibrationResults).
 * Выбор сохраняется в CameraSettings (SharedPreferences) и переживает перезапуск.
 *
 * ДОПУЩЕНИЕ: пользователь держит в кадре ОДИН известный знак на фиксированной дистанции весь
 * тест. Калибровка не проверяет, какая детекция "целевая", и берёт максимум confidence среди
 * ВСЕХ сырых детекций за окно — посторонний знак в кадре исказит результат.
 */
class FocusCalibrator(
    private val cameraController: Camera2Controller,
    private val mainHandler: Handler
) {

    data class CandidateResult(
        val optionIndex: Int,
        val label: String,
        val bestConfidence: Float,
        val detectionCount: Int
    )

    @Volatile
    private var isRunning = false

    // Доступ только с mainHandler-потока — весь перебор управляется оттуда,
    // onRawDetection тоже перепрыгивает на mainHandler перед записью.
    private var currentBestConfidence = 0f
    private var currentDetectionCount = 0

    fun isCalibrating(): Boolean = isRunning

    /** Вызывается из TrafficSignAnalyzer.onRawDetection (детекционный поток). */
    fun onRawDetection(@Suppress("UNUSED_PARAMETER") label: String, confidence: Float) {
        if (!isRunning) return
        mainHandler.post {
            if (!isRunning) return@post
            currentDetectionCount += 1
            if (confidence > currentBestConfidence) currentBestConfidence = confidence
        }
    }

    fun start(
        onProgress: (String) -> Unit,
        onComplete: (List<CandidateResult>) -> Unit
    ) {
        if (isRunning) return
        isRunning = true
        runCandidate(0, mutableListOf(), onProgress, onComplete)
    }

    fun cancel() {
        if (!isRunning) return
        isRunning = false
        cameraController.restoreConfiguredFocusDistance()
        Log.d(TAG, "Calibration cancelled by user")
    }

    private fun runCandidate(
        index: Int,
        results: MutableList<CandidateResult>,
        onProgress: (String) -> Unit,
        onComplete: (List<CandidateResult>) -> Unit
    ) {
        // Проверка isRunning на каждом шаге — cancel() не обязан отменять уже запланированные
        // postDelayed на ОБЩЕМ mainHandler сервиса (чистить его целиком опасно).
        if (!isRunning) return

        val options = CameraSettings.FOCUS_OPTIONS
        if (index >= options.size) {
            finish(results, onComplete)
            return
        }

        val option = options[index]
        currentBestConfidence = 0f
        currentDetectionCount = 0

        onProgress("${option.label} — ${index + 1}/${options.size}...")
        Log.d(TAG, "Testing focus ${option.label} diopters=${option.diopters} (${index + 1}/${options.size})")

        cameraController.setManualFocusDistance(option.diopters)

        mainHandler.postDelayed({
            if (!isRunning) return@postDelayed
            // Детекции, накопившиеся за время перестройки объектива, не считаем.
            currentBestConfidence = 0f
            currentDetectionCount = 0
            mainHandler.postDelayed({
                if (!isRunning) return@postDelayed
                val result = CandidateResult(index, option.label, currentBestConfidence, currentDetectionCount)
                results.add(result)
                Log.d(
                    TAG,
                    "Result focus=${option.label} bestConfidence=${result.bestConfidence} " +
                            "detections=${result.detectionCount}"
                )
                runCandidate(index + 1, results, onProgress, onComplete)
            }, SAMPLE_WINDOW_MS)
        }, LENS_SETTLE_MS)
    }

    private fun finish(results: List<CandidateResult>, onComplete: (List<CandidateResult>) -> Unit) {
        isRunning = false
        cameraController.restoreConfiguredFocusDistance()
        Log.d(TAG, "Calibration finished, ${results.size} candidates tested")
        onComplete(results)
    }
}