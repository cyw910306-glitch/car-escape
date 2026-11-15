package com.carescape.game

import com.carescape.core.DriveMode
import com.carescape.core.MissionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 마이 차고: unlock rules, saved look round trip, broken strings. */
class CustomizationTest {
    @Test fun freshFamilyHasTheBasics() {
        val none = Earned()
        assertEquals(listOf(Paint.SNOW, Paint.MIDNIGHT, Paint.SILVER, Paint.OCEAN), Paint.entries.filter { it.open(none) })
        assertEquals(listOf(Wheel.BASIC), Wheel.entries.filter { it.open(none) })
        assertTrue(STICKERS.none { it.open(none) })
        assertTrue("default mode mapping must be usable", DEFAULT_MODE_THEMES.values.all { it.open(none) })
        assertEquals(12, STICKERS.size)
        assertEquals(DriveMode.entries.toSet(), DEFAULT_MODE_THEMES.keys)
    }

    @Test fun rulesAndHints() {
        assertTrue(Paint.FOREST.open(Earned(stars = 3)))
        assertFalse(Paint.FOREST.open(Earned(stars = 2)))
        assertTrue(Wheel.PINWHEEL.open(Earned(clears = 2)))
        assertTrue(Theme.UNDERSEA.open(Earned(badges = setOf(MissionKind.WINDOW, MissionKind.MORSE))))
        assertTrue(Sticker(MissionKind.MORSE).open(Earned(badges = setOf(MissionKind.MORSE))))
        assertFalse(Sticker(MissionKind.WIPER).open(Earned(badges = setOf(MissionKind.MORSE))))
        assertEquals("별을 3개 모으면 열려요", Paint.FOREST.need.hint)
        assertEquals("'등대' 배지를 받으면 열려요", Sticker(MissionKind.MORSE).need.hint)
        assertEquals("이야기를 2개 끝내면 열려요", Wheel.PINWHEEL.need.hint)
        assertEquals("", Paint.SNOW.need.hint)
        // Every locked item says how to open it, without em dash.
        CATALOG.filter { it.need != Need() }.forEach { assertTrue(it.label, it.need.hint.isNotEmpty() && '—' !in it.need.hint) }
        // Everything is reachable with 4 episodes x 3 stars and 12 badges.
        assertTrue(CATALOG.all { it.open(Earned(stars = 12, badges = MissionKind.entries.toSet(), clears = 4)) })
    }

    @Test fun newlyOpenedListsOnlyTheNewOnes() {
        val before = Earned(stars = 2, badges = setOf(MissionKind.WINDOW))
        val after = Earned(stars = 4, badges = setOf(MissionKind.WINDOW, MissionKind.MORSE), clears = 1)
        assertEquals(listOf(Paint.FOREST, Wheel.STAR, Sticker(MissionKind.MORSE), Theme.UNDERSEA), newlyOpened(before, after))
        assertEquals(emptyList<Item>(), newlyOpened(after, after))
    }

    @Test fun lookRoundTrip() {
        val l = Look(
            Paint.CHERRY, Wheel.GOLD,
            mapOf(StickerSlot.FRONT_DOOR to MissionKind.WINDOW, StickerSlot.TRUNK to MissionKind.LIGHTNING),
            Theme.STRAWBERRY_MILK,
            mapOf(DriveMode.ECO to Theme.UNDERSEA),
            mapOf(DriveMode.SPORT to 0xFF0000, DriveMode.NORMAL to 0x00AA11),
            followMode = false,
        )
        assertEquals(l, Look.decode(l.encode()))
        assertEquals(Look(), Look.decode(Look().encode()))
    }

    @Test fun brokenStringsFallBackPartByPart() {
        assertEquals(Look(), Look.decode(null))
        assertEquals(Look(), Look.decode(""))
        assertEquals(Look(), Look.decode("garbage"))
        assertEquals(Look(), Look.decode("||||||||||"))
        // Unknown paint, half a sticker list, bad color: the good parts stay.
        val l = Look.decode("PURPLE|STAR|FRONT_DOOR:WINDOW,REAR_DOOR:NOPE,BAD|SPACE_STATION|ECO:RACING,X:Y|SPORT:FFZZ00,ECO:00FF00|maybe")
        assertEquals(Paint.SNOW, l.paint)
        assertEquals(Wheel.STAR, l.wheel)
        assertEquals(mapOf(StickerSlot.FRONT_DOOR to MissionKind.WINDOW), l.stickers)
        assertEquals(Theme.SPACE_STATION, l.theme)
        assertEquals(mapOf(DriveMode.ECO to Theme.RACING), l.modeThemes)
        assertEquals(mapOf(DriveMode.ECO to 0x00FF00), l.modeMoods)
        assertTrue(l.followMode)
    }

    @Test fun skinFollowsTheModeOnlyWhenAsked() {
        val l = Look(theme = Theme.UNDERSEA, modeMoods = mapOf(DriveMode.SPORT to 0x123456))
        assertEquals(Theme.FOREST_CAMP, l.shownTheme(DriveMode.ECO))
        assertEquals(Theme.RACING, l.shownTheme(DriveMode.SPORT))
        assertEquals(0x123456, l.shownMood(DriveMode.SPORT))
        assertEquals(Theme.FOREST_CAMP.mood, l.shownMood(DriveMode.ECO))
        assertEquals("unknown mode: the fixed pick", Theme.UNDERSEA, l.shownTheme(null))
        assertEquals(Theme.UNDERSEA.mood, l.shownMood(null))
        val fixed = l.copy(followMode = false)
        assertEquals(Theme.UNDERSEA, fixed.shownTheme(DriveMode.SPORT))
        assertEquals(Theme.UNDERSEA.mood, fixed.shownMood(DriveMode.SPORT))
    }
}
