# 카 이스케이프: 우리 차 방탈출

EV 충전을 기다리는 15~30분 동안, 가족이 **차 자체를 조작해** 퍼즐을 푸는 협동 방탈출 게임입니다.

현대자동차그룹 차량용 앱 개발 공모전 출품작입니다. 팀 **0won**. 기준 차량은 3열 7인승 전기 SUV 아이오닉 9, 실행 환경은 Pleos Connect(Android Automotive)입니다.

- 패키지 `com.carescape.app` · 버전 0.1.0 · minSdk 28 · targetSdk 35
- release APK: [`apk/0won_CarEscape_release.apk`](apk/0won_CarEscape_release.apk)
- 웹 체험판(차 없이 브라우저로 해 보기): https://car-escape-play.vercel.app

## 1. 주요 기능

- **차가 곧 게임기**: 창문, 독서등, 방향지시등, 핸들 열선, 시트 열선, 와이퍼, 앞좌석 안전벨트를 실제로 조작하면 퍼즐이 풀립니다. 화면은 금고 다이얼, 모스 램프, 화살표 순서, 열선 게이지, 좌석 지도로 반응합니다.
- **미션 11종**: 창문 금고, 깜빡이 암호, 독서등 등대(모스), 따뜻한 핸들, 따뜻한 의자, 와이퍼 인사, 기억 금고, 수수께끼 금고, 연쇄 금고, 찰칵(안전벨트), 쌍둥이 창문. 제한 시간 안에 풀면 번개 배지를 받습니다.
- **충전 시간 맞춤 에피소드**: 배터리 충전량 변화율로 남은 충전 시간을 추정해 그 안에 끝나는 가장 긴 이야기(15분, 20분, 25분, 30분)를 추천합니다. 목표 충전량에 닿는 순간 엔딩이 열립니다.
- **오늘의 금고**: 날짜를 시드로 써서 같은 날에는 모든 가족이 같은 퍼즐을 받습니다. 그 밖의 판은 퍼즐 순서, 목표 숫자, 변형이 매번 바뀝니다.
- **정차 중에만**: UX 제한이 걸리거나, 기어가 P가 아니거나, 속도가 0.5km/h를 넘으면 즉시 음성을 멈추고 화면 전체를 정지 안내로 덮습니다. P로 돌아오면 하던 퍼즐에서 이어집니다. `distractionOptimized`를 선언하지 않아 시스템도 주행 중에는 앱을 가립니다.
- **앰비언트 라이트와 음성 게임마스터**: 퍼즐마다 실내 무드등 색이 바뀌고(정답 초록, 엔딩 금색) 게임이 끝나면 원래 설정으로 되돌립니다. 이야기, 지시, 3단계 힌트를 한국어 음성으로 읽어 줍니다.
- **출발 금고(주차 암호)**: 충전 중 차를 떠날 때 주차 위치(층, 구역, 번호)를 암호로 남기고, 돌아와 문을 열면 함께 맞혀 봅니다. 자리를 비운 사이 충전된 양과 주행 가능 거리를 알려 줍니다. 기록은 기기에만 저장하고 출발하면 지웁니다.
- **마이 차고**: 모은 별과 배지로 화면 속 차의 페인트, 휠, 스티커, 테마를 꾸밉니다. 테마는 주행 모드를 따라 바뀌게 할 수 있습니다.
- **안전 상한과 자동 대체**: 2열 창문은 절반 이하, 와이퍼는 퍼즐 하나에 6회까지만 요구합니다. 차가 지원하지 않는 입력의 퍼즐은 지원되는 입력의 퍼즐로 자동 대체합니다. 모든 입력은 capability 확인을 통과한 것만 씁니다.

## 2. 실행 방법

### 2.1 Pleos Connect 에뮬레이터 준비

공식 안내: https://document.pleos.ai (Pleos Connect Emulator 설치 및 실행, Virtual Console 가이드)

