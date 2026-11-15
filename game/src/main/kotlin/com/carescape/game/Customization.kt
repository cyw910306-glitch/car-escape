package com.carescape.game

import com.carescape.core.DriveMode
import com.carescape.core.MissionKind

/**
 * 마이 차고: paint, wheels, stickers and theme packs for the in-app IONIQ 9.
 * Only ids, names, unlock rules and mood colors live here; the UI owns how they look.
 */

/** What the saved progress has earned. stars = best stars summed per episode, clears = episodes finished. */
data class Earned(val stars: Int = 0, val badges: Set<MissionKind> = emptySet(), val clears: Int = 0)

/** Unlock rule, one condition per item. All zero = free from the start. */
data class Need(val stars: Int = 0, val badgeCount: Int = 0, val clears: Int = 0, val badge: MissionKind? = null) {
    fun met(e: Earned) = e.stars >= stars && e.badges.size >= badgeCount && e.clears >= clears && (badge == null || badge in e.badges)

    /** One line under a locked item, "" when free. */
    val hint: String get() = when {
        badge != null -> "'${badge.badge}' 배지를 받으면 열려요"
        stars > 0 -> "별을 ${stars}개 모으면 열려요"
        badgeCount > 0 -> "배지를 ${badgeCount}개 모으면 열려요"
        clears > 0 -> "이야기를 ${clears}개 끝내면 열려요"
        else -> ""
    }
}

/** Anything in the garage that can be locked. */
sealed interface Item {
    val label: String
    val need: Need
    fun open(e: Earned) = need.met(e)
}

/** Body colors, rgb = 0xRRGGBB. The first four are free. */
enum class Paint(override val label: String, val rgb: Int, override val need: Need = Need()) : Item {
    SNOW("눈꽃 흰색", 0xF2F1EC),
    MIDNIGHT("밤 검정", 0x1C2029),
    SILVER("달빛 은색", 0xA8AEB6),
    OCEAN("바다 파랑", 0x2C5D8C),
    FOREST("숲 초록", 0x3F6E4A, Need(stars = 3)),
    SUNSET("노을 주황", 0xE07B39, Need(badgeCount = 3)),
    CHERRY("체리 빨강", 0xB7313D, Need(stars = 6)),
    GOLD("반짝 금색", 0xC9A24B, Need(badgeCount = 8)),
}

enum class Wheel(override val label: String, override val need: Need = Need()) : Item {
    BASIC("기본 휠"),
    STAR("별 휠", Need(stars = 4)),
    PINWHEEL("바람개비 휠", Need(clears = 2)),
    GOLD("금빛 휠", Need(stars = 10)),
}

/** One sticker per mission badge, opened by that badge. */
data class Sticker(val kind: MissionKind) : Item {
    override val label get() = kind.badge
    override val need get() = Need(badge = kind)
}

val STICKERS = MissionKind.entries.map(::Sticker)

/** Sticker spots on the body, one sticker each. */
enum class StickerSlot { FRONT_DOOR, REAR_DOOR, TRUNK }

/** App-wide skins (카카오톡 테마처럼). mood = matching ambient light, 0xRRGGBB. NIGHT_SKY is today's palette. */
enum class Theme(override val label: String, val mood: Int, override val need: Need = Need()) : Item {
    NIGHT_SKY("밤하늘", 0xE8B962),
    FOREST_CAMP("숲속 캠핑", 0x5BD27A),
    RACING("불꽃 서킷", 0xFF4A3D),
    UNDERSEA("바닷속", 0x2FB8E8, Need(badgeCount = 2)),
    SPACE_STATION("우주 정거장", 0x8C7CFF, Need(clears = 3)),
    STRAWBERRY_MILK("딸기 우유", 0xFF8FB8, Need(stars = 8)),
}

/** Out of the box: 에코 = 숲, 스포츠 = 빨강, 노멀 = 밤하늘. All free, so the default is always usable. */
val DEFAULT_MODE_THEMES = mapOf(DriveMode.NORMAL to Theme.NIGHT_SKY, DriveMode.ECO to Theme.FOREST_CAMP, DriveMode.SPORT to Theme.RACING)

