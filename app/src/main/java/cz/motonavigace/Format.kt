package cz.motonavigace

import kotlin.math.roundToInt

fun formatDistance(meters: Double): String = when {
    meters < 1000 -> "${((meters / 10).roundToInt() * 10).coerceAtLeast(0)} m"
    meters < 10_000 -> "%.1f km".format(meters / 1000)
    else -> "${(meters / 1000).roundToInt()} km"
}

fun formatDuration(seconds: Double): String {
    val total = (seconds / 60).roundToInt()
    val h = total / 60
    val min = total % 60
    return if (h > 0) "$h h $min min" else "$min min"
}