1. Android Studio > Tools > SDK Manager > SDK Update Sites 에 Pleos Connect System Image 주소를 추가합니다.
   - Mac: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
   - Windows, Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
2. SDK Platforms 탭에서 Android API 34 아래의 Pleos Connect 시스템 이미지를 설치하고 Android Studio를 다시 시작합니다.
3. Device Manager > Create device > Automotive > **Pleos Connect v2.1.0** 으로 AVD를 만듭니다. 부팅 방식은 반드시 **Cold boot**, RAM은 4GB로 설정합니다(Quick boot 에서는 차량 신호가 오지 않습니다).
4. AVD를 실행하고 `adb devices` 로 연결을 확인합니다.

### 2.2 APK 설치와 실행

```bash
adb install -r -g apk/0won_CarEscape_release.apk
adb shell am start -n com.carescape.app/.MainActivity
```

- `-g` 는 속도와 충전 상태를 읽는 런타임 권한(`android.car.permission.CAR_SPEED`, `CAR_ENERGY`)을 설치할 때 허용합니다. 빼고 설치하면 첫 실행 때 앱이 직접 요청합니다.
- 같은 패키지의 디버그 빌드가 이미 있으면 서명이 달라 설치가 거절됩니다. `adb uninstall com.carescape.app` 후 다시 설치하세요.
- 런처(앱 목록)에서 "카 이스케이프"를 눌러도 됩니다.

### 2.3 Virtual Console 로 차량 조작하기

```bash
adb forward tcp:8088 tcp:8088
```

PC 브라우저에서 `http://127.0.0.1:8088/` 을 열고 Emulation Controls 카드로 차량 상태를 바꿉니다.

| 도메인 | 카드 | 게임에서의 쓰임 |
|---|---|---|
| Energy | Battery SOC, Charge Percent Limit, Charge Switch, Charge Port, Remaining Range | 충전 링, 남은 시간 추정, 엔딩, 출발 금고 |
| Motion | Gear Selection, Steering Wheel Heat | D로 바꾸면 즉시 정지, 핸들 열선 퍼즐 |
| Convenience | Window Position, Reading Lights, Turn Signal State, Windshield Wiper Switch, Door Lock, Door Position, Seat Occupancy | 창문·독서등·깜빡이·와이퍼 퍼즐, 떠남과 귀환 감지 |
| Climate | Seat Temperature | 시트 열선 퍼즐 |

권장 시작값: Gear P, Battery SOC 42, Charge Percent Limit 80, Charge Switch On.

### 2.4 소스에서 빌드하기

```bash
export JAVA_HOME=<JDK 21 경로>
export ANDROID_HOME=<Android SDK 경로>      # 또는 local.properties 에 sdk.dir=<경로>

./gradlew :app:assembleCar       # 에뮬레이터 개발용: 실제 Pleos SDK 연동, 디버그 서명  -> app/build/outputs/apk/car/app-car.apk
./gradlew :app:assembleDebug     # 차 없이 보기: 내장 시뮬레이터와 시연 패널        -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # 제출용 release                                    -> app/build/outputs/apk/release/
./gradlew :game:test :ui:verifyPaparazziDebug   # 단위 테스트와 화면 스냅샷 검증
```

| 빌드 종류 | 차량 연동 | 서명 | 용도 |
|---|---|---|---|
| `release` | 실제 Pleos Vehicle SDK. 시뮬레이터로 전환하지 않음 | 업로드 키(v3) | 제출용 APK |
| `car` | 실제 Pleos Vehicle SDK | 디버그 키 | 에뮬레이터 개발, 시드 고정 녹화 |
| `debug` | 내장 시뮬레이터와 시연 패널 | 디버그 키 | 차량 신호 없이 흐름 확인 |

서명 키와 비밀번호는 저장소에 없습니다. 키 없이 `assembleRelease` 를 돌리면 빌드는 성공하고 서명 없는 `app-release-unsigned.apk` 가 나옵니다. 직접 서명하려면 `signing/keystore.properties` 에 `storeFile`, `storePassword`, `keyAlias`, `keyPassword` 를 적어 두면 됩니다(이 경로는 `.gitignore` 에 등록되어 있습니다).

