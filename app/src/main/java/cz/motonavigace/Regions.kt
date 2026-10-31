package cz.motonavigace

import kotlin.math.floor

/** Region ke stažení: mapa a body zájmu Mapsforge + routovací segmenty pokrývající jeho obdélník. */
data class Region(
    /** Stálý klíč (ukládá se ke stahování), zobrazuje se [label]. */
    val name: String,
    @androidx.annotation.StringRes val label: Int,
    val mapPath: String,
    val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double,
) {
    val mapFileName get() = mapPath.substringAfterLast('/')
    val mapUrl get() = "$MAPS_BASE/$mapPath"
    val poiPath get() = mapPath.removeSuffix(".map") + ".poi"
    val poiFileName get() = poiPath.substringAfterLast('/')
    val poiUrl get() = "$POIS_BASE/$poiPath"

    /** Názvy rd5 souborů (např. E15_N45.rd5), které region protínají. */
    val segments: List<String>
        get() {
            val result = mutableListOf<String>()
            var lat = floor5(minLat)
            while (lat < maxLat) {
                var lon = floor5(minLon)
                while (lon < maxLon) {
                    result += segmentName(lon, lat)
                    lon += 5
                }
                lat += 5
            }
            return result
        }

    companion object {
        const val MAPS_BASE = "https://download.mapsforge.org/maps/v5"
        const val POIS_BASE = "https://download.mapsforge.org/pois"
        const val SEGMENTS_BASE = "https://brouter.de/brouter/segments4"

        private fun floor5(v: Double) = (floor(v / 5) * 5).toInt()

        fun segmentName(lon: Int, lat: Int): String {
            val slon = if (lon < 0) "W${-lon}" else "E$lon"
            val slat = if (lat < 0) "S${-lat}" else "N$lat"
            return "${slon}_$slat.rd5"
        }

        fun segmentUrl(name: String) = "$SEGMENTS_BASE/$name"

        val ALL = listOf(
            Region("Česko", R.string.region_cz, "europe/czech-republic.map", 48.55, 12.09, 51.06, 18.86),
            Region("Slovensko", R.string.region_sk, "europe/slovakia.map", 47.73, 16.83, 49.61, 22.57),
            Region("Rakousko", R.string.region_at, "europe/austria.map", 46.37, 9.53, 49.02, 17.16),
            Region("Polsko", R.string.region_pl, "europe/poland.map", 49.0, 14.12, 54.84, 24.15),
            Region("Maďarsko", R.string.region_hu, "europe/hungary.map", 45.74, 16.11, 48.59, 22.9),
            Region("Slovinsko", R.string.region_si, "europe/slovenia.map", 45.42, 13.37, 46.88, 16.61),
            Region("Chorvatsko", R.string.region_hr, "europe/croatia.map", 42.39, 13.49, 46.56, 19.45),
            Region("Německo – Bavorsko", R.string.region_de_by, "europe/germany/bayern.map", 47.27, 8.97, 50.57, 13.84),
            Region("Německo – Sasko", R.string.region_de_sn, "europe/germany/sachsen.map", 50.17, 11.87, 51.69, 15.04),
            Region("Švýcarsko", R.string.region_ch, "europe/switzerland.map", 45.82, 5.95, 47.81, 10.49),
            Region("Itálie – sever (Alpy)", R.string.region_it_ne, "europe/italy/nord-est.map", 44.8, 10.4, 47.1, 13.92),
            Region("Rumunsko", R.string.region_ro, "europe/romania.map", 43.62, 20.26, 48.27, 29.7),
        )
    }
}
