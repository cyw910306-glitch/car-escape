package com.carescape.game

import com.carescape.core.GameEvent
import com.carescape.core.Gear
import com.carescape.core.ParkingAnchor
import com.carescape.core.Phase
import com.carescape.core.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 출발 금고: 갈림길 at P, 주차 암호, 떠남 at door lock, 다시 탔어요 on the way back, gone on D. */
class ParkingTest {
    private val b4c12 = ParkingAnchor(-4, "C", "12")
    private fun said(r: Rig) = r.game.log.filterIsInstance<GameEvent.Speak>().map { it.text }
    private val ui get() = rig.game.ui.value
    private lateinit var rig: Rig

    private fun parked(s: VehicleState = VehicleState(socPercent = 42f, chargePortOpen = false, doorLocked = false)): Rig {
        rig = Rig(s = s)
        rig.tick()
        rig.game.onParkedAfterDrive()
        return rig
    }

    /** Saved, then the family locked the car and came back: unlock, a door opens, all doors close. */
    private fun awayAndBack(r: Rig, back: (VehicleState) -> VehicleState = { it }) {
        r.set { it.copy(doorOpen = true) }; r.set { it.copy(doorOpen = false) } // getting out
        r.set { it.copy(doorLocked = true) }
        r.set { back(it) }
        r.set { it.copy(doorLocked = false, doorOpen = true) }
        r.set { it.copy(doorOpen = false) }
    }

    @Test fun forkOnceThenStayCloses() {
        val r = parked()
        assertTrue(ui.parkFork)
        r.game.stayInCar()
        assertFalse(ui.parkFork)
        r.tick(1_000)
        assertFalse(ui.parkFork)
    }

    @Test fun chargePortOpeningGivesTheEpisodeCard() {
        val r = parked()
        assertTrue(ui.parkFork)
        r.set { it.copy(chargePortOpen = true) }
        assertFalse(ui.parkFork)
        assertEquals(1, ui.readyEpisode)
        r.game.dismissReady()
        r.set { it.copy(chargePortOpen = false) } // unplugged later: the fork does not come back for this stop
        assertFalse(ui.parkFork)
    }

    @Test fun portAlreadyOpenSkipsTheFork() {
        parked(VehicleState(socPercent = 42f, chargePortOpen = true))
        rig.tick()
        assertFalse(ui.parkFork)
    }

    @Test fun savingNamesTheAnchorOnce() {
        val r = parked()
        r.game.saveAnchor(b4c12)
        assertEquals("지하 4층 · C 12", ui.parkAnchor)
        assertFalse(ui.parkFork)
        assertEquals(listOf("오늘의 주차 암호는 지하 4층, C 12예요. 다 같이 한 번 따라 말해요."), said(r))
        r.game.onParkedAfterDrive() // a saved 암호 means no second fork
        assertFalse(ui.parkFork)
    }

    @Test fun josaFollowsTheLastSound() {
        assertEquals("지상", ParkingAnchor(0, "", "").label())
        assertEquals("지하 2층 · A", ParkingAnchor(-2, "A", "").label())
        assertEquals("지하 1층 · 10", ParkingAnchor(-1, "", "10").label())
        val r = parked()
        r.game.saveAnchor(ParkingAnchor(0, "", ""))
        assertTrue(said(r).last(), said(r).last().startsWith("오늘의 주차 암호는 지상이에요."))
        r.game.onDriveAway(); r.set { it }
        r.game.onParkedAfterDrive()
        r.game.saveAnchor(ParkingAnchor(-3, "", "10"))
        assertTrue(said(r).last(), said(r).last().startsWith("오늘의 주차 암호는 지하 3층, 10이에요."))
    }

