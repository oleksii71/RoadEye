package com.roadeye.app.dashcam

import android.media.MediaFormat
import java.util.ArrayDeque

/**
 * Кольцевой буфер закодированных H.264-сэмплов в RAM, глубина —
 * DashcamConfig.PRE_ROLL_MS. Потокобезопасен через synchronized —
 * писатель (DashcamEncoder, свой поток) и читатели (snapshot при
 * событии, live-подписчики) работают конкурентно.
 *
 * ПОКОЛЕНИЯ: энкодер пересоздаётся при смене ориентации (другое
 * разрешение), а один MP4-файл не может сменить формат на середине.
 * [beginNewGeneration] очищает буфер и формат и увеличивает номер;
 * сэмплы и формат от энкодера СТАРОГО поколения после этого молча
 * отбрасываются. Побочный эффект: pre-roll до момента смены
 * ориентации теряется (он в другом формате и в новый клип не годится).
 *
 * ВАЖНО: snapshot() не гарантирует РОВНО 15 сек — возвращает срез,
 * обрезанный до ближайшего keyframe НЕ ПОЗЖЕ начала окна, т.е. клип
 * может начинаться чуть раньше 15 сек назад (до KEYFRAME_INTERVAL_SEC),
 * но ВСЕГДА декодируется с первого кадра. Это осознанный компромисс —
 * лучше чуть длиннее, чем без опорного кадра.
 */
class DashcamRingBuffer {

    private val lock = Any()
    private val samples = ArrayDeque<EncodedSample>()
    private var codecFormat: MediaFormat? = null
    private var generation = 0
    private val liveTaps = mutableListOf<(EncodedSample) -> Unit>()

    // запас сверх PRE_ROLL_MS, чтобы гарантированно не потерять keyframe,
    // предшествующий началу окна
    private val retentionUs: Long
        get() = (DashcamConfig.PRE_ROLL_MS + DashcamConfig.KEYFRAME_INTERVAL_SEC * 1000L) * 1000

    fun currentGeneration(): Int = synchronized(lock) { generation }

    /**
     * Начинает новое поколение: очищает сэмплы и формат, возвращает новый
     * номер — его нужно передать новому DashcamEncoder.
     */
    fun beginNewGeneration(): Int = synchronized(lock) {
        generation++
        samples.clear()
        codecFormat = null
        generation
    }

    /** Вызывается DashcamEncoder один раз при INFO_OUTPUT_FORMAT_CHANGED. */
    fun setFormat(format: MediaFormat, encoderGeneration: Int) = synchronized(lock) {
        if (encoderGeneration == generation) codecFormat = format
    }

    /**
     * Формат И поколение одним атомарным чтением — писатель должен взять
     * их согласованно, иначе между двумя вызовами может произойти смена.
     * null — формат текущего поколения ещё не пришёл от энкодера.
     */
    fun currentFormatAndGeneration(): Pair<MediaFormat, Int>? = synchronized(lock) {
        codecFormat?.let { it to generation }
    }

    fun push(sample: EncodedSample) {
        val tapsSnapshot: List<(EncodedSample) -> Unit>
        synchronized(lock) {
            // Кадр от энкодера устаревшего поколения (успел выйти уже
            // после смены) — отбрасываем, иначе он попал бы в файл с
            // форматом нового поколения.
            if (sample.generation != generation) return

            samples.addLast(sample)
            val cutoff = sample.presentationTimeUs - retentionUs
            while (samples.isNotEmpty() && samples.first().presentationTimeUs < cutoff) {
                samples.removeFirst()
            }
            tapsSnapshot = liveTaps.toList()
        }
        // уведомляем подписчиков ВНЕ synchronized-блока — не держим лок
        // на время вызова чужого кода (муксер пишет на диск)
        tapsSnapshot.forEach { it(sample) }
    }

    /** Снимок последних PRE_ROLL_MS. Копия — не влияет на дальнейшую запись. */
    fun snapshot(): List<EncodedSample> = synchronized(lock) {
        if (samples.isEmpty()) return emptyList()
        val windowStart = samples.last().presentationTimeUs - DashcamConfig.PRE_ROLL_MS * 1000
        val list = samples.toList()
        val startIdx = list.indexOfLast { it.isKeyFrame && it.presentationTimeUs <= windowStart }
            .let { if (it >= 0) it else list.indexOfFirst { s -> s.isKeyFrame } }
        if (startIdx < 0) emptyList() else list.subList(startIdx, list.size)
    }

    /** Подписка на новые сэмплы после момента вызова (для продолжения записи после события). */
    fun registerLiveTap(tap: (EncodedSample) -> Unit) = synchronized(lock) { liveTaps.add(tap) }

    fun unregisterLiveTap(tap: (EncodedSample) -> Unit) = synchronized(lock) { liveTaps.remove(tap) }
}