## 3. 테스트 환경과 필요한 설정

| 항목 | 값 |
|---|---|
| JDK | 21 (toolchain 21) |
| Gradle | 8.14.3 (wrapper 포함) |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.3.21 |
| Android API | minSdk 28, compileSdk 35, targetSdk 35 |
| UI | Jetpack Compose (BOM 2025.07.00), Material 3 |
| 차량 SDK | Pleos Vehicle SDK `ai.pleos.playground:Vehicle:2.3.2`, AAOS `android.car` 라이브러리 |
| 테스트 | JUnit 4.13.2, Paparazzi 2.0.0-alpha04 |
| 개발 PC | macOS (Apple Silicon) |

**Pleos SDK 의존성 받는 법**: 별도 로그인이나 수동 내려받기가 필요 없습니다. `settings.gradle.kts` 에 Pleos 공개 Maven 저장소가 등록되어 있어 Gradle 이 빌드할 때 자동으로 받습니다. SDK 바이너리는 이 저장소에 들어 있지 않습니다.

```kotlin
// settings.gradle.kts
maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
// vehicle/build.gradle.kts
implementation("ai.pleos.playground:Vehicle:2.3.2")
```

네트워크는 Google Maven, Maven Central, Pleos Nexus 세 곳에 닿아야 합니다. Android SDK 에는 Platform 35 와 Build-Tools 가 필요합니다(`android.car` 는 Platform 35 의 선택 라이브러리로 들어 있습니다).

**필요한 권한** (`app/src/main/AndroidManifest.xml`)

| 권한 | 읽거나 쓰는 것 |
|---|---|
| `pleos.car.permission.CAR_WINDOWS` | 창문 위치, 창문 잠금, 와이퍼 |
| `pleos.car.permission.CAR_LIGHTS` | 독서등, 방향지시등, 앰비언트 현재 값 |
| `pleos.car.permission.CONTROL_CAR_LIGHTS` | 앰비언트 라이트 설정(이 앱이 차에 쓰는 유일한 값) |
| `pleos.car.permission.CAR_ENERGY` | 배터리 충전량, 충전 스위치, 충전구 열림 |
| `pleos.car.permission.CAR_DRIVE` | 기어, 주행 가능 거리, 주행 모드 |
| `pleos.car.permission.CAR_SEATS` | 시트 열선, 탑승 감지, 안전벨트 |
| `pleos.car.permission.CAR_CLIMATE` | 핸들 열선 |
| `pleos.car.permission.CAR_DOORS` | 도어 열림, 도어 잠금 |
| `pleos.car.permission.CAR_INFO` | 차량 기본 정보 |
| `android.car.permission.CAR_SPEED` | 속도(런타임 권한) |
| `android.car.permission.CAR_ENERGY` | 충전 상태, 목표 충전량(런타임 권한) |
| `android.car.permission.CAR_POWERTRAIN` | 기어 |

매니페스트는 `android.hardware.type.automotive` 를 필수로 선언합니다. 일반 휴대폰 에뮬레이터에는 설치되지 않습니다.

## 4. 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격

- **Pleos Connect Emulator v2.1.0** (시스템 이미지 빌드 `release_connect_v2.1.0`, Android API 34, Virtual Console 포함)
- **실측 기준 프리셋: Connect-S 17", 2560x1440, 160dpi** (에뮬레이터 기본값. 1px = 1dp). 이 프리셋에서 앱 창을 단독으로 띄웠을 때 실측한 창 영역은 1682x1210 입니다.

아래 12종은 에뮬레이터에 내장된 폼팩터 콘솔(FormFactorConsole)이 프리셋과 방향마다 표시하는 공식 앱 창 크기를 2026-10-23 에 읽어 적은 값입니다(`docs/pleos/form-factor-official-dp.md`). 세로는 1:1 분할 창, 가로는 단독 창입니다.

