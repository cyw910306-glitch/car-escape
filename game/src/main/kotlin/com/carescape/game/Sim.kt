package com.carescape.game

import com.carescape.core.Gear
import com.carescape.core.VehicleState

/** Simulated charger speed for demos: about a 7 kW home charger. Time-lapse multiplies it. */
const val SIM_CHARGE_PER_MIN = 1f
const val TIMELAPSE = 30f

/** Demo range per SoC percent: about 500 km at 100 %. */
const val SIM_METERS_PER_PERCENT = 5_000f

/** One simulator step: SoC climbs toward the target while charging; a known range climbs with it. */
fun simCharge(s: VehicleState, dtMs: Long, speedup: Float = 1f): VehicleState {
    if (!s.charging || s.socPercent >= s.chargeTargetPercent) return s
    val soc = (s.socPercent + SIM_CHARGE_PER_MIN * speedup * dtMs / 60_000f).coerceAtMost(s.chargeTargetPercent.toFloat())
    return s.copy(socPercent = soc, rangeMeters = s.rangeMeters?.let { it + (soc - s.socPercent) * SIM_METERS_PER_PERCENT })
}

/** DriveGuard said blocked (UX restriction, or moving): show it to the engine as "not parked" so it pauses and re-baselines. */
fun gated(s: VehicleState, blocked: Boolean): VehicleState = if (blocked) s.copy(gear = Gear.UNKNOWN) else s
