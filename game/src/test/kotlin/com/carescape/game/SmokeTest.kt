package com.carescape.game

import com.carescape.core.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test fun coreIsVisible() = assertEquals(80, VehicleState().chargeTargetPercent)
}
