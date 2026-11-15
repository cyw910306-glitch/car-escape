package com.carescape.ui

import com.carescape.core.DriveMode
import com.carescape.core.GameUiState
import com.carescape.core.MissionKind
import com.carescape.core.Phase
import com.carescape.core.VehicleState
import com.carescape.game.Look
import com.carescape.game.Paint
import com.carescape.game.StickerSlot
import com.carescape.game.Theme
import com.carescape.game.Wheel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 마이 차고 at every Pleos window size: each tab with some items still locked, a light theme, the ending's new unlocks. */
@RunWith(Parameterized::class)
class GarageScreensTest(private val screen: PleosScreen) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun screens() = PleosScreen.entries.toList()
    }

    @get:Rule val paparazzi = pleosPaparazzi(screen)

    @After fun resetTheme() { C.theme = Theme.NIGHT_SKY } // C.theme is global; the other snapshot classes expect 밤하늘

    private val base = GameUiState(socPercent = 42f, targetPercent = 80, chargeEtaMinutes = 34)
    // One episode (3 stars) and two badges: some of every tab open, some locked.
    private val some = Progress(best = mapOf("map-15" to BestRecord(3, 412)), badges = setOf(MissionKind.WINDOW, MissionKind.MORSE))
    private val look = Look(Paint.OCEAN, Wheel.BASIC, mapOf(StickerSlot.FRONT_DOOR to MissionKind.WINDOW, StickerSlot.TRUNK to MissionKind.MORSE))
    private val all = Progress(
        best = mapOf("a" to BestRecord(3, 1), "b" to BestRecord(3, 1), "c" to BestRecord(3, 1), "d" to BestRecord(3, 1)), badges = MissionKind.entries.toSet(),
    )

    private fun shot(
        name: String, tab: GarageTab?, theme: Theme = Theme.NIGHT_SKY, ui: GameUiState = base, progress: Progress = some,
        look: Look = this.look, vehicle: VehicleState = VehicleState(), newOpened: List<com.carescape.game.Item> = emptyList(),
    ) {
        C.theme = theme
        paparazzi.snapshot(name = "${screen.name}_$name") {
            CarEscapeApp(ui, vehicle, GameActions(), DemoControls(), showDemo = false, blocked = false, progress = progress, look = look, newOpened = newOpened, garageTab = tab)
        }
    }

    @Test fun paint() = shot("garage_paint", GarageTab.PAINT)
    @Test fun wheel() = shot("garage_wheel", GarageTab.WHEEL, look = look.copy(wheel = Wheel.STAR), progress = some.copy(best = mapOf("a" to BestRecord(3, 1), "b" to BestRecord(2, 1))))
    @Test fun sticker() = shot("garage_sticker", GarageTab.STICKER)
    @Test fun theme() = shot("garage_theme", GarageTab.THEME, theme = Theme.UNDERSEA, look = look.copy(theme = Theme.UNDERSEA, followMode = false))
    @Test fun drive() = shot("garage_drive", GarageTab.DRIVE, theme = Theme.FOREST_CAMP, vehicle = VehicleState(driveMode = DriveMode.ECO))
    @Test fun driveUnknown() = shot("garage_drive_unknown", GarageTab.DRIVE)
    @Test fun lightGarage() = shot("garage_light", GarageTab.PAINT, theme = Theme.STRAWBERRY_MILK, progress = all,
        look = Look(Paint.CHERRY, Wheel.PINWHEEL, mapOf(StickerSlot.FRONT_DOOR to MissionKind.LIGHTNING, StickerSlot.REAR_DOOR to MissionKind.SEATBELT, StickerSlot.TRUNK to MissionKind.RIDDLE), Theme.STRAWBERRY_MILK, followMode = false))
    @Test fun lightTitle() = shot("title_light", null, theme = Theme.STRAWBERRY_MILK, progress = some, look = look.copy(paint = Paint.SNOW))
    @Test fun spaceTitle() = shot("title_space", null, theme = Theme.SPACE_STATION, look = look.copy(wheel = Wheel.GOLD, paint = Paint.MIDNIGHT))
    @Test fun racingTitle() = shot("title_racing", null, theme = Theme.RACING, look = look.copy(paint = Paint.CHERRY))
    @Test fun endingUnlock() = shot("ending_unlock", null, ui = base.copy(phase = Phase.ENDING, episodeTitle = "꼬마 탐정과 충전소 지도", episodeId = "map-15", elapsedSec = 412, stars = 3,
        summary = "힌트 0번, 건너뛰기 0번, 번개 0개", endingText = "80% 충전 완료! 출발 준비 완료! 온 가족이 한 팀이었어요.", restoreLeft = emptyList()),
        progress = some.copy(newBadges = setOf(MissionKind.MORSE)), look = look,
        newOpened = listOf(Paint.FOREST, com.carescape.game.Sticker(MissionKind.MORSE), Theme.UNDERSEA))
}