    @Test fun returnCardNeedsLockThenUnlockAndClosedDoors() {
        val r = parked()
        r.game.saveAnchor(b4c12)
        r.set { it.copy(doorOpen = true) }; r.set { it.copy(doorOpen = false) }
        assertFalse("not away before the lock", ui.returnCard)
        r.set { it.copy(doorLocked = true) }
        assertFalse(ui.returnCard)
        r.set { it.copy(doorLocked = false, doorOpen = true) }
        assertFalse(ui.returnCard)
        r.set { it.copy(doorOpen = false) }
        assertTrue(ui.returnCard)
        assertEquals(1, said(r).count { it == EscapeGame.RETURN_ASK })
        r.tick(1_000)
        assertEquals(1, said(r).count { it == EscapeGame.RETURN_ASK })
    }

    @Test fun unlockAloneIsNotComingBack() {
        val r = parked()
        r.game.saveAnchor(b4c12)
        r.set { it.copy(doorLocked = true) }
        r.set { it.copy(doorLocked = false) } // remote unlock from far away: nobody is in yet
        assertFalse(ui.returnCard)
    }

    @Test fun revealTellsChargeGainedAndRange() {
        val r = parked()
        r.game.saveAnchor(b4c12)
        awayAndBack(r) { it.copy(socPercent = 78f, rangeMeters = 312_000f) }
        assertTrue(ui.returnCard)
        assertFalse(ui.returnReveal)
        r.game.revealReturn()
        assertTrue(ui.returnReveal)
        assertEquals("우리가 없는 동안 42%에서 78%까지 충전했어요. 이제 312km 갈 수 있어요.", ui.awayLine)
        assertEquals("정답은 지하 4층, C 12였어요. 우리가 없는 동안 42퍼센트에서 78퍼센트까지 충전했어요. 이제 312킬로미터 갈 수 있어요.", said(r).last())
        r.game.closeReturn()
        assertFalse(ui.returnCard)
        assertEquals("지하 4층 · C 12", ui.parkAnchor) // kept until the car drives off
    }

    @Test fun lockMomentValuesReplaceSavedOnes() {
        val r = parked()
        r.game.saveAnchor(b4c12)
        r.set { it.copy(socPercent = 50f) } // charged a bit before they walked off
        r.set { it.copy(doorLocked = true) }
        assertEquals(50, r.game.parkMemo?.soc)
        awayAndBack(r) { it.copy(socPercent = 60f) }
        r.game.revealReturn()
        assertEquals("우리가 없는 동안 50%에서 60%까지 충전했어요.", ui.awayLine)
    }

    @Test fun unknownValuesLeaveTheLineOut() {
        val r = parked()
        r.game.saveAnchor(b4c12)
        awayAndBack(r) // same SoC, no range
        r.game.revealReturn()
        assertEquals("", ui.awayLine)
        assertEquals("정답은 지하 4층, C 12였어요.", said(r).last())
    }

    @Test fun placeholderSocIsNeverCompared() {
        // A combustion Pleos car: no SoC reading at all (socKnown false), range known.
        val r = parked(VehicleState(socPercent = 42f, socKnown = false, doorLocked = false, rangeMeters = 480_000f))
        r.game.saveAnchor(b4c12)
        assertNull(r.game.parkMemo?.soc)
        awayAndBack(r) { it.copy(socPercent = 80f) }
        r.game.revealReturn()
        assertEquals("이제 480km 갈 수 있어요.", ui.awayLine)
    }

    @Test fun memoSurvivesRelaunch() {
        val m = ParkMemo(b4c12, 42, 300_000f, away = false)
        assertEquals(m, ParkMemo.decode(m.encode()))
        assertEquals(ParkMemo(ParkingAnchor(0, "", ""), null, null, true), ParkMemo.decode(ParkMemo(ParkingAnchor(0, "", ""), null, null, true).encode()))
        assertNull(ParkMemo.decode("-9|C|12|42||false"))
        assertNull(ParkMemo.decode("-4|Z|12|42||false"))
        assertNull(ParkMemo.decode("-4|C|1234|42||false"))
        assertNull(ParkMemo.decode("garbage"))

        // App relaunched: the saved memo is put back, the car is parked with doors closed.
        val r = Rig(s = VehicleState(socPercent = 78f, rangeMeters = 312_000f))
        r.game.parkMemo = ParkMemo.decode(m.encode())
        r.tick()
        assertTrue(r.game.ui.value.returnCard)
        assertTrue(r.game.parkMemo!!.away)
        r.game.revealReturn()
        assertEquals("우리가 없는 동안 42%에서 78%까지 충전했어요. 이제 312km 갈 수 있어요.", r.game.ui.value.awayLine)
    }

