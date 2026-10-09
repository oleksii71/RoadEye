package com.roadeye.app.dashcam

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface

private const val TAG = "RoadEyeDashcam"

/**
 * Фасад видеорегистратора: владеет энкодером, кольцевым буфером,
 * детектором удара и storage-менеджером. Создаётся и уничтожается вместе
 * с RoadEyeOverlayService — см. реальную интеграцию:
 * - Camera2Controller.setVideoSurface() / startCapture() — второй output.
 * - RoadEyeOverlayService.onCreate() — создание/старт ДО openCamera().
 * - RoadEyeOverlayService.createOverlayWindow() — двойной тап (GestureDetector
 *   рядом с handleDrag) и кнопка записи.
 * - RoadEyeOverlayService.onDestroy() — stop() ПОСЛЕ cameraController.release().
 *
 * МОДЕЛЬ КНОПКИ (уточнено пользователем, заменяет более раннюю версию с
 * отдельным "ручным" состоянием):
 * - Кнопка КРАСНАЯ, если идёт ЛЮБАЯ запись — от удара, двойного тапа или
 *   самой кнопки, без разницы источника.
 * - Нажатие, когда НИЧЕГО не пишется — запускает ЦИКЛИЧЕСКУЮ запись в
 *   NORMAL (не FAVORITE) — последовательные сегменты по
 *   DashcamConfig.CONTINUOUS_SEGMENT_MS, автоматически цепляются один за
 *   другим, пока пользователь не остановит.
 * - Нажатие, когда идёт ЛЮБАЯ запись — останавливает ВСЁ активное
 *   (в том числе событийные клипы от удара/тапа, если они в этот момент
 *   тоже активны) — так и было явно запрошено ("остановить любую").
 */
class DashcamController(private val context: Context) {

    private val ringBuffer = DashcamRingBuffer()
    private val storage = DashcamStorageManager(context)
    private var shockDetector: ShockDetector? = null

    // Энкодер ПЕРЕСОЗДАЁТСЯ при смене ориентации записи (альбомная
    // 1280x720 <-> книжная 720x1280, см. handleOrientationSwitch()) —
    // поэтому var, а не val. @Volatile: читается/пишется и с главного
    // потока (start()/stop()), и с GL-потока компоновщика (колбэк
    // switchOutput при успешной смене).
    @Volatile
    private lateinit var encoder: DashcamEncoder

    // GL-компоновщик (оверлей время/координаты/скорость поверх кадра) —
    // создаётся в start(), т.к. ему нужен уже готовый encoder.inputSurface.
    private var compositor: DashcamGlCompositor? = null

    /**
     * Источник телеметрии для оверлея — опрашивается GL-потоком раз в
     * DashcamConfig.OVERLAY_UPDATE_INTERVAL_MS. Установить ДО или ПОСЛЕ
     * start() — оба варианта безопасны (после start() пробрасывается
     * сразу в уже созданный compositor).
     */
    var telemetryProvider: (() -> TelemetrySnapshot)? = null
        set(value) {
            field = value
            compositor?.telemetryProvider = value
        }

    /**
     * Источник текущего угла поворота (0/90/180/270) для GL-компоновщика —
     * тот же Camera2Controller.currentTotalRotationDegrees(), что уже
     * применяется к превью/детекции. Установить ДО или ПОСЛЕ start() —
     * оба варианта безопасны, как и с telemetryProvider.
     */
    var rotationProvider: (() -> Int)? = null
        set(value) {
            field = value
            compositor?.rotationProvider = value
        }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ЕДИНЫЙ список всех сейчас активных записей — независимо от
    // источника (удар/тап/кнопка). Кнопка ориентируется ИМЕННО на него,
    // не на отдельный "ручной" флаг, как было в предыдущей версии.
    private val activeWriters = mutableListOf<DashcamMuxerWriter>()

    // true, пока пользователь явно хочет, чтобы циклическая запись от
    // кнопки продолжалась — используется, чтобы решить, стартовать ли
    // СЛЕДУЮЩИЙ сегмент после завершения текущего, или это была
    // остановка по кнопке (тогда цепочку рвём).
    @Volatile
    private var continuousSessionRequested = false

    /**
     * Вызывается на ГЛАВНОМ потоке при любом изменении ОБЩЕГО состояния
     * "идёт хотя бы одна запись" — используется кнопкой для цвета.
     */
    var onRecordingStateChanged: ((isRecording: Boolean) -> Unit)? = null

    /** Синхронный опрос текущего состояния (помимо колбэка выше). */
    fun isRecording(): Boolean = activeWriters.isNotEmpty()

