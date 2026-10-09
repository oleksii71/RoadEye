package com.roadeye.app.camera

import android.os.Handler
import android.util.Log

private const val TAG = "RoadEyeFocusCalib"

// Сколько ждать после смены фокуса, прежде чем начать засчитывать
// детекции для этого кандидата — объективу нужно физически
// перестроиться (не мгновенно).
private const val LENS_SETTLE_MS = 400L

// Сколько СОБИРАТЬ детекции для каждого кандидата после settle-паузы —
// с учётом адаптивного троттлинга детекции (skip=2..10 кадров) этого
// достаточно для нескольких попыток детекции даже при skip=10.
private const val SAMPLE_WINDOW_MS = 1200L

/**
 * Ассистированная калибровка фокуса: перебирает список кандидатов
 * FIXED_FOCUS_DISTANCE, для каждого меняет фокус "на лету"
 * (Camera2Controller.setManualFocusDistance — БЕЗ пересоздания камеры),
 * собирает МАКСИМАЛЬНЫЙ confidence среди сырых детекций за окно
 * SAMPLE_WINDOW_MS (через TrafficSignAnalyzer.onRawDetection).
 *
 * СОЗНАТЕЛЬНО использует тот же реальный CONFIDENCE_THRESHOLD, что и в
 * проде (не занижается для теста) — результат отражает реальное поведение
 * детектора на каждом значении фокуса, а не искусственно смягчённое.
 * Следствие: на совсем размытых значениях фокуса кандидат может вообще
 * не дать НИ ОДНОЙ детекции (confidence=0, detectionCount=0) — это
 * ожидаемо и само по себе информативно (явно плохое значение), не баг.
 *
 * НЕ применяет результат автоматически и НЕ трогает CameraConfig.kt —
 * пользователь сам переносит лучшее найденное значение в код вручную
 * (осознанное решение — избегает риска непредсказуемо перезаписать
 * рабочий конфиг автоматическим кодом).
 *
 * ВАЖНОЕ ДОПУЩЕНИЕ: предполагает, что пользователь физически стоит на
 * фиксированной дистанции от ОДНОГО известного знака и держит его в
 * кадре весь тест — калибровка не проверяет, какая детекция "целевая",
 * просто берёт максимум confidence среди ВСЕХ сырых детекций за окно.
 * Если в кадре случайно окажется другой знак/шум — результат исказится.
 */
class FocusCalibrator(
    private val cameraController: Camera2Controller,
    private val mainHandler: Handler
) {

    data class CandidateResult(
        val focusDistance: Float,
        val bestConfidence: Float,
        val detectionCount: Int
    )

    @Volatile
    private var isRunning = false

    // Доступ только с mainHandler-потока — весь sweep управляется оттуда,
    // onRawDetection тоже перепрыгивает на mainHandler перед записью.
    private var currentBestConfidence = 0f
    private var currentDetectionCount = 0

    fun isCalibrating(): Boolean = isRunning

    /** Вызывается из TrafficSignAnalyzer.onRawDetection (детекционный поток). */
    fun onRawDetection(label: String, confidence: Float) {
        if (!isRunning) return
        mainHandler.post {
            if (!isRunning) return@post
            currentDetectionCount += 1
            if (confidence > currentBestConfidence) currentBestConfidence = confidence
        }
    }

    fun start(
        candidates: List<Float>,
        onProgress: (String) -> Unit,
        onComplete: (List<CandidateResult>) -> Unit
    ) {
        if (isRunning) return
        isRunning = true

        val results = mutableListOf<CandidateResult>()
        runCandidate(candidates, 0, results, onProgress, onComplete)
    }

    fun cancel() {
        if (!isRunning) return
        isRunning = false
        cameraController.restoreConfiguredFocusDistance()
        Log.d(TAG, "Calibration cancelled by user")
    }

    private fun runCandidate(
        candidates: List<Float>,
        index: Int,
        results: MutableList<CandidateResult>,
        onProgress: (String) -> Unit,
        onComplete: (List<CandidateResult>) -> Unit
    ) {
        // Проверка isRunning на каждом шаге — так cancel() не обязан
        // отменять уже запланированные postDelayed на ОБЩЕМ mainHandler
        // сервиса (он используется и для других задач — settings panel,
        // список знаков — чистить его целиком было бы опасно).
        if (!isRunning) return

        if (index >= candidates.size) {
            finish(results, onComplete)
            return
        }

        val distance = candidates[index]
        currentBestConfidence = 0f
        currentDetectionCount = 0

        onProgress("Фокус $distance — ${index + 1}/${candidates.size}...")
        Log.d(TAG, "Testing focus distance=$distance (${index + 1}/${candidates.size})")

        cameraController.setManualFocusDistance(distance)

        mainHandler.postDelayed({
            if (!isRunning) return@postDelayed
            mainHandler.postDelayed({
                if (!isRunning) return@postDelayed
                val result = CandidateResult(distance, currentBestConfidence, currentDetectionCount)
                results.add(result)
                Log.d(
                    TAG,
                    "Result focus=$distance bestConfidence=${result.bestConfidence} " +
                            "detections=${result.detectionCount}"
                )
                runCandidate(candidates, index + 1, results, onProgress, onComplete)
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