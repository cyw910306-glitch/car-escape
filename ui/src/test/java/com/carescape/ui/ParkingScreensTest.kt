package com.carescape.ui

import com.carescape.core.GameUiState
import com.carescape.core.VehicleState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 출발 금고 screens at every Pleos window size: fork, entry, confirm, title chip, return ask, return reveal. */
@RunWith(Parameterized::class)
class ParkingScreensTest(private val screen: PleosScreen) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun screens() = PleosScreen.entries.toList()
    }

    @get:Rule val paparazzi = pleosPaparazzi(screen)

    private val base = GameUiState(socPercent = 42f, targetPercent = 80, chargeEtaMinutes = null)
    private val kept = base.copy(parkAnchor = "지하 4층 · C 12")

    private fun shot(name: String, ui: GameUiState, draft: ParkDraft = ParkDraft()) = paparazzi.snapshot(name = "${screen.name}_$name") {
        CarEscapeApp(ui, VehicleState(), GameActions(), DemoControls(), showDemo = false, blocked = false, parkDraft = draft)
    }

    @Test fun parkFork() = shot("park_fork", base.copy(parkFork = true))
    @Test fun parkEntry() = shot("park_entry", base.copy(parkFork = true), ParkDraft(ParkStep.ENTRY, -4, "C", "12"))
    @Test fun parkConfirm() = shot("park_confirm", base.copy(parkFork = true), ParkDraft(ParkStep.CONFIRM, -4, "C", "12"))
    @Test fun parkAway() = shot("park_away", kept)
    @Test fun returnAsk() = shot("return_ask", kept.copy(returnCard = true))
    @Test fun returnReveal() = shot("return_reveal", kept.copy(returnCard = true, returnReveal = true,
        awayLine = "우리가 없는 동안 42%에서 78%까지 충전했어요. 이제 312km 갈 수 있어요."))
}