    @Test fun drivingAwayDeletesEverything() {
        val r = parked()
        r.game.saveAnchor(b4c12)
        awayAndBack(r)
        assertTrue(ui.returnCard)
        r.game.onDriveAway()
        r.set { it.copy(gear = Gear.DRIVE) }
        assertNull(r.game.parkMemo)
        assertEquals("", ui.parkAnchor)
        assertFalse(ui.returnCard)
        r.set { it.copy(gear = Gear.PARK) }
        assertFalse(ui.returnCard)
        assertEquals("", ui.parkAnchor)
    }

    @Test fun noForkMidEpisode() {
        rig = Rig(s = VehicleState(socPercent = 40f, chargePortOpen = false))
        rig.toPuzzle("map-15")
        rig.game.onParkedAfterDrive()
        assertFalse(ui.parkFork)
        rig.set { it.copy(gear = Gear.DRIVE) }
        rig.game.onParkedAfterDrive() // paused from a puzzle
        rig.set { it.copy(gear = Gear.PARK) }
        assertEquals(Phase.PUZZLE, rig.phase)
        rig.game.backToTitle()
        rig.tick()
        assertFalse(ui.parkFork)
    }

    @Test fun nothingNewWhileDriving() {
        // Gated state: DriveGuard blocked -> the engine sees UNKNOWN gear. No card, no voice, no save.
        val r = parked()
        r.set { it.copy(gear = Gear.UNKNOWN) }
        assertEquals(Phase.PAUSED_DRIVING, r.phase)
        assertFalse(ui.parkFork)
        r.game.saveAnchor(b4c12)
        assertNull(r.game.parkMemo)
        assertTrue(said(r).isEmpty())

        r.set { it.copy(gear = Gear.PARK) }
        assertTrue("fork comes back once parked", ui.parkFork)
        r.game.saveAnchor(b4c12)
        r.set { it.copy(doorLocked = true) }
        r.set { it.copy(doorLocked = false, doorOpen = true) }
        r.set { it.copy(doorOpen = false, gear = Gear.UNKNOWN, speedKmh = 12f) } // already rolling when the doors shut
        assertFalse(ui.returnCard)
        val before = said(r).size
        r.game.openReturn(); r.game.revealReturn()
        assertEquals(before, said(r).size)
        assertNotNull(r.game.parkMemo)
    }

    @Test fun pluggingInAfterSavingOffersNoEpisode() {
        val r = parked()
        r.game.saveAnchor(b4c12)
        r.set { it.copy(chargePortOpen = true) } // plugged in on the way out
        r.set { it.copy(doorLocked = true) }
        assertEquals(0, ui.readyEpisode)
        assertEquals("지하 4층 · C 12", ui.parkAnchor)
    }

    @Test fun titleChipOpensTheCardByHand() {
        // Car without door lock data: doorLocked stays null, so only the chip (or a relaunch) asks.
        val r = parked(VehicleState(socPercent = 42f, chargePortOpen = false))
        r.game.saveAnchor(b4c12)
        r.set { it.copy(doorOpen = true) }; r.set { it.copy(doorOpen = false) }
        assertFalse(ui.returnCard)
        r.game.openReturn()
        assertTrue(ui.returnCard)
        assertEquals(EscapeGame.RETURN_ASK, said(r).last())
    }
}
