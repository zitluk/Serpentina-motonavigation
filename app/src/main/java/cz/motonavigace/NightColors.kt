package cz.motonavigace

/**
 * Noční barvy mapy: styl mapy (MOTORIDER) se při načtení přebarví, žádná jiná data nejsou potřeba.
 * Plochy výrazně ztmavnou, silnice zůstanou světlejší než okolí, aby byly v noci dobře vidět,
 * a popisky se obrátí (tmavé písmo se světlým lemem → světlé písmo s tmavým lemem).
 */
object NightColors {
    enum class Kind { AREA, LINE, TEXT }

    fun transform(kind: Kind, argb: Int): Int {
        val a = argb ushr 24 and 0xFF
        val (h, s, l) = toHsl(argb)
        val (s2, l2) = when (kind) {
            // Země, les, voda, budovy: tmavé, ať displej v noci neoslňuje
            Kind.AREA -> s * 0.6 to 0.07 + l * 0.18
            // Silnice a hranice: o poznání světlejší než plochy pod nimi
            Kind.LINE -> s * 0.8 to 0.16 + l * 0.42
            Kind.TEXT -> s to 1.0 - l * 0.82
        }
        return (a shl 24) or fromHsl(h, s2.coerceIn(0.0, 1.0), l2.coerceIn(0.0, 1.0))
    }

    private fun toHsl(argb: Int): Triple<Double, Double, Double> {
        val r = (argb shr 16 and 0xFF) / 255.0
        val g = (argb shr 8 and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2
        if (max == min) return Triple(0.0, 0.0, l)
        val d = max - min
        val s = if (l > 0.5) d / (2 - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6 else 0)) / 6
            g -> ((b - r) / d + 2) / 6
            else -> ((r - g) / d + 4) / 6
        }
        return Triple(h, s, l)
    }

    private fun fromHsl(h: Double, s: Double, l: Double): Int {
        fun channel(p: Double, q: Double, t0: Double): Int {
            val t = (t0 % 1 + 1) % 1
            val v = when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
            return (v * 255 + 0.5).toInt().coerceIn(0, 255)
        }
        if (s == 0.0) {
            val v = (l * 255 + 0.5).toInt().coerceIn(0, 255)
            return (v shl 16) or (v shl 8) or v
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        return (channel(p, q, h + 1.0 / 3) shl 16) or (channel(p, q, h) shl 8) or channel(p, q, h - 1.0 / 3)
    }
}
