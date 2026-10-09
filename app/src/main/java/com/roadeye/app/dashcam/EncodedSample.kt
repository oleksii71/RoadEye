package com.roadeye.app.dashcam

/**
 * Один закодированный H.264 access unit от MediaCodec.
 *
 * [generation] — номер "поколения" энкодера, выдавшего кадр. Энкодер
 * пересоздаётся при смене ориентации (1280x720 <-> 720x1280), а один
 * MP4-файл не может сменить разрешение на середине — кадры разных
 * поколений в одном файле смешивать нельзя. См. DashcamRingBuffer.
 *
 * ByteArray-поле сравнивается data-классом по ССЫЛКЕ (Kotlin/Java не
 * переопределяют equals() для массивов) — это осознанный выбор, не баг:
 * контентное сравнение больших массивов на каждый equals() было бы
 * дорогим и не нужно нигде в этом пайплайне.
 */
data class EncodedSample(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean,
    val flags: Int, // сырые MediaCodec.BufferInfo.flags — на случай доп. обработки
    val generation: Int = 0
)