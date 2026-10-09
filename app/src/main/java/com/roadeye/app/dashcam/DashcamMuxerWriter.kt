package com.roadeye.app.dashcam

import android.media.MediaCodec
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "RoadEyeDashcam"

// Ожидание формата нового поколения кодека: 30 попыток по 100 мс = 3 сек.
private const val FORMAT_WAIT_STEP_MS = 100L
private const val FORMAT_WAIT_MAX_ATTEMPTS = 30

/**
 * Одна активная запись события (удар / двойной тап / ручная кнопка):
 * берёт pre-roll снимок из DashcamRingBuffer, затем подписывается на
 * живой поток сэмплов до набора targetDurationUs, пишет всё в один
 * .mp4 через MediaMuxer. Каждый экземпляр — одна запись, не переиспользуется.
 *
 * Собственный HandlerThread: MediaMuxer.writeSampleData() блокирующий,
 * не должен идти ни на encoderThread (затормозит энкодер), ни на UI-потоке.
 */
class DashcamMuxerWriter(
    private val ringBuffer: DashcamRingBuffer,
    private val outputFile: File,
    private val targetDurationUs: Long,
    // false — начинать ТОЛЬКО с живых сэмплов, без снимка кольцевого
    // буфера. Нужно для сегментов ЦИКЛИЧЕСКОЙ записи от кнопки, кроме
    // первого — иначе каждый следующий сегмент дублировал бы последние
    // 15 сек предыдущего (снимок буфера смотрит на PRE_ROLL_MS назад
    // от текущего момента, а не от начала ЭТОЙ конкретной записи).
    private val usePreRoll: Boolean = true,
    private val onFinished: (file: File, success: Boolean) -> Unit
) {
    private val thread = HandlerThread("DashcamMuxerThread").apply { start() }
    private val handler = Handler(thread.looper)

    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var firstSampleTimeUs = -1L
    private var writtenDurationUs = 0L
    @Volatile private var finished = false

    // Поколение кодека, формат которого записан в ЭТОТ файл (см.
    // DashcamRingBuffer). Когда приходит кадр другого поколения — ориентация
    // сменилась, энкодер пересоздан с другим разрешением — файл закрывается
    // на этой границе: MP4 не может сменить размер кадра на середине.
    private var myGeneration = -1

    // Для сегментов БЕЗ pre-roll (usePreRoll=false) — ждём первый keyframe
    // в живом потоке, прежде чем начинать писать. Без этого файл мог бы
    // начаться с произвольного не-опорного кадра (максимум ~1 сек потери
    // на старте сегмента, т.к. KEYFRAME_INTERVAL_SEC=1) — для одного
    // события это было бы редким глюком, но при цепочке 5-минутных
    // сегментов подряд это происходило бы на КАЖДОМ стыке.
    private var awaitingKeyframe = !usePreRoll

    private val liveTap: (EncodedSample) -> Unit = { onLiveSample(it) }

    fun start() {
        handler.post { beginWhenFormatReady(attempt = 0) }
    }

    /**
     * Формат нового поколения приходит от энкодера чуть позже его старта
     * (после смены ориентации буфер очищен, формат ещё null). Раньше здесь
     * был немедленный отказ — для цепочки сегментов это означало бы
     * мгновенный перезапуск в цикле на каждом стыке. Теперь ждём до
     * FORMAT_WAIT_MAX_ATTEMPTS * FORMAT_WAIT_STEP_MS.
     */
    private fun beginWhenFormatReady(attempt: Int) {
        if (finished) return

        val formatAndGeneration = ringBuffer.currentFormatAndGeneration()
        if (formatAndGeneration == null) {
            if (attempt >= FORMAT_WAIT_MAX_ATTEMPTS) {
                Log.e(TAG, "Codec format not available after ${attempt * FORMAT_WAIT_STEP_MS}ms, dropping dashcam clip")
                finish(success = false)
            } else {
                handler.postDelayed({ beginWhenFormatReady(attempt + 1) }, FORMAT_WAIT_STEP_MS)
            }
            return
        }

        val (format, generation) = formatAndGeneration
        myGeneration = generation

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        videoTrackIndex = muxer!!.addTrack(format)
        muxer!!.start()
        muxerStarted = true

        if (usePreRoll) {
            // Фильтр по поколению — страховка на случай смены между чтением
            // формата выше и снимком буфера.
            ringBuffer.snapshot().filter { it.generation == myGeneration }.forEach { writeSample(it) }
        }

        if (writtenDurationUs >= targetDurationUs) {
            finish(success = true)
            return
        }

        ringBuffer.registerLiveTap(liveTap)
    }

    private fun onLiveSample(sample: EncodedSample) {
        handler.post {
            if (finished) return@post

            if (sample.generation != myGeneration) {
                // Ориентация сменилась, энкодер пересоздан — закрываем файл
                // на этой границе (сохраняем то, что успели записать).
                Log.i(TAG, "Codec generation changed mid-clip, closing ${outputFile.name} (written=${writtenDurationUs / 1000}ms)")
                ringBuffer.unregisterLiveTap(liveTap)
                finish(success = writtenDurationUs > 0)
                return@post
            }

            if (awaitingKeyframe) {
                if (!sample.isKeyFrame) return@post
                awaitingKeyframe = false
            }

            writeSample(sample)
            if (writtenDurationUs >= targetDurationUs) {
                ringBuffer.unregisterLiveTap(liveTap)
                finish(success = true)
            }
        }
    }

    private fun writeSample(sample: EncodedSample) {
        if (firstSampleTimeUs < 0) firstSampleTimeUs = sample.presentationTimeUs
        val relativeTimeUs = sample.presentationTimeUs - firstSampleTimeUs
        writtenDurationUs = relativeTimeUs

        val buffer = ByteBuffer.wrap(sample.data)
        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.data.size, relativeTimeUs, sample.flags)
        }
        try {
            muxer?.writeSampleData(videoTrackIndex, buffer, info)
        } catch (e: Exception) {
            Log.e(TAG, "writeSampleData failed", e)
        }
    }

    private fun finish(success: Boolean) {
        finished = true
        try {
            if (muxerStarted) {
                muxer?.stop()
                muxer?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finishing dashcam muxer", e)
        }
        thread.quitSafely()
        onFinished(outputFile, success)
    }

    /** Досрочная остановка (например, сервис уничтожается раньше срока записи). */
    fun cancel() {
        handler.post {
            if (!finished) {
                ringBuffer.unregisterLiveTap(liveTap)
                finish(success = writtenDurationUs > 0)
            }
        }
    }
}