| 프리셋 | DPI | 세로 창 dp (px) | 가로 창 dp (px) |
|---|---|---|---|
| Connect-S 17" 2560x1440 | 160 | 840x1184 (840x1184) | 1700x1184 (1700x1184) |
| Connect-S 14.6" 2048x1152 | 200 | 672x948 (840x1185) | 1360x948 (1700x1185) |
| Connect-S 12.9" 1920x1080 | 160 | 628x855 (628x855) | 1274x855 (1274x855) |
| Connect-WS 24.6" 3840x850 | 160 | 800x810 (800x810) | 1640x810 (1640x810) |
| Connect-WE 24.6" 3840x1440 | 160 | 800x1400 (800x1400) | 1640x1400 (1640x1400) |
| Connect-WF 18.7" 3072x816 | 200 | 648x800 (810x1000) | 1256x800 (1570x1000) |

이 12종을 그대로 테스트 규격으로 씁니다(`ui/src/test/java/com/carescape/ui/PleosScreens.kt`). 가로 창은 퍼즐 그림 왼쪽과 지시문 오른쪽, 세로 창은 위아래로 쌓습니다. 폭이 높이의 1.8배를 넘는 낮고 넓은 창(Connect-WS 가로)은 헤더를 한 줄로 줄인 컴팩트 배치를 씁니다. 글자는 16sp 이상, 터치 영역은 76dp 이상입니다. 화면 회전과 분할 전환에도 게임 상태는 ViewModel 에 남습니다.

## 5. APK 위치

[`apk/0won_CarEscape_release.apk`](apk/0won_CarEscape_release.apk)

공모전 규정의 `팀명_앱이름_release.apk` 형식입니다. 앱 이름을 한글 대신 영문 `CarEscape` 로 적은 이유는, 한글 파일명이 운영체제마다 자모 조합 방식(NFC, NFD)이 달라 Git, ZIP, 내려받기 과정에서 깨지거나 다른 파일로 인식되는 일을 막기 위해서입니다.

| 항목 | 값 |
|---|---|
| 패키지 | `com.carescape.app` |
| 버전 | versionName 0.1.0, versionCode 1 |
| SDK | minSdk 28, targetSdk 35 |
| 서명 | APK Signature Scheme v3 (minSdk 28 이라 v1, v2 는 생략됨) |
| 인증서 | `CN=Car Escape, O=Car Escape Team, C=KR` |
| 인증서 SHA-256 | `1e1990240be354313d46f06af82f307139eeef6a35adef1950d19e2d56d0bcdd` |
| 차량 연동 | 실제 Pleos Vehicle SDK 로만 동작(시뮬레이터로 전환하지 않음). R8 미적용 |

## 6. 사용한 SDK와 API

공모전에서 공개한 SDK 가운데 **Pleos Vehicle SDK 2.3.2** 를 씁니다. 아래 표는 `vehicle/src/main/java/com/carescape/vehicle/PleosVehicleGateway.kt`, `DriveGuard.kt` 에서 실제로 호출하는 것만 적었습니다. 근거 노트는 `docs/pleos-api-notes.md` 입니다.

