package com.roadeye.app.camera

import android.content.Context
import android.content.SharedPreferences

data class FocusOption(val label: String, val diopters: Float)

/**
 * Настройка фокуса, переживающая перезапуск (SharedPreferences).
 * Три позиции: бесконечность, 25 м, 50 м (диоптрии = 1 / дистанция в метрах).
 * Экспозицией управляет автоэкспозиция камеры, здесь она не настраивается.
 */
object CameraSettings {

    private const val PREFS_NAME = "roadeye_camera"

    val FOCUS_OPTIONS = listOf(
        FocusOption("∞", 0.0f),
        FocusOption("25 м", 0.04f),
        FocusOption("50 м", 0.02f)
    )

    // HYPOTHESIS: 25 м как значение по умолчанию не проверено на этом устройстве.
    private const val DEFAULT_FOCUS_INDEX = 1

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun focusIndex(): Int =
        (prefs?.getInt("focus_idx", DEFAULT_FOCUS_INDEX) ?: DEFAULT_FOCUS_INDEX)
            .coerceIn(0, FOCUS_OPTIONS.size - 1)

    fun setFocusIndex(index: Int) {
        prefs?.edit()?.putInt("focus_idx", index.coerceIn(0, FOCUS_OPTIONS.size - 1))?.apply()
    }
}