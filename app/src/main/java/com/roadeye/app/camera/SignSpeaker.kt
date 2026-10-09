package com.roadeye.app.camera

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

private const val TAG = "RoadEyeTTS"
private const val COOLDOWN_MS = 5000L // не повторять один и тот же знак чаще, чем раз в 5 сек

/**
 * Озвучивание распознанных знаков через системный TextToSpeech.
 *
 * Выбор русского голоса идёт НЕ через setLanguage(Locale) — некоторые
 * сторонние движки (например, RHVoice) не всегда корректно матчатся по
 * Locale, даже если голос реально установлен и работает (проверяется
 * через "Прослушать пример" в системных настройках). Вместо этого
 * перебираем tts.voices и выбираем русский голос явно через setVoice() —
 * надёжнее для не-Google движков.
 *
 * Состояние cooldown хранится только в памяти — ничего не пишется
 * на диск.
 */
class SignSpeaker(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    // label -> timestamp последнего озвучивания. Только in-memory.
    private val lastSpokenAt = HashMap<String, Long>()

    // Известный package name RHVoice — используется как явный fallback,
    // если системный default-движок не инициализируется (status=-1),
    // что наблюдалось на LineageOS этого устройства.
    private val knownRhvoicePackage = "com.github.olga_yakovleva.rhvoice.android"

    init {
        Log.i(TAG, "Installed TTS engines: ${listInstalledTtsEngines(context)}")

        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                onTtsInitialized()
            } else {
                Log.e(TAG, "Default-engine TextToSpeech init failed, status=$status")
                retryWithExplicitEngine(context)
            }
        }
    }

    private fun listInstalledTtsEngines(context: Context): List<String> {
        return try {
            val intent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            context.packageManager
                .queryIntentServices(intent, 0)
                .map { it.serviceInfo.packageName }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list TTS engines", e)
            emptyList()
        }
    }

    private fun retryWithExplicitEngine(context: Context) {

        val engines = listInstalledTtsEngines(context)
        val targetPackage = engines.firstOrNull { it == knownRhvoicePackage } ?: engines.firstOrNull()

        if (targetPackage == null) {
            Log.e(TAG, "No TTS engine package found at all on this device")
            return
        }

        Log.i(TAG, "Retrying TTS init with explicit engine package: $targetPackage")

        tts = TextToSpeech(
            context.applicationContext,
            { status ->
                if (status == TextToSpeech.SUCCESS) {
                    onTtsInitialized()
                } else {
                    Log.e(TAG, "Explicit-engine TextToSpeech init also failed, status=$status")
                }
            },
            targetPackage
        )
    }

    private fun onTtsInitialized() {

        val engine = tts

        if (engine == null) {
            Log.e(TAG, "TTS init callback fired but tts instance is null")
            return
        }

        val voices: Set<Voice> = try {
            engine.voices ?: emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query engine.voices", e)
            emptySet()
        }

        Log.i(TAG, "Engine reports ${voices.size} voice(s) total")

        val russianVoices = voices.filter { it.locale.language == "ru" }

        Log.i(
            TAG,
            "Russian voices found: ${russianVoices.size} " +
                    "[${russianVoices.joinToString { it.name }}]"
        )

        val chosenVoice = russianVoices.firstOrNull { !it.isNetworkConnectionRequired }
            ?: russianVoices.firstOrNull()

        if (chosenVoice != null) {
            val setVoiceResult = engine.setVoice(chosenVoice)
            ready = setVoiceResult == TextToSpeech.SUCCESS
            Log.i(
                TAG,
                "setVoice('${chosenVoice.name}') result=$setVoiceResult, ready=$ready"
            )
        } else {
            // Голоса через voices не нашли — fallback на старый способ
            // через Locale, вдруг движок его всё же поддерживает.
            Log.e(TAG, "No Russian voice found in engine.voices, falling back to setLanguage()")

            val langResult = engine.setLanguage(Locale.forLanguageTag("ru-RU"))
            ready = langResult != TextToSpeech.LANG_MISSING_DATA &&
                    langResult != TextToSpeech.LANG_NOT_SUPPORTED

            Log.i(TAG, "setLanguage(ru-RU) result=$langResult, ready=$ready")
        }

        if (!ready) {
            Log.e(TAG, "TTS still not ready — проверьте, что голос реально скачан на устройстве")
        }
    }

    /**
     * Озвучивает [phrase], если по [key] (стабильный код знака) не было
     * озвучивания последние COOLDOWN_MS мс. Безопасно вызывать на каждый
     * подтверждённый кадр — дедупликация происходит внутри.
     */
    fun speakIfNeeded(key: String, phrase: String) {

        if (!ready) {
            Log.w(TAG, "speakIfNeeded('$phrase') skipped — TTS not ready")
            return
        }

        val now = System.currentTimeMillis()
        val last = lastSpokenAt[key]

        if (last != null && now - last < COOLDOWN_MS) {
            Log.d(TAG, "speakIfNeeded('$phrase') skipped — cooldown active")
            return
        }

        lastSpokenAt[key] = now

        val speakResult = tts?.speak(
            phrase,
            TextToSpeech.QUEUE_ADD,
            null,
            "sign_$key${'_'}$now"
        )

        Log.i(TAG, "speak('$phrase') called, result=$speakResult")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}