| 구분 | 도메인 | 호출하는 API | 게임에서의 쓰임 |
|---|---|---|---|
| Pleos Vehicle SDK | Window | `checkWindowPositionCapabilities`, `getCurrentWindowPosition`, `registerWindowPosition`, `checkWindowLockCapabilities`, `getCurrentWindowLockState`, `registerWindowLockState` | 창문 금고, 쌍둥이 창문, 창문 릴레이. 2열 창문이 잠겨 있으면 그 퍼즐은 제외 |
| Pleos Vehicle SDK | Light | `checkReadingLightStateCapabilities`, `getCurrentReadingLightState`, `registerReadingLightState` | 독서등 모스 입력 |
| Pleos Vehicle SDK | Light (앰비언트) | `checkAmbientLightBrightnessCapabilities`, `checkAmbientLightBrightnessColorCapabilities`, `getAmbientLightSwitch`, `getAmbientLightBrightness`, `getAmbientLightBrightnessColor`, `setAmbientLightSwitch`, `setAmbientLightBrightness`, `setAmbientLightBrightnessColor` | 퍼즐별 무드등 연출. 시작할 때 원래 값을 저장하고 끝나면 되돌림 |
| Pleos Vehicle SDK | TurnSignal | `checkTurnSignalStateCapabilities`, `getCurrentTurnSignalState`, `registerTurnSignalState` | 깜빡이 암호, 기억 금고 |
| Pleos Vehicle SDK | SteeringWheel | `checkHvacSteeringWheelHeatCapabilities`, `getHvacSteeringWheelHeatState`, `registerHvacSteeringWheelHeatState` | 핸들 열선 단계 퍼즐. 최대 단계는 capability 에서 읽음 |
| Pleos Vehicle SDK | Seat | `checkSeatTemperatureCapabilities`, `getHvacSeatTemperature`, `registerHvacSeatTemperature` | 시트 열선 단계 퍼즐 |
| Pleos Vehicle SDK | Seat | `checkSeatBeltBuckledCapabilities`, `getCurrentSeatBeltBuckledState`, `registerSeatBeltBuckledState`, `checkSeatOccupancyCapabilities`, `getCurrentSeatOccupancy`, `registerSeatOccupancy` | 찰칵(안전벨트) 미션, 탑승한 자리만 미션 대상으로 선택 |
| Pleos Vehicle SDK | Wiper | `checkWiperStateCapabilities`, `getCurrentWiperState`, `registerWiperState` | 와이퍼 인사 |
| Pleos Vehicle SDK | EvBattery | `checkEvBatteryStateOfChargeCapabilities`, `getEvBatteryStateOfCharge`, `registerEvBatteryStateOfCharge`, `getEvChargeSwitchState`, `checkEvChargePortOpenCapabilities`, `registerEvChargePortOpen` | 충전 링, 남은 충전 시간 추정, 엔딩 조건, 충전구 열림 감지 |
| Pleos Vehicle SDK | Door | `checkDoorLockCapabilities`, `getDoorLockState`, `registerDoorLockState`, `checkDoorPositionCapabilities`, `getCurrentDoorPosition`, `registerDoorPosition` | 출발 금고의 떠남과 귀환 감지, 문이 열리면 일시정지 |
| Pleos Vehicle SDK | Gear | `getCurrentGearSelectionState`, `registerGearSelection` | 주행 차단(기어 P 여부) |
| Pleos Vehicle SDK | DrivingMode | `checkDrivingModeStateCapabilities`, `getCurrentDrivingModeState`, `registerDrivingModeState` | 마이 차고 테마를 주행 모드에 맞춤 |
| Pleos Vehicle SDK | Odometer | `getRangeRemainingDistance`, `registerRangeRemainingListener` | 돌아왔을 때 주행 가능 거리 안내 |
| AAOS `android.car` | CarPropertyManager | `PERF_VEHICLE_SPEED`, `GEAR_SELECTION`, `EV_CHARGE_STATE`, `EV_CHARGE_PERCENT_LIMIT` | 속도와 기어에 따른 주행 차단, 충전 중 판정, 목표 충전량 |
| AAOS `android.car` | CarUxRestrictionsManager | `isRequiresDistractionOptimization` | 주행 중 UX 제한 감지 |
| Android 표준 | TextToSpeech, ToneGenerator | 한국어 음성 안내, 효과음 | 음성 게임마스터 |
| Android 표준 | SharedPreferences | 기록, 배지, 꾸미기, 주차 암호 | 기기 안에만 저장. 서버 전송 없음 |

쓰지 않는 것: NaviHelper, SpeechToText, FusedLocation, Pleos TextToSpeech SDK. 마이크, 위치, 네트워크 권한을 요구하지 않습니다.

## 7. 검증 현황

### 자동 테스트 (2026-11-15, 이 저장소의 코드로 실행)

