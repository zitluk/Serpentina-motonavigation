package cz.motonavigace

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat

enum class AppTheme(val id: Int, @StringRes val label: Int, val nightMode: Int) {
    SYSTEM(0, R.string.theme_system, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(1, R.string.theme_light, AppCompatDelegate.MODE_NIGHT_NO),
    DARK(2, R.string.theme_dark, AppCompatDelegate.MODE_NIGHT_YES),
}

enum class AppLanguage(val id: Int, @StringRes val label: Int, val tag: String) {
    SYSTEM(0, R.string.language_system, ""),
    CZECH(1, R.string.language_czech, "cs"),
    ENGLISH(2, R.string.language_english, "en"),
}

/**
 * Typ motocyklu určuje výchozí volby trasy a odhad cestovní rychlosti.
 * @param maxKmh nejvyšší rychlost, se kterou počítá odhad času jízdy
 */
enum class BikeType(
    val id: Int,
    @StringRes val label: Int,
    @StringRes val description: Int,
    val routeType: RouteType,
    val avoidMotorways: Boolean,
    val avoidUnpaved: Boolean,
    val maxKmh: Double,
) {
    SPORT(0, R.string.bike_sport, R.string.bike_sport_desc, RouteType.CURVY, false, true, 130.0),
    TOURING(1, R.string.bike_touring, R.string.bike_touring_desc, RouteType.FASTEST, false, true, 130.0),
    NAKED(2, R.string.bike_naked, R.string.bike_naked_desc, RouteType.CURVY, false, true, 120.0),
    ADVENTURE(3, R.string.bike_adventure, R.string.bike_adventure_desc, RouteType.CURVY, false, false, 120.0),
    CRUISER(4, R.string.bike_cruiser, R.string.bike_cruiser_desc, RouteType.ECO, false, true, 110.0),
    SCOOTER(5, R.string.bike_scooter, R.string.bike_scooter_desc, RouteType.SHORTEST, true, true, 90.0),
}

/** Uživatelské nastavení aplikace. */
object Settings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(theme.nightMode)
    }

    var theme: AppTheme
        get() = AppTheme.entries.firstOrNull { it.id == prefs.getInt("theme", 0) } ?: AppTheme.SYSTEM
        set(value) {
            prefs.edit { putInt("theme", value.id) }
            AppCompatDelegate.setDefaultNightMode(value.nightMode)
        }

    var bike: BikeType
        get() = BikeType.entries.firstOrNull { it.id == prefs.getInt("bike", BikeType.TOURING.id) } ?: BikeType.TOURING
        set(value) = prefs.edit { putInt("bike", value.id) }

    /** Jazyk si pamatuje AppCompat (autoStoreLocales v manifestu), tady ho jen čteme a nastavujeme. */
    var language: AppLanguage
        get() {
            val tag = AppCompatDelegate.getApplicationLocales().get(0)?.language.orEmpty()
            return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
        }
        set(value) = AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(value.tag))

    /**
     * Texty mimo resources (hlasové pokyny, typy míst, název jízdy) mají českou a anglickou verzi.
     * Výchozí resources jsou české, takže angličtina jen tam, kde se použije values-en.
     */
    fun isEnglish(): Boolean {
        val locale = AppCompatDelegate.getApplicationLocales().get(0)
            ?: android.content.res.Resources.getSystem().configuration.locales[0]
        return locale.language == "en"
    }
}