val CATALOG: List<Item> = Paint.entries + Wheel.entries + STICKERS + Theme.entries

/** Items [after] opens that [before] did not, catalog order. For the ending screen. */
fun newlyOpened(before: Earned, after: Earned): List<Item> = CATALOG.filter { !it.open(before) && it.open(after) }

/**
 * The family's saved car and skin.
 * theme = the fixed pick (모드 따라가기 off, or mode unknown). modeThemes / modeMoods hold only changes from the default.
 */
data class Look(
    val paint: Paint = Paint.SNOW,
    val wheel: Wheel = Wheel.BASIC,
    val stickers: Map<StickerSlot, MissionKind> = emptyMap(),
    val theme: Theme = Theme.NIGHT_SKY,
    val modeThemes: Map<DriveMode, Theme> = emptyMap(),
    val modeMoods: Map<DriveMode, Int> = emptyMap(),
    val followMode: Boolean = true,
) {
    fun themeOf(mode: DriveMode): Theme = modeThemes[mode] ?: DEFAULT_MODE_THEMES.getValue(mode)
    fun moodOf(mode: DriveMode): Int = modeMoods[mode] ?: themeOf(mode).mood

    /** Skin to show now. mode null = unknown: the fixed pick. */
    fun shownTheme(mode: DriveMode?): Theme = if (followMode && mode != null) themeOf(mode) else theme
    fun shownMood(mode: DriveMode?): Int = if (followMode && mode != null) moodOf(mode) else theme.mood

    /** "SNOW|BASIC|FRONT_DOOR:WINDOW|NIGHT_SKY|ECO:UNDERSEA|SPORT:FF0000|true". Names, not positions: reordering enums is safe. */
    fun encode() = listOf(
        paint.name, wheel.name, pairs(stickers) { it.name }, theme.name,
        pairs(modeThemes) { it.name }, pairs(modeMoods) { "%06X".format(it and 0xFFFFFF) }, followMode,
    ).joinToString("|")

    companion object {
        /** Never fails: each broken or unknown part falls back to its default on its own. */
        fun decode(s: String?): Look {
            val p = s?.split("|").orEmpty()
            val d = Look()
            return Look(
                paint = p.getOrNull(0)?.let { enumOf<Paint>(it) } ?: d.paint,
                wheel = p.getOrNull(1)?.let { enumOf<Wheel>(it) } ?: d.wheel,
                stickers = p.getOrNull(2)?.let { parsePairs<StickerSlot, MissionKind>(it) { v -> enumOf<MissionKind>(v) } }.orEmpty(),
                theme = p.getOrNull(3)?.let { enumOf<Theme>(it) } ?: d.theme,
                modeThemes = p.getOrNull(4)?.let { parsePairs<DriveMode, Theme>(it) { v -> enumOf<Theme>(v) } }.orEmpty(),
                modeMoods = p.getOrNull(5)?.let { parsePairs<DriveMode, Int>(it) { v -> v.takeIf { v.length == 6 }?.toIntOrNull(16) } }.orEmpty(),
                followMode = p.getOrNull(6)?.toBooleanStrictOrNull() ?: d.followMode,
            )
        }
    }
}

private fun <K : Enum<K>, V> pairs(m: Map<K, V>, v: (V) -> String) = m.entries.joinToString(",") { "${it.key.name}:${v(it.value)}" }

private inline fun <reified E : Enum<E>> enumOf(name: String): E? = enumValues<E>().firstOrNull { it.name == name }

private inline fun <reified K : Enum<K>, V> parsePairs(s: String, v: (String) -> V?): Map<K, V> =
    s.split(",").mapNotNull { e ->
        val kv = e.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
        val k = enumOf<K>(kv[0]) ?: return@mapNotNull null
        v(kv[1])?.let { k to it }
    }.toMap()
