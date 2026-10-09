package com.roadeye.app.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

/**
 * Утилиты конвертации YUV_420_888 (формат ImageReader) в Bitmap-кропы,
 * пригодные для передачи в TFLite-классификатор.
 */
object YuvUtils {

    /**
     * Собирает NV21-массив байт из Image в формате YUV_420_888.
     * Обрабатывает произвольные rowStride/pixelStride, т.к. они
     * различаются между производителями сенсоров.
     *
     * @param reuseBuffer если передан и имеет правильный размер —
     *   переиспользуется вместо новой аллокации (снижает частоту GC
     *   при вызове на каждом кадре с камеры). Если размер не совпадает
     *   (например, первый вызов) — выделяется новый и возвращается,
     *   вызывающий код должен сохранить именно возвращённую ссылку.
     */
    fun imageToNv21(image: Image, reuseBuffer: ByteArray? = null): ByteArray {

        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val requiredSize = width * height * 3 / 2
        val nv21 =
            if (reuseBuffer != null && reuseBuffer.size == requiredSize) reuseBuffer
            else ByteArray(requiredSize)

        // Y-плоскость — построчное копирование с учётом rowStride
        var pos = 0
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride

        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, pos, width)
            pos += width
        }

        // U/V-плоскости — чередуем VU (NV21) с учётом pixelStride/rowStride
        val chromaHeight = height / 2
        val chromaWidth = width / 2

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        for (row in 0 until chromaHeight) {
            var uCol = row * uRowStride
            var vCol = row * vRowStride

            for (col in 0 until chromaWidth) {
                nv21[pos++] = vBuffer.get(vCol)
                nv21[pos++] = uBuffer.get(uCol)

                uCol += uPixelStride
                vCol += vPixelStride
            }
        }

        return nv21
    }

    /**
     * Прямая конвертация всего NV21-кадра в Bitmap, БЕЗ промежуточного
     * JPEG-кодирования/декодирования (в отличие от cropToBitmap выше).
     * Значимо дешевле по CPU — JPEG-круг тратит циклы на сжатие,
     * которое тут же откатывается декодированием, чистая трата.
     *
     * Используется для превью на весь кадр (не для маленьких кропов —
     * там JPEG-путь через cropToBitmap всё ещё оправдан, если понадобится
     * где-то ещё).
     *
     * Формулы BT.601 full-range — те же, что в SignColorDetector.yuvToRgb,
     * но здесь применяются попиксельно на полном разрешении, а не
     * усреднённо по блоку.
     *
     * @param reusePixelBuffer переиспользуемый IntArray под пиксели —
     *   снижает частоту GC, как и с ByteArray в imageToNv21. Сам Bitmap
     *   при этом создаётся заново на каждый вызов (не переиспользуется!) —
     *   это осознанно: результат уходит в UI-поток через колбэк, и
     *   переиспользование одного mutable Bitmap между потоками без
     *   синхронизации создало бы гонку (запись из FrameAnalysisThread
     *   поверх кадра, который в этот момент ещё отрисовывается в UI).
     */
    fun nv21ToRgbBitmap(
        nv21: ByteArray,
        width: Int,
        height: Int,
        reusePixelBuffer: IntArray? = null
    ): Bitmap {

        val pixels =
            if (reusePixelBuffer != null && reusePixelBuffer.size == width * height) reusePixelBuffer
            else IntArray(width * height)

        val chromaRowStride = width // NV21: VU-плоскость, width байт на строку

        for (y in 0 until height) {

            val yRowStart = y * width
            val chromaRowStart = width * height + (y / 2) * chromaRowStride

            for (x in 0 until width) {

                val yValue = nv21[yRowStart + x].toInt() and 0xFF

                val chromaIndex = chromaRowStart + (x / 2) * 2
                val v = (nv21[chromaIndex].toInt() and 0xFF) - 128
                val u = (nv21[chromaIndex + 1].toInt() and 0xFF) - 128

                val r = (yValue + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (yValue - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                val b = (yValue + 1.772f * u).toInt().coerceIn(0, 255)

                pixels[yRowStart + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Вырезает регион [rect] из NV21-буфера и конвертирует в Bitmap
     * через промежуточный JPEG (стандартный, надёжный путь конвертации
     * YUV -> Bitmap на Android без сторонних библиотек).
     */
    fun cropToBitmap(
        nv21: ByteArray,
        imageWidth: Int,
        imageHeight: Int,
        rect: Rect
    ): Bitmap? {

        val safeRect = Rect(
            rect.left.coerceIn(0, imageWidth - 1),
            rect.top.coerceIn(0, imageHeight - 1),
            rect.right.coerceIn(1, imageWidth),
            rect.bottom.coerceIn(1, imageHeight)
        )

        if (safeRect.width() <= 0 || safeRect.height() <= 0) return null

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageWidth, imageHeight, null)
        val outputStream = ByteArrayOutputStream()

        val ok = yuvImage.compressToJpeg(safeRect, 90, outputStream)
        if (!ok) return null

        val jpegBytes = outputStream.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }
}