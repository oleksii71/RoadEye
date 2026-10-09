package com.roadeye.app.dashcam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch

private const val TAG = "RoadEyeDashcam"

/** Снимок телеметрии на момент обновления оверлея. Все поля кроме времени — nullable: источник может быть недоступен. */
data class TelemetrySnapshot(
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val speedKmh: Float?
)

// Камера (GL_TEXTURE_EXTERNAL_OES) — полноэкранный quad. uTexMatrix здесь —
// НАША фиксированная матрица (переворот по Y), а НЕ системная от
// SurfaceTexture.getTransformMatrix(): та запекает поворот на 90° из
// подсказки ориентации камеры (подтверждено логом устройства), и с ней
// текстура приходит не в сырой ориентации, а уже транспонированной. Поворот
// по углу устройства делается отдельно, поворотом позиций вершин.
private const val CAMERA_VERTEX_SHADER = """
    attribute vec4 aPosition;
    attribute vec4 aTexCoord;
    uniform mat4 uTexMatrix;
    varying vec2 vTexCoord;
    void main() {
        gl_Position = aPosition;
        vTexCoord = (uTexMatrix * aTexCoord).xy;
    }
"""

private const val CAMERA_FRAGMENT_SHADER = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 vTexCoord;
    uniform samplerExternalOES sTexture;
    void main() {
        gl_FragColor = texture2D(sTexture, vTexCoord);
    }
"""

// Оверлей (обычная 2D-текстура, ARGB с альфа-каналом) — маленький quad
// в углу кадра, рисуется ПОВЕРХ камеры со смешиванием по альфе.
private const val OVERLAY_VERTEX_SHADER = """
    attribute vec4 aPosition;
    attribute vec2 aTexCoord;
    varying vec2 vTexCoord;
    void main() {
        gl_Position = aPosition;
        vTexCoord = aTexCoord;
    }
"""

private const val OVERLAY_FRAGMENT_SHADER = """
    precision mediump float;
    varying vec2 vTexCoord;
    uniform sampler2D sTexture;
    void main() {
        gl_FragColor = texture2D(sTexture, vTexCoord);
    }
