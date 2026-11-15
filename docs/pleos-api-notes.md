# Pleos Vehicle SDK 2.3.2 API 노트 (javap 근거)

근거: `ai.pleos.playground:Vehicle:2.3.2` AAR의 `classes.jar`를 `javap -public` / `javap -v`(어노테이션) 로 분석. 의존 아티팩트 `vehicle-data:1.1.11`, `vehicle-aidl:1.1.1` 도 확인. 추측 없음. javap로 확정 안 되는 의미는 "미확정"으로 표기.

표기: Kotlin 람다로 축약. `(T)->Unit` = `kotlin.jvm.functions.Function1<? super T, Unit>`. 모든 get/set/capability 는 `onComplete`, `onFailed: (Exception)->Unit` 두 람다를 받는 비동기 IPC.

## 패키지

| 종류 | 패키지 |
|---|---|
| 진입점 | `ai.pleos.playground.vehicle.Vehicle` |
| 도메인 인터페이스 | `ai.pleos.playground.vehicle.api.*` |
| Area | `ai.pleos.playground.vehicle.api.model.area.{SeatArea, WindowArea, TurnSignalArea, DoorArea, MirrorArea, WheelArea, GlobalArea}` |
| Area 값 enum | `ai.pleos.playground.vehicle.constant.area.{SeatValue, WindowValue, TurnSignalValue, ...}` |
| 제어 enum | `ai.pleos.playground.vehicle.constant.control.{VehicleGear, LightSwitch, LightState, WiperState, WiperSwitch, ...}` |
| Capability | `ai.pleos.playground.vehicle.api.model.capability.{CapabilityConfig, AccessType, AreaBounds}` |
| 리스너 | `ai.pleos.playground.vehicle.listener.*` (모두 `VehicleListener { fun onFailed(e: Exception) }` 상속) |
| 퍼미션 상수 | `ai.pleos.playground.vehicle.constant.PleosPermission` (값 = `"pleos.car.permission.<이름>"`) |
| ErrorCode | `ai.pleos.playground.vehicle.constant.ErrorCode` **(vehicle-data 아티팩트에 있음, 아래 주의 참고)** |

## 생명주기

```kotlin
class Vehicle(context: Context) {
  fun initialize()          // 반환값·준비완료 콜백 없음
  fun release()
  fun getEvBattery(): EvBattery;   fun getHVAC(): HVAC;        fun getDoor(): Door
  fun getSeat(): Seat;             fun getWindow(): Window;    fun getDisplay(): Display
  fun getOdometer(): Odometer;     fun getLight(): Light;      fun getSafety(): Safety
  fun getCarInfo(): CarInfo;       fun getDrivingMode(): DrivingMode
  fun getGear(): Gear;             fun getSteeringWheel(): SteeringWheel
  fun getToll(): Toll;             fun getTurnSignal(): TurnSignal
  fun getWiper(): Wiper;           fun getTire(): Tire;        fun getSideMirror(): SideMirror
  fun getBrake(): Brake;           fun getFuel(): Fuel
}
```
- `initialize()` 는 void, 연결 완료 콜백이 공개 API에 없다. 연결 전 호출은 `onFailed`(ErrorCode `ERROR_SERVICE_CONNECTION_FAILURE` 예상)로 올 수 있으니 실패 시 재시도/재구독 처리 필요.
- 문서: 비동기 콜백이 끝나기 전 `release()` 금지. Activity `onStart`→initialize+register, `onStop`→unregister 후 release 권장.
- SDK 매니페스트가 자동 병합: `uses-permission ai.pleos.playground.service.vehicle.VEHICLE_BINDING`, `<queries>` action `ai.pleos.playground.vehicle.VEHICLE_START`, 최소 Vehicle Service 2.3.13(code 65).

