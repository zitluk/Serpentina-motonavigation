package cz.motonavigace

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat

enum class AppTheme(val id: Int, @StringRes val label: Int, val nightMode: Int) {
    SYSTEM(0, R.string.theme_system, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(1, R.string.theme_light, AppCompatDelegate.MODE_NIGHT_NO),
    DARK(2, R.string.theme_dark, AppCompatDelegate.MODE_NIGHT_YES),
}

/** Kdy kreslit mapu v nočních barvách. */
enum class NightMap(val id: Int, @StringRes val label: Int) {
    AUTO(0, R.string.night_map_auto),
    APP_THEME(1, R.string.night_map_theme),
    ALWAYS(2, R.string.night_map_always),
    NEVER(3, R.string.night_map_never),
}

/**
 * Druh bodů zájmu na mapě = vrstva stylu MOTORIDER. [default] = ukazuje se, dokud uživatel
 * výběr nezmění (pro motorkáře užitečné; parkoviště, obchody, MHD… mapu jen zahlcují).
 */
enum class MapPoi(val layer: String, @StringRes val label: Int, val default: Boolean) {
    FUEL("fuel", R.string.poi_fuel, true),
    /** Radary nejsou vrstva stylu mapy – aplikace je kreslí sama a hlásí za navigace. */
    RADAR("speed_camera", R.string.poi_radar, true),
    VIEW("view", R.string.poi_view, true),
    MOUNTAIN_PASS("mountain_pass", R.string.poi_mountain_pass, true),
    MOUNTAIN_PEAK("mountain_peak", R.string.poi_mountain_peak, true),
    ATTRACTION("attraction", R.string.poi_attraction, true),
    ACCOMMODATION("accommodation", R.string.poi_accommodation, true),
    EMERGENCY("emergency", R.string.poi_emergency, true),
    FOOD("food", R.string.poi_food, false),
    PARKING("parking", R.string.poi_parking, false),
    CHARGING("charging_station", R.string.poi_charging, false),
    SHOP("shop", R.string.poi_shop, false),
    MONEY("money", R.string.poi_money, false),
    ENTERTAINMENT("entertainment", R.string.poi_entertainment, false),
    PUBLIC_TRANSPORT("public_transport", R.string.poi_public_transport, false),
    MISC("misc", R.string.poi_misc, true),
}

enum class AppLanguage(val id: Int, @StringRes val label: Int, val tag: String) {
    SYSTEM(0, R.string.language_system, ""),
    CZECH(1, R.string.language_czech, "cs"),
    ENGLISH(2, R.string.language_english, "en"),
}

/**
 * Typ cest, po kterých jezdec nejčastěji jezdí – určuje výchozí volby trasy a odhad cestovní rychlosti.
 * Id odpovídají dřívějším typům motocyklu, uložené nastavení tak zůstane (dřívější naked = id 2 → [CURVY]).
 * @param maxKmh nejvyšší rychlost, se kterou počítá odhad času jízdy
 */
enum class RoadType(
    val id: Int,
    @StringRes val label: Int,
    @StringRes val description: Int,
    val routeType: RouteType,
    val avoidMotorways: Boolean,
    val avoidUnpaved: Boolean,
    val maxKmh: Double,
) {
    CURVY(0, R.string.road_curvy, R.string.road_curvy_desc, RouteType.CURVY, false, true, 130.0),
    LONG(1, R.string.road_long, R.string.road_long_desc, RouteType.FASTEST, false, true, 130.0),
    GRAVEL(3, R.string.road_gravel, R.string.road_gravel_desc, RouteType.CURVY, false, false, 120.0),
    RELAXED(4, R.string.road_relaxed, R.string.road_relaxed_desc, RouteType.ECO, false, true, 110.0),
    LOCAL(5, R.string.road_local, R.string.road_local_desc, RouteType.SHORTEST, true, true, 90.0),
    ;

    companion object {
        private const val OLD_NAKED = 2

        fun byId(id: Int): RoadType =
            if (id == OLD_NAKED) CURVY else entries.firstOrNull { it.id == id } ?: LONG
    }
}

class Manufacturer(val name: String, val launcherAlias: String, @StyleRes val splashTheme: Int)

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