| 항목 | 결과 |
|---|---|
| 게임 로직 단위 테스트 (`:game:test`, JUnit) | 90개 실행, 실패 0 |
| 화면 스냅샷 테스트 (`:ui:verifyPaparazziDebug`, Paparazzi) | 566개 실행, 실패 0 |
| 기준 스냅샷 | 564장 (`ui/src/test/snapshots/images/`). 타이틀부터 엔딩, 출발 금고, 마이 차고까지의 화면 상태를 공식 앱 창 12종 크기로 렌더링 |
| release APK | `:app:assembleRelease` 성공, `apksigner verify` 통과(v3) |

스냅샷 테스트는 기준 이미지를 새로 기록한 뒤 같은 코드로 다시 그려 픽셀 단위로 비교합니다.

### 에뮬레이터 수동 검증 (Pleos Connect Emulator v2.1.0, Connect-S 17" 2560x1440 160dpi, Virtual Console)

실제 Pleos SDK 로 동작하는 `car` 빌드(차량 연동 코드는 release 와 같고 서명만 디버그 키)를 설치하고 Virtual Console 만으로 15분 편과 30분 편을 처음부터 엔딩까지 완주했습니다(2026-10-23, 2026-10-25).

| 항목 | 결과 |
|---|---|
| 충전량, 목표 충전량 | Battery SOC 40, Charge Percent Limit 80 이 타이틀에 즉시 반영 |
| 창문 금고 | Window Position 앞 왼쪽 50% 로 해제. 원시값은 0~10 단위(5 = 50%) |
| 깜빡이 암호 | Turn Signal Blink 왼쪽, 오른쪽, 왼쪽 으로 해제 |
| 독서등 모스 | Reading Lights 앞 왼쪽 짧게(0.25초), 길게(1.3초) 로 해제 |
| 와이퍼 인사 | 앞유리 와이퍼 Low, Off 2회로 해제 |
| 시트 열선, 핸들 열선 | Seat Temperature 2단(원시값 2), Steering Wheel Heat 1단(원시값 1) 으로 해제 |
| 주행 전환 | Gear D: 시스템이 앱을 가리고 "주행 중에는 사용할 수 없습니다" 표시. P 복귀 시 같은 퍼즐에서 이어짐 |
| 남은 시간 추정 | 충전량을 40에서 42로 50초 간격으로 올리자 "남은 충전 시간 약 34분, 30분 에피소드 추천" |
| 엔딩 | 충전량 80% 도달 시 "80% 충전 완료!" 엔딩. 퍼즐을 먼저 다 풀면 대기 화면 |
| 앰비언트 | `CONTROL_CAR_LIGHTS` 권한 추가 후 설정 성공, 무드등 색 적용 확인 |
| 출발 금고 | 주차 암호 저장, Door Lock 과 운전석 문 열림·닫힘으로 귀환 카드, 충전량 42에서 78, 주행 가능 거리 312km 안내, 앱을 강제 종료해도 복구, Gear D 뒤 기록 삭제 확인 |

## 8. 웹 체험판

https://car-escape-play.vercel.app

차량이나 에뮬레이터 없이 브라우저에서 같은 게임 규칙을 해 볼 수 있는 체험판입니다. 화면 속 조작 패널이 차량 신호를 대신합니다. 소스는 `web/` 에 있으며 빌드 과정 없이 정적 파일로 동작합니다(`web/index.html`). 게임 규칙 자체 검사는 `node web/selftest.mjs` 입니다. 내레이션 음성 파일은 용량과 권리 관계 때문에 저장소에서 제외했습니다. 저장소 사본은 음성 없이 동작하고, 위 주소에서는 음성이 함께 재생됩니다.

## 9. 저장소 구조