## 공통 모델
- `AreaBounds<T>(minValue: T, maxValue: T)`
- `CapabilityConfig(accessType: AccessType)`, `AccessType = NONE | READ | WRITE | READ_WRITE`
- `SeatArea(vararg SeatValue)`, `SeatValue = ROW_1_LEFT, ROW_1_CENTER, ROW_1_RIGHT, ROW_2_*, ROW_3_*`
- `WindowArea(vararg WindowValue)`, `WindowValue = FRONT_WINDSHIELD, REAR_WINDSHIELD, ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_RIGHT, ROOF_TOP_1, ROOF_TOP_2`
- 운전석 = `ROW_1_LEFT` 로 가정(좌핸들 국내 기준. SDK에 "DRIVER" 상수는 없음).
- `ErrorCode = ERROR_INTERNAL_SERVICE, ERROR_SERVICE_CONNECTION_FAILURE, ERROR_DRIVING_RESTRICTED, ERROR_NOT_SUPPORTED_PROPERTY, ERROR_NOT_SUPPORTED_VALUE_TYPE, ERROR_NOT_SUPPORTED_AREA, ERROR_EMPTY_RESULT, ERROR_SECURITY_PERMISSION, ERROR_UNDEFINED`

## 신호별 API

퍼미션은 각 메서드의 `@androidx.annotation.RequiresPermission(value=...)` 를 javap -v 로 읽은 값.

### 운전석 창문 위치 · `vehicle.getWindow()` · 읽기 CAR_WINDOWS / 쓰기 CONTROL_CAR_WINDOWS
```kotlin
checkWindowPositionCapabilities((CapabilityConfig, Map<WindowArea, AreaBounds<Int>>)->Unit, onFailed)
getCurrentWindowPosition(area: WindowArea, (Int)->Unit, onFailed)
registerWindowPosition(area: WindowArea, WindowPositionListener) / unregisterWindowPosition(area, l)
setWindowPosition(area: WindowArea, pos: Int, (WindowArea, Int)->Unit, onFailed)    // CONTROL_CAR_WINDOWS
interface WindowPositionListener { fun onWindowPositionUpdated(pos: Int?) }
```
값 범위는 capability 의 `AreaBounds` 로 확인 (0=닫힘 여부 등 의미는 javap로 미확정 → min..max 를 0..100% 로 정규화할 것).

### 독서등(reading/map light) · `vehicle.getLight()` · CAR_LIGHTS (set은 CONTROL_CAR_LIGHTS)
```kotlin
checkReadingLightStateCapabilities((CapabilityConfig, Map<SeatArea, List<LightState>>)->Unit, onFailed)
checkReadingLightSwitchCapabilities((CapabilityConfig, Map<SeatArea, List<LightSwitch>>)->Unit, onFailed)
getCurrentReadingLightState(area: SeatArea, (LightState)->Unit, onFailed)
getReadingLightSwitch(area: SeatArea, (LightSwitch)->Unit, onFailed)
registerReadingLightState(area: SeatArea, LightStateListener) / unregisterReadingLightState(area, l)
setReadingLightSwitch(area, LightSwitch, (LightSwitch)->Unit, onFailed)   // CONTROL_CAR_LIGHTS
interface LightStateListener { fun onLightStateUpdated(state: LightState?) }
LightState = OFF | ON | DAYTIME_RUNNING;  LightSwitch = OFF | ON | DAYTIME_RUNNING | AUTOMATIC
```

### 실내등(cabin light) · `getLight()` · CAR_LIGHTS
```kotlin
checkCabinLightStateCapabilities((CapabilityConfig, List<LightState>)->Unit, onFailed)
getCurrentCabinLightState((LightState)->Unit, onFailed)
registerCabinLightState(LightStateListener) / unregisterCabinLightState(l)
setCabinLightSwitch(LightSwitch, (LightSwitch)->Unit, onFailed)   // CONTROL_CAR_LIGHTS
```