    /** Záznam jízdy se spustí sám, jakmile motorka jede aspoň [Recording.AUTO_START_KMH]. */
    /** Automatický start a dotaz na záznam se vylučují – zapnutí jednoho vypne druhé. */
    var autoStartRecording: Boolean
        get() = prefs.getBoolean("auto_start_recording", false)
        set(value) = prefs.edit {
            putBoolean("auto_start_recording", value)
            if (value) putBoolean("ask_to_record", false)
        }

    /** Při jízdě bez navigace a bez automatického záznamu se zeptat, jestli jízdu zaznamenat. */
    var askToRecord: Boolean
        get() = !autoStartRecording && prefs.getBoolean("ask_to_record", true)
        set(value) = prefs.edit {
            putBoolean("ask_to_record", value)
            if (value) putBoolean("auto_start_recording", false)
        }

    /** Po ukončení navigace se záznam jízdy zastaví a uloží. */
    var stopRecordingAfterNavigation: Boolean
        get() = prefs.getBoolean("stop_recording_after_nav", false)
        set(value) = prefs.edit { putBoolean("stop_recording_after_nav", value) }

    /** Hlas navigace (Voice.name), prázdný = výchozí hlas, [NavVoice.OFF] = bez hlasu. */
    var voice: String
        get() = prefs.getString("voice", "").orEmpty()
        set(value) = prefs.edit { putString("voice", value) }

    var nightMap: NightMap
        get() = NightMap.entries.firstOrNull { it.id == prefs.getInt("night_map", NightMap.AUTO.id) } ?: NightMap.AUTO
        set(value) = prefs.edit { putInt("night_map", value.id) }

    var roadType: RoadType
        get() = RoadType.byId(prefs.getInt("bike", RoadType.LONG.id))
        set(value) = prefs.edit { putInt("bike", value.id) }

    var markerShape: MarkerShape
        get() = MarkerShape.byId(prefs.getInt("marker_shape", MarkerShape.ARROW.id))
        set(value) = prefs.edit { putInt("marker_shape", value.id) }

    var markerColor: Int
        get() = prefs.getInt("marker_color", MARKER_COLORS[0])
        set(value) = prefs.edit { putInt("marker_color", value) }

    /** Druhy bodů zájmu, jejichž ikony mapa ukazuje (vrstvy stylu MOTORIDER). */
    var poiLayers: Set<String>
        get() {
            val stored = prefs.getStringSet("poi_layers", null)?.toSet()
                ?: return MapPoi.entries.filter { it.default }.map { it.layer }.toSet()
            // Radary dřív upozorňovaly vždy – kdo si POI vybral před jejich přidáním, má je dál zapnuté
            if (!prefs.getBoolean("poi_radar_added", false)) {
                val withRadar = stored + MapPoi.RADAR.layer
                prefs.edit { putStringSet("poi_layers", withRadar); putBoolean("poi_radar_added", true) }
                return withRadar
            }
            return stored
        }
        set(value) = prefs.edit { putStringSet("poi_layers", value); putBoolean("poi_radar_added", true) }

    /** Radary na mapě a upozornění na ně za navigace. */
    val showRadars: Boolean get() = MapPoi.RADAR.layer in poiLayers

    /** Vypnuto = aplikace stahuje a odesílá jen přes Wi-Fi (nebo jinou neměřenou síť). */
    var mobileData: Boolean
        get() = prefs.getBoolean("mobile_data", true)
        set(value) = prefs.edit { putBoolean("mobile_data", value) }

    /** Smí aplikace teď na internet – při vypnutých mobilních datech jen mimo měřenou síť. */
    fun canUseNetwork(context: Context): Boolean =
        mobileData || context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false

    /** Výrobce motocyklu, prázdný = nevybráno. */
    var manufacturer: String
        get() = prefs.getString("manufacturer", "").orEmpty()
        set(value) = prefs.edit { putString("manufacturer", value) }

