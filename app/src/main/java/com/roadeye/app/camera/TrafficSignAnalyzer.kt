package com.roadeye.app.camera

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import kotlin.math.max
import kotlin.math.min

private const val TAG = "RoadEyeML"

// Сколько раз ОДИН И ТОТ ЖЕ инстанс знака (не класс "вообще", а конкретный
// отслеживаемый бокс) должен быть замечен подряд, прежде чем считается
// подтверждённым и озвучивается. Раньше считалось по label во времени —
// теперь по конкретному TrackedSign, что позволяет отличать несколько
// ОДНОВРЕМЕННЫХ знаков одного класса друг от друга (не делят один счётчик).
private const val REQUIRED_CONFIRMATIONS = 2

// Сколько ПОДРЯД обработанных кадров бокс инстанса может отсутствовать в
// новых детекциях, прежде чем трек удаляется. При повторном появлении
// того же знака после удаления — это уже НОВЫЙ трек (заново озвучивается
// после REQUIRED_CONFIRMATIONS), как и требовалось.
private const val MAX_MISSING_FRAMES = 3

// Порог IOU для сопоставления бокса из нового кадра с уже отслеживаемым
// треком. НАМЕРЕННО ниже, чем iouThreshold в YoloDetector.nonMaxSuppression
// (0.45) — там сравниваются боксы ВНУТРИ одного кадра (должны почти
// совпадать), здесь — боксы одного и того же физического знака между
// РАЗНЫМИ кадрами с интервалом ~400мс (MIN_DETECTION_INTERVAL_MS в
// Camera2Controller), за это время знак на видео может заметно вырасти
// (приближение) или сместиться — совпадение площадей ожидаемо ниже.
private const val TRACK_MATCH_IOU_THRESHOLD = 0.15f

/**
 * Только ДЕТЕКЦИЯ: YOLO -> трекинг инстансов по боксам -> озвучивание.
 * Подключается через Camera2Controller.setFrameAnalyzer(...).
 *
 * Выполняется на ОТДЕЛЬНОМ потоке (DetectionThread в Camera2Controller),
 * не на потоке чтения кадров с камеры — так долгий YOLO-инференс не
 * блокирует обработку следующих кадров превью.
 *
 * Превью в окошке ПОЛНОСТЬЮ независимо от этого класса — им занимается
 * Camera2Controller.setPreviewCallback(...) напрямую.
 *
 * ВАЖНО: держит нативные ресурсы (TFLite Interpreter, TTS) — обязательно
 * вызывать release(), когда сервис останавливается.
 *
 * АЛГОРИТМ ТРЕКИНГА ИНСТАНСОВ (заменяет прежний label-based confirmDetection):
 * каждый бокс детекции сопоставляется с уже отслеживаемым треком того же
 * label по IOU (см. TRACK_MATCH_IOU_THRESHOLD). Если совпадение найдено —
 * трек считается тем же физическим знаком (независимо от того, вырос ли
 * бокс от приближения — рост площади сам по себе НЕ сбрасывает трек, пока
 * IOU-перекрытие достаточное). Если знак уже был озвучен по этому треку —
 * повторно НЕ озвучивается, пока трек жив. Трек считается "потерянным" и
 * удаляется, если не находит совпадения MAX_MISSING_FRAMES кадров подряд —
 * следующее появление того же знака создаёт НОВЫЙ трек и озвучивается
 * заново, как отдельное событие.
 *
 * ПОРЯДОК ОБРАБОТКИ В КАДРЕ: детекции сортируются по confidence по
 * убыванию (самая уверенная — первая), сопоставление с треками и
 * последующая озвучка идут в этом порядке — знак с наибольшим % при
 * прочих равных обрабатывается/озвучивается первым.
 */
