package com.roadeye.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

private const val TAG = "RoadEyeML"

// Раз в сколько вызовов detect() логировать время инференса — не на
// каждый кадр, чтобы не спамить лог (детекция и так троттлится, но при
// низком skipInterval это всё равно может быть часто).
private const val LOG_INFERENCE_TIME_EVERY_N_CALLS = 10

/**
 * YOLOv8 TFLite детектор. Вход — NHWC ([1, H, W, 3]), получен через
 * экспорт с закреплённой версией ultralytics<8.4.83 (старый путь
 * onnx2tf). Выход остался в порядке features-first ([1, 4+nc,
 * numAnchors]) даже при NHWC-входе — это подтверждено фактическими
 * данными (Kaggle: Output shape [1, 52, 8400] для 48 классов), не
 * предположением. Если модель переэкспортируют другой версией
 * ultralytics, оба порядка осей стоит перепроверять заново, не
 * полагаться на этот комментарий.
 *
 * Принимает ВЕСЬ кадр целиком, сам находит все знаки с координатами.
 * NMS реализован вручную — экспорт ultralytics с nms=True для TFLite
 * не работает (баг апстрима).
 *
 * НЕ потокобезопасно — вызывать последовательно с одного потока
 * (FrameAnalysisThread).
 */
class YoloDetector(
    context: Context,
    modelAssetName: String = "roadeye_yolo.tflite",
    labelsAssetName: String = "labels.txt",
    private val confidenceThreshold: Float = 0.5f,
    private val iouThreshold: Float = 0.45f,
    // DEFAULT (не откалибровано — не видел реальных кадров с этой
    // камеры/установки, чтобы подобрать точное значение): доля ВЫСОТЫ
    // кадра снизу, отбрасываемая ПЕРЕД letterbox-преобразованием —
    // капот/приборная панель у большинства видеорегистраторов обычно
    // не превышают 10-20% кадра по высоте. Не требует переобучения/
    // переэкспорта модели — вход модели остаётся тем же 416x416
    // (или каким он объявлен), просто в эти же пиксели теперь попадает
    // больше деталей верхней (полезной) части сцены вместо нижней.
    // 0f = выключено (прежнее поведение, весь кадр целиком).
    private val bottomCropFraction: Float = 0.15f
) {

    private val interpreter: Interpreter
    private val labels: List<String>

    // GpuDelegate НЕ закрывается вместе с Interpreter — без явного close() при каждой смене модели
    // утекала бы GPU-память.
    private var gpuDelegate: org.tensorflow.lite.gpu.GpuDelegate? = null

    // Буферы лучшего класса/скора на каждый анкор — переиспользуются между кадрами.
    private var bestScoreBuf = FloatArray(0)
    private var bestClassBuf = IntArray(0)

    private val inputWidth: Int
    private val inputHeight: Int
    private val inputIsQuantized: Boolean
    private val inputScale: Float
    private val inputZeroPoint: Int

    private val outputIsQuantized: Boolean
    private val outputDataType: DataType
    private val outputScale: Float
    private val outputZeroPoint: Int
    private val numAnchors: Int
    private val numClasses: Int

    private val letterboxBitmap: Bitmap
    private val letterboxCanvas: Canvas
    private val inputBuffer: ByteBuffer
    private val pixelsBuffer: IntArray
    private val floatOutputBuffer: Array<Array<FloatArray>>?
    private val byteOutputBuffer: Array<Array<ByteArray>>?

    init {
        val options = Interpreter.Options()

        val compatList = org.tensorflow.lite.gpu.CompatibilityList()

        if (compatList.isDelegateSupportedOnThisDevice) {
            try {
                val delegateOptions = compatList.bestOptionsForThisDevice
                val delegate = org.tensorflow.lite.gpu.GpuDelegate(delegateOptions)
                gpuDelegate = delegate
                options.addDelegate(delegate)
                Log.i(TAG, "GPU delegate enabled for this device")
            } catch (e: Exception) {
                // Устройство теоретически поддерживает GPU-делегат, но
                // создание всё равно может упасть на конкретной модели/
                // прошивке — не роняем инициализацию, откатываемся на CPU.
                Log.e(TAG, "GPU delegate creation failed, falling back to CPU", e)
                options.numThreads = minOf(4, Runtime.getRuntime().availableProcessors())
            }
        } else {
            Log.i(TAG, "GPU delegate not supported on this device, using CPU")
            options.numThreads = minOf(4, Runtime.getRuntime().availableProcessors())
        }

        interpreter = try {
            Interpreter(loadModelFile(context, modelAssetName), options)
        } catch (e: Throwable) {
            gpuDelegate?.close()
            gpuDelegate = null
            throw e
        }
        labels = context.assets.open(labelsAssetName)
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }

        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape() // [1, H, W, 3] NHWC
        inputHeight = inputShape[1]
        inputWidth = inputShape[2]

        inputIsQuantized = inputTensor.dataType() == DataType.UINT8 ||
                inputTensor.dataType() == DataType.INT8
        val inputQuantParams = inputTensor.quantizationParams()
        inputScale = if (inputIsQuantized) inputQuantParams.scale else 1f
        inputZeroPoint = if (inputIsQuantized) inputQuantParams.zeroPoint else 0

        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape() // [1, 4+nc, numAnchors] — features first
        numClasses = outputShape[1] - 4
        numAnchors = outputShape[2]

        outputIsQuantized = outputTensor.dataType() == DataType.UINT8 ||
                outputTensor.dataType() == DataType.INT8
        outputDataType = outputTensor.dataType()
        val outputQuantParams = outputTensor.quantizationParams()
        outputScale = if (outputIsQuantized) outputQuantParams.scale else 1f
        outputZeroPoint = if (outputIsQuantized) outputQuantParams.zeroPoint else 0

        Log.i(
            TAG,
            "YOLO model loaded: input=${inputWidth}x${inputHeight} " +
                    "inputQuantized=$inputIsQuantized outputQuantized=$outputIsQuantized " +
                    "numClasses=$numClasses numAnchors=$numAnchors labelsLoaded=${labels.size}"
        )

        if (numClasses != labels.size) {
            Log.e(
                TAG,
                "MISMATCH: model has $numClasses classes but labels.txt has ${labels.size} lines"
            )
        }

        letterboxBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        letterboxCanvas = Canvas(letterboxBitmap)

        val bytesPerChannel = if (inputIsQuantized) 1 else 4
        inputBuffer = ByteBuffer.allocateDirect(
            1 * inputWidth * inputHeight * 3 * bytesPerChannel
        ).apply { order(ByteOrder.nativeOrder()) }

        pixelsBuffer = IntArray(inputWidth * inputHeight)

        if (outputIsQuantized) {
            byteOutputBuffer = Array(1) { Array(4 + numClasses) { ByteArray(numAnchors) } }
            floatOutputBuffer = null
        } else {
            floatOutputBuffer = Array(1) { Array(4 + numClasses) { FloatArray(numAnchors) } }
            byteOutputBuffer = null
        }
    }

    private fun loadModelFile(context: Context, assetName: String): MappedByteBuffer {
        val fd = context.assets.openFd(assetName)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    // Замер времени САМОГО interpreter.run() (без препроцессинга/NMS) —
    // раз в LOG_INFERENCE_TIME_EVERY_N_CALLS вызовов, чтобы не спамить
    // лог на каждый кадр. Общая диагностика производительности инференса
    // (NNAPI/Hexagon-эксперимент был и убран — см. переписку; в
    // следующей итерации может пригодиться реальный путь через
    // Qualcomm CDSP, не NNAPI, — libaidenoiser.so подтверждён как
    // рабочий клиент этого пути на данном устройстве).
    private var detectCallCounter = 0

    /**
     * Находит все знаки на [bitmap] (весь кадр целиком). Возвращает
     * список после NMS, координаты — в системе координат [bitmap].
     */
    fun detect(bitmap: Bitmap): List<Detection> {

        // Обрезка нижней неинформативной части — ДО letterbox. Координаты
        // boxes в decodeOutput() ниже естественно остаются верными в системе
        // координат ИСХОДНОГО bitmap: обрезаем только снизу, начало координат
        // (0,0) не сдвигается, пересчёт не нужен.
        val usableHeight = (bitmap.height * (1f - bottomCropFraction))
            .toInt()
            .coerceIn(1, bitmap.height)
        val sourceBitmap = if (usableHeight < bitmap.height) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, usableHeight)
        } else {
            bitmap
        }

        val letterbox = letterboxTransform(sourceBitmap.width, sourceBitmap.height)

        letterboxCanvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
        letterboxCanvas.drawBitmap(
            sourceBitmap,
            null,
            RectF(letterbox.padX, letterbox.padY, letterbox.padX + letterbox.scaledW, letterbox.padY + letterbox.scaledH),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )

        fillInputBuffer()

        val inferenceStartNs = System.nanoTime()

        if (outputIsQuantized) {
            interpreter.run(inputBuffer, byteOutputBuffer)
        } else {
            interpreter.run(inputBuffer, floatOutputBuffer)
        }

        val inferenceMs = (System.nanoTime() - inferenceStartNs) / 1_000_000.0

        val decodeStartNs = System.nanoTime()
        val rawDetections = decodeOutput(letterbox, sourceBitmap.width, sourceBitmap.height)
        val decodeMs = (System.nanoTime() - decodeStartNs) / 1_000_000.0

        detectCallCounter++
        if (detectCallCounter % LOG_INFERENCE_TIME_EVERY_N_CALLS == 0) {
            Log.i(
                TAG,
                "Inference time: ${"%.1f".format(inferenceMs)}ms, decode: ${"%.1f".format(decodeMs)}ms, " +
                        "raw candidates: ${rawDetections.size} (call #$detectCallCounter)"
            )
        }

        return nonMaxSuppression(rawDetections)
    }

    private data class Letterbox(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val scaledW: Float,
        val scaledH: Float
    )

    private fun letterboxTransform(srcWidth: Int, srcHeight: Int): Letterbox {
        val scale = min(inputWidth.toFloat() / srcWidth, inputHeight.toFloat() / srcHeight)
        val scaledW = srcWidth * scale
        val scaledH = srcHeight * scale
        val padX = (inputWidth - scaledW) / 2f
        val padY = (inputHeight - scaledH) / 2f
        return Letterbox(scale, padX, padY, scaledW, scaledH)
    }

    private fun fillInputBuffer() {

        inputBuffer.rewind()

        letterboxBitmap.getPixels(pixelsBuffer, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        for (pixel in pixelsBuffer) {
            writeChannelValue((pixel shr 16) and 0xFF)
            writeChannelValue((pixel shr 8) and 0xFF)
            writeChannelValue(pixel and 0xFF)
        }

        inputBuffer.rewind()
    }

    private fun writeChannelValue(value0to255: Int) {
        if (inputIsQuantized) {
            inputBuffer.put(quantize(value0to255 / 255f))
        } else {
            inputBuffer.putFloat(value0to255 / 255f)
        }
    }

    private fun quantize(normalizedValue: Float): Byte {
        val q = (normalizedValue / inputScale) + inputZeroPoint
        return q.toInt().coerceIn(-128, 255).toByte()
    }

    private fun dequantizeOutput(raw: Byte): Float {
        val rawInt = if (outputDataType == DataType.UINT8) raw.toInt() and 0xFF else raw.toInt()
        return (rawInt - outputZeroPoint) * outputScale
    }

    private fun decodeOutput(
        letterbox: Letterbox,
        origWidth: Int,
        origHeight: Int
    ): List<Detection> {

        val results = mutableListOf<Detection>()

        fun value(feature: Int, anchor: Int): Float {
            return if (outputIsQuantized) dequantizeOutput(byteOutputBuffer!![0][feature][anchor])
            else floatOutputBuffer!![0][feature][anchor]
        }

        // Лучший класс для каждого анкора считаем ПОСЛЕДОВАТЕЛЬНЫМ проходом по строкам
        // [признак][анкор]. Раньше для каждого анкора читались numClasses разных строк по 8400 float
        // (кэш-неудобный доступ, при 83 классах заметно дороже). Результат тот же: при равных скорах
        // побеждает класс с меньшим индексом.
        if (bestScoreBuf.size != numAnchors) {
            bestScoreBuf = FloatArray(numAnchors)
            bestClassBuf = IntArray(numAnchors)
        }
        java.util.Arrays.fill(bestScoreBuf, -1f)
        java.util.Arrays.fill(bestClassBuf, -1)

        for (c in 0 until numClasses) {
            if (outputIsQuantized) {
                val row = byteOutputBuffer!![0][4 + c]
                for (a in 0 until numAnchors) {
                    val score = dequantizeOutput(row[a])
                    if (score > bestScoreBuf[a]) {
                        bestScoreBuf[a] = score
                        bestClassBuf[a] = c
                    }
                }
            } else {
                val row = floatOutputBuffer!![0][4 + c]
                for (a in 0 until numAnchors) {
                    val score = row[a]
                    if (score > bestScoreBuf[a]) {
                        bestScoreBuf[a] = score
                        bestClassBuf[a] = c
                    }
                }
            }
        }

        for (a in 0 until numAnchors) {

            val bestClass = bestClassBuf[a]
            val bestScore = bestScoreBuf[a]

            if (bestScore < confidenceThreshold || bestClass < 0 || bestClass >= labels.size) continue

            // Координаты нормализованы (0-1) — домножаем на размер входа.
            val cx = value(0, a) * inputWidth
            val cy = value(1, a) * inputHeight
            val w = value(2, a) * inputWidth
            val h = value(3, a) * inputHeight

            val x1 = ((cx - w / 2f) - letterbox.padX) / letterbox.scale
            val y1 = ((cy - h / 2f) - letterbox.padY) / letterbox.scale
            val x2 = ((cx + w / 2f) - letterbox.padX) / letterbox.scale
            val y2 = ((cy + h / 2f) - letterbox.padY) / letterbox.scale

            val box = RectF(
                x1.coerceIn(0f, origWidth.toFloat()),
                y1.coerceIn(0f, origHeight.toFloat()),
                x2.coerceIn(0f, origWidth.toFloat()),
                y2.coerceIn(0f, origHeight.toFloat())
            )

            if (box.width() <= 0 || box.height() <= 0) continue

            results.add(Detection(labels[bestClass], bestScore, box))
        }

        return results
    }

    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {

        val result = mutableListOf<Detection>()
        val byClass = detections.groupBy { it.label }

        for ((_, group) in byClass) {
            val sorted = group.sortedByDescending { it.confidence }.toMutableList()

            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                result.add(best)
                sorted.removeAll { candidate -> iou(best.box, candidate.box) > iouThreshold }
            }
        }

        return result
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

    fun close() {
        interpreter.close()
        gpuDelegate?.close()
        gpuDelegate = null
        letterboxBitmap.recycle()
    }
}