```
apk/        제출용 release APK
app/        Android 앱 모듈: MainActivity, GameViewModel, 매니페스트(권한)
core/       공유 계약(Contract.kt): 차량 상태, 게이트웨이 인터페이스, 내장 시뮬레이터
game/       게임 로직(순수 Kotlin)과 JUnit 테스트: 퍼즐, 에피소드, 충전 시계, 출발 금고, 마이 차고
vehicle/    Pleos Vehicle SDK 와 android.car 어댑터: PleosVehicleGateway, DriveGuard(주행 차단)
ui/         Compose 화면, Paparazzi 스냅샷 테스트와 기준 이미지
web/        웹 체험판 소스
docs/       API 근거 노트, 실차 실현 가능성 검토, 공식 창 크기 실측, Pleos UI 실측 스펙, 폰트 라이선스
tools/      시연 녹화 스크립트(tools/demo), 차량 3D 모델과 렌더 스크립트(tools/ioniq9)
gradle/     Gradle wrapper, 버전 카탈로그(libs.versions.toml)
```

모듈 의존: `app` → `ui`, `vehicle`, `game`, `core`. 게임 로직(`game`)은 Android 에 의존하지 않아 JVM 에서 바로 테스트합니다.

Pleos 공식 개발 문서는 저작권을 고려해 저장소에 옮겨 싣지 않았습니다. 원문은 https://document.pleos.ai 에서 볼 수 있습니다(주행 규제 개발 가이드: https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide).

## 10. 알려진 한계

- 실차에서는 아직 실행하지 못했습니다. 검증은 모두 Pleos Connect Emulator v2.1.0 과 Virtual Console 에서 했습니다.
- 속도 단독 차단과 UX 제한 단독 차단은 에뮬레이터에서 따로 재현하지 못했습니다(기어 D 전환으로 확인).
- Pleos Vehicle SDK 2.3.2 에는 비상등 값과 충전 잔여 시간 API 가 없어, 남은 시간은 충전량 변화율로 추정합니다.
- 와이퍼 High, Auto 모드의 값 매핑은 확인이 더 필요합니다(Low 는 작동으로 판정되는 것을 확인).
- 기어를 끝내 알려 주지 않는 차에서는 게임이 계속 정지 화면에 머뭅니다(안전 쪽 선택).
- 안전벨트 미션은 Virtual Console 에 대응하는 카드가 없어 debug 빌드의 시연 패널로 확인했습니다.
- 아이오닉 9 의 Pleos Connect 탑재는 공식 발표를 확인하지 못한 목표 플랫폼 가정입니다(`docs/feasibility.md`).
- 음성 안내는 Android 표준 TextToSpeech 를 씁니다. 음성 엔진이 없는 환경에서는 화면 글자만 나옵니다.
- R8(코드 축소)을 적용하지 않아 APK 가 약 25MB 입니다.

## 11. 라이선스와 상표 고지

- 이 저장소의 소스 코드, 게임 시나리오, 화면 디자인의 저작권은 0won 팀에 있으며, 공모전 심사와 검토를 위해 공개합니다.
- 화면 속 차량 그림과 3D 모델은 공개된 제원 수치를 바탕으로, 공개 사진을 형상 참고로만 삼아 팀이 Blender 스크립트(`tools/ioniq9/build_ioniq9.py`)로 직접 만든 것입니다. 제조사의 공식 사진, 도면, 3D 데이터, 엠블럼은 포함하지 않았습니다.
- 글꼴 Asta Sans: SIL Open Font License 1.1 (`docs/licenses/AstaSans-OFL.txt`).
- Pleos Vehicle SDK: 저장소에 포함하지 않으며, 빌드할 때 Pleos 공개 Maven 저장소에서 받습니다. 사용 조건은 Pleos Playground 약관을 따릅니다.
- AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines, Paparazzi, Gradle wrapper: Apache License 2.0. JUnit 4: Eclipse Public License 1.0. three.js(웹 체험판, CDN 로드): MIT License.
- Pleos, Pleos Connect, 아이오닉(IONIQ)은 각 권리자의 상표입니다. 이 프로젝트는 공모전 출품작이며 해당 권리자의 공식 제품이 아닙니다.