    private fun notifyRecordingState() {
        val recording = activeWriters.isNotEmpty()
        mainHandler.post { onRecordingStateChanged?.invoke(recording) }
    }

    /**
     * Запускает энкодер и детектор удара. Surface для передачи в
     * Camera2Controller доступен через videoInputSurface() СРАЗУ после
     * этого вызова — до того, как Camera2Controller сконфигурирует
     * CaptureSession с этим Surface как вторым output, кодировать
     * нечего (Surface просто не получает кадров).
     */
    fun start() {
        val generation = ringBuffer.beginNewGeneration()
        val initialEncoder = DashcamEncoder(
            ringBuffer, DashcamConfig.VIDEO_OUTPUT_WIDTH_LANDSCAPE, DashcamConfig.VIDEO_OUTPUT_HEIGHT_LANDSCAPE, generation
        )
        initialEncoder.start()
        encoder = initialEncoder

        val comp = DashcamGlCompositor(
            encoderInputSurface = initialEncoder.inputSurface,
            sourceWidth = DashcamConfig.VIDEO_WIDTH,
            sourceHeight = DashcamConfig.VIDEO_HEIGHT,
            initialOutputWidth = DashcamConfig.VIDEO_OUTPUT_WIDTH_LANDSCAPE,
            initialOutputHeight = DashcamConfig.VIDEO_OUTPUT_HEIGHT_LANDSCAPE
        )
        comp.telemetryProvider = telemetryProvider
        comp.rotationProvider = rotationProvider
        comp.onOutputOrientationWanted = { wantPortrait -> handleOrientationSwitch(wantPortrait) }
        comp.start()
        compositor = comp

        shockDetector = ShockDetector(context) { startEvent("shock") }
        shockDetector?.start()
    }