### 앰비언트 라이트 색/밝기 set · `getLight()` · SDK 어노테이션은 CAR_LIGHTS, 단 에뮬레이터는 CONTROL_CAR_LIGHTS 없이 거부(2026-10-23 실측)
```kotlin
checkAmbientLightBrightnessCapabilities((CapabilityConfig, Map<SeatArea, AreaBounds<Int>>)->Unit, onFailed)
checkAmbientLightBrightnessColorCapabilities((CapabilityConfig, List<SeatArea>)->Unit, onFailed)
checkAmbientLightSwitchCapabilities((CapabilityConfig, Map<SeatArea, List<LightSwitch>>)->Unit, onFailed)
getAmbientLightBrightness(area: SeatArea, (Int)->Unit, onFailed)
setAmbientLightBrightness(area: SeatArea, v: Int, (SeatArea, Int)->Unit, onFailed)
getAmbientLightBrightnessColor(area: SeatArea, (Int)->Unit, onFailed)
setAmbientLightBrightnessColor(area: SeatArea, color: Int, (SeatArea, Int)->Unit, onFailed)
getAmbientLightSwitch / setAmbientLightSwitch(area, LightSwitch, (SeatArea, LightSwitch)->Unit, onFailed)
getAmbientLightState(area, (LightState)->Unit, onFailed)
get/setAmbientColorAutoByDriveModeEnabled(Boolean)   // set 은 CONTROL_CAR_LIGHTS
registerAmbientLightBrightnessListener(area, AmbientLightBrightnessListener { onBrightnessUpdated(Int?) })
registerAmbientLightBrightnessColorListener(area, AmbientLightBrightnessColorListener { onBrightnessColorUpdated(Int?) })
```
- color 값 인코딩(0xRRGGBB 여부) 은 javap로 미확정. 에뮬레이터 Virtual Console 로 실측 필요.
- restore: 게임 시작 전 `getAmbientLightBrightness/Color/Switch` 로 원래 값을 저장 → 종료 시 set.
- 드라이브모드 연동 자동색이 켜져 있으면 덮어쓸 수 있음 → `getAmbientColorAutoByDriveModeEnabled` 확인.

### 방향지시등 · `vehicle.getTurnSignal()` · CAR_LIGHTS
```kotlin
checkTurnSignalStateCapabilities((CapabilityConfig)->Unit, onFailed)
getCurrentTurnSignalState((TurnSignalValue)->Unit, onFailed)
registerTurnSignalState(TurnSignalStateListener) / unregisterTurnSignalState(l)
interface TurnSignalStateListener { fun onTurnSignalStateUpdated(v: TurnSignalValue?) }
TurnSignalValue = OFF | RIGHT | LEFT     // getId(): Int
```
- **비상등(HAZARD) 값 없음.** 대안: 짧은 시간 내 LEFT/RIGHT 동시 이벤트로는 판별 불가하므로 Contract의 `TurnSignal.HAZARD` 는 SDK 경로에서 생성 불가. AAOS `VehiclePropertyIds.HAZARD_LIGHTS_STATE`(289410563) 존재. 단 android.car.jar 의 공개 `Car.PERMISSION_*` 상수에 외부등 권한이 없어 3rd party 접근 가능 여부 미확정 → 에뮬레이터 실측 전까지 퍼즐에서 비상등 미사용 권장.

### 핸들 열선 레벨 · `vehicle.getSteeringWheel()` · 읽기 CAR_CLIMATE / 쓰기 CONTROL_CAR_CLIMATE
```kotlin
checkHvacSteeringWheelHeatCapabilities((CapabilityConfig, AreaBounds<Int>)->Unit, onFailed)
getHvacSteeringWheelHeatState((Int)->Unit, onFailed)
registerHvacSteeringWheelHeatState(HvacSteeringWheelHeatStateListener) / unregister...
setHvacSteeringWheelHeat(level: Int, (Int)->Unit, onFailed)   // CONTROL_CAR_CLIMATE
interface HvacSteeringWheelHeatStateListener { fun onHvacSteeringWheelHeatStateUpdated(level: Int?) }
```
레벨 범위는 AreaBounds(min..max). 0=끔 여부는 미확정(min 값으로 판단).

