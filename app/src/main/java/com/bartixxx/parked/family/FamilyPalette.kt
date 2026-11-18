package com.bartixxx.parked.family

/** Stable per-device colours shared by the settings list and the map pins. */
object FamilyPalette {
    private val colors = intArrayOf(
        0xFF2563EB.toInt(),
        0xFF059669.toInt(),
        0xFFD97706.toInt(),
        0xFF7C3AED.toInt(),
        0xFFDC2626.toInt(),
        0xFF0891B2.toInt(),
        0xFFDB2777.toInt(),
        0xFF65A30D.toInt(),
    )

    fun colorFor(deviceId: String): Int {
        var hash = 0
        for (char in deviceId) {
            hash = (hash * 31 + char.code) and 0x7FFFFFFF
        }
        return colors[hash % colors.size]
    }
}