    /**
     * Výrobci a jejich ikona (activity-alias v manifestu). Ikony jsou zástupné – název značky,
     * ne logo; oficiální logo si lze vložit podle README.
     */
    val MANUFACTURERS = listOf(
        Manufacturer("Aprilia", ".LauncherBrandAprilia", R.style.Theme_Motonavigace_Starting_Aprilia),
        Manufacturer("Benelli", ".LauncherBrandBenelli", R.style.Theme_Motonavigace_Starting_Benelli),
        Manufacturer("BMW", ".LauncherBrandBmw", R.style.Theme_Motonavigace_Starting_Bmw),
        Manufacturer("CFMOTO", ".LauncherBrandCfmoto", R.style.Theme_Motonavigace_Starting_Cfmoto),
        Manufacturer("Ducati", ".LauncherBrandDucati", R.style.Theme_Motonavigace_Starting_Ducati),
        Manufacturer("Harley-Davidson", ".LauncherBrandHarleyDavidson", R.style.Theme_Motonavigace_Starting_HarleyDavidson),
        Manufacturer("Honda", ".LauncherBrandHonda", R.style.Theme_Motonavigace_Starting_Honda),
        Manufacturer("Husqvarna", ".LauncherBrandHusqvarna", R.style.Theme_Motonavigace_Starting_Husqvarna),
        Manufacturer("Indian", ".LauncherBrandIndian", R.style.Theme_Motonavigace_Starting_Indian),
        Manufacturer("Jawa", ".LauncherBrandJawa", R.style.Theme_Motonavigace_Starting_Jawa),
        Manufacturer("Kawasaki", ".LauncherBrandKawasaki", R.style.Theme_Motonavigace_Starting_Kawasaki),
        Manufacturer("KTM", ".LauncherBrandKtm", R.style.Theme_Motonavigace_Starting_Ktm),
        Manufacturer("Moto Guzzi", ".LauncherBrandMotoGuzzi", R.style.Theme_Motonavigace_Starting_MotoGuzzi),
        Manufacturer("MV Agusta", ".LauncherBrandMvAgusta", R.style.Theme_Motonavigace_Starting_MvAgusta),
        Manufacturer("Piaggio", ".LauncherBrandPiaggio", R.style.Theme_Motonavigace_Starting_Piaggio),
        Manufacturer("Royal Enfield", ".LauncherBrandRoyalEnfield", R.style.Theme_Motonavigace_Starting_RoyalEnfield),
        Manufacturer("Suzuki", ".LauncherBrandSuzuki", R.style.Theme_Motonavigace_Starting_Suzuki),
        Manufacturer("Triumph", ".LauncherBrandTriumph", R.style.Theme_Motonavigace_Starting_Triumph),
        Manufacturer("Vespa", ".LauncherBrandVespa", R.style.Theme_Motonavigace_Starting_Vespa),
        Manufacturer("Yamaha", ".LauncherBrandYamaha", R.style.Theme_Motonavigace_Starting_Yamaha),
    )

    private const val DEFAULT_ALIAS = ".LauncherDefault"

    /** Styl úvodní obrazovky s logem zvoleného výrobce (pro Android 8–11). */
    val splashTheme: Int
        get() = MANUFACTURERS.firstOrNull { it.name == manufacturer }?.splashTheme ?: R.style.Theme_Motonavigace_Starting

    /**
     * Ikona aplikace podle výrobce. Android neumí ikonu změnit přímo, přepínáme
     * proto mezi activity-alias v manifestu – zapnutý je vždy právě jeden.
     */
    fun applyLauncherIcon(context: Context) {
        val pm = context.packageManager
        val wanted = MANUFACTURERS.firstOrNull { it.name == manufacturer }?.launcherAlias ?: DEFAULT_ALIAS
        for (alias in MANUFACTURERS.map { it.launcherAlias } + DEFAULT_ALIAS) {
            val state = if (alias == wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val component = ComponentName(context, context.packageName + alias)
            // Měníme jen rozdíly – každá změna nutí launcher znovu načíst ikony
            if (pm.getComponentEnabledSetting(component) != state) {
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
    }

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