### 운전석 시트 열선 레벨 · `vehicle.getSeat()` · 읽기 CAR_SEATS / 쓰기 CONTROL_CAR_SEATS
```kotlin
checkSeatTemperatureCapabilities((CapabilityConfig, Map<SeatArea, AreaBounds<Int>>)->Unit, onFailed)
getHvacSeatTemperature(area: SeatArea, (Int)->Unit, onFailed)
registerHvacSeatTemperature(area: SeatArea, HvacSeatTemperatureListener) / unregister...(area, l)
setHvacSeatTemperature(area, level: Int, (SeatArea, Int)->Unit, onFailed)   // CONTROL_CAR_SEATS
interface HvacSeatTemperatureListener { fun onHvacSeatTemperatureUpdated(v: Int?) }
```
HVAC 도메인이 아니라 Seat 도메인. AAOS `HVAC_SEAT_TEMPERATURE` 관례상 음수=통풍, 양수=열선일 가능성이 있으나 javap로 미확정 → AreaBounds 로 확인. 통풍은 별도 `getHvacSeatVentilationState`.

### 와이퍼 상태 · `vehicle.getWiper()` · 읽기 CAR_WINDOWS / 쓰기 CONTROL_CAR_WINDOWS
```kotlin
checkWiperStateCapabilities((CapabilityConfig, Map<WindowArea, List<WiperState>>)->Unit, onFailed)
getCurrentWiperState(area: WindowArea, (WiperState)->Unit, onFailed)
registerWiperState(area: WindowArea, WiperStateListener) / unregisterWiperState(area, l)
getCurrentWiperSwitch(area, (WiperSwitch)->Unit, onFailed)   // 레버 위치. 콜백 등록 API는 없음
setWiperSwitch(area, WiperSwitch, ...)   // CONTROL_CAR_WINDOWS
interface WiperStateListener { fun onWiperStateUpdated(s: WiperState?) }
WiperState = OTHER | OFF | ON | SERVICE
WiperSwitch = OTHER | OFF | MIST | INTERMITTENT_LEVEL_1..5 | CONTINUOUS_LEVEL_1..5 | AUTO | SERVICE
```
area 는 `WindowArea(WindowValue.FRONT_WINDSHIELD)`.

### EV 배터리 SoC · 충전 · 목표 · `vehicle.getEvBattery()` · 읽기 CAR_ENERGY / 쓰기 CONTROL_CAR_ENERGY
```kotlin
checkEvBatteryStateOfChargeCapabilities((CapabilityConfig)->Unit, onFailed)
getEvBatteryStateOfCharge((Int)->Unit, onFailed)                 // SoC (정수, % 로 추정)
registerEvBatteryStateOfCharge(EvBatteryStateOfChargeListener { onEvBatteryStateOfChargeUpdated(Int?) })
getEvBatteryLevel((Float)->Unit, onFailed)                       // 잔량 (단위 미확정, AAOS 관례는 Wh)
registerEvBatteryLevel(EvBatteryLevelListener { onEvBatteryLevelUpdated(Float?) })
getEvBatteryCapacity((Float)->Unit, onFailed)                    // 용량 → level/capacity 로 % 계산 가능
getEvChargeSwitchState((Boolean)->Unit, onFailed)                // 충전 스위치 on/off (콜백 없음)
isEvChargePortOpen((Boolean)->Unit, onFailed) / registerEvChargePortOpen(EvChargePortOpenListener)
checkEvChargePercentLimitCapabilities((CapabilityConfig, List<Float>)->Unit, onFailed)
setEvChargePercentLimit(Float, (Float)->Unit, onFailed)          // CONTROL_CAR_ENERGY
```
- **충전 목표(%) getter 없음** (set 과 capability 만 있음).
- **충전 상태(CHARGING/FULLY_CHARGED) 공개 API 없음.** `EvChargeState` enum 은 존재하지만 이를 반환하는 public 메서드 없음. **충전 잔여시간 없음.**
- 대안(AAOS CarPropertyManager, 권한 `android.car.permission.CAR_ENERGY`): `VehiclePropertyIds.EV_CHARGE_STATE`(289410881), `EV_CHARGE_TIME_REMAINING`(289410883, 초), `EV_CHARGE_PERCENT_LIMIT`(291508032), `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE`(291504908, mW). 차량 지원 여부는 `getCarPropertyConfig(id) != null` 로 확인. 그래도 없으면 SoC 변화율로 ETA 추정, 목표는 사용자 입력(기본 80).

