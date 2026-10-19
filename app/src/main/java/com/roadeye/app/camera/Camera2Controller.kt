package com.roadeye.app.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "RoadEyeCamera"

// Сколько ждать видео-Surface от компоновщика записи после открытия камеры, прежде чем запустить
// превью БЕЗ записи (страховка от зависшей инициализации дашкама).
private const val VIDEO_SURFACE_WAIT_MS = 3000L

// Целевое разрешение единственного потока с камеры — используется и для
// ML-анализа, и как источник кадров для плавающего окошка превью.
//
// Подтверждено диагностикой камеры (Camera ID 0, задняя камера,
// Hardware Level 3): 640x480 есть в нативном списке YUV_420_888 sizes.
private const val ANALYSIS_TARGET_WIDTH = 640
private const val ANALYSIS_TARGET_HEIGHT = 480

// Троттлинг лёгкого превью — независим от троттлинга тяжёлой детекции.
// ~24 fps — частота, при которой видео на глаз воспринимается плавным.
private const val MIN_PREVIEW_INTERVAL_MS = 42L

// Троттлинг тяжёлой YOLO-детекции — раньше был фиксированным по времени
// (MIN_DETECTION_INTERVAL_MS = 400мс), теперь АДАПТИВНЫЙ по количеству
// кадров камеры, управляется SystemLoadMonitor (см. этот файл): при
// низкой нагрузке/температуре — каждый 2-й кадр, при росте нагрузки —
// реже, вплоть до каждого 10-го (экономия заряда/тепла). Конкретные
// пороги — см. SystemLoadMonitor.kt.

// Ниже этой скорости детекция ОТКЛЮЧАЕТСЯ (экономия заряда — предполагается
// стоянка/парковка, знаки распознавать не нужно). FAIL-OPEN: см.
// SpeedProvider.hasReliableSpeed() — если скорость неизвестна (нет
// разрешения, нет fix'а, fix устарел), gate считается ОТКРЫТЫМ, детекция
// работает как обычно. Снимается флагом speedGateOverrideEnabled — см.
// setSpeedGateOverride() (для будущей кнопки настроек).
private const val SPEED_THRESHOLD_KMH = 5f

