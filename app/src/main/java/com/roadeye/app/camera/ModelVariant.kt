package com.roadeye.app.camera

import android.content.Context

/**
 * Выбор модели распознавания. В панели настроек отображается как "Tflite48" и "Tflite83".
 * По умолчанию Tflite48 — проверенная модель; без выбора поведение приложения не меняется.
 */
enum class ModelVariant(
    val title: String,
    val classesText: String,
    val modelAsset: String,
    val labelsAsset: String,
    // Пороги пока одинаковые; для Tflite83 их, возможно, придётся подобрать отдельно по логам RoadEyeML.
    val confidenceThreshold: Float,
    // Пауза между анализами = idleFactor * длительность анализа (но не меньше minDetectionGapMs).
    // 0 -> без ограничения (как раньше). Для Tflite83 стоит эксперимент 0.5; если дёрганье не уйдёт — причина не в загрузке.
    val detectionIdleFactor: Float,
    val minDetectionGapMs: Long
) {
    TFLITE48("Tflite48", "48 классов", "roadeye_yolo.tflite", "labels.txt", 0.5f, 0f, 0L),
    TFLITE83("Tflite83", "83 класса", "roadeye_yolo_83.tflite", "labels_83.txt", 0.5f, 0.5f, 200L);

    /** Русская фраза для озвучки с учётом набора классов выбранной модели. */
    fun phraseFor(code: String): String = when (this) {
        TFLITE48 -> SignLabelsRu.phraseFor(code)
        TFLITE83 -> SignLabelsRu83.phraseFor(code)
    }

    fun next(): ModelVariant = if (this == TFLITE48) TFLITE83 else TFLITE48

    companion object {
        private const val PREFS = "roadeye_settings"
        private const val KEY_VARIANT = "model_variant"

        fun load(context: Context): ModelVariant {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VARIANT, null)
            return values().firstOrNull { it.name == saved } ?: TFLITE48
        }

        fun save(context: Context, variant: ModelVariant) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_VARIANT, variant.name).apply()
        }
    }
}