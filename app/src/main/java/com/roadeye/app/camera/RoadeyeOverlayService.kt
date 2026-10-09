package com.roadeye.app.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.roadeye.app.MainActivity
import com.roadeye.app.dashcam.DashcamConfig
import com.roadeye.app.dashcam.DashcamController
import com.roadeye.app.dashcam.TelemetrySnapshot
import kotlin.math.max
import kotlin.math.min

private const val TAG = "RoadEyeOverlay"
private const val NOTIFICATION_CHANNEL_ID = "roadeye_camera_service"
private const val NOTIFICATION_ID = 1
private const val LOADING_TEXT = "Загрузка..."

// Раньше onDetection просто перезаписывал phraseTextView последней
// детекцией за кадр — при нескольких РАЗНЫХ знаках подряд видна была
// только одна строка (см. NEXT_STEPS.md HIGH #2, гипотеза подтвердилась
// после введения трекинга по инстансам в TrafficSignAnalyzer — теперь
// несколько треков реально озвучиваются независимо, но UI это никак не
// показывал). Теперь показываем список последних MAX_DISPLAYED_SIGNS
// озвученных знаков, каждый автоматически исчезает из списка через
// DISPLAY_DURATION_MS после появления — независимо от TTS-cooldown в
// SignSpeaker (5 сек НА ЗВУК), это отдельная чисто визуальная длительность.
private const val MAX_DISPLAYED_SIGNS = 4
private const val DISPLAY_DURATION_MS = 4000L

// Пока панель настроек открыта — обновляем метрики (частота детекции,
// CPU, температура) раз в SETTINGS_REFRESH_INTERVAL_MS. НЕ работает в
// фоне, пока панель закрыта — нет смысла тратить ресурсы на обновление
// невидимого текста.
private const val SETTINGS_REFRESH_INTERVAL_MS = 1000L

// Увеличенный режим окна (см. isEnlarged) — доля МЕНЬШЕГО измерения
// экрана, используемая как ширина окна вместо обычных 4/9*0.84. НЕ
// настоящий multi-window/split-screen (см. обсуждение в чате — попытки
// программно войти в системный split-screen дали REJECTED на этой
// прошивке, ShellDesktopMode/Launcher3 не даёт публичного API для
// этого) — это просто собственное окно RoadEye, временно растянутое
// почти на весь экран, та же WindowManager-архитектура, что и обычно.
private const val ENLARGED_WIDTH_FRACTION = 0.95f

/**
 * Кнопка записи видеорегистратора — рисуется напрямую через Canvas/Paint,
 * НЕ через текстовый символ/emoji. Раньше использовался TextView с
 * символом "⏺" (U+23FA) и setTextColor() — на части прошивок такие
 * символы рендерятся системным ЦВЕТНЫМ emoji-шрифтом как картинка,
 * которая setTextColor() физически игнорирует (подтверждено на практике:
 * вся логика состояния работала верно, эффекта не было видно именно
 * из-за этого). Прямая отрисовка фигур исключает эту категорию бага.
 *
 * Не идёт запись — красный круг с белой точкой (нажать, чтобы начать).
 * Идёт запись (любой источник) — белый круг с красным квадратом
 * (нажать, чтобы остановить).
 */
private class RecordButtonView(context: Context) : View(context) {

    var isRecording: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val circlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val outerRadius = min(width, height) / 2f * 0.9f

        if (isRecording) {
            circlePaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, outerRadius, circlePaint)

            markerPaint.color = Color.RED
            val half = outerRadius * 0.45f
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, markerPaint)
        } else {
            circlePaint.color = Color.RED
            canvas.drawCircle(cx, cy, outerRadius, circlePaint)

            markerPaint.color = Color.WHITE
            canvas.drawCircle(cx, cy, outerRadius * 0.35f, markerPaint)
        }
    }
}

/**
 * Foreground-сервис — владеет камерой, ML-анализом, видеорегистратором
 * и оверлей-окном (WindowManager, TYPE_APPLICATION_OVERLAY). Живёт
 * независимо от MainActivity: можно свернуть приложение, открыть
 * навигатор — RoadEye продолжит распознавать и озвучивать знаки, окошко
 * останется поверх всех приложений.
 *
 * Оверлей — обычные Android View (не Compose): ComposeView вне Activity
 * требует ручной реализации LifecycleOwner/SavedStateRegistryOwner,
 * усложняет и без того большой кусок работы.
 */
