package com.roadeye.app.dashcam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

private const val TAG = "RoadEyeDashcam"

/**
 * Аппаратный H.264-энкодер в Surface-режиме (MediaCodec.createInputSurface()).
 * В inputSurface пишет GL-компоновщик (DashcamGlCompositor), а не камера
 * напрямую — компоновщик рисует кадр камеры с поворотом и оверлеем.
 *
 * ОДИН ЭКЗЕМПЛЯР = ОДНО РАЗРЕШЕНИЕ, одна жизнь: после stop() не
 * переиспользуется (свой HandlerThread завершается). При смене ориентации
 * планшета DashcamController создаёт НОВЫЙ экземпляр с обменянными
 * width/height (1280x720 <-> 720x1280) и новым [generation], старый
 * останавливает. Камеру это не затрагивает.
 */
class DashcamEncoder(
    private val ringBuffer: DashcamRingBuffer,
    private val width: Int,
    private val height: Int,
    private val generation: Int
) {

    private lateinit var codec: MediaCodec
    lateinit var inputSurface: Surface
        private set

    private val encoderThread = HandlerThread("DashcamEncoderThread").apply { start() }
    private val encoderHandler = Handler(encoderThread.looper)

    @Volatile private var running = false

    fun start() {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, DashcamConfig.VIDEO_BITRATE_BPS)
            setInteger(MediaFormat.KEY_FRAME_RATE, DashcamConfig.VIDEO_FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DashcamConfig.KEYFRAME_INTERVAL_SEC)
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.setCallback(callback, encoderHandler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        running = true
        Log.i(TAG, "Dashcam encoder started: ${width}x${height} (generation=$generation)")
    }

    private val callback = object : MediaCodec.Callback() {

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // не используется — вход идёт через Surface от камеры, не через буферы
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            if (!running) return

            // BUFFER_FLAG_CODEC_CONFIG (SPS/PPS) НЕ пишем в кольцевой буфер
            // как обычный сэмпл — эти данные уже приходят целиком через
            // onOutputFormatChanged() -> ringBuffer.setFormat(), и
            // MediaMuxer.addTrack(format) берёт их оттуда. Повторная запись
            // того же самого как "кадра" — реальный баг, который был здесь
            // до этого фикса: на части энкодеров такие буферы приходят
            // периодически (не только один раз при старте), и со временем
            // накапливались как мусорные записи в потоке сэмплов, что
            // могло объяснять "первая запись читается, последующие — нет"
            // (баг зафиксирован пользователем на реальном устройстве).
            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                codec.releaseOutputBuffer(index, false)
                return
            }

            val outputBuffer = codec.getOutputBuffer(index)
            if (outputBuffer != null && info.size > 0) {
                val data = ByteArray(info.size)
                outputBuffer.position(info.offset)
                outputBuffer.limit(info.offset + info.size)
                outputBuffer.get(data)

                val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                ringBuffer.push(
                    EncodedSample(
                        data = data,
                        presentationTimeUs = info.presentationTimeUs,
                        isKeyFrame = isKeyFrame,
                        flags = info.flags,
                        generation = generation
                    )
                )
            }
            codec.releaseOutputBuffer(index, false)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Encoder output format: $format")
            ringBuffer.setFormat(format, generation)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Dashcam encoder error", e)
        }
    }

    fun stop() {
        running = false
        try {
            codec.stop()
            codec.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping dashcam encoder", e)
        }
        inputSurface.release()
        encoderThread.quitSafely()
    }
}