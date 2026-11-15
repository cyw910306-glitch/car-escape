package com.carescape.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import com.carescape.core.Gear
import com.carescape.core.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Pleos review rule: the game runs only while parked. blocked = UX restriction OR vehicle state says moving.
 *
 * Decision on unknowns: gear UNKNOWN counts as blocked, whatever the speed says. PleosVehicleGateway starts at
 * UNKNOWN and flips to PARK on the first Pleos (or CarPropertyManager) gear reading, which on the Pleos
 * emulator arrives within the first second, so a parked car unblocks almost immediately. A car that never
 * reports gear stays blocked: we would rather show the pause screen than let a game run while driving.
 * Desk demos use SimVehicleGateway (gear PARK by default) and are unaffected.
 * Unknown speed is reported as 0, so speed alone never blocks without a real reading.
 */
class DriveGuard(context: Context, state: StateFlow<VehicleState>, scope: CoroutineScope) {
    private val uxRestricted = MutableStateFlow(false)
    /** The car's own "driving" verdict. Keeps arriving while the activity is stopped (the OS covers it in D). */
    val restricted: StateFlow<Boolean> = uxRestricted
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null

    val blocked: StateFlow<Boolean> = combine(uxRestricted, state) { r, s -> r || isMoving(s) }
        .stateIn(scope, SharingStarted.Eagerly, true)

    init {
        try {
            val c = Car.createCar(context)
            if (c != null) {
                car = c
                val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                ux = m
                m.registerListener { r -> uxRestricted.value = r.isRequiresDistractionOptimization }
                uxRestricted.value = m.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: false
            }
        } catch (t: Throwable) {
            // Not an automotive build or service missing: fall back to vehicle state only.
            Log.w("DriveGuard", "CarUxRestrictionsManager unavailable", t)
        }
    }

    fun release() {
        try { ux?.unregisterListener() } catch (t: Throwable) { Log.w("DriveGuard", "unregister", t) }
        try { car?.disconnect() } catch (t: Throwable) { Log.w("DriveGuard", "disconnect", t) }
        ux = null
        car = null
    }

    companion object {
        fun isMoving(s: VehicleState) = s.gear != Gear.PARK || s.speedKmh > 0.5f
    }
}