class TrafficSignAnalyzer(
    context: android.content.Context,
    private val onDetection: (
        label: String,
        phraseRu: String,
        confidence: Float,
        box: android.graphics.RectF,
        frameWidth: Int,
        frameHeight: Int
    ) -> Unit = { _, _, _, _, _, _ -> },
    // Вызывается для КАЖДОЙ сырой детекции (до трекинга/подтверждения) —
    // используется ТОЛЬКО калибровкой фокуса (см. FocusCalibrator.kt),
    // чтобы не ждать REQUIRED_CONFIRMATIONS кадров на каждое тестовое
    // значение фокуса. В обычной работе не задействован (no-op по умолчанию).
    private val onRawDetection: (label: String, confidence: Float) -> Unit = { _, _ -> },
    // Какая модель (Tflite48/Tflite83): ассеты, порог уверенности и набор русских фраз.
    private val variant: ModelVariant = ModelVariant.TFLITE48
) : FrameAnalyzer {

    private val detector = YoloDetector(
        context,
        modelAssetName = variant.modelAsset,
        labelsAssetName = variant.labelsAsset,
        confidenceThreshold = variant.confidenceThreshold
    )
    private val speaker = SignSpeaker(context)

    private class TrackedSign(
        var box: RectF,
        val label: String,
        var seenCount: Int = 1,
        var missingFrames: Int = 0,
        var announced: Boolean = false
    )

    // Доступ только с detectionHandler-потока (см. класс-комментарий
    // Camera2Controller — этот analyzer вызывается последовательно с
    // одного потока), поэтому без дополнительной синхронизации.
    private val tracks = mutableListOf<TrackedSign>()

    // Блокировка нужна, чтобы release() (смена модели в настройках или остановка сервиса)
    // дожидался конца идущего analyze() на detectionThread и не закрыл TFLite/TTS "под ногами".
    // Кадры, уже стоявшие в очереди detectionHandler после release(), просто пропускаются.
    private val lock = Any()

    @Volatile
    private var released = false

    override fun analyze(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int) {
        synchronized(lock) {
            if (released) return
            analyzeLocked(nv21, width, height, rotationDegrees)
        }
    }

    private fun analyzeLocked(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int) {

        val fullFrameRect = Rect(0, 0, width, height)
        val rawBitmap = YuvUtils.cropToBitmap(nv21, width, height, fullFrameRect) ?: return

        val bitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            android.graphics.Bitmap.createBitmap(
                rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
            )
        } else {
            rawBitmap
        }

        val detections = detector.detect(bitmap).sortedByDescending { it.confidence }

        Log.d(TAG, "detect() called, found=${detections.size}")

        val matchedTrackIndices = HashSet<Int>()

        for (detection in detections) {

            Log.d(
                TAG,
                "Raw detection: ${detection.label} " +
                        "(${(detection.confidence * 100).toInt()}%) at ${detection.box}"
            )

            onRawDetection(detection.label, detection.confidence)

            val matchedIndex = findBestMatchingTrack(detection.label, detection.box)

            if (matchedIndex != null && matchedIndex in matchedTrackIndices) {
                // ИСПРАВЛЕННЫЙ БАГ (было: "4 рамки на одном знаке, 4
                // озвучки"): YoloDetector.nonMaxSuppression подавляет
                // дубликаты только при IOU > 0.45 — более "рыхлые"
                // повторные боксы одного и того же физического знака
                // (IOU между TRACK_MATCH_IOU_THRESHOLD и 0.45) переживают
                // NMS как формально разные детекции. Раньше такой бокс
                // не мог сопоставиться с уже занятым в этом кадре треком
                // (индекс был исключён из поиска) и создавал НОВЫЙ трек —
                // отсюда несколько треков на один знак. Теперь: трек уже
                // занят в этом кадре более сильной (по confidence, т.к.
                // список отсортирован по убыванию) детекцией — этот бокс
                // считается дубликатом и просто отбрасывается, НЕ создаёт
                // новый трек и не трогает уже обновлённый.
                Log.d(
                    TAG,
                    "Discarded as in-frame duplicate of track $matchedIndex: ${detection.label}"
                )
                continue
            }

            val track: TrackedSign
            if (matchedIndex != null) {
                track = tracks[matchedIndex]
                track.box = detection.box
                track.missingFrames = 0
                if (track.seenCount < REQUIRED_CONFIRMATIONS) track.seenCount += 1
                matchedTrackIndices.add(matchedIndex)
            } else {
                track = TrackedSign(box = detection.box, label = detection.label)
                tracks.add(track)
                matchedTrackIndices.add(tracks.size - 1)
                Log.d(TAG, "New track created: ${detection.label} at ${detection.box}")
            }

            if (track.announced || track.seenCount < REQUIRED_CONFIRMATIONS) continue

            track.announced = true

            Log.i(
                TAG,
                "CONFIRMED: ${detection.label} (${(detection.confidence * 100).toInt()}%)"
            )

            val phrase = variant.phraseFor(detection.label)
            speaker.speakIfNeeded(key = detection.label, phrase = phrase)
            onDetection(
                detection.label,
                phrase,
                detection.confidence,
                detection.box,
                bitmap.width,
                bitmap.height
            )
        }

        ageOutMissingTracks(matchedTrackIndices)
    }

    /**
     * Ищет среди ВСЕХ [tracks] лучший по IOU трек того же label — БЕЗ
     * исключения уже занятых в этом кадре (см. комментарий в analyze()
     * про исправленный баг дублирования). Возвращает индекс в [tracks]
     * или null, если совпадения выше порога нет.
     */
    private fun findBestMatchingTrack(label: String, box: RectF): Int? {

        var bestIndex: Int? = null
        var bestIou = TRACK_MATCH_IOU_THRESHOLD

        for (i in tracks.indices) {
            val track = tracks[i]
            if (track.label != label) continue

            val overlap = iou(track.box, box)
            if (overlap > bestIou) {
                bestIou = overlap
                bestIndex = i
            }
        }

        return bestIndex
    }

    /**
     * Треки, не сопоставленные ни с одной детекцией в этом кадре, стареют
     * на 1 "пропущенный кадр". По достижении MAX_MISSING_FRAMES — трек
     * удаляется, следующее появление того же знака станет НОВЫМ треком.
     */
    private fun ageOutMissingTracks(matchedTrackIndices: Set<Int>) {

        val iterator = tracks.listIterator()
        var index = 0

        while (iterator.hasNext()) {
            val track = iterator.next()
            if (index !in matchedTrackIndices) {
                track.missingFrames += 1
                if (track.missingFrames >= MAX_MISSING_FRAMES) {
                    Log.d(TAG, "Track dropped after $MAX_MISSING_FRAMES missing frames: ${track.label}")
                    iterator.remove()
                }
            }
            index += 1
        }
    }

    private fun iou(a: RectF, b: RectF): Float {

        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        if (interArea <= 0f) return 0f

        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()

        return interArea / (areaA + areaB - interArea)
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            detector.close()
            speaker.shutdown()
        }
    }
}