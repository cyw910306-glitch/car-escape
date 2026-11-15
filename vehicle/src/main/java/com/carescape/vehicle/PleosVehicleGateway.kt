package com.carescape.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacSeatTemperatureListener
import ai.pleos.playground.vehicle.listener.HvacSteeringWheelHeatStateListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WindowLockStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.hardware.property.EvChargeState
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.carescape.core.CarSeat
import com.carescape.core.CarWindow
import com.carescape.core.DriveMode
import com.carescape.core.Gear
import com.carescape.core.TurnSignal
import com.carescape.core.VehicleGateway
import com.carescape.core.VehicleInput
import com.carescape.core.VehicleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "PleosGateway"
private const val RELEASE_DELAY_MS = 800L

/**
 * Real car gateway. Pleos Vehicle SDK 2.3.2 for cabin inputs, SoC, occupancy, ambient and gear fallback.
 * android.car CarPropertyManager for gear (primary), speed, charge state and charge limit.
 * Every failure is logged, never thrown. Gear starts UNKNOWN so the game stays paused until the car answers.
 */
class PleosVehicleGateway(private val context: Context) : VehicleGateway {
    // charging stays false until the first real SoC: the contract's placeholder 42% must never reach ChargeClock.
    private val _state = MutableStateFlow(VehicleState(gear = Gear.UNKNOWN, charging = false, socKnown = false))
    override val state: StateFlow<VehicleState> = _state
    private val _supported = MutableStateFlow<Set<VehicleInput>>(emptySet())
    override val supportedInputs: StateFlow<Set<VehicleInput>> = _supported

    private val main = Handler(Looper.getMainLooper())
    private val releaser = Handler(Looper.getMainLooper()) // separate: stop() clears `main`, never a pending release
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private val unregisters = mutableListOf<() -> Unit>()
    @Volatile private var aaosGearOk = false
    @Volatile private var running = false
    @Volatile private var socKnown = false
    @Volatile private var chargingRaw = true // no charge-state source at all: assume plugged in, the app's use case
    @Volatile private var targetKnown = false

    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)

    // Ambient: areas we may write, brightness bounds, and what to restore.
    @Volatile private var ambientAreas: List<SeatArea> = emptyList()
    @Volatile private var ambientBounds: AreaBounds<Int>? = null
    private val ambientSaved = mutableMapOf<SeatArea, Triple<Int?, Int?, LightSwitch?>>()

    private val occupied = mutableMapOf<SeatArea, Boolean>()
    private val doorsOpen = mutableMapOf<DoorArea, Boolean>()
    private val rearLocks = mutableMapOf<WindowArea, Boolean>()
    private val doorLocks = mutableMapOf<DoorArea, Boolean>()

    override fun start() {
        if (running) return
        running = true
        // Gear may have changed while we were stopped: pause until the car answers again.
        aaosGearOk = false; gearListening = false; rangeListening = false; chargeState = null; chargeSwitch = null
        _state.update { it.copy(gear = Gear.UNKNOWN) }
        try {
            vehicle = Vehicle(context).also { it.initialize() }
        } catch (t: Throwable) {
            Log.w(TAG, "Pleos Vehicle init failed", t)
        }
        startCarProperties()
        // initialize() has no ready callback. Calls made before binding fail with onFailed, so each probe retries.
        vehicle?.let { v -> probe(v) }
    }

    override fun stop() {
        if (!running) return
        running = false
        main.removeCallbacksAndMessages(null)
        synchronized(unregisters) {
            unregisters.forEach { safe("unregister") { it() } }
            unregisters.clear()
        }
        // restoreAmbient() just queued async set calls; releasing at once can drop them (API notes).
        vehicle?.let { v -> releaser.postDelayed({ safe("release") { v.release() } }, RELEASE_DELAY_MS) }
        vehicle = null
        safe("car disconnect") { car?.disconnect() }
        car = null
        props = null
    }

    // ---------- Pleos probes ----------

    private fun probe(v: Vehicle) {
        // Driver, passenger and 2nd row windows, each only if the car reports bounds for it (IONIQ 9 has all four).
        retry("window") { fail -> v.getWindow().checkWindowPositionCapabilities({ cfg, map ->
            if (readable(cfg)) WINDOWS.forEach { w ->
                val b = map[WindowArea(w)] ?: map.entries.firstOrNull { w in it.key.values }?.value
                if (b != null) subscribeWindow(v, w, b)
            }
        }, fail) }

        retry("readingLight") { fail -> v.getLight().checkReadingLightStateCapabilities({ cfg, map ->
            if (readable(cfg) && map.isNotEmpty()) subscribeReadingLight(v, map.keys.firstOrNull { it == driverSeat } ?: map.keys.first())
        }, fail) }

        retry("turnSignal") { fail -> v.getTurnSignal().checkTurnSignalStateCapabilities({ cfg ->
            if (readable(cfg)) subscribeTurnSignal(v)
        }, fail) }

        retry("steeringHeat") { fail -> v.getSteeringWheel().checkHvacSteeringWheelHeatCapabilities({ cfg, b ->
            if (readable(cfg)) subscribeSteeringHeat(v, b)
        }, fail) }

        retry("seatHeat") { fail -> v.getSeat().checkSeatTemperatureCapabilities({ cfg, map ->
            val b = map[driverSeat] ?: map.entries.firstOrNull { SeatValue.ROW_1_LEFT in it.key.values }?.value
            if (readable(cfg) && b != null) subscribeSeatHeat(v, b)
        }, fail) }

        retry("wiper") { fail -> v.getWiper().checkWiperStateCapabilities({ cfg, map ->
            if (readable(cfg) && map.isNotEmpty()) subscribeWiper(v, map.keys.firstOrNull { it == windshield } ?: map.keys.first())
        }, fail) }

        retry("soc") { fail -> v.getEvBattery().checkEvBatteryStateOfChargeCapabilities({ cfg ->
            if (readable(cfg)) subscribeSoc(v)
        }, fail) }

        // Charge switch has no listener in Pleos: read now and again on every SoC change (subscribeSoc).
        // A definite EV_CHARGE_STATE wins; the emulator reports it as unknown, so the switch must still count.
        retry("chargeSwitch") { fail -> readChargeSwitch(v, fail) }

        retry("occupancy") { fail -> v.getSeat().checkSeatOccupancyCapabilities({ cfg, areas ->
            if (readable(cfg)) areas.forEach { subscribeOccupancy(v, it) }
        }, fail) }

        retry("belts") { fail -> v.getSeat().checkSeatBeltBuckledCapabilities({ cfg, areas ->
            if (readable(cfg)) areas.forEach { a -> a.toCarSeat()?.let { subscribeBelt(v, a, it) } }
        }, fail) }

        // v3 data. Each one is optional: no capability (or a failed read) leaves its VehicleState field unknown.
        retry("chargePort") { fail -> v.getEvBattery().checkEvChargePortOpenCapabilities({ cfg ->
            if (readable(cfg)) subscribeChargePort(v)
        }, fail) }

        retry("doors") { fail -> v.getDoor().checkDoorPositionCapabilities({ cfg, map ->
            Log.i(TAG, "doors ${cfg.accessType} ${map.keys.map { it.values.toList() }}")
            if (readable(cfg)) map.forEach { (area, b) -> if (area.values.any { it in CABIN_DOORS }) subscribeDoor(v, area, b) }
        }, fail) }

        // 출발 금고: all reported lock areas locked = the family left the car. Read only (CAR_DOORS); setDoorLock is never called.
        retry("doorLock") { fail -> v.getDoor().checkDoorLockCapabilities({ cfg, areas ->
            Log.i(TAG, "doorLock ${cfg.accessType} ${areas.map { it.values.toList() }}")
            // Cabin doors only: the emulator also reports HOOD, which would keep "all locked" false forever.
            if (readable(cfg)) areas.filter { a -> a.values.any { it in CABIN_DOORS } }.forEach { subscribeDoorLock(v, it) }
        }, fail) }

        retry("windowLock") { fail -> v.getWindow().checkWindowLockCapabilities({ cfg, areas ->
            Log.i(TAG, "windowLock ${cfg.accessType} ${areas.map { it.values.toList() }}")
            if (readable(cfg)) areas.filter { a -> a.values.any { it in REAR_WINDOWS } }.forEach { subscribeWindowLock(v, it) }
        }, fail) }

        // 마이 차고 skin. Read only (CAR_DRIVE): DrivingMode has no set in 2.3.2.
        retry("drivingMode") { fail -> v.getDrivingMode().checkDrivingModeStateCapabilities({ cfg ->
            if (readable(cfg)) subscribeDrivingMode(v)
        }, fail) }

        // Odometer has no capability check for range in 2.3.2: a successful first read stands in for it.
        retry("range") { fail -> v.getOdometer().getRangeRemainingDistance({ m ->
            if (m != null && !stale(v)) subscribeRange(v, m)
        }, fail) }

        // Pleos gear API is deprecated ("use the Google GEAR_SELECTION property"). CarPropertyManager GEAR_SELECTION is
        // primary; Pleos gear only fills in until/unless the first AAOS gear event arrives.
        subscribeGear(v)

        retry("ambient") { fail -> v.getLight().checkAmbientLightBrightnessColorCapabilities({ cfg, areas ->
            if (writable(cfg) && areas.isNotEmpty()) {
                ambientAreas = areas
                areas.forEach { saveAmbient(v, it) }
            }
        }, fail) }
        retry("ambientBounds") { fail -> v.getLight().checkAmbientLightBrightnessCapabilities({ _, map ->
            ambientBounds = map.values.firstOrNull()
        }, fail) }
    }

    /** Driver window (ROW_1_LEFT) fills windowDriverPercent, the others go into `windows`. */
    private fun subscribeWindow(v: Vehicle, value: WindowValue, b: AreaBounds<Int>) {
        if (stale(v)) return
        val area = WindowArea(value)
        val w = CarWindow.entries.firstOrNull { it.name == value.name } // null = driver
        val apply = { pos: Int? -> if (pos != null) { _state.update {
            if (w == null) it.copy(windowDriverPercent = pct(pos, b)) else it.copy(windows = it.windows + (w to pct(pos, b)))
        } } }
        v.getWindow().getCurrentWindowPosition(area, apply, log("window get $value"))
        val l = object : WindowPositionListener {
            override fun onWindowPositionUpdated(position: Int?) { apply(position) }
            override fun onFailed(e: Exception) = logE("window listen $value", e)
        }
        register({ v.getWindow().registerWindowPosition(area, l) }, { v.getWindow().unregisterWindowPosition(area, l) })
        // The game's rear window missions use ROW_2_RIGHT only; ROW_2_LEFT is state, not a capability.
        when (w) {
            null -> support(VehicleInput.WINDOW)
            CarWindow.ROW_1_RIGHT -> support(VehicleInput.PASSENGER_WINDOW)
            CarWindow.ROW_2_RIGHT -> support(VehicleInput.REAR_WINDOW)
            CarWindow.ROW_2_LEFT -> Unit
        }
    }

    private fun subscribeBelt(v: Vehicle, area: SeatArea, seat: CarSeat) {
        if (stale(v)) return
        val apply = { on: Boolean? -> if (on != null) { _state.update { it.copy(belts = if (on) it.belts + seat else it.belts - seat) } } }
        v.getSeat().getCurrentSeatBeltBuckledState(area, apply, log("belt get $seat"))
        val l = object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) { apply(buckled) }
            override fun onFailed(e: Exception) = logE("belt listen $seat", e)
        }
        register({ v.getSeat().registerSeatBeltBuckledState(area, l) }, { v.getSeat().unregisterSeatBeltBuckledState(area, l) })
        _state.update { it.copy(beltSeats = it.beltSeats + seat) } // belt missions only target these seats
        support(VehicleInput.SEATBELT)
    }

    private fun subscribeReadingLight(v: Vehicle, area: SeatArea) {
        if (stale(v)) return
        val apply = { s: LightState? -> if (s != null) { _state.update { it.copy(readingLightOn = s == LightState.ON) } } }
        v.getLight().getCurrentReadingLightState(area, apply, log("reading get"))
        val l = object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) { apply(state) }
            override fun onFailed(e: Exception) = logE("reading listen", e)
        }
        register({ v.getLight().registerReadingLightState(area, l) }, { v.getLight().unregisterReadingLightState(area, l) })
        support(VehicleInput.READING_LIGHT)
    }

    private fun subscribeTurnSignal(v: Vehicle) {
        if (stale(v)) return
        // SDK has no HAZARD value, so TurnSignal.HAZARD never comes from this gateway.
        val apply = { s: TurnSignalValue? -> if (s != null) {
            val t = when (s) { TurnSignalValue.LEFT -> TurnSignal.LEFT; TurnSignalValue.RIGHT -> TurnSignal.RIGHT; else -> TurnSignal.OFF }
            _state.update { it.copy(turnSignal = t) }
        } }
        v.getTurnSignal().getCurrentTurnSignalState(apply, log("turn get"))
        val l = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) { apply(state) }
            override fun onFailed(e: Exception) = logE("turn listen", e)
        }
        register({ v.getTurnSignal().registerTurnSignalState(l) }, { v.getTurnSignal().unregisterTurnSignalState(l) })
        support(VehicleInput.TURN_SIGNAL)
    }

    private fun subscribeSteeringHeat(v: Vehicle, b: AreaBounds<Int>) {
        if (stale(v)) return
        val top = b.maxValue - maxOf(b.minValue, 0) // highest heat level; missions never ask above it
        if (top <= 0) return
        _state.update { it.copy(steeringHeatMax = top) }
        // Level = raw - max(min, 0): 0 means off. Negative (cooling) values clamp to 0.
        val apply = { lv: Int? -> if (lv != null) { _state.update { it.copy(steeringHeatLevel = (lv - maxOf(b.minValue, 0)).coerceAtLeast(0)) } } }
        v.getSteeringWheel().getHvacSteeringWheelHeatState(apply, log("steering get"))
        val l = object : HvacSteeringWheelHeatStateListener {
            override fun onHvacSteeringWheelHeatStateUpdated(state: Int?) { apply(state) }
            override fun onFailed(e: Exception) = logE("steering listen", e)
        }
        register({ v.getSteeringWheel().registerHvacSteeringWheelHeatState(l) }, { v.getSteeringWheel().unregisterHvacSteeringWheelHeatState(l) })
        support(VehicleInput.STEERING_HEAT)
    }

    private fun subscribeSeatHeat(v: Vehicle, b: AreaBounds<Int>) {
        if (stale(v)) return
        val top = b.maxValue - maxOf(b.minValue, 0)
        if (top <= 0) return
        _state.update { it.copy(seatHeatMax = top) }
        // AAOS convention: negative = ventilation, positive = heat. Not confirmed for Pleos; clamp to heat side.
        val apply = { t: Int? -> if (t != null) { _state.update { it.copy(seatHeatDriverLevel = (t - maxOf(b.minValue, 0)).coerceAtLeast(0)) } } }
        v.getSeat().getHvacSeatTemperature(driverSeat, apply, log("seat get"))
        val l = object : HvacSeatTemperatureListener {
            override fun onHvacSeatTemperatureUpdated(temperature: Int?) { apply(temperature) }
            override fun onFailed(e: Exception) = logE("seat listen", e)
        }
        register({ v.getSeat().registerHvacSeatTemperature(driverSeat, l) }, { v.getSeat().unregisterHvacSeatTemperature(driverSeat, l) })
        support(VehicleInput.SEAT_HEAT)
    }

    private fun subscribeWiper(v: Vehicle, area: WindowArea) {
        if (stale(v)) return
        val apply = { s: WiperState? -> if (s != null) { _state.update { it.copy(wiperOn = s == WiperState.ON) } } }
        v.getWiper().getCurrentWiperState(area, apply, log("wiper get"))
        val l = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) { apply(state) }
            override fun onFailed(e: Exception) = logE("wiper listen", e)
        }
        register({ v.getWiper().registerWiperState(area, l) }, { v.getWiper().unregisterWiperState(area, l) })
        support(VehicleInput.WIPER)
    }

    private fun subscribeSoc(v: Vehicle) {
        if (stale(v)) return
        val apply = { soc: Int? -> if (soc != null) {
            val p = soc.toFloat().coerceIn(0f, 100f)
            // No charge limit from the car: guess once, never below 80 and always ahead of the current SoC.
            val guess = if (targetKnown) null else maxOf(80, p.toInt() + 10).coerceAtMost(100).also { targetKnown = true }
            socKnown = true
            _state.update { it.copy(socPercent = p, socKnown = true, charging = chargingRaw, chargeTargetPercent = guess ?: it.chargeTargetPercent) }
            readChargeSwitch(v, log("chargeSwitch get"))
        } }
        v.getEvBattery().getEvBatteryStateOfCharge(apply, log("soc get"))
        val l = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) { apply(value) }
            override fun onFailed(e: Exception) = logE("soc listen", e)
        }
        register({ v.getEvBattery().registerEvBatteryStateOfCharge(l) }, { v.getEvBattery().unregisterEvBatteryStateOfCharge(l) })
    }

    private fun subscribeOccupancy(v: Vehicle, area: SeatArea) {
        if (stale(v)) return
        val apply = { on: Boolean? -> if (on != null) {
            val (n, seats) = synchronized(occupied) {
                occupied[area] = on
                occupied.values.count { it } to occupied.filterValues { it }.keys.mapNotNull { it.toCarSeat() }.toSet()
            }
            _state.update { it.copy(occupants = n.coerceAtLeast(1), occupied = seats) }
        } }
        v.getSeat().getCurrentSeatOccupancy(area, apply, log("occupancy get"))
        val l = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupancy: Boolean?) { apply(occupancy) }
            override fun onFailed(e: Exception) = logE("occupancy listen", e)
        }
        register({ v.getSeat().registerSeatOccupancy(area, l) }, { v.getSeat().unregisterSeatOccupancy(area, l) })
    }

    private fun subscribeChargePort(v: Vehicle) {
        if (stale(v)) return
        val apply = { open: Boolean? -> if (open != null) { _state.update { it.copy(chargePortOpen = open) } } }
        v.getEvBattery().isEvChargePortOpen(apply, log("chargePort get"))
        val l = object : EvChargePortOpenListener {
            override fun onEvChargePortOpenUpdated(open: Boolean?) { apply(open) }
            override fun onFailed(e: Exception) = logE("chargePort listen", e)
        }
        register({ v.getEvBattery().registerEvChargePortOpen(l) }, { v.getEvBattery().unregisterEvChargePortOpen(l) })
    }

    /** Any cabin door above its closed position = doorOpen. Used only to pause, never as "someone got out". */
    private fun subscribeDoor(v: Vehicle, area: DoorArea, b: AreaBounds<Int>) {
        if (stale(v)) return
        val apply = { pos: Int? -> if (pos != null) {
            val any = synchronized(doorsOpen) { doorsOpen[area] = pos > b.minValue; doorsOpen.values.any { it } }
            _state.update { it.copy(doorOpen = any) }
        } }
        v.getDoor().getCurrentDoorPosition(area, apply, log("door get"))
        val l = object : DoorPositionListener {
            override fun onPositionUpdated(position: Int?) { apply(position) }
            override fun onFailed(e: Exception) = logE("door listen", e)
        }
        register({ v.getDoor().registerDoorPosition(area, l) }, { v.getDoor().unregisterDoorPosition(area, l) })
    }

    /** 2nd row window lock (child lock). The engine swaps 2nd row window missions to a front window while it is on. */
    private fun subscribeWindowLock(v: Vehicle, area: WindowArea) {
        if (stale(v)) return
        val apply = { on: Boolean? -> if (on != null) {
            val any = synchronized(rearLocks) { rearLocks[area] = on; rearLocks.values.any { it } }
            _state.update { it.copy(rearWindowLocked = any) }
        } }
        v.getWindow().getCurrentWindowLockState(area, apply, log("windowLock get"))
        val l = object : WindowLockStateListener {
            override fun onWindowLockStateUpdated(locked: Boolean?) { apply(locked) }
            override fun onFailed(e: Exception) = logE("windowLock listen", e)
        }
        register({ v.getWindow().registerWindowLockState(area, l) }, { v.getWindow().unregisterWindowLockState(area, l) })
    }

    /** doorLocked = every lock area that answered is locked. Unknown until the first answer. */
    private fun subscribeDoorLock(v: Vehicle, area: DoorArea) {
        if (stale(v)) return
        val apply = { on: Boolean? -> if (on != null && !stale(v)) {
            val all = synchronized(doorLocks) { doorLocks[area] = on; doorLocks.values.all { it } }
            _state.update { it.copy(doorLocked = all) }
        } }
        v.getDoor().getDoorLockState(area, apply, log("doorLock get"))
        val l = object : DoorLockStateListener {
            override fun onDoorLockStateUpdated(locked: Boolean?) { apply(locked) }
            override fun onFailed(e: Exception) = logE("doorLock listen", e)
        }
        register({ v.getDoor().registerDoorLockState(area, l) }, { v.getDoor().unregisterDoorLockState(area, l) })
    }

    private fun subscribeDrivingMode(v: Vehicle) {
        if (stale(v)) return
        val apply = { m: DrivingModeType? -> if (m != null && !stale(v)) {
            _state.update { it.copy(driveMode = DriveMode.entries.firstOrNull { d -> d.name == m.name }) }
        } }
        v.getDrivingMode().getCurrentDrivingModeState(apply, log("drivingMode get"))
        val l = object : DrivingModeStateListener {
            override fun onDrivingModeStateUpdated(mode: DrivingModeType?) { apply(mode) }
            override fun onFailed(e: Exception) = logE("drivingMode listen", e)
        }
        register({ v.getDrivingMode().registerDrivingModeState(l) }, { v.getDrivingMode().unregisterDrivingModeState(l) })
    }

    @Volatile private var rangeListening = false

    /** Unit taken as meters (Virtual Console card); confirm on a real car before printing km in materials. */
    private fun subscribeRange(v: Vehicle, first: Float) {
        val apply = { m: Float? -> if (m != null && m >= 0f && !stale(v)) { _state.update { it.copy(rangeMeters = m) } } }
        apply(first)
        if (rangeListening) return
        rangeListening = true
        val l = object : RangeRemainingListener {
            override fun onRangeRemainingChanged(meters: Float?) { apply(meters) }
            override fun onFailed(e: Exception) = logE("range listen", e)
        }
        register({ v.getOdometer().registerRangeRemainingListener(l) }, { v.getOdometer().unregisterRangeRemainingListener(l) })
    }

    @Volatile private var gearListening = false

    @Suppress("DEPRECATION")
    private fun subscribeGear(v: Vehicle) {
        val apply = { g: VehicleGear? -> if (g != null && !aaosGearOk && !stale(v)) { _state.update { it.copy(gear = g.toGear()) } } }
        val l = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) { apply(gear) }
            override fun onFailed(e: Exception) = logE("gear listen", e)
        }
        // Register only after a get succeeded (service bound), like the capability-gated inputs; before that it fails silently.
        retry("gear get") { fail -> v.getGear().getCurrentGearSelectionState({ g ->
            apply(g)
            if (!gearListening && !stale(v)) {
                gearListening = true
                register({ v.getGear().registerGearSelection(l) }, { v.getGear().unregisterGearSelection(l) })
            }
        }, fail) }
    }

    // ---------- android.car properties ----------

    @Suppress("DEPRECATION") // registerCallback/status: subscribePropertyEvents is API 35+, minSdk is 28
    private val propCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
            safe("prop ${value.propertyId}") { when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                    _state.update { it.copy(speedKmh = kotlin.math.abs((value.value as Float) * 3.6f)) }
                VehiclePropertyIds.GEAR_SELECTION ->
                    { aaosGearOk = true; _state.update { it.copy(gear = aaosGear(value.value as Int)) } }
                VehiclePropertyIds.EV_CHARGE_STATE -> {
                    val s = value.value as Int
                    Log.i(TAG, "EV_CHARGE_STATE $s")
                    // Either source saying "charging" wins: the Pleos emulator pins this to FULLY_CHARGED (2) while the
                    // charge switch is on and SoC rises. A false positive is harmless: ChargeClock needs a rising SoC.
                    chargeState = if (s == EvChargeState.STATE_UNKNOWN) null else s == EvChargeState.STATE_CHARGING
                    setCharging(chargeState == true || chargeSwitch ?: (chargeState == null))
                }
                VehiclePropertyIds.EV_CHARGE_PERCENT_LIMIT ->
                    { targetKnown = true; _state.update { it.copy(chargeTargetPercent = (value.value as Float).toInt().coerceIn(1, 100)) } }
            } }
        }
        override fun onErrorEvent(propId: Int, zone: Int) { Log.w(TAG, "prop error $propId zone $zone") }
    }

    @Suppress("DEPRECATION")
    private fun startCarProperties() {
        try {
            val c = Car.createCar(context) ?: return
            car = c
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            props = pm
            listOf(
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                VehiclePropertyIds.GEAR_SELECTION,
                VehiclePropertyIds.EV_CHARGE_STATE,
                VehiclePropertyIds.EV_CHARGE_PERCENT_LIMIT,
            ).forEach { id ->
                safe("prop $id") {
                    if (pm.isSupported(id)) {
                        pm.registerCallback(propCallback, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                    } else Log.i(TAG, "prop $id not supported")
                }
            }
            unregisters += { pm.unregisterCallback(propCallback) }
        } catch (t: Throwable) {
            // Non automotive device or missing permission. Speed stays 0, gear stays whatever Pleos says (UNKNOWN by default).
            Log.w(TAG, "CarPropertyManager unavailable", t)
        }
    }

    private fun CarPropertyManager.isSupported(id: Int) = try { getCarPropertyConfig(id) != null } catch (_: Throwable) { false }

    // ---------- Ambient ----------

    private fun saveAmbient(v: Vehicle, area: SeatArea) {
        if (stale(v)) return
        val l = v.getLight()
        // Save the car's own colors once per gateway: a later start() would read back the game's colors.
        if (synchronized(ambientSaved) { ambientSaved.putIfAbsent(area, Triple(null, null, null)) != null }) return
        fun put(f: (Triple<Int?, Int?, LightSwitch?>) -> Triple<Int?, Int?, LightSwitch?>) =
            synchronized(ambientSaved) { ambientSaved[area]?.let { ambientSaved[area] = f(it) } }
        // A failed read forgets the area so the next start() reads it again; until then setAmbient leaves it alone.
        fun failed(what: String): (Exception) -> Unit = { e -> logE(what, e); synchronized(ambientSaved) { ambientSaved.remove(area) } }
        l.getAmbientLightBrightnessColor(area, { c -> put { it.copy(first = c) } }, failed("ambient color get"))
        l.getAmbientLightBrightness(area, { b -> put { it.copy(second = b) } }, failed("ambient brightness get"))
        l.getAmbientLightSwitch(area, { s -> put { it.copy(third = s) } }, failed("ambient switch get"))
    }

    override fun setAmbient(color: Int, brightness: Int) {
        val l = vehicle?.getLight() ?: return
        val b = ambientBounds
        val raw = if (b == null) brightness else b.minValue + (b.maxValue - b.minValue) * brightness.coerceIn(0, 100) / 100
        // Color encoding assumed 0xRRGGBB (not confirmed by javap; verify on emulator Virtual Console).
        // Only areas whose own colors are fully saved: anything else could not be put back.
        val restorable = synchronized(ambientSaved) { ambientSaved.filterValues { it.first != null && it.second != null && it.third != null }.keys }
        ambientAreas.filter { it in restorable }.forEach { area -> safe("ambient set") {
            l.setAmbientLightSwitch(area, LightSwitch.ON, { _, _ -> }, log("ambient switch set"))
            l.setAmbientLightBrightnessColor(area, color and 0xFFFFFF, { _, _ -> }, log("ambient color set"))
            l.setAmbientLightBrightness(area, raw, { _, _ -> }, log("ambient brightness set"))
        } }
    }

    override fun restoreAmbient() {
        val l = vehicle?.getLight() ?: return
        val saved = synchronized(ambientSaved) { ambientSaved.toMap() }
        saved.forEach { (area, s) -> safe("ambient restore") {
            s.first?.let { l.setAmbientLightBrightnessColor(area, it, { _, _ -> }, log("ambient color restore")) }
            s.second?.let { l.setAmbientLightBrightness(area, it, { _, _ -> }, log("ambient brightness restore")) }
            s.third?.let { l.setAmbientLightSwitch(area, it, { _, _ -> }, log("ambient switch restore")) }
        } }
    }

    // ---------- helpers ----------

    /** Retries a call up to 3 times, 1.5 s apart, to ride out the unannounced Pleos service binding. */
    private fun retry(name: String, attempt: Int = 0, call: (onFailed: (Exception) -> Unit) -> Unit) {
        if (!running) return
        safe(name) {
            call { e ->
                logE(name, e)
                if (attempt < 3 && running) main.postDelayed({ retry(name, attempt + 1, call) }, 1500L)
            }
        }
    }

    private fun register(on: () -> Unit, off: () -> Unit) {
        safe("register") { on(); synchronized(unregisters) { unregisters += off } }
    }

    @Volatile private var chargeState: Boolean? = null // definite EV_CHARGE_STATE, null = unknown
    @Volatile private var chargeSwitch: Boolean? = null // Pleos EV charge switch

    private fun readChargeSwitch(v: Vehicle, fail: (Exception) -> Unit) {
        if (stale(v)) return
        v.getEvBattery().getEvChargeSwitchState({ on ->
            if (on != null && !stale(v)) { chargeSwitch = on; setCharging(chargeState == true || on) }
        }, fail)
    }

    private fun setCharging(c: Boolean) {
        chargingRaw = c
        _state.update { it.copy(charging = c && socKnown) }
    }

    /** Callback from a Vehicle released by stop() (or from before a restart): ignore it. */
    private fun stale(v: Vehicle) = !running || v !== vehicle

    private fun support(i: VehicleInput) = _supported.update { it + i }

    private fun readable(c: CapabilityConfig) = c.accessType == AccessType.READ || c.accessType == AccessType.READ_WRITE
    private fun writable(c: CapabilityConfig) = c.accessType == AccessType.WRITE || c.accessType == AccessType.READ_WRITE

    private fun pct(pos: Int, b: AreaBounds<Int>): Int {
        val span = b.maxValue - b.minValue
        return if (span <= 0) 0 else ((pos - b.minValue) * 100 / span).coerceIn(0, 100)
    }

    private fun log(what: String): (Exception) -> Unit = { logE(what, it) }
    private fun logE(what: String, e: Exception) { Log.w(TAG, "$what failed: ${e.message}") }
    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "$what threw", t) }
    }
}