"""

// ПОВОРОТ (добавлено после реального теста — картинка была "повёрнута на
// 90 и растянута"): Camera2 НЕ поворачивает сырой YUV/PRIV-кадр сама —
// ни для одного output-потока, включая этот. Превью/детекция уже это
// компенсируют программно (Matrix.postRotate(totalRotationDegrees) в
// Camera2Controller/TrafficSignAnalyzer) — здесь этого не было вообще.
//
// ВСЕГДА один и тот же (identity) набор texcoord. Текстура сэмплируется
// СЫРОЙ (через нашу матрицу-переворот по Y, см. cameraTexMatrix), то есть
// в той же ориентации, что кадр ImageReader в превью — поэтому угол
// поворота можно брать тот же самый (totalRotationDegrees), без
// эмпирических добавок. Прежнее утверждение здесь, что системная
// getTransformMatrix() "гарантированно даёт естественную ориентацию для
// identity texcoord", НЕ подтвердилось: на этом устройстве она запекает
// поворот на 90° и меняет оси местами.
//
// Поворот делается поворотом ПОЗИЦИЙ вершин в реальных пиксельных
// координатах (см. computeRotatedCropPositions), а texcoord каждой
// позиции остаётся неизменным — какой texel был привязан к слоту вершины
// при постройке геометрии, тот и остаётся, независимо от того, куда эта
// вершина потом повёрнута.
private val CAMERA_TEXCOORDS = floatArrayOf(
    0f, 0f, 0f, 1f,
    1f, 0f, 0f, 1f,
    0f, 1f, 0f, 1f,
    1f, 1f, 0f, 1f
)

// Texcoord для оверлея — с переворотом по Y (v: 1,1,0,0 вместо 0,0,1,1),
// т.к. Android Bitmap хранит строку 0 сверху изображения, а
// GLUtils.texImage2D загружает её как есть — без переворота текст
// оказался бы вверх ногами. NOT PROVEN на реальном устройстве — если
// оверлей окажется перевёрнут, поменять местами эти два ряда значений.
private val OVERLAY_TEXCOORDS = floatArrayOf(
    0f, 1f,
    1f, 1f,
    0f, 0f,
    1f, 0f
)

/**
 * Композитор кадра: принимает кадры камеры через SurfaceTexture
 * (GL_TEXTURE_EXTERNAL_OES), рисует поверх текстовый оверлей
 * (время/координаты/скорость) через Canvas -> Bitmap -> GL-текстура,
 * выводит результат в Surface энкодера (DashcamEncoder.inputSurface)
 * через EGL.
 *
 * ЗАМЕНЯЕТ прямое подключение "камера -> Surface энкодера" (было в
 * Варианте C до этого фикса) на "камера -> SurfaceTexture -> этот
 * класс (свой GL-поток) -> Surface энкодера". DashcamController теперь
 * отдаёт наружу cameraFacingSurface() ЭТОГО класса, а не
 * encoder.inputSurface напрямую — DashcamEncoder сам по себе не
 * меняется, ему всё равно, кто пишет в его Surface.
 *
 * ЧЕСТНО: первый EGL/GL-код в проекте, написан по спецификации, НЕ
 * протестирован итеративно на реальном устройстве (в отличие от
 * остального пайплайна). Наиболее вероятные точки первого отказа:
 * 1. eglChooseConfig может не найти подходящий конфиг на конкретном
 *    GPU/драйвере — тогда setup() бросит исключение, стоит проверить
 *    лог "GL compositor setup failed" первым делом.
 * 2. Оверлей может оказаться перевёрнут по вертикали (см. комментарий
 *    у OVERLAY_TEXCOORDS) — картинка камеры при этом от оверлея не
 *    зависит.
 * 3. Производительность — GL-рендеринг на отдельном потоке добавляет
 *    нагрузку поверх уже работающих captureThread/detectionThread —
 *    не измерялось.
 */
class DashcamGlCompositor(
    private val encoderInputSurface: Surface,
    // Размер СЫРОГО буфера камеры (SurfaceTexture) — не меняется, камера
    // всегда отдаёт его в альбомной форме.
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    // Размер ВЫХОДНОГО кадра (Surface энкодера) в начале. Меняется при
    // смене ориентации записи — см. switchOutput().
    initialOutputWidth: Int,
    initialOutputHeight: Int
) {

    // Текущий размер выходного кадра. Читается/пишется ТОЛЬКО на GL-потоке.
    private var outputWidth = initialOutputWidth
    private var outputHeight = initialOutputHeight

    /**
     * Вызывается на GL-потоке, когда новая ориентация (true = книжная)
     * продержалась стабильной ORIENTATION_SWITCH_STABLE_MS. Обработчик
     * должен вернуться быстро (тяжёлую работу — в другой поток) и потом
     * вызвать switchOutput() либо abortPendingSwitch().
     */
    var onOutputOrientationWanted: ((portrait: Boolean) -> Unit)? = null

    private var orientationWantedSince = 0L
    private var switchInFlight = false

    /** Источник телеметрии — опрашивается раз в OVERLAY_UPDATE_INTERVAL_MS, на GL-потоке. */
    var telemetryProvider: (() -> TelemetrySnapshot)? = null

    /**
     * Источник текущего угла поворота (0/90/180/270) — тот же
     * Camera2Controller.currentTotalRotationDegrees(), что уже применяется
     * к превью/детекции. Опрашивается на КАЖДОМ кадре (дёшево — 4
     * заранее посчитанных варианта геометрии, пересборка буфера только
     * при реальной смене значения, см. updateCameraGeometryIfNeeded()).
     */
    var rotationProvider: (() -> Int)? = null

    private val thread = HandlerThread("DashcamGlThread").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var cameraTextureId = 0
    private lateinit var cameraSurfaceTexture: SurfaceTexture
    private lateinit var cameraInputSurface: Surface
    // Матрица, которая РЕАЛЬНО загружается в шейдер: только переворот по Y
    // ((u,v) -> (u, 1-v), column-major), без поворота. Это компенсирует
    // разницу "строка 0 буфера сверху" и "V вверх" в GL — и всё. Системная
    // матрица SurfaceTexture НЕ используется, потому что она запекает
    // поворот на 90° (см. ниже) — подтверждено логом реального
    // устройства: [0,-1,0,0, -1,0,0,0, 0,0,1,0, 1,1,0,1] = (u,v)->(1-v,1-u),
    // транспозиция осей + отражение. При буфере 1280x720 это давало
    // растяжение примерно в 3.16 раза (1.778^2) и делало ВСЕ прежние
    // выводы угла неверными: они исходили из того, что identity-texcoord
    // даёт сырую ориентацию сенсора, как ImageReader в превью.
    private val cameraTexMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f
    )


    private var overlayTextureId = 0
    private var overlayReady = false

    private var cameraProgram = 0
    private var overlayProgram = 0

    private var cameraPosLoc = 0
    private var cameraTexCoordLoc = 0
    private var cameraTexMatrixLoc = 0
    private var cameraSamplerLoc = 0

    private var overlayPosLoc = 0
    private var overlayTexCoordLoc = 0
    private var overlaySamplerLoc = 0

    private lateinit var cameraPosBuffer: FloatBuffer
    private lateinit var cameraTexCoordBuffer: FloatBuffer
    private lateinit var overlayPosBuffer: FloatBuffer
    private lateinit var overlayTexCoordBuffer: FloatBuffer

    @Volatile
    private var released = false

    // Sentinel вне диапазона 0..270 — гарантирует пересборку геометрии
    // на самом первом кадре независимо от того, какой угол придёт первым.
    private var lastAppliedRotation = -1

    private val overlayUpdateRunnable = object : Runnable {
        override fun run() {
            updateOverlayBitmap()
            if (!released) handler.postDelayed(this, DashcamConfig.OVERLAY_UPDATE_INTERVAL_MS)
        }
    }

    /**
     * Синхронная инициализация EGL/GL на выделенном потоке — блокирует
     * вызывающий поток до готовности (CountDownLatch), т.к. вызывающему
     * (DashcamController.start() -> Camera2Controller.setVideoSurface())
     * нужен готовый cameraFacingSurface() сразу после возврата.
     */
    fun start() {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                setupEgl()
                setupCameraTexture()
                setupOverlayTexture()
                setupShaders()
                setupGeometry()
                Log.i(TAG, "GL compositor ready: source ${sourceWidth}x${sourceHeight} -> output ${outputWidth}x${outputHeight}")
            } catch (e: Exception) {
                Log.e(TAG, "GL compositor setup failed", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        handler.post(overlayUpdateRunnable)
    }

    /** Surface, в который камера должна писать кадры (второй output CaptureSession). */
    fun cameraFacingSurface(): Surface = cameraInputSurface

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw RuntimeException("eglChooseConfig failed — no matching EGL config on this device")
        }
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderInputSurface, surfaceAttribs, 0)
        if (windowSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")

        if (!EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    private fun setupCameraTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        cameraSurfaceTexture = SurfaceTexture(cameraTextureId)
        cameraSurfaceTexture.setDefaultBufferSize(sourceWidth, sourceHeight)
        cameraSurfaceTexture.setOnFrameAvailableListener({ handler.post { drawFrame() } }, handler)
        cameraInputSurface = Surface(cameraSurfaceTexture)
    }

    private fun setupOverlayTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        overlayTextureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // Сама текстура заполняется позже, в updateOverlayBitmap() —
        // здесь только выделение id, overlayReady остаётся false до
        // первого реального кадра текста.
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    private fun linkProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun setupShaders() {
        cameraProgram = linkProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        cameraPosLoc = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        cameraTexCoordLoc = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord")
        cameraTexMatrixLoc = GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix")
        cameraSamplerLoc = GLES20.glGetUniformLocation(cameraProgram, "sTexture")

        overlayProgram = linkProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        overlayPosLoc = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        overlayTexCoordLoc = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        overlaySamplerLoc = GLES20.glGetUniformLocation(overlayProgram, "sTexture")
    }

    private fun setupGeometry() {
        // Texcoord камеры — ФИКСИРОВАННЫЙ, не зависит от поворота (см.
        // комментарий у CAMERA_TEXCOORDS). Позиции камеры — НЕ строятся
        // здесь, зависят от текущего угла поворота, которого до первого
        // кадра ещё нет. См. updateCameraGeometryIfNeeded(), вызывается
        // из drawFrame().
        cameraTexCoordBuffer = makeFloatBuffer(CAMERA_TEXCOORDS)

        rebuildOverlayGeometry()
        overlayTexCoordBuffer = makeFloatBuffer(OVERLAY_TEXCOORDS)
    }

    /**
     * Позиция и размер оверлея в NDC — считаются из ПИКСЕЛЕЙ текущего
     * выходного кадра, а высота — из пропорций самого битмапа (иначе текст
     * был бы растянут: раньше ширина и высота задавались независимыми
     * долями кадра). Ширина — доля ДЛИННОЙ стороны, поэтому физический
     * размер плашки одинаков в альбомной и книжной ориентации.
     */
    private fun rebuildOverlayGeometry() {
        val frameW = outputWidth.toFloat()
        val frameH = outputHeight.toFloat()

        val quadWidthPx = kotlin.math.max(frameW, frameH) * DashcamConfig.OVERLAY_WIDTH_FRACTION
        val quadHeightPx = quadWidthPx * DashcamConfig.OVERLAY_BITMAP_HEIGHT / DashcamConfig.OVERLAY_BITMAP_WIDTH
        val marginPx = kotlin.math.min(frameW, frameH) * 0.03f

        val w = quadWidthPx / frameW * 2f
        val h = quadHeightPx / frameH * 2f
        val mx = marginPx / frameW * 2f
        val my = marginPx / frameH * 2f

        // BL, BR, TL, TR — нижний левый угол кадра.
        overlayPosBuffer = makeFloatBuffer(
            floatArrayOf(
                -1f + mx, -1f + my,
                -1f + mx + w, -1f + my,
                -1f + mx, -1f + my + h,
                -1f + mx + w, -1f + my + h
            )
        )
    }

    private fun makeFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(data); position(0) }

    /**
     * Читает текущий угол, следит за сменой ориентации записи и при
     * смене угла пересобирает позиции camera quad'а (иначе no-op).
     * Texcoords НЕ меняются вообще (см. CAMERA_TEXCOORDS).
     */
    private fun updateCameraGeometryIfNeeded() {
        val rotation = ((rotationProvider?.invoke() ?: 0) % 360 + 360) % 360

        checkOutputOrientation(rotation)

        if (rotation == lastAppliedRotation) return

        val positions = computeRotatedCropPositions(rotation)
        cameraPosBuffer = makeFloatBuffer(positions)
        lastAppliedRotation = rotation

        Log.i(
            TAG,
            "Camera rotation applied: ${rotation}° NDC positions=${positions.toList().map { "%.2f".format(it) }}"
        )
    }

    /**
     * Нужна ли смена ориентации ЗАПИСИ: 90/270° = книжная (720x1280),
     * 0/180° = альбомная (1280x720). Переключаем не сразу, а когда новая
     * ориентация держится ORIENTATION_SWITCH_STABLE_MS подряд — пересоздание
     * энкодера дорогое (рвёт клипы, сбрасывает pre-roll), а наклон планшета
     * около 45° иначе вызывал бы бесконечные пересоздания.
     */
    private fun checkOutputOrientation(rotation: Int) {
        val wantPortrait = rotation == 90 || rotation == 270
        val currentPortrait = outputHeight > outputWidth

        if (wantPortrait == currentPortrait) {
            orientationWantedSince = 0L
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (orientationWantedSince == 0L) {
            orientationWantedSince = now
            return
        }

        if (!switchInFlight && now - orientationWantedSince >= DashcamConfig.ORIENTATION_SWITCH_STABLE_MS) {
            switchInFlight = true
            Log.i(TAG, "Output orientation change requested: portrait=$wantPortrait")
            onOutputOrientationWanted?.invoke(wantPortrait)
        }
    }

    /**
     * Переключает выходной кадр на новый Surface энкодера другого размера.
     * Камера и SurfaceTexture НЕ трогаются. Вызывается контроллером после
     * создания нового энкодера.
     *
     * Порядок важен для отката при ошибке: сначала создаётся и активируется
     * НОВАЯ EGL-поверхность, и только потом уничтожается старая — при сбое
     * старый вывод остаётся рабочим. [onCommit] вызывается на GL-потоке
     * ПОСЛЕ успешной смены и ДО первого кадра в новый энкодер — туда
     * контроллер ставит смену поколения в кольцевом буфере, чтобы формат и
     * кадры нового энкодера были приняты, а старого — отброшены.
     * [onFinished] — на GL-потоке, с признаком успеха.
     */
    fun switchOutput(
        newSurface: Surface,
        newWidth: Int,
        newHeight: Int,
        onCommit: () -> Unit,
        onFinished: (success: Boolean) -> Unit
    ) {
        handler.post {
            var success = false
            try {
                val newWindow = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, newSurface, intArrayOf(EGL14.EGL_NONE), 0
                )
                if (newWindow == EGL14.EGL_NO_SURFACE) {
                    throw RuntimeException("eglCreateWindowSurface failed for the new output")
                }
                if (!EGL14.eglMakeCurrent(eglDisplay, newWindow, newWindow, eglContext)) {
                    EGL14.eglDestroySurface(eglDisplay, newWindow)
                    throw RuntimeException("eglMakeCurrent failed for the new output")
                }

                val oldWindow = windowSurface
                windowSurface = newWindow
                EGL14.eglDestroySurface(eglDisplay, oldWindow)

                outputWidth = newWidth
                outputHeight = newHeight
                lastAppliedRotation = -1 // принудительно пересобрать геометрию под новый кадр
                rebuildOverlayGeometry()

                onCommit()
                success = true
                Log.i(TAG, "GL output switched to ${newWidth}x${newHeight}")
            } catch (e: Exception) {
                Log.e(TAG, "GL output switch failed, keeping the previous output", e)
            } finally {
                switchInFlight = false
                // При неудаче не пытаемся снова мгновенно — ждём ещё один
                // стабильный интервал.
                orientationWantedSince = if (success) 0L else SystemClock.elapsedRealtime()
            }
            onFinished(success)
        }
    }

    /** Контроллер не смог даже начать смену (например, не создался энкодер) — снять флаг. */
    fun abortPendingSwitch() {
        handler.post {
            switchInFlight = false
            orientationWantedSince = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Поворот + CENTER_CROP кадра камеры под ТЕКУЩИЙ размер выходного кадра,
     * целиком через позиции вершин, без изменения texcoord.
     *
     * 1. Берём 4 угла НЕПОВЁРНУТОГО сырого буфера камеры в его РЕАЛЬНЫХ
     *    пикселях (0..sourceWidth, 0..sourceHeight), не в NDC: NDC
     *    соответствует РАЗНОМУ числу пикселей по X и Y, поворот прямо в NDC
     *    давал перекос.
     * 2. Поворачиваем точки вокруг центра на -rotationDegrees. МИНУС потому,
     *    что формула — вращение ПРОТИВ часовой для положительных углов при
     *    Y ВВЕРХ (GL), а Matrix.postRotate в превью вращает ПО часовой при
     *    Y ВНИЗ (Canvas) — с минусом визуальное направление совпадает.
     * 3. CENTER_CROP: масштаб = MAX(outW/rotW, outH/rotH), чтобы повёрнутый
     *    буфер перекрыл выходной кадр; лишнее отрежет GL-клиппинг. При
     *    согласованной ориентации выхода (альбомная 0/180° при 1280x720 и
     *    книжная 90/270° при 720x1280) масштаб ровно 1.000 — обрезки нет.
     *    Зум появляется только в короткие окна, пока ориентация выхода ещё
     *    не догнала ориентацию планшета.
     * 4. Пиксели -> NDC относительно выходного кадра.
     */
    private fun computeRotatedCropPositions(rotationDegrees: Int): FloatArray {
        val srcW = sourceWidth.toFloat()
        val srcH = sourceHeight.toFloat()
        val outW = outputWidth.toFloat()
        val outH = outputHeight.toFloat()

        val thetaRad = Math.toRadians(-rotationDegrees.toDouble())
        val cosT = kotlin.math.cos(thetaRad).toFloat()
        val sinT = kotlin.math.sin(thetaRad).toFloat()

        val cx = srcW / 2f
        val cy = srcH / 2f

        fun rotateAroundCenter(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - cx
            val dy = y - cy
            return (cx + dx * cosT - dy * sinT) to (cy + dx * sinT + dy * cosT)
        }

        // BL, BR, TL, TR — тот же порядок слотов, что и texcoord.
        val rotatedCorners = listOf(
            rotateAroundCenter(0f, 0f),
            rotateAroundCenter(srcW, 0f),
            rotateAroundCenter(0f, srcH),
            rotateAroundCenter(srcW, srcH)
        )

        val rotatedWidth = rotatedCorners.maxOf { it.first } - rotatedCorners.minOf { it.first }
        val rotatedHeight = rotatedCorners.maxOf { it.second } - rotatedCorners.minOf { it.second }

        val scale = kotlin.math.max(outW / rotatedWidth, outH / rotatedHeight)

        Log.i(
            TAG,
            "Rotation geometry: angle=${rotationDegrees}° rotatedBox=${"%.1f".format(rotatedWidth)}x${"%.1f".format(rotatedHeight)} " +
                    "scale=${"%.3f".format(scale)} output=${outputWidth}x${outputHeight}"
        )

        val positions = FloatArray(8)
        rotatedCorners.forEachIndexed { i, (x, y) ->
            positions[i * 2] = (x - cx) * scale / (outW / 2f)
            positions[i * 2 + 1] = (y - cy) * scale / (outH / 2f)
        }
        return positions
    }

    private fun drawFrame() {
        if (released) return
        try {
            cameraSurfaceTexture.updateTexImage()

            updateCameraGeometryIfNeeded()

            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            drawCameraQuad()
            drawOverlayQuad()

            // Presentation time — из СЫРОГО таймстампа камеры (наносекунды),
            // не System.nanoTime() на момент рисования — стандартная
            // практика при GL-компоновке кадров камеры, сохраняет
            // корректные интервалы между кадрами для энкодера.
            EGLExt.eglPresentationTimeANDROID(eglDisplay, windowSurface, cameraSurfaceTexture.timestamp)

            if (!EGL14.eglSwapBuffers(eglDisplay, windowSurface)) {
                Log.e(TAG, "eglSwapBuffers failed, error=${EGL14.eglGetError()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "drawFrame failed", e)
        }
    }

    private fun drawCameraQuad() {
        GLES20.glUseProgram(cameraProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(cameraSamplerLoc, 0)
        GLES20.glUniformMatrix4fv(cameraTexMatrixLoc, 1, false, cameraTexMatrix, 0)

        cameraPosBuffer.position(0)
        GLES20.glEnableVertexAttribArray(cameraPosLoc)
        GLES20.glVertexAttribPointer(cameraPosLoc, 2, GLES20.GL_FLOAT, false, 0, cameraPosBuffer)

        cameraTexCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(cameraTexCoordLoc)
        GLES20.glVertexAttribPointer(cameraTexCoordLoc, 4, GLES20.GL_FLOAT, false, 0, cameraTexCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(cameraPosLoc)
        GLES20.glDisableVertexAttribArray(cameraTexCoordLoc)
    }

    private fun drawOverlayQuad() {
        if (!overlayReady) return // телеметрия ещё ни разу не приходила

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(overlayProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glUniform1i(overlaySamplerLoc, 0)

        overlayPosBuffer.position(0)
        GLES20.glEnableVertexAttribArray(overlayPosLoc)
        GLES20.glVertexAttribPointer(overlayPosLoc, 2, GLES20.GL_FLOAT, false, 0, overlayPosBuffer)

        overlayTexCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(overlayTexCoordLoc)
        GLES20.glVertexAttribPointer(overlayTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, overlayTexCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(overlayPosLoc)
        GLES20.glDisableVertexAttribArray(overlayTexCoordLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun updateOverlayBitmap() {
        val snapshot = telemetryProvider?.invoke()
        val bitmap = renderOverlayBitmap(snapshot)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        overlayReady = true
    }

    private fun renderOverlayBitmap(snapshot: TelemetrySnapshot?): Bitmap {
        val bitmap = Bitmap.createBitmap(
            DashcamConfig.OVERLAY_BITMAP_WIDTH,
            DashcamConfig.OVERLAY_BITMAP_HEIGHT,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        canvas.drawRoundRect(
            RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
            20f, 20f, bgPaint
        )

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = DashcamConfig.OVERLAY_TEXT_SIZE_PX
        }

        val timestampMs = snapshot?.timestampMs ?: System.currentTimeMillis()
        val timeText = SimpleDateFormat("HH:mm:ss dd.MM.yyyy", Locale.getDefault()).format(Date(timestampMs))

        val coordsText = if (snapshot?.latitude != null && snapshot.longitude != null) {
            "GPS: %.5f, %.5f".format(snapshot.latitude, snapshot.longitude)
        } else {
            // ПЛЕЙСХОЛДЕР — telemetryProvider пока не отдаёт координаты
            // (SpeedProvider.kt отдаёт только скорость, не lat/lon —
            // нужен реальный файл, чтобы дописать это, не гадая).
            "GPS: нет данных"
        }

        val speedText = if (snapshot?.speedKmh != null) {
            "Скорость: ${snapshot.speedKmh.toInt()} км/ч"
        } else {
            "Скорость: н/д"
        }

        val lineHeight = DashcamConfig.OVERLAY_TEXT_SIZE_PX * 1.3f
        canvas.drawText(timeText, 20f, lineHeight, textPaint)
        canvas.drawText(coordsText, 20f, lineHeight * 2, textPaint)
        canvas.drawText(speedText, 20f, lineHeight * 3, textPaint)

        return bitmap
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        handler.post {
            try {
                GLES20.glDeleteProgram(cameraProgram)
                GLES20.glDeleteProgram(overlayProgram)
                GLES20.glDeleteTextures(2, intArrayOf(cameraTextureId, overlayTextureId), 0)
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, windowSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            } catch (e: Exception) {
                Log.e(TAG, "GL compositor release failed", e)
            }
            cameraSurfaceTexture.release()
            cameraInputSurface.release()
            thread.quitSafely()
        }
    }
}