class Camera2Controller(
    private val context: Context
) {

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // Сохраняется, чтобы менять параметры (в частности, фокус — см.
    // setManualFocusDistance) без пересоздания сессии. Доступ только с
    // captureHandler-потока — тот же поток, что вызывает startCapture().
    private var activeRequestBuilder: CaptureRequest.Builder? = null

    // Единственный output-стрим для ML-анализа/превью.
    private var imageReader: ImageReader? = null

    // DASHCAM (Вариант C): ВТОРОЙ output-стрим, независимый от анализа —
    // Surface от MediaCodec (DashcamEncoder.inputSurface), устанавливается
    // СНАРУЖИ через setVideoSurface() ДО openCamera(). Camera2Controller
    // НЕ владеет его жизненным циклом (не создаёт, не освобождает) —
    // только добавляет вторым output в CaptureSession, если он задан.
    private var videoSurface: Surface? = null

    // Ожидание видео-Surface: позволяет открывать камеру ПАРАЛЛЕЛЬНО с настройкой EGL у компоновщика
    // записи. Сессия камеры создаётся, когда готовы оба: камера открыта И видео-Surface решён.
    // По умолчанию (expectVideoSurface() не вызывали) ждать не нужно — поведение как раньше.
    private val videoGateLock = Any()
    private var videoSurfaceDecided = true
    private var captureWaitingForVideo = false

    @Volatile
    private var frameAnalyzer: FrameAnalyzer? = null

    // Превью — НЕЗАВИСИМО от frameAnalyzer.
    @Volatile
    private var previewCallback: ((Bitmap) -> Unit)? = null

    private var nv21PreviewBuffer: ByteArray? = null
    private var lastPreviewAtMs = 0L

    // Счётчик кадров камеры для адаптивного frame-skip троттлинга детекции
    // (см. loadMonitor.currentSkipInterval()). Инкрементируется в
    // ImageReader-колбэке на captureThread — доступ только оттуда,
    // синхронизация не нужна (единственный писатель/читатель).
    private var detectionFrameCounter = 0L

    private val loadMonitor = SystemLoadMonitor(context)
    private val speedProvider = SpeedProvider(context)

    // Снимает ограничение по скорости (для будущей кнопки настроек —
    // "шестерёнка"). false по умолчанию — ограничение активно.
    @Volatile
    private var speedGateOverrideEnabled = false

    // Backpressure для детекции: если детекция ещё обрабатывает
    // предыдущий кадр НА ОТДЕЛЬНОМ ПОТОКЕ, новые кадры для детекции
    // пропускаются. Превью при этом продолжает обновляться независимо —
    // не ждёт освобождения detectionHandler.
    private val analyzerBusy = AtomicBoolean(false)

    // Ограничение "скважности" детекции: после каждого анализа выдерживается пауза не меньше
    // detectionIdleFactor * (длительность анализа) и не меньше minDetectionGapMs. Нужно, чтобы тяжёлая
    // модель не занимала CPU/GPU непрерывно и не отнимала ресурсы у превью и записи видео.
    // idleFactor = 0 и minGap = 0 -> прежнее поведение (только skipInterval + analyzerBusy).
    @Volatile private var detectionIdleFactor = 0f
    @Volatile private var minDetectionGapMs = 0L
    @Volatile private var nextDetectionAllowedAtMs = 0L
    @Volatile private var lastDetectionDurationMs = 0L

    // Поток чтения кадров с камеры (ImageReader-колбэки) + быстрая
    // конвертация превью — должен оставаться лёгким и быстрым.
    private val captureThread = HandlerThread("CameraCaptureThread").apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    // ОТДЕЛЬНЫЙ поток под тяжёлый YOLO-инференс — принципиально другой
    // поток, чем captureThread. Раньше детекция и превью делили один
    // поток: пока шёл инференс, превью буквально не могло обновляться,
    // отсюда дёрганость картинки именно после загрузки детектора.
    private val detectionThread = HandlerThread("DetectionThread").apply { start() }
    private val detectionHandler = Handler(detectionThread.looper)

    // Превью: тяжёлая JPEG-конвертация (десятки мс) вынесена с captureThread. Один capture-запрос
    // питает И ImageReader анализа, И видео-Surface записи, поэтому медленный колбэк ImageReader
    // тормозит камеру целиком — дёргаются и превью, и запись. Колбэк должен быстро возвращать кадр.
    private val previewThread = HandlerThread("PreviewThread").apply { start() }
    private val previewHandler = Handler(previewThread.looper)
    private val previewBusy = AtomicBoolean(false)

    private val cameraLock = Semaphore(1)

    // Освобождается в onClosed() — closeCamera() дожидается этого
    // сигнала, прежде чем считать закрытие завершённым.
    private val cameraCloseSignal = Semaphore(0)

    @Volatile
    private var generation = 0

    // Физическая ориентация планшета — через акселерометр
    // (OrientationEventListener), не Display.rotation.
    @Volatile
    private var deviceOrientationDegrees = 0

    // Фиксированная ориентация сенсора камеры — запрашивается один раз,
    // не меняется. Для задней камеры обычно 90°. БЕЗ комбинирования
    // этого значения с deviceOrientationDegrees поворот получается
    // смещён ровно на эту константу — именно так и было до этого фикса.
    private val cameraSensorOrientation: Int by lazy {
        try {
            cameraManager
                .getCameraCharacteristics(CameraConfig.CAMERA_ID)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Итоговый угол поворота кадра — комбинация фиксированной ориентации
     * сенсора и текущей физической ориентации планшета. Стандартная
     * формула Camera2 API.
     *
     * ТОЧЕЧНАЯ ПОПРАВКА (не часть стандартной формулы): подтверждено
     * тестом на устройстве, что базовая формула верна для книжных
     * положений планшета (deviceOrientationDegrees == 0 или 180), но
     * даёт систематическую ошибку ровно 180° в обоих альбомных
     * положениях (== 90 или 270). Рабочая гипотеза (НЕ подтверждена
     * логированием SENSOR_ORIENTATION) — natural orientation планшета
     * landscape, поэтому SENSOR_ORIENTATION и "сырой" акселерометр
     * используют разные системы отсчёта именно в этих двух положениях.
     * Если после этого фикса поворот снова окажется неверным —
     * НЕ считать эту поправку доказанной, залогировать реальное
     * SENSOR_ORIENTATION (см. NEXT_STEPS.md / CONTEXT_FOR_NEW_CHAT.md).
     */
    private val totalRotationDegrees: Int
        get() {
            val base = (cameraSensorOrientation - deviceOrientationDegrees + 360) % 360
            val isLandscapeDevicePosition =
                deviceOrientationDegrees == 90 || deviceOrientationDegrees == 270
            return if (isLandscapeDevicePosition) (base + 180) % 360 else base
        }

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return

            deviceOrientationDegrees = when {
                orientation >= 315 || orientation < 45 -> 0
                orientation < 135 -> 90
                orientation < 225 -> 180
                else -> 270
            }
        }
    }

    /**
     * Подключает превью-колбэк — вызывается на КАЖДОМ (троттленом до
     * ~24fps) кадре, независимо от того, подключён ли ML-анализатор.
     */
    fun setPreviewCallback(callback: ((Bitmap) -> Unit)?) {
        previewCallback = callback
    }

    /**
     * Подключает ML-анализатор кадров — независимо от превью, выполняется
     * на отдельном detectionHandler-потоке.
     */
    fun setFrameAnalyzer(analyzer: FrameAnalyzer?) {
        frameAnalyzer = analyzer
        Log.d(TAG, "Frame analyzer ${if (analyzer != null) "attached" else "detached"}")
    }

    /**
     * DASHCAM (Вариант C): регистрирует второй output-Surface для
     * видеорегистратора (обычно — DashcamController.videoInputSurface()).
     *
     * ОБЯЗАТЕЛЬНО вызывать ДО openCamera() — CaptureSession строится один
     * раз в startCapture() со списком output, зафиксированным на момент
     * открытия камеры. Вызов ПОСЛЕ того, как камера уже открыта и сессия
     * создана, ни на что не повлияет без явного пересоздания сессии (не
     * реализовано — сейчас не требуется, т.к. дашкам стартует один раз
     * вместе с сервисом, а не переподключается на лету, как FrameAnalyzer).
     *
     * Camera2Controller НЕ владеет жизненным циклом этого Surface — не
     * создаёт и не освобождает его. Освобождать (DashcamEncoder.stop())
     * можно только ПОСЛЕ closeCamera()/release() этого класса — если
     * Surface освобождается, пока сессия камеры ещё активна и пишет в
     * него, это может уронить камеру на уровне фреймворка.
     */
    fun setVideoSurface(surface: Surface?) {
        val startNow = synchronized(videoGateLock) {
            videoSurface = surface
            videoSurfaceDecided = true
            val waiting = captureWaitingForVideo
            captureWaitingForVideo = false
            waiting
        }
        // Камера уже открыта и ждала Surface — создаём сессию (на потоке камеры, как и раньше).
        if (startNow) captureHandler.post { startCapture() }
    }

    /**
     * Вызвать ДО openCamera(), если видео-Surface будет передан позже через setVideoSurface()
     * (в том числе null, если записи не будет). Тогда сессия не создаётся, пока Surface не решён.
     */
    fun expectVideoSurface() {
        synchronized(videoGateLock) { videoSurfaceDecided = false }
    }

    fun openCamera() {

        if (cameraDevice != null) {
            Log.d(TAG, "Camera already opened")
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Camera permission denied")
            return
        }

        if (!cameraLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Timeout waiting for camera lock, aborting open()")
            return
        }

        val requestGen = ++generation

        Log.d(TAG, "openCamera() gen=$requestGen")

        // DASHCAM: диагностика уровня поддержки железа — влияет на то,
        // гарантированно ли устройство поддерживает одновременно
        // ImageReader(640x480 YUV) + video Surface. Логируем один раз,
        // до создания сессии — если комбинация не сработает, эта строка
        // в логе будет первым, что нужно проверить.
        try {
            val level = cameraManager
                .getCameraCharacteristics(CameraConfig.CAMERA_ID)
                .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            Log.i(TAG, "Camera INFO_SUPPORTED_HARDWARE_LEVEL=$level (videoSurfaceAttached=${videoSurface != null})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read hardware level", e)
        }

        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        } else {
            Log.e(TAG, "OrientationEventListener: sensor not available on this device")
        }

        loadMonitor.start()
        speedProvider.start()

        setupImageReaderAndSession(requestGen)
    }

    private fun setupImageReaderAndSession(requestGen: Int) {

        if (imageReader == null) {

            Log.d(TAG, "Analysis stream size = ${ANALYSIS_TARGET_WIDTH}x${ANALYSIS_TARGET_HEIGHT}")

            imageReader = ImageReader.newInstance(
                ANALYSIS_TARGET_WIDTH,
                ANALYSIS_TARGET_HEIGHT,
                android.graphics.ImageFormat.YUV_420_888,
                /* maxImages = */ 3
            ).apply {
                setOnImageAvailableListener(
                    { reader ->
                        val image = try {
                            reader.acquireLatestImage()
                        } catch (e: Exception) {
                            Log.e(TAG, "acquireLatestImage failed", e)
                            null
                        } ?: return@setOnImageAvailableListener

                        val width = image.width
                        val height = image.height
                        val rotation = totalRotationDegrees

                        try {
                            // Превью — здесь же, на captureThread, быстро.
                            maybeUpdatePreview(image, width, height, rotation)

                            // Детекция — решаем, нужна ли она СЕЙЧАС
                            // (троттлинг + backpressure), и если да —
                            // берём СВОЮ копию NV21 (не переиспользуемую,
                            // не связанную с превью) и уходим на
                            // ОТДЕЛЬНЫЙ detectionHandler-поток. captureThread
                            // после этого сразу свободен для следующего
                            // кадра превью, не дожидаясь конца инференса.
                            val analyzer = frameAnalyzer
                            detectionFrameCounter += 1
                            val skipInterval = loadMonitor.currentSkipInterval()
                            val detectionDue = detectionFrameCounter % skipInterval == 0L &&
                                    SystemClock.elapsedRealtime() >= nextDetectionAllowedAtMs

                            val speedGateOpen = speedGateOverrideEnabled ||
                                    !speedProvider.hasReliableSpeed() ||
                                    speedProvider.currentSpeedKmh() >= SPEED_THRESHOLD_KMH

                            if (analyzer != null && detectionDue && speedGateOpen &&
                                analyzerBusy.compareAndSet(false, true)
                            ) {
                                val nv21ForDetection = YuvUtils.imageToNv21(image, null)

                                detectionHandler.post {
                                    val startMs = SystemClock.elapsedRealtime()
                                    try {
                                        analyzer.analyze(nv21ForDetection, width, height, rotation)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "FrameAnalyzer threw an exception", e)
                                    } finally {
                                        val endMs = SystemClock.elapsedRealtime()
                                        val durationMs = endMs - startMs
                                        lastDetectionDurationMs = durationMs
                                        nextDetectionAllowedAtMs =
                                            endMs + maxOf(minDetectionGapMs, (durationMs * detectionIdleFactor).toLong())
                                        analyzerBusy.set(false)
                                    }
                                }
                            }
                        } finally {
                            image.close()
                        }
                    },
                    captureHandler
                )
            }
        }

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Camera permission revoked right before openCamera() call")
            cameraLock.release()
            return
        }

        try {
            cameraManager.openCamera(
                CameraConfig.CAMERA_ID,
                object : CameraDevice.StateCallback() {

                    override fun onOpened(camera: CameraDevice) {
                        cameraLock.release()

                        if (requestGen != generation) {
                            Log.d(TAG, "Stale onOpened gen=$requestGen, closing")
                            camera.close()
                            return
                        }

                        cameraDevice = camera
                        Log.d(TAG, "CameraDevice OPENED gen=$requestGen")

                        val startNow = synchronized(videoGateLock) {
                            if (videoSurfaceDecided) {
                                true
                            } else {
                                captureWaitingForVideo = true
                                false
                            }
                        }

                        if (startNow) {
                            startCapture()
                        } else {
                            Log.d(TAG, "Camera opened, waiting for video surface before creating session")
                            captureHandler.postDelayed({
                                val timedOut = synchronized(videoGateLock) {
                                    if (captureWaitingForVideo) {
                                        captureWaitingForVideo = false
                                        videoSurfaceDecided = true
                                        true
                                    } else {
                                        false
                                    }
                                }
                                if (timedOut) {
                                    Log.e(TAG, "Video surface not provided in $VIDEO_SURFACE_WAIT_MS ms — starting preview WITHOUT recording")
                                    startCapture()
                                }
                            }, VIDEO_SURFACE_WAIT_MS)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        cameraLock.release()
                        Log.d(TAG, "CameraDevice disconnected")
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        cameraLock.release()
                        Log.e(TAG, "Camera error $error")
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onClosed(camera: CameraDevice) {
                        Log.d(TAG, "CameraDevice fully closed")
                        cameraCloseSignal.release()
                    }
                },
                captureHandler
            )
        } catch (e: SecurityException) {
            cameraLock.release()
            Log.e(TAG, "openCamera() failed — permission denied", e)
        } catch (e: Exception) {
            cameraLock.release()
            Log.e(TAG, "openCamera() failed", e)
        }
    }

    /**
     * Превью ~24fps. На captureThread делается ТОЛЬКО быстрая копия YUV (NV21) — кадр камеры затем
     * сразу закрывается вызывающим кодом. JPEG-конвертация, поворот и callback идут на previewThread.
     * Если предыдущий кадр превью ещё обрабатывается, текущий пропускается.
     */
    private fun maybeUpdatePreview(
        image: android.media.Image,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ) {

        val callback = previewCallback ?: return

        val now = System.currentTimeMillis()
        if (now - lastPreviewAtMs < MIN_PREVIEW_INTERVAL_MS) return
        if (!previewBusy.compareAndSet(false, true)) return
        lastPreviewAtMs = now

        val nv21 = try {
            YuvUtils.imageToNv21(image, nv21PreviewBuffer)
        } catch (e: Exception) {
            previewBusy.set(false)
            Log.e(TAG, "maybeUpdatePreview: NV21 copy failed", e)
            return
        }
        // Буфер не перезапишется, пока previewBusy == true (то есть пока идёт обработка этого кадра).
        nv21PreviewBuffer = nv21

        previewHandler.post {
            try {
                val fullFrameRect = Rect(0, 0, width, height)
                val rawBitmap = YuvUtils.cropToBitmap(nv21, width, height, fullFrameRect) ?: return@post

                val bitmap = if (rotationDegrees != 0) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    Bitmap.createBitmap(
                        rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                    )
                } else {
                    rawBitmap
                }

                callback(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "maybeUpdatePreview failed", e)
            } finally {
                previewBusy.set(false)
            }
        }
    }

    private fun startCapture() {

        val camera = cameraDevice ?: return
        val analysisSurface = imageReader?.surface ?: return
        // DASHCAM: локальная копия — videoSurface volatile-не помечен
        // (пишется один раз до openCamera(), после этого не меняется),
        // но берём в локальную val на случай будущих гонок.
        val video = videoSurface

        val requestBuilder =
            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

        requestBuilder.addTarget(analysisSurface)
        video?.let { requestBuilder.addTarget(it) }

        if (CameraConfig.AUTO_FOCUS) {
            requestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        } else {
            requestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            requestBuilder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                CameraConfig.FIXED_FOCUS_DISTANCE
            )
        }

        requestBuilder.set(
            CaptureRequest.CONTROL_AE_MODE,
            CaptureRequest.CONTROL_AE_MODE_ON
        )

        requestBuilder.set(
            CaptureRequest.CONTROL_AWB_MODE,
            CaptureRequest.CONTROL_AWB_MODE_AUTO
        )

        try {
            // DASHCAM: второй OutputConfiguration добавляется, только
            // если videoSurface реально установлен — без него поведение
            // ПОЛНОСТЬЮ идентично прежнему single-stream варианту.
            val outputConfigs = listOfNotNull(
                OutputConfiguration(analysisSurface),
                video?.let { OutputConfiguration(it) }
            )
            val handlerExecutor = Executor { runnable -> captureHandler.post(runnable) }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                handlerExecutor,
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(session: CameraCaptureSession) {

                        captureSession = session
                        activeRequestBuilder = requestBuilder

                        try {
                            session.setRepeatingRequest(
                                requestBuilder.build(),
                                null,
                                captureHandler
                            )
                            Log.d(TAG, "Capture started, streams=${outputConfigs.size} (video=${video != null})")
                        } catch (e: Exception) {
                            Log.e(TAG, "setRepeatingRequest failed", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        // DASHCAM: если это происходит именно при наличии
                        // video-Surface — вероятная причина: устройство не
                        // поддерживает эту комбинацию потоков несмотря на
                        // Hardware Level 3 (см. лог INFO_SUPPORTED_HARDWARE_LEVEL
                        // в openCamera()). Первое, что стоит попробовать —
                        // понизить DashcamConfig.VIDEO_WIDTH/HEIGHT.
                        Log.e(
                            TAG,
                            "Capture session configure failed" +
                                    if (video != null) " (video surface attached — possible unsupported stream combination)" else ""
                        )
                    }
                }
            )

            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession failed", e)
        }
    }

    fun closeCamera() {
        synchronized(videoGateLock) { captureWaitingForVideo = false }

        try {
            cameraLock.acquire()

            orientationListener.disable()

            captureSession?.let {
                try {
                    it.stopRepeating()
                } catch (e: Exception) {
                    Log.e(TAG, "stopRepeating failed", e)
                }
                it.close()
            }
            captureSession = null
            activeRequestBuilder = null

            val hadDevice = cameraDevice != null

            if (hadDevice) {
                cameraCloseSignal.drainPermits()
            }

            cameraDevice?.close()
            cameraDevice = null

            val readerToClose = imageReader
            imageReader = null

            readerToClose?.let { reader ->
                captureHandler.post {
                    try {
                        reader.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "ImageReader close failed", e)
                    }
                }
            }

            Log.d(TAG, "Camera close requested, waiting for onClosed callback...")

            if (hadDevice) {
                val closed = cameraCloseSignal.tryAcquire(1500, TimeUnit.MILLISECONDS)
                if (!closed) {
                    Log.e(TAG, "Timeout waiting for CameraDevice onClosed callback")
                }
            }

            Log.d(TAG, "Camera close completed")

        } catch (e: Exception) {
            Log.e(TAG, "closeCamera failed", e)
        } finally {
            cameraLock.release()
        }
    }

    /**
     * Для будущего экрана настроек (шестерёнка) — текущие метрики
     * адаптивного троттлинга, доступны без побочных эффектов.
     */
    fun currentDetectionSkipInterval(): Int = loadMonitor.currentSkipInterval()

    /** Паузы между анализами: см. комментарий у detectionIdleFactor. Вызывается при смене модели. */
    fun setDetectionDutyCycle(idleFactor: Float, minGapMs: Long) {
        detectionIdleFactor = idleFactor.coerceAtLeast(0f)
        minDetectionGapMs = minGapMs.coerceAtLeast(0L)
        nextDetectionAllowedAtMs = 0L
    }

    /** Длительность последнего анализа кадра целиком (YUV -> bitmap -> YOLO -> трекинг), мс. */
    fun lastDetectionDurationMs(): Long = lastDetectionDurationMs
    fun currentThermalStatus(): Int = loadMonitor.currentThermalStatus()

    fun currentSpeedKmh(): Float = speedProvider.currentSpeedKmh()
    fun hasReliableSpeed(): Boolean = speedProvider.hasReliableSpeed()
    fun isSpeedGateOverrideEnabled(): Boolean = speedGateOverrideEnabled

    // DASHCAM: угол поворота для GL-компоновщика видео — тот же самый
    // totalRotationDegrees, что уже применяется программно к превью и
    // детекции (Matrix.postRotate). GL-путь раньше НЕ применял его вообще
    // (баг, привёл к "повёрнуто на 90 и растянуто" на реальном тесте) —
    // теперь компоновщик опрашивает это перед каждым кадром.
    fun currentTotalRotationDegrees(): Int = totalRotationDegrees

    // DASHCAM: координаты для оверлея видео — тот же SpeedProvider,
    // независимое от скорости отслеживание (см. SpeedProvider.kt).
    fun currentLatitude(): Double? = speedProvider.currentLatitude()
    fun currentLongitude(): Double? = speedProvider.currentLongitude()
    fun hasReliableLocation(): Boolean = speedProvider.hasReliableLocation()

    /**
     * Включает/выключает ограничение детекции по скорости >5 км/ч.
     * true — ограничение СНЯТО (детекция работает всегда, независимо от
     * скорости) — предназначено для кнопки настроек ("шестерёнка"),
     * которая пока не реализована на UI-стороне.
     */
    fun setSpeedGateOverride(enabled: Boolean) {
        speedGateOverrideEnabled = enabled
        Log.d(TAG, "Speed gate override set to $enabled")
    }

    /**
     * Меняет фокус "на лету", БЕЗ пересоздания сессии/камеры — нужно
     * только для калибровки фокуса (см. FocusCalibrator.kt). Принудительно
     * переводит объектив в ручной режим (CONTROL_AF_MODE_OFF), даже если
     * CameraConfig.AUTO_FOCUS=true — калибровка бессмысленна при активном
     * автофокусе, который сам решает, куда фокусироваться.
     *
     * ВАЖНО: это НЕ меняет CameraConfig.FIXED_FOCUS_DISTANCE и не
     * сохраняется между запусками — временное значение для теста.
     * Финальное значение пользователь переносит в CameraConfig.kt вручную
     * по результатам калибровки (сознательное решение — см. обсуждение).
     */
    fun setManualFocusDistance(distance: Float) {
        val builder = activeRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
            session.setRepeatingRequest(builder.build(), null, captureHandler)
        } catch (e: Exception) {
            Log.e(TAG, "setManualFocusDistance($distance) failed", e)
        }
    }

    /** Возвращает фокус к значению из CameraConfig — вызывать по окончании калибровки. */
    fun restoreConfiguredFocusDistance() {
        if (!CameraConfig.AUTO_FOCUS) {
            setManualFocusDistance(CameraConfig.FIXED_FOCUS_DISTANCE)
        }
    }

    fun release() {
        closeCamera()
        loadMonitor.release()
        speedProvider.release()
        captureThread.quitSafely()
        detectionThread.quitSafely()
        previewThread.quitSafely()
        Log.d(TAG, "Camera threads released")
    }
}