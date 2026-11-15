package com.carescape.game

import com.carescape.core.ParkingAnchor

/**
 * 출발 금고 memo, kept by the app on the device only and deleted on the next drive.
 * soc / rangeM: values when it was saved, replaced by the values at door lock (떠남). null = unknown.
 * away: the doors were locked after saving (or the app came back with a saved memo).
 */
data class ParkMemo(val anchor: ParkingAnchor, val soc: Int?, val rangeM: Float?, val away: Boolean) {
    fun encode() = listOf(anchor.floor, anchor.zone, anchor.number, soc ?: "", rangeM ?: "", away).joinToString("|")

    companion object {
        val ZONES = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        val FLOORS = -6..0

        /** Same "|" format as the 충전 일지. Anything malformed reads as no memo. */
        fun decode(s: String): ParkMemo? {
            val p = s.split("|").takeIf { it.size == 6 } ?: return null
            val floor = p[0].toIntOrNull()?.takeIf { it in FLOORS } ?: return null
            val zone = p[1].takeIf { it.isEmpty() || it in ZONES } ?: return null
            val number = p[2].takeIf { it.length <= 3 && it.all(Char::isDigit) } ?: return null
            return ParkMemo(ParkingAnchor(floor, zone, number), p[3].toIntOrNull(), p[4].toFloatOrNull(), p[5] == "true")
        }
    }
}

/** True when the Korean reading of [s] ends in a final consonant (지하 4층, C 10), for 이에요/예요 and 이었어요/였어요. */
internal fun endsWithBatchim(s: String): Boolean {
    val c = s.trimEnd().lastOrNull() ?: return false
    return when {
        c in '가'..'힣' -> (c - '가') % 28 != 0
        c.isDigit() -> c in "013678" // 영 일 삼 육 칠 팔; a trailing 0 reads 십 or 백 (both closed)
        else -> false // A..H read 에이 비 씨 디 이 에프 지 에이치
    }
}

internal fun isWord(s: String) = if (endsWithBatchim(s)) "이에요" else "예요"
internal fun wasWord(s: String) = if (endsWithBatchim(s)) "이었어요" else "였어요"