class RoadEyeOverlayService : Service() {

    private lateinit var cameraController: Camera2Controller
    private var focusCalibrator: FocusCalibrator? = null
    private var signAnalyzer: TrafficSignAnalyzer? = null

    // Текущая модель распознавания (Tflite48/Tflite83) и защита от устаревших загрузок:
    // при быстром переключении или остановке сервиса результат старой загрузки отбрасывается.
    private var currentModelVariant: ModelVariant = ModelVariant.TFLITE48
    private val analyzerGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var analyzerLoading = false
    private var modelRow: TextView? = null

    // DASHCAM (Вариант C): фасад видеорегистратора — см. com.roadeye.app.dashcam.
    // Создаётся и стартует в onCreate() ДО cameraController.openCamera(),
    // останавливается в onDestroy() ПОСЛЕ cameraController.release() —
    // порядок важен, см. комментарии в местах вызова ниже.
    private var dashcamController: DashcamController? = null

    // Двойной тап по превью — событие видеорегистратора (как удар).
    // Тот же root View уже использует setOnTouchListener для
    // перетаскивания окна (handleDrag) — GestureDetector работает
    // ПАРАЛЛЕЛЬНО с ним на тех же событиях, не заменяет его (см.
    // setOnTouchListener в createOverlayWindow()). by lazy — чтобы не
    // зависеть от порядка инициализации полей относительно onCreate().
    private val dashcamGestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                Log.i(TAG, "Double tap on preview — dashcam event triggered")
                dashcamController?.onDoubleTap()
                return true
            }
        })
    }

    private lateinit var windowManager: WindowManager
    private var overlayRoot: FrameLayout? = null
    private var frameImageView: ImageView? = null
    private var phraseTextView: TextView? = null
    private var recordButton: RecordButtonView? = null

    private lateinit var overlayLayoutParams: WindowManager.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())

    // Для перетаскивания — запоминаем начальные координаты касания и окна
    private var dragStartTouchX = 0f
    private var dragStartTouchY = 0f
    private var dragStartWindowX = 0
    private var dragStartWindowY = 0

    private var firstFrameReceived = false

    // Панель настроек (шестерёнка) — показывает частоту детекции,
    // загрузку CPU, температуру, переключатель ограничения по скорости.
    // Изначально скрыта (GONE), открывается/закрывается тапом по gearButton.
    private var settingsPanel: View? = null
    private var enlargeButton: TextView? = null
    private var speedGateRow: TextView? = null
    private var statsTextView: TextView? = null
    private var isSettingsPanelVisible = false

    // Увеличенный режим — окно временно занимает почти весь экран вместо
    // обычного маленького плавающего окошка (см. toggleEnlargedMode()).
    // Перетаскивание отключается в этом режиме (see handleDrag) — двигать
    // окно, занимающее весь экран, бессмысленно.
    private var isEnlarged = false

    private val settingsRefreshRunnable = object : Runnable {
        override fun run() {
            refreshSettingsPanelText()
            if (isSettingsPanelVisible) {
                mainHandler.postDelayed(this, SETTINGS_REFRESH_INTERVAL_MS)
            }
        }
    }

    // Список последних озвученных знаков, отображаемых одновременно в
    // phraseTextView (многострочно), вместо перезаписи одной строкой.
    // Индекс 0 — самый новый. См. константы MAX_DISPLAYED_SIGNS /
    // DISPLAY_DURATION_MS выше.
    private class DisplayedSign(val id: Long, val text: String)
    private val displayedSigns = mutableListOf<DisplayedSign>()
    private var nextDisplayedSignId = 0L

    @Volatile
    private var isDestroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startForeground(NOTIFICATION_ID, buildNotification())

        // Окошко показываем СРАЗУ — и именно поэтому всё остальное (камера,
        // дашкам, детектор) идёт НЕ здесь же синхронно, а через
        // mainHandler.post(): addView() только ставит окно в очередь на
        // отрисовку, а не рисует его немедленно — реальная отрисовка
        // происходит на следующей итерации цикла сообщений главного
        // потока. Если между addView() и этой итерацией главный поток
        // занят блокирующей работой, окно физически появится позже, чем
        // "первым в коде". Раньше это было не важно (все вызовы ниже были
        // короткими), но DashcamController.start() теперь синхронно ждёт
        // полную настройку EGL/GL-компоновщика (контекст, шейдеры,
        // текстуры — реальная работа, не быстрый вызов, как было раньше,
        // до появления оверлея) — без post() это гарантированно
        // откладывало бы первую отрисовку окна превью.
        createOverlayWindow()
        phraseTextView?.text = LOADING_TEXT

        mainHandler.post { initCameraAndDashcam() }
    }

    /**
     * Вся инициализация камеры/дашкама/детектора — см. onCreate() насчёт
     * того, почему это вынесено в отдельный, отложенный через
     * mainHandler.post() метод, а не идёт синхронно в onCreate().
     */
    private fun initCameraAndDashcam() {

        cameraController = Camera2Controller(this)
        focusCalibrator = FocusCalibrator(cameraController, mainHandler)

        // DASHCAM (Вариант C): создаём и стартуем ДО cameraController.
        // openCamera() — нужен готовый Surface от компоновщика, чтобы
        // передать его в Camera2Controller.setVideoSurface() ДО того, как
        // соберётся CaptureSession (см. комментарий в самом
        // Camera2Controller.setVideoSurface() — вызов после открытия
        // камеры ни на что не повлияет без пересоздания сессии).
        // DashcamController.start() СИНХРОННО ждёт полную настройку EGL —
        // это реальная блокировка вызывающего потока, не быстрый вызов
        // (см. комментарий у mainHandler.post() выше, откуда это вызвано).
        val dashcam = DashcamController(this)
        dashcam.start()
        cameraController.setVideoSurface(dashcam.videoInputSurface())
        dashcamController = dashcam

        // Кнопка красится/светлеет ТОЛЬКО по факту реального состояния —
        // идёт ли СЕЙЧАС хоть какая-то запись (удар/тап/кнопка, без
        // разницы источника), не по предположению в момент клика. Колбэк
        // уже приходит на главном потоке (DashcamController сам это
        // гарантирует).
        dashcam.onRecordingStateChanged = { isRecording ->
            recordButton?.isRecording = isRecording
        }

        // Время, скорость и координаты — все из уже существующего
        // cameraController (SpeedProvider проброшен через него тем же
        // паттерном, что currentSpeedKmh()/hasReliableSpeed()).
        dashcam.telemetryProvider = {
            TelemetrySnapshot(
                timestampMs = System.currentTimeMillis(),
                latitude = if (cameraController.hasReliableLocation()) cameraController.currentLatitude() else null,
                longitude = if (cameraController.hasReliableLocation()) cameraController.currentLongitude() else null,
                speedKmh = if (cameraController.hasReliableSpeed()) cameraController.currentSpeedKmh() else null
            )
        }

        // Тот же угол, что применяется к превью/детекции
        // (Camera2Controller.totalRotationDegrees). Эмпирическое "+270" убрано:
        // оно гасило поворот на 90°, запечённый в системную матрицу
        // SurfaceTexture, а теперь компоновщик сэмплирует СЫРОЙ буфер, как
        // ImageReader в превью. Если после теста нужна остаточная поправка —
        // это DashcamConfig.VIDEO_ROTATION_OFFSET_DEGREES, единственное место.
        dashcam.rotationProvider = {
            (cameraController.currentTotalRotationDegrees() + DashcamConfig.VIDEO_ROTATION_OFFSET_DEGREES) % 360
        }

        // Превью — НЕЗАВИСИМО от детектора, подключается и запускается
        // сразу же. Камера открывается немедленно, картинка появляется
        // в окошке, не дожидаясь загрузки TFLite-модели (~10 сек по
        // логам — GPU-делегат компилирует шейдеры).
        cameraController.setPreviewCallback { bitmap ->
            mainHandler.post {
                frameImageView?.setImageBitmap(bitmap)

                if (!firstFrameReceived) {
                    firstFrameReceived = true
                    if (phraseTextView?.text == LOADING_TEXT) {
                        phraseTextView?.text = ""
                    }
                }
            }
        }

        cameraController.openCamera()

        // TrafficSignAnalyzer (TFLite-модель + GPU-шейдеры + TTS) грузится
        // ПАРАЛЛЕЛЬНО, в фоновом потоке — не блокирует ни главный поток,
        // ни уже работающую камеру/превью. Подключается к камере через
        // setFrameAnalyzer(...) отдельным шагом, когда будет готов —
        // детекция "включится" на уже идущем потоке кадров, без
        // необходимости заново открывать камеру.
        startAnalyzer(ModelVariant.load(this))
    }

    /**
     * Запускает (или перезапускает) анализатор с выбранной моделью. Старый анализатор
     * отсоединяется от камеры и освобождается в фоне (release() дожидается конца идущего
     * analyze()), новый грузится в AnalyzerInitThread — камера и превью не останавливаются.
     * Если выбранная модель не загрузилась, откатываемся на Tflite48. Вызывать с главного потока.
     */
    private fun startAnalyzer(variant: ModelVariant) {

        currentModelVariant = variant
        analyzerLoading = true
        val generation = analyzerGeneration.incrementAndGet()

        val old = signAnalyzer
        signAnalyzer = null
        cameraController.setFrameAnalyzer(null)

        if (firstFrameReceived) phraseTextView?.text = LOADING_TEXT
        refreshSettingsPanelText()

        Thread({
            Log.d(TAG, "Initializing TrafficSignAnalyzer (${variant.title}) on background thread...")

            old?.release()

            val analyzer = try {
                TrafficSignAnalyzer(
                    context = this,
                    onDetection = { _, phraseRu, confidence, _, _, _ ->
                        mainHandler.post {
                            addDisplayedSign(phraseRu, confidence)
                        }
                    },
                    onRawDetection = { label, confidence ->
                        focusCalibrator?.onRawDetection(label, confidence)
                    },
                    variant = variant
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to create TrafficSignAnalyzer for ${variant.title}", e)
                null
            }

            mainHandler.post {
                if (isDestroyed || generation != analyzerGeneration.get()) {
                    // Сервис остановлен или уже запрошена другая модель — этот результат устарел.
                    Log.d(TAG, "Stale analyzer init (${variant.title}), releasing without use")
                    analyzer?.release()
                    return@post
                }

                analyzerLoading = false

                if (analyzer == null) {
                    if (variant != ModelVariant.TFLITE48) {
                        Log.w(TAG, "${variant.title} failed to load, falling back to Tflite48")
                        ModelVariant.save(this, ModelVariant.TFLITE48)
                        startAnalyzer(ModelVariant.TFLITE48)
                    } else {
                        refreshSettingsPanelText()
                    }
                    return@post
                }

                Log.d(TAG, "TrafficSignAnalyzer (${variant.title}) ready, attaching to running camera")
                signAnalyzer = analyzer
                cameraController.setFrameAnalyzer(analyzer)
                if (firstFrameReceived) updatePhraseTextView()
                refreshSettingsPanelText()
            }
        }, "AnalyzerInitThread").start()
    }

    /** Тап по строке "Модель" в панели настроек: Tflite48 <-> Tflite83. */
    private fun toggleModelVariant() {
        if (!::cameraController.isInitialized) return
        val next = currentModelVariant.next()
        ModelVariant.save(this, next)
        displayedSigns.clear()
        startAnalyzer(next)
    }

    /**
     * Добавляет новый озвученный знак в начало отображаемого списка,
     * обрезает список до MAX_DISPLAYED_SIGNS, планирует автоматическое
     * удаление этой конкретной записи через DISPLAY_DURATION_MS.
     * Вызывать ТОЛЬКО с главного потока (mainHandler.post).
     */
    private fun addDisplayedSign(phraseRu: String, confidence: Float) {

        val id = nextDisplayedSignId++
        val text = "$phraseRu (${(confidence * 100).toInt()}%)"

        displayedSigns.add(0, DisplayedSign(id, text))
        while (displayedSigns.size > MAX_DISPLAYED_SIGNS) {
            displayedSigns.removeAt(displayedSigns.size - 1)
        }

        updatePhraseTextView()

        mainHandler.postDelayed({ removeDisplayedSign(id) }, DISPLAY_DURATION_MS)
    }

    private fun removeDisplayedSign(id: Long) {
        if (displayedSigns.removeAll { it.id == id }) {
            updatePhraseTextView()
        }
    }

    private fun updatePhraseTextView() {
        phraseTextView?.text = displayedSigns.joinToString(separator = "\n") { it.text }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY — если систему прижмёт по памяти и убьёт сервис,
        // Android попробует перезапустить его снова (без исходного intent).
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Поворот экрана — пересчитываем размер/позицию окна под новые
        // границы экрана, иначе окно может оказаться за пределами
        // видимой области или неправильного размера.
        mainHandler.post { resizeAndClampOverlay() }
    }

    private fun buildNotification(): Notification {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "RoadEye — распознавание знаков",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("RoadEye активен")
            .setContentText("Распознавание дорожных знаков работает в фоне")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    // ---------------------------------------------------------------
    // Оверлей-окно
    // ---------------------------------------------------------------

    private fun createOverlayWindow() {

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val (windowW, windowH) = computeWindowSizePx()

        overlayLayoutParams = WindowManager.LayoutParams(
            windowW,
            windowH,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, // постоянно включённый экран
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = topMarginPx()
        }

        val root = FrameLayout(this)

        val imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val textView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setPadding(16, 8, 16, 8)
            textSize = 14f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        }

        val powerButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_lock_power_off)
            setBackgroundColor(Color.argb(180, 60, 0, 0))
            val paddingPx = (8 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                (36 * resources.displayMetrics.density).toInt(),
                (36 * resources.displayMetrics.density).toInt()
            ).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            setOnClickListener {
                Log.d(TAG, "Power button tapped — stopping service")
                stopSelf() // вызовет onDestroy(), там уже вся логика очистки
            }
        }

        val gearButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            setBackgroundColor(Color.argb(180, 0, 0, 60))
            val paddingPx = (8 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                (36 * resources.displayMetrics.density).toInt(),
                (36 * resources.displayMetrics.density).toInt()
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            setOnClickListener { toggleSettingsPanel() }
        }

        // Переключатель обычный/увеличенный режим (см. toggleEnlargedMode).
        // Текстовая кнопка (не ImageView) — нет надёжной системной иконки
        // "развернуть на весь экран" во всех версиях Android, юникод-символ
        // проще и предсказуемее по внешнему виду.
        val enlargeButtonView = TextView(this).apply {
            text = "⤢"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(180, 0, 0, 60))
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                (36 * resources.displayMetrics.density).toInt(),
                (36 * resources.displayMetrics.density).toInt()
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
            setOnClickListener {
                toggleEnlargedMode()
                text = if (isEnlarged) "⤡" else "⤢"
            }
        }

        // DASHCAM (Вариант C): кнопка записи. МОДЕЛЬ (уточнено
        // пользователем — см. DashcamController, класс-комментарий):
        // красная, пока идёт ЛЮБАЯ запись (удар/тап/кнопка, без разницы
        // источника); нажатие при пустом состоянии — запускает
        // циклическую запись в NORMAL (не FAVORITE) сегментами по
        // DashcamConfig.CONTINUOUS_SEGMENT_MS; нажатие во время ЛЮБОЙ
        // активной записи — останавливает ВСЁ, что сейчас пишется.
        val recordButtonView = RecordButtonView(this).apply {
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                (36 * resources.displayMetrics.density).toInt(),
                (36 * resources.displayMetrics.density).toInt()
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
            setOnClickListener {
                val controller = dashcamController
                if (controller == null) {
                    Log.w(TAG, "Record button tapped before dashcam controller is ready")
                    return@setOnClickListener
                }
                controller.onManualRecordToggle()
                // Вид кнопки НЕ меняем здесь напрямую — обновится через
                // onRecordingStateChanged колбэк (см. RecordButtonView.isRecording),
                // когда реальное состояние действительно изменится.
            }
        }

        val modelRowView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
            isClickable = true
            setOnClickListener { toggleModelVariant() }
        }

        val speedGateRowView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
            isClickable = true
            setOnClickListener {
                if (!::cameraController.isInitialized) return@setOnClickListener
                cameraController.setSpeedGateOverride(!cameraController.isSpeedGateOverrideEnabled())
                refreshSettingsPanelText()
            }
        }

        val statsTextViewInner = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
        }

        val focusCalibrationRowView = TextView(this).apply {
            text = "Калибровка фокуса (тап — запустить)"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            isClickable = true
            setOnClickListener { toggleFocusCalibration(this) }
        }

        val settingsPanelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Полностью прозрачный фон по запросу пользователя — раньше
            // было Color.argb(210, 0, 0, 0), почти непрозрачный чёрный,
            // перекрывавший картинку с камеры, пока открыта панель
            // настроек. Теперь камера видна под панелью без затемнения.
            setBackgroundColor(Color.TRANSPARENT)
            val paddingPx = (16 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
            }
            addView(modelRowView)
            addView(speedGateRowView)
            addView(statsTextViewInner)
            addView(focusCalibrationRowView)
        }

        root.addView(imageView)
        root.addView(textView)
        root.addView(settingsPanelView)
        root.addView(powerButton)
        root.addView(gearButton)
        root.addView(enlargeButtonView)
        root.addView(recordButtonView)

        // DASHCAM: двойной тап живёт РЯДОМ с перетаскиванием, не вместо
        // него — оба обработчика получают КАЖДОЕ событие, ни один не
        // блокирует другой. На практике двойной тап почти не двигает
        // окно (пальцем почти не смещаются между двумя касаниями), а
        // одиночное перетаскивание не похоже по таймингу на двойной тап
        // (GestureDetector сам отсеивает медленные/далёкие последовательности
        // касаний как НЕ double-tap).
        root.setOnTouchListener { _, event ->
            val doubleTapConsumed = dashcamGestureDetector.onTouchEvent(event)
            val dragConsumed = handleDrag(event)
            doubleTapConsumed || dragConsumed
        }

        windowManager.addView(root, overlayLayoutParams)

        overlayRoot = root
        frameImageView = imageView
        phraseTextView = textView
        settingsPanel = settingsPanelView
        speedGateRow = speedGateRowView
        modelRow = modelRowView
        statsTextView = statsTextViewInner
        enlargeButton = enlargeButtonView
        recordButton = recordButtonView
    }


    /**
     * Открывает/закрывает панель настроек. Пока открыта — запускает
     * периодическое обновление метрик (settingsRefreshRunnable).
     */
    private fun toggleSettingsPanel() {
        isSettingsPanelVisible = !isSettingsPanelVisible
        settingsPanel?.visibility = if (isSettingsPanelVisible) View.VISIBLE else View.GONE

        if (isSettingsPanelVisible) {
            refreshSettingsPanelText()
            mainHandler.postDelayed(settingsRefreshRunnable, SETTINGS_REFRESH_INTERVAL_MS)
        }
    }

    private fun refreshSettingsPanelText() {

        if (!::cameraController.isInitialized) return

        modelRow?.text = "Модель (тап — переключить):\n" +
                ModelVariant.values().joinToString("   ") {
                    if (it == currentModelVariant) "[${it.title}]" else it.title
                } +
                if (analyzerLoading) "\nзагрузка..." else ""

        val overrideEnabled = cameraController.isSpeedGateOverrideEnabled()
        speedGateRow?.text = "Ограничение по скорости (тап — переключить):\n" +
                if (overrideEnabled) "СНЯТО — детекция работает всегда"
                else "включено — детекция только при >5 км/ч"

        val skipInterval = cameraController.currentDetectionSkipInterval()
        val thermalText = thermalStatusText(cameraController.currentThermalStatus())
        val speedText = if (cameraController.hasReliableSpeed()) {
            "${cameraController.currentSpeedKmh().toInt()} км/ч"
        } else {
            "нет достоверных данных"
        }

        statsTextView?.text = "Частота детекции: каждый $skipInterval-й кадр\n" +
                "Температура: $thermalText\n" +
                "Скорость: $speedText"
    }

    private fun thermalStatusText(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "норма"
        PowerManager.THERMAL_STATUS_LIGHT -> "лёгкий нагрев"
        PowerManager.THERMAL_STATUS_MODERATE -> "умеренный нагрев"
        PowerManager.THERMAL_STATUS_SEVERE -> "сильный нагрев"
        PowerManager.THERMAL_STATUS_CRITICAL -> "критический нагрев"
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "аварийный перегрев"
        else -> "неизвестно ($status)"
    }

    /**
     * Запускает/останавливает ассистированную калибровку фокуса (см.
     * FocusCalibrator.kt). Пользователь должен физически стоять на
     * фиксированной дистанции (например, 50м) от ОДНОГО знака и держать
     * его в кадре, пока идёт перебор — калибровка сама меняет фокус и
     * слушает сырые детекции, но НЕ проверяет, что в кадре именно нужный
     * знак. Результат НЕ применяется автоматически — пользователь сам
     * переносит лучшее значение в CameraConfig.FIXED_FOCUS_DISTANCE.
     */
    private fun toggleFocusCalibration(rowView: TextView) {

        val calibrator = focusCalibrator ?: return

        if (calibrator.isCalibrating()) {
            calibrator.cancel()
            rowView.text = "Калибровка фокуса (тап — запустить)"
            return
        }

        // Шаг 0.02 от 0.0 (бесконечность) до 0.24 (~4м) — плотнее, чем
        // ручной bracket из комментария CameraConfig.kt, т.к. перебор
        // теперь автоматический и не требует пересборки APK на каждое
        // значение.
        val candidates = (0..12).map { it * 0.02f }

        rowView.text = "Калибровка: подготовка..."

        calibrator.start(
            candidates = candidates,
            onProgress = { progress -> rowView.text = "Калибровка: $progress" },
            onComplete = { results ->
                val sorted = results.sortedByDescending { it.bestConfidence }
                val best = sorted.firstOrNull()

                val summary = buildString {
                    if (best == null || best.bestConfidence == 0f) {
                        append("Ни одно значение не дало детекций.\n")
                        append("Знак в кадре? Дистанция похожа на рабочую?")
                    } else {
                        append("Лучшее: ${best.focusDistance} ")
                        append("(${(best.bestConfidence * 100).toInt()}%, ")
                        append("${best.detectionCount} детекций)\n")
                        append("Перенесите это значение в CameraConfig.FIXED_FOCUS_DISTANCE вручную.\n\n")
                        append("Топ-3:\n")
                        sorted.take(3).forEach {
                            append("${it.focusDistance} — ${(it.bestConfidence * 100).toInt()}%\n")
                        }
                    }
                }

                rowView.text = summary
                Log.i(TAG, "Focus calibration complete: $summary")
            }
        )
    }

    /**
     * Перетаскивание окна пальцем — стандартная последовательность
     * ACTION_DOWN (запоминаем точку старта) / ACTION_MOVE (двигаем окно)
     * с ограничением, чтобы окно не улетало за пределы экрана.
     */
    private fun handleDrag(event: MotionEvent): Boolean {

        // Окно, занимающее почти весь экран, двигать бессмысленно —
        // в увеличенном режиме перетаскивание отключено целиком.
        if (isEnlarged) return false

        when (event.action) {

            MotionEvent.ACTION_DOWN -> {
                dragStartTouchX = event.rawX
                dragStartTouchY = event.rawY
                dragStartWindowX = overlayLayoutParams.x
                dragStartWindowY = overlayLayoutParams.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - dragStartTouchX).toInt()
                val dy = (event.rawY - dragStartTouchY).toInt()

                val screenSize = screenSizePx()
                val maxX = max(0, (screenSize.first - overlayLayoutParams.width) / 2)
                val maxY = max(0, screenSize.second - overlayLayoutParams.height)

                // gravity CENTER_HORIZONTAL + x означает смещение ОТ
                // центра, поэтому ограничиваем в обе стороны от 0.
                overlayLayoutParams.x = (dragStartWindowX + dx).coerceIn(-maxX, maxX)
                overlayLayoutParams.y = (dragStartWindowY + dy).coerceIn(0, maxY)

                windowManager.updateViewLayout(overlayRoot, overlayLayoutParams)
                return true
            }
        }

        return false
    }

    /**
     * Пересчитывает размер окна (2/3 ширины экрана, пропорция кадра
     * анализа) и подгоняет позицию под новые границы — вызывается при
     * повороте экрана.
     */
    private fun resizeAndClampOverlay() {

        val (windowW, windowH) = computeWindowSizePx()
        overlayLayoutParams.width = windowW
        overlayLayoutParams.height = windowH

        val screenSize = screenSizePx()
        val maxX = max(0, (screenSize.first - windowW) / 2)
        val maxY = max(0, screenSize.second - windowH)

        overlayLayoutParams.x = overlayLayoutParams.x.coerceIn(-maxX, maxX)
        overlayLayoutParams.y = overlayLayoutParams.y.coerceIn(0, maxY)

        overlayRoot?.let {
            try {
                windowManager.updateViewLayout(it, overlayLayoutParams)
            } catch (e: Exception) {
                Log.e(TAG, "updateViewLayout failed on rotation", e)
            }
        }
    }

    /**
     * Размер окна — фиксированный, не пересчитывается по-разному под
     * книжную/планшетную ориентацию. База — МЕНЬШЕЕ из двух измерений
     * экрана (это физически совпадает с "книжной" шириной устройства
     * независимо от того, в какой ориентации мы сейчас находимся) —
     * поэтому результат автоматически одинаковый в обеих ориентациях,
     * без отдельного кода на случай поворота.
     *
     * Форма окна ВСЕГДА альбомная (16:9, ширина > высоты) — как у
     * настоящего PIP для видео: контент структурно альбомный и
     * стабильный, не подстраивается под поворот устройства (камера
     * физически закреплена и смотрит на дорогу — поворот планшета для
     * чтения экрана не должен вращать саму картинку).
     *
     * ВАЖНО: исходный поток камеры — 4:3 (640x480, см. Camera2Controller).
     * Нативного 16:9-источника нет — CENTER_CROP обрезает верх/низ
     * кадра, чтобы вписать 4:3-контент в более узкое по высоте окно.
     * На саму детекцию (TrafficSignAnalyzer работает с полным кадром)
     * это не влияет — сужается только видимая пользователю область.
     *
     * 4/9 от меньшего измерения экрана (было 2/3, затем уменьшено втрое
     * до 2/9, увеличено вдвое обратно до 4/9, затем -30%, затем +20%
     * — итоговый множитель 0.7 * 1.2 = 0.84).
     */
    private fun computeWindowSizePx(): Pair<Int, Int> {
        val (screenW, screenH) = screenSizePx()
        val portraitBaseWidth = min(screenW, screenH)

        val windowW = if (isEnlarged) {
            (portraitBaseWidth * ENLARGED_WIDTH_FRACTION).toInt()
        } else {
            (portraitBaseWidth * 4 / 9 * 0.84f).toInt()
        }
        val windowH = windowW * 9 / 16
        return windowW to windowH
    }

    /**
     * Переключает обычный/увеличенный режим окна и сразу пересчитывает
     * размер/позицию (переиспользует resizeAndClampOverlay — та же
     * логика, что и при повороте экрана).
     */
    private fun toggleEnlargedMode() {
        isEnlarged = !isEnlarged
        resizeAndClampOverlay()
    }

    private fun screenSizePx(): Pair<Int, Int> {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            android.graphics.Rect(0, 0, point.x, point.y)
        }
        return bounds.width() to bounds.height()
    }

    private fun topMarginPx(): Int = (24 * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        isDestroyed = true
        analyzerGeneration.incrementAndGet()

        focusCalibrator?.cancel()

        // Отменяет все отложенные mainHandler.postDelayed — в частности,
        // запланированные removeDisplayedSign(id) (см. addDisplayedSign).
        // Без этого колбэк может сработать после уничтожения View и
        // обратиться к уже недействительному phraseTextView.
        mainHandler.removeCallbacksAndMessages(null)

        overlayRoot?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "removeView failed", e)
            }
        }

        if (::cameraController.isInitialized) {
            cameraController.release()
        }

        // DASHCAM: останавливаем СТРОГО ПОСЛЕ cameraController.release().
        // closeCamera() внутри release() дожидается CameraDevice.onClosed()
        // через cameraCloseSignal, прежде чем считать закрытие завершённым
        // — только после этого безопасно освобождать video Surface
        // энкодера (DashcamEncoder.stop() -> inputSurface.release()).
        // Если поменять порядок местами — камера ещё может писать в
        // Surface в момент его освобождения.
        dashcamController?.stop()

        signAnalyzer?.release()

        super.onDestroy()
    }
}