### 탑승 감지 · 안전벨트 · `vehicle.getSeat()` CAR_SEATS / `vehicle.getSafety()` CAR_SAFETY
```kotlin
// Seat (CAR_SEATS)
checkSeatOccupancyCapabilities((CapabilityConfig, List<SeatArea>)->Unit, onFailed)
getCurrentSeatOccupancy(area: SeatArea, (Boolean)->Unit, onFailed)
registerSeatOccupancy(area, SeatOccupancyListener { onSeatOccupancyUpdated(Boolean?) })
checkSeatBeltBuckledCapabilities((CapabilityConfig, List<SeatArea>)->Unit, onFailed)
getCurrentSeatBeltBuckledState(area, (Boolean)->Unit, onFailed)
registerSeatBeltBuckledState(area, SeatBeltBuckledStateListener { onSeatBeltBuckledStateUpdated(Boolean?) })
// Safety (CAR_SAFETY)
hasSeatBeltNotBuckledWarning(area, (Boolean)->Unit, onFailed)
registerSeatBeltNotBuckledWarning(area, SeatBeltNotBuckledWarningListener)
```
인원수 = 지원 SeatArea 들의 occupancy true 개수.

### 기어 · `vehicle.getGear()` · CAR_DRIVE
```kotlin
getCurrentGearSelectionState((VehicleGear)->Unit, onFailed)
registerGearSelection(GearSelectionListener) / unregisterGearSelection(l)
interface GearSelectionListener { fun onSelected(gear: VehicleGear?) }
VehicleGear = GEAR_UNKNOWN | GEAR_NEUTRAL | GEAR_REVERSE | GEAR_PARK | GEAR_DRIVE | GEAR_1..GEAR_9
```
Capability API 없음. 보조: `vehicle.getBrake()` (CAR_DRIVE) `getCurrentBrakePedalStatus`/`registerBrakePedalStatus` 는 **브레이크 페달**이지 주차 브레이크 아님.

### 주행 모드 · `vehicle.getDrivingMode()` · CAR_DRIVE (읽기 전용)
```kotlin
checkDrivingModeStateCapabilities((CapabilityConfig)->Unit, onFailed)
getCurrentDrivingModeState((DrivingModeType)->Unit, onFailed)
registerDrivingModeState(DrivingModeStateListener) / unregisterDrivingModeState(l)
interface DrivingModeStateListener { fun onDrivingModeStateUpdated(mode: DrivingModeType) }
DrivingModeType = NORMAL | ECO | SPORT
```
- 2.3.2 javap 기준 **set API 없음**: 앱은 주행 모드를 바꿀 수 없고 읽기만 한다. 같은 인터페이스의 크루즈 컨트롤 get/register 는 쓰지 않음.
- 퍼미션 표(`docs/pleos/V-vehicle-permission.txt`): DrivingMode·Brake·Odometer = `pleos.car.permission.CAR_DRIVE` (이미 선언됨).
- 용도: 마이 차고 "모드 따라가기". capability READ 확인 후 get + register, stop 시 unregister(`PleosVehicleGateway.subscribeDrivingMode`). capability 없음 = `VehicleState.driveMode == null` = 수동 테마만.

### 속도 · **Pleos SDK에 없음**
- 차량 속도 API 없음(`DrivingMode` 의 크루즈 목표속도만 있음). 시동 상태 `IgnitionState` enum 도 반환 API 없음.
- 대안 1 (권장, Pleos 주행규제 가이드 방식): `CarUxRestrictionsManager`
  ```kotlin
  val car = Car.createCar(context)
  val ux = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
  ux.registerListener { r -> if (r.isRequiresDistractionOptimization) pauseAndHide() }
  ux.getCurrentCarUxRestrictions()
  // 해제: ux.unregisterListener(); car.disconnect()
  ```
  별도 권한 상수 없음(javap 기준). 어떤 주행 상태에서 `isRequiresDistractionOptimization()==true` 가 되는지는 플랫폼 설정 의존(정차 D단 포함 여부 미확정) → 기어 신호와 OR 조건으로 사용.