private val WINDOWS = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)
private val REAR_WINDOWS = setOf(WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)
/** Doors people use; hood and tailgate (REAR) are not a reason to pause. */
private val CABIN_DOORS = setOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT, DoorValue.ROW_3_LEFT, DoorValue.ROW_3_RIGHT)

/** Single-seat area to the contract seat by name; multi-seat or center-of-row-1/3 areas map to null. */
internal fun SeatArea.toCarSeat(): CarSeat? = values.singleOrNull()?.let { s -> CarSeat.entries.firstOrNull { it.name == s.name } }

internal fun VehicleGear.toGear(): Gear = when (this) {
    VehicleGear.GEAR_PARK -> Gear.PARK
    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
    VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
    else -> Gear.DRIVE // GEAR_DRIVE, GEAR_1..9
}

/** AAOS VehicleGear bit values: N=1, R=2, P=4, D=8, GEAR_1..9 = 16..4096. */
internal fun aaosGear(v: Int): Gear = when (v) {
    android.car.VehicleGear.GEAR_PARK -> Gear.PARK
    android.car.VehicleGear.GEAR_REVERSE -> Gear.REVERSE
    android.car.VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
    android.car.VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
    else -> if (v >= android.car.VehicleGear.GEAR_DRIVE) Gear.DRIVE else Gear.UNKNOWN
}
