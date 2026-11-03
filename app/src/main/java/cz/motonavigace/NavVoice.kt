package cz.motonavigace

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Hlas navigace: jazyk podle aplikace a hlas zvolený v nastavení. */
object NavVoice {
    /** Hodnota volby hlasu pro vypnuté hlasové pokyny. */
    const val OFF = "off"

    fun locale(): Locale = Locale.forLanguageTag(if (Settings.isEnglish()) "en-GB" else "cs-CZ")

    val enabled: Boolean get() = Settings.voice != OFF

    /** Hlasy v jazyce navigace, které fungují bez internetu, v pevném pořadí. */
    fun voices(tts: TextToSpeech): List<Voice> {
        val lang = locale().language
        return runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == lang && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            // Nejdřív hlasy pro českou (britskou) variantu jazyka, pak ostatní
            .sortedWith(compareBy<Voice> { it.locale.country != locale().country }.thenBy { it.name })
    }

    /**
     * Nastaví jazyk a zvolený hlas. Vrací false, když hlas pro jazyk navigace v telefonu chybí
     * (pak je potřeba stáhnout hlasová data).
     */
    fun apply(tts: TextToSpeech): Boolean {
        val result = tts.setLanguage(locale())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false
        val name = Settings.voice
        if (name.isNotEmpty() && name != OFF) voices(tts).firstOrNull { it.name == name }?.let { tts.voice = it }
        return true
    }
}