- 대안 2: `CarPropertyManager` (`car.getCarManager(Car.PROPERTY_SERVICE)`)
  - `VehiclePropertyIds.PERF_VEHICLE_SPEED` (291504647, Float m/s) · 권한 `android.car.permission.CAR_SPEED`
  - `VehiclePropertyIds.GEAR_SELECTION` (289408000) / `CURRENT_GEAR` (289408001) · `android.car.permission.CAR_POWERTRAIN`
  - `VehiclePropertyIds.PARKING_BRAKE_ON` (287310850) · CAR_POWERTRAIN
  - 구독: `registerCallback(CarPropertyEventCallback, propId, CarPropertyManager.SENSOR_RATE_ONCHANGE)` (모든 API 레벨). `subscribePropertyEvents(...)` 는 Android 15 이상에서만.
- 게임 일시정지 조건(권장): `uxRestricted || gear != PARK || speed > 0` 중 하나라도 참이면 PAUSED_DRIVING.

## 퍼미션 요약 (게임에 필요한 것)

| 퍼미션 | 용도 |
|---|---|
| `pleos.car.permission.CAR_WINDOWS` | 창문 위치, 와이퍼 |
| `pleos.car.permission.CAR_LIGHTS` | 독서등, 실내등, 방향지시등, **앰비언트 get/set** |
| `pleos.car.permission.CAR_ENERGY` | SoC, 충전 스위치/포트 |
| `pleos.car.permission.CAR_DRIVE` | 기어, 브레이크 페달, 주행 모드(읽기) |
| `pleos.car.permission.CAR_SEATS` | 시트 열선, 탑승, 벨트 |
| `pleos.car.permission.CAR_CLIMATE` | 핸들 열선 |
| `pleos.car.permission.CAR_INFO` | 차량 정보 |
| `android.car.permission.CAR_SPEED` | PERF_VEHICLE_SPEED |
| `android.car.permission.CAR_POWERTRAIN` | GEAR_SELECTION, PARKING_BRAKE_ON |
| `android.car.permission.CAR_ENERGY` | EV_CHARGE_STATE, EV_CHARGE_TIME_REMAINING |

게임은 창문·와이퍼·열선을 직접 조작하지 않습니다. 앰비언트 set 은 SDK 2.3.2 어노테이션상 CAR_LIGHTS 이지만, 에뮬레이터 v2.1.0 은 `CONTROL_CAR_LIGHTS` 없이 "need Permission" 으로 거부해 이것도 선언합니다(2026-10-23 실측). 퍼즐 힌트로 독서등을 직접 켜는 등의 연출을 하려면 `CONTROL_CAR_LIGHTS` 추가.

## 주의 (빌드 관련)
1. **ErrorCode/VehicleException 컴파일 접근 불가.** `ErrorCode` 는 `vehicle-data` 에 있고, Vehicle 2.3.2 의 Gradle metadata 는 vehicle-data 를 **runtime** 의존으로만 선언. 또 문서의 `VehicleException` 은 2.3.2 바이트코드에서 난독화된 `ai.pleos.playground.vehicle.callback.exception.a` (메서드 `a(): ErrorCode`) 로만 존재. 에러코드 분기가 필요하면 `implementation("ai.pleos.playground:vehicle-data:1.1.11")` 을 추가하고 `e.message` 위주로 처리하는 것이 안전(난독화 클래스 직접 참조는 버전업 시 깨짐).
2. vehicle-data POM 은 `hilt-android` 를 의존으로 가짐(런타임 전이).
3. 결과 람다/리스너 호출 스레드는 javap로 미확정 → StateFlow 갱신은 `MutableStateFlow.update` 로 스레드 안전하게.
4. `checkXxxCapabilities` 는 오버로드 2개(구버전 1인자 람다, 신버전 `CapabilityConfig` 포함). 신버전 사용.