    /**
     * Вызывается НА GL-ПОТОКЕ компоновщика, когда новая ориентация записи
     * продержалась стабильной DashcamConfig.ORIENTATION_SWITCH_STABLE_MS.
     * Создаёт новый энкодер (размеры камеры не трогает — она всегда отдаёт
     * альбомный буфер), сдвигает поколение кольцевого буфера ДО его
     * старта, чтобы кадры/формат старого энкодера не попали в новый файл,
     * и просит компоновщик переключить вывод на новый Surface. Активные
     * DashcamMuxerWriter сами закрываются на границе поколения (см.
     * DashcamMuxerWriter.onLiveSample) — здесь их трогать не нужно.
     */
    private fun handleOrientationSwitch(wantPortrait: Boolean) {
        val comp = compositor ?: return
        val oldEncoder = encoder

        val newWidth = if (wantPortrait) DashcamConfig.VIDEO_OUTPUT_HEIGHT_LANDSCAPE else DashcamConfig.VIDEO_OUTPUT_WIDTH_LANDSCAPE
        val newHeight = if (wantPortrait) DashcamConfig.VIDEO_OUTPUT_WIDTH_LANDSCAPE else DashcamConfig.VIDEO_OUTPUT_HEIGHT_LANDSCAPE
        val newGeneration = ringBuffer.beginNewGeneration()

        val newEncoder = try {
            DashcamEncoder(ringBuffer, newWidth, newHeight, newGeneration).apply { start() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encoder for orientation switch, keeping current output", e)
            comp.abortPendingSwitch()
            return
        }

        comp.switchOutput(
            newSurface = newEncoder.inputSurface,
            newWidth = newWidth,
            newHeight = newHeight,
            onCommit = {},
            onFinished = { success ->
                if (success) {
                    encoder = newEncoder
                    oldEncoder.stop()
                } else {
                    newEncoder.stop()
                }
            }
        )
    }

    /**
     * Surface для второго output CaptureSession — камера пишет СЮДА (в
     * компоновщик), не напрямую в encoder.inputSurface — см.
     * Camera2Controller.setVideoSurface() (вызывать ДО openCamera()).
     */
    fun videoInputSurface(): Surface =
        compositor?.cameraFacingSurface()
            ?: throw IllegalStateException("DashcamController.start() must be called before videoInputSurface()")

    /** Двойной тап по превью (RoadEyeOverlayService, GestureDetector). */
    fun onDoubleTap() = startEvent("doubleTap")

    /**
     * Кнопка записи (RoadEyeOverlayService). См. модель в комментарии
     * класса — если что-то уже пишется (любой источник), останавливает
     * всё; иначе запускает циклическую NORMAL-запись.
     */
    fun onManualRecordToggle() {
        if (activeWriters.isNotEmpty()) {
            Log.i(TAG, "Button pressed while recording — stopping ALL active recordings (${activeWriters.size})")
            continuousSessionRequested = false
            activeWriters.toList().forEach { it.cancel() }
            return
        }

        Log.i(TAG, "Button pressed — starting continuous (cyclic) recording to normal storage")
        continuousSessionRequested = true
        startContinuousSegment(isFirstSegment = true)
    }

    private fun startContinuousSegment(isFirstSegment: Boolean) {
        if (!continuousSessionRequested) return // остановлено кнопкой, пока планировался следующий сегмент

        startWriter(
            kind = ClipKind.NORMAL,
            source = "manual",
            usePreRoll = isFirstSegment,
            targetDurationUs = DashcamConfig.CONTINUOUS_SEGMENT_MS * 1000
        ) {
            // Цепляем следующий сегмент, ТОЛЬКО если сессия всё ещё
            // запрошена — иначе это была остановка кнопкой, и cancel()
            // уже это учёл (continuousSessionRequested=false выставлен
            // ДО отмены — см. onManualRecordToggle).
            if (continuousSessionRequested) {
                startContinuousSegment(isFirstSegment = false)
            }
        }
    }

    // true, пока идёт СОБЫТИЙНАЯ запись (удар ИЛИ тап) — новые события
    // ЛЮБОГО из этих двух источников игнорируются, пока текущая не
    // завершится. Без этой защиты повторный удар/тап во время уже идущей
    // ~60-секундной записи создавал бы ВТОРОЙ параллельный файл — по
    // ощущениям выглядит как "запись продолжается", хотя технически это
    // разные файлы. Пользователь явно попросил "один раз, без
    // продолжения" — это и обеспечивает.
    @Volatile
    private var eventRecordingActive = false

    private fun startEvent(source: String) {
        if (eventRecordingActive) {
            Log.i(TAG, "[$source] Ignored — an event recording is already in progress (один раз, без продолжения)")
            return
        }
        eventRecordingActive = true

        startWriter(
            kind = ClipKind.FAVORITE,
            source = source,
            usePreRoll = true,
            targetDurationUs = (DashcamConfig.PRE_ROLL_MS + DashcamConfig.POST_EVENT_MS) * 1000
        ) {
            eventRecordingActive = false
        }
    }

    /**
     * Создаёт и СРАЗУ стартует один DashcamMuxerWriter, ведёт учёт в
     * activeWriters (для общего состояния кнопки). [onSegmentFinished]
     * вызывается ПОСЛЕ применения квоты и обновления activeWriters —
     * используется ТОЛЬКО циклической записью для цепочки следующего
     * сегмента, событийным клипам не нужен (пустая лямбда по умолчанию).
     */
    private fun startWriter(
        kind: ClipKind,
        source: String,
        usePreRoll: Boolean,
        targetDurationUs: Long,
        onSegmentFinished: () -> Unit = {}
    ) {
        if (!storage.isAvailable()) {
            Log.e(TAG, "[$source] Storage not available, dropping dashcam event")
            return
        }
        val file = storage.newOutputFile(kind)
        if (file == null) {
            Log.e(TAG, "[$source] Could not create output file, dropping dashcam event")
            return
        }

        Log.i(
            TAG,
            "[$source] Dashcam recording started -> ${file.name} " +
                    "(kind=$kind, target=${targetDurationUs / 1000}ms, preRoll=$usePreRoll)"
        )

        lateinit var writerRef: DashcamMuxerWriter
        writerRef = DashcamMuxerWriter(
            ringBuffer = ringBuffer,
            outputFile = file,
            usePreRoll = usePreRoll,
            targetDurationUs = targetDurationUs,
            onFinished = { savedFile, success ->
                activeWriters.remove(writerRef)
                notifyRecordingState()

                if (success) {
                    storage.enforceQuota(kind)
                    storage.requestMediaScan(savedFile)
                    Log.i(TAG, "[$source] Dashcam clip saved: ${savedFile.absolutePath}")
                } else {
                    savedFile.delete()
                    Log.e(TAG, "[$source] Dashcam clip failed: ${savedFile.absolutePath}")
                }

                onSegmentFinished()
            }
        )

        activeWriters.add(writerRef)
        notifyRecordingState()
        writerRef.start()
    }

    fun stop() {
        continuousSessionRequested = false
        activeWriters.toList().forEach { it.cancel() }
        shockDetector?.stop()
        compositor?.release()
        if (::encoder.isInitialized) encoder.stop()
    }
}