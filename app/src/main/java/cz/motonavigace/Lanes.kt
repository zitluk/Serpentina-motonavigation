package cz.motonavigace

/**
 * Doporučení pruhu před manévrem.
 *
 * Offline data BRouteru znají jen počet pruhů (`lanes`), ne kam který pruh vede
 * (`turn:lanes`). Doporučení je proto odhad podle směru manévru: doleva levé pruhy,
 * doprava pravé, na kruháči podle čísla výjezdu.
 *
 * @param count počet pruhů v našem směru
 * @param recommended indexy doporučených pruhů, 0 = nejlevější
 */
data class LaneHint(val count: Int, val recommended: Set<Int>, val side: Side) {
    enum class Side { LEFT, RIGHT, ANY }

    companion object {
        /** Víc pruhů už se do panelu nevejde a na silnicích pro motorky to nemá smysl. */
        private const val MAX_LANES = 6

        /** @param tags tagy cesty, po které se k manévru přijíždí (z BRouteru) */
        fun forManeuver(m: Maneuver, tags: String?): LaneHint? {
            val t = parseTags(tags)
            val total = t["lanes"]?.toDoubleOrNull()?.toInt() ?: return null
            val oneway = t["oneway"] in setOf("yes", "1", "true", "-1") ||
                t["highway"] in setOf("motorway", "motorway_link", "trunk_link") || t["junction"] == "roundabout"
            // Bez jednosměrky platí lanes pro oba směry dohromady
            val n = (if (oneway) total else total / 2).coerceAtMost(MAX_LANES)
            if (n < 2) return null

            val left = 0 until (n + 1) / 2
            val right = n / 2 until n
            return when (m.command) {
                "TL", "TSHL", "TU" -> LaneHint(n, setOf(0), Side.LEFT)
                "TSLL", "KL", "EL" -> LaneHint(n, left.toSet(), Side.LEFT)
                "TR", "TSHR" -> LaneHint(n, setOf(n - 1), Side.RIGHT)
                "TSLR", "KR", "ER" -> LaneHint(n, right.toSet(), Side.RIGHT)
                "RNDB", "RNLB" -> when {
                    m.exit == 1 -> LaneHint(n, setOf(n - 1), Side.RIGHT)
                    m.exit >= 3 -> LaneHint(n, setOf(0), Side.LEFT)
                    else -> LaneHint(n, (0 until n).toSet(), Side.ANY)
                }
                else -> null
            }
        }

        internal fun parseTags(tags: String?): Map<String, String> = tags.orEmpty().split(' ').mapNotNull {
            val eq = it.indexOf('=')
            if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else null
        }.toMap()
    }
}
