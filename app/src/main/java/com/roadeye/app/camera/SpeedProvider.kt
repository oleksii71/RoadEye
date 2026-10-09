package com.roadeye.app.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "RoadEyeSpeed"

// Обновления GPS не чаще, чем раз в это время — для скорости достаточно,
// экономит заряд по сравнению с непрерывным HIGH_ACCURACY-опросом
// (см. как MAPS.ME запрашивает — было в логе dumpsys location).
private const val UPDATE_INTERVAL_MS = 2000L
private const val UPDATE_MIN_DISTANCE_M = 0f

// Если последний fix старше этого — считаем скорость НЕНАДЁЖНОЙ, не
// используем её для отключения детектора (fail-open: лучше лишний раз
// детектировать, чем пропустить знак из-за устаревших данных о скорости).
// GPS на этом устройстве подтверждён рабочим только как HYPOTHESIS —
// в логах dumpsys location видны редкие единичные fix'ы, не стабильный
// непрерывный поток (см. CONTEXT_FOR_NEW_CHAT.md).
private const val FIX_FRESHNESS_MS = 10_000L

/**
 * Источник скорости движения через LocationManager (GPS_PROVIDER,
 * БЕЗ Google Play Services — Fused Location недоступен на LineageOS без
 * GMS). Используется для:
 *  1. Отключения YOLO-детекции при скорости < 5 км/ч (экономия заряда,
 *     см. Camera2Controller.SPEED_THRESHOLD_KMH).
 *  2. Координаты для видеорегистратора (оверлей вшитого в кадр текста —
 *     см. DashcamGlCompositor/TelemetrySnapshot) — ДОБАВЛЕНО, было
 *     запланировано изначально ("один и тот же провайдер закроет обе
 *     потребности").
 *
 * FAIL-OPEN ПО ДИЗАЙНУ (для скорости/gating): если разрешение не выдано,
 * GPS выключен в системе, или последний fix устарел (см.
 * FIX_FRESHNESS_MS) — hasReliableSpeed() возвращает false, и вызывающий
 * код (Camera2Controller) трактует это как "скорость неизвестна —
 * детекцию НЕ отключать". ACCESS_FINE_LOCATION НЕ блокирует запуск
 * приложения (в отличие от CAMERA/SYSTEM_ALERT_WINDOW) — без него
 * теряется только экономия заряда по скорости, детекция продолжает
 * работать всегда.
 *
 * КООРДИНАТЫ (для видеорегистратора) — ОТДЕЛЬНОЕ отслеживание от
 * скорости, НЕ переиспользует lastFixAtMs/hasReliableSpeed(). Раньше
 * onLocation() полностью игнорировал fix без поля speed (см.
 * Location.hasSpeed() ниже) — это верно для gating по скорости, но
 * неверно для координат: fix с валидными lat/lon, но без speed, всё
 * равно должен обновить координаты. Если бы координаты делили
 * lastFixAtMs со скоростью, hasReliableSpeed() могла бы вернуть true
 * на основании "свежего" fix'а без speed, реально означающего
 * УСТАРЕВШУЮ скорость — это сломало бы уже подтверждённую логику
 * ограничения детекции. Поэтому — независимые поля и своя свежесть.
 */
class SpeedProvider(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    private var lastSpeedKmh = 0f

    @Volatile
    private var lastFixAtMs = 0L

    @Volatile
    private var lastLatitude: Double? = null

    @Volatile
    private var lastLongitude: Double? = null

    @Volatile
    private var lastLocationFixAtMs = 0L

    @Volatile
    private var started = false

    private val listener = LocationListener { location -> onLocation(location) }

    @SuppressLint("MissingPermission") // разрешение проверяется явно ниже перед вызовом
    fun start() {
        if (started) return

        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "ACCESS_FINE_LOCATION не выдано — gating по скорости отключён, детектор работает всегда")
            return
        }

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.i(TAG, "GPS provider выключен в системных настройках — gating по скорости отключён")
            return
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL_MS,
                UPDATE_MIN_DISTANCE_M,
                listener
            )
            started = true
            Log.d(TAG, "GPS speed updates requested")
        } catch (e: Exception) {
            Log.e(TAG, "requestLocationUpdates failed", e)
        }
    }

    fun release() {
        if (!started) return
        try {
            locationManager.removeUpdates(listener)
        } catch (e: Exception) {
            Log.e(TAG, "removeUpdates failed", e)
        }
        started = false
    }

    private fun onLocation(location: Location) {
        // Координаты — обновляются на КАЖДОМ fix'е, независимо от
        // наличия поля speed (см. комментарий класса про раздельное
        // отслеживание).
        lastLatitude = location.latitude
        lastLongitude = location.longitude
        lastLocationFixAtMs = System.currentTimeMillis()

        // Location.hasSpeed() может быть false у некоторых провайдеров/
        // устройств (не все GPS HAL репортуют скорость напрямую в fix'е) —
        // тогда fix для gating не используем, ждём следующий. ПОВЕДЕНИЕ
        // НЕ ИЗМЕНЕНО относительно исходного файла.
        if (!location.hasSpeed()) {
            Log.d(TAG, "Location update без поля speed — координаты обновлены, скорость игнорируется (как раньше)")
            return
        }
        lastSpeedKmh = location.speed * 3.6f
        lastFixAtMs = System.currentTimeMillis()
        Log.d(TAG, "Speed update: $lastSpeedKmh km/h")
    }

    /** Скорость в км/ч по последнему fix'у — использовать вместе с hasReliableSpeed(). */
    fun currentSpeedKmh(): Float = lastSpeedKmh

    /** false — данным о скорости доверять нельзя (нет fix'а или он устарел). */
    fun hasReliableSpeed(): Boolean {
        if (lastFixAtMs == 0L) return false
        return System.currentTimeMillis() - lastFixAtMs <= FIX_FRESHNESS_MS
    }

    /** Широта последнего fix'а, или null, если фикса ещё не было. Для видеорегистратора. */
    fun currentLatitude(): Double? = lastLatitude

    /** Долгота последнего fix'а, или null, если фикса ещё не было. Для видеорегистратора. */
    fun currentLongitude(): Double? = lastLongitude

    /**
     * false — координатам доверять нельзя (нет fix'а или он устарел).
     * НЕЗАВИСИМО от hasReliableSpeed() — см. комментарий класса.
     */
    fun hasReliableLocation(): Boolean {
        if (lastLocationFixAtMs == 0L) return false
        return System.currentTimeMillis() - lastLocationFixAtMs <= FIX_FRESHNESS_MS
    }
}