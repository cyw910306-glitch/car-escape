# 실차 실현 가능성 매트릭스 (v2 기획 검토용)

근거: `docs/pleos-api-notes.md`(Vehicle SDK 2.3.2 javap), `README.md` 에뮬레이터 실기 검증(2026-10-23, Pleos Connect Emulator v2.1.0), `docs/pleos/virtual-console-guide.txt`. 게임은 차를 **읽기만** 하며, 쓰기는 앰비언트 라이트 연출 하나뿐입니다. 모든 퍼즐은 P단 정차 중에만 진행됩니다.

## 1. 입력별 매트릭스

| 입력 | Pleos SDK API · 퍼미션 | R/W | 에뮬레이터 검증 | 마모·안전 위험과 완화 | 차에 없을 때 |
|---|---|---|---|---|---|
| WINDOW (운전석) | `Window.getCurrentWindowPosition` / `registerWindowPosition` (`WindowArea(ROW_1_LEFT)`), `checkWindowPositionCapabilities` · `CAR_WINDOWS` | R | **검증됨**. 50%, 1/3 해제. 원시값 0~10(5 = 50%) | 모터 사이클: 퍼즐당 1~2회 왕복, 목표 30/50/70%만 사용해 완전 개방 없음. 끼임: 아이에게 손을 안쪽에 두라는 안내 | capability 미통과 시 `substitute()`로 다른 입력 퍼즐로 교체 |
| PASSENGER_WINDOW / 2열 창문 (신규) | 위와 같은 API, area만 `ROW_1_RIGHT`, `ROW_2_LEFT`, `ROW_2_RIGHT` · `CAR_WINDOWS` | R | **에뮬레이터 미검증, SDK 근거만**. Virtual Console에 앞뒤 좌우 Window Position 카드가 있어 검증 가능 | 2열 아이 단독 조작: 목표를 절반 이하로 제한, 몸·손 내밀기 금지 안내. 차일드락(창문 잠금) 시 아이 쪽 스위치가 막힐 수 있음 | 영역별 capability로 판단. 잠금 상태 조회 API는 노트에 없으므로 잠금이면 "앞자리 창문으로 바꿀까요?" 안내 후 대체 |
| READING_LIGHT | `Light.getCurrentReadingLightState` / `registerReadingLightState`, `checkReadingLightStateCapabilities` · `CAR_LIGHTS` | R | **검증됨**. 짧게 0.25초, 길게 1.3초 모스 해제 | LED라 마모 미미. 12V 소모 극소, 퍼즐 끝나면 끄라는 안내 | `substitute()` |
| TURN_SIGNAL | `TurnSignal.getCurrentTurnSignalState` / `registerTurnSignalState` (OFF/LEFT/RIGHT) · `CAR_LIGHTS` | R | **검증됨**. L·R·L, R·R·L 해제 | 정차 중 깜빡이는 주변 차에 신호가 되므로 충전소 안에서만, 짧게. 비상등은 SDK 값 없음(미사용) | `substitute()` |
| STEERING_HEAT | `SteeringWheel.getHvacSteeringWheelHeatState` / `registerHvacSteeringWheelHeatState`, `checkHvacSteeringWheelHeatCapabilities` · `CAR_CLIMATE` | R | **검증됨**. 1단 = 1 | 열선은 수백 W급. 충전 중이라 HV 배터리 영향은 작지만, 퍼즐 후 끄기 안내. 목표 단계는 capability max 이내 | `substitute()` |
| SEAT_HEAT (운전석) | `Seat.getHvacSeatTemperature` / `registerHvacSeatTemperature`, `checkSeatTemperatureCapabilities` · `CAR_SEATS` | R | **검증됨**. 2단 = 2 | 저온 화상 방지: 고단은 짧게, 정답 후 끄기 안내. 음수(통풍) 값은 목표로 쓰지 않음 | `substitute()` |
| WIPER (앞유리) | `Wiper.getCurrentWiperState` / `registerWiperState` (`FRONT_WINDSHIELD`), `checkWiperStateCapabilities` · `CAR_WINDOWS` | R | **검증됨**. Low 작동, 2회·3회 해제. High/Auto 매핑 미확인 | 마른 유리 블레이드 마모·소음: 퍼즐 전체 3회 상한, 원터치(MIST)·워셔 사용 안내. 주변 사람 확인 | `substitute()` |
| SEATBELT (신규) | `Seat.getCurrentSeatBeltBuckledState` / `registerSeatBeltBuckledState`, `checkSeatBeltBuckledCapabilities`, 인원은 `getCurrentSeatOccupancy` · `CAR_SEATS` | R | **에뮬레이터 미검증, SDK 근거만**. Virtual Console에 벨트 카드가 없어 에뮬레이터로는 확인 불가(Seat Occupancy 카드는 앞뒤 좌우만) | 위험 가장 낮음. 벨트를 매는 행동 자체가 안전 습관. 단 3열 승하차 중 미션 금지(문 닫힘 후 시작) | 지원 SeatArea가 2개 미만이면 대체. 탑승 감지가 없으면 "몇 명 타고 있어요?" 화면 입력으로 목표 수 결정 |
| 앰비언트 라이트 (출력) | `Light.setAmbientLightSwitch` / `setAmbientLightBrightnessColor` / `setAmbientLightBrightness`, 시작 전 get으로 저장 후 원복 · javap상 `CAR_LIGHTS`, **에뮬레이터는 `CONTROL_CAR_LIGHTS` 요구** | W | **검증됨**. 권한 추가 후 앰버색 적용 | 유일한 쓰기. 종료·onStop·주행 전환 시 원복. 드라이브모드 자동색 켜짐 여부 확인 | 실패해도 게임 진행. 화면 색 연출만 |
| 기어·주행 게이트 | android.car `GEAR_SELECTION`(Pleos `Gear.registerGearSelection` 폴백), `PERF_VEHICLE_SPEED`, `CarUxRestrictionsManager` · `CAR_POWERTRAIN`, `CAR_DRIVE`, `CAR_SPEED` | R | **검증됨**(기어 D 차단, P 복귀 이어하기). 속도·UX 단독 차단은 미검증 | 기어 UNKNOWN이면 정지 화면 유지(안전 우선) | 대체 없음. 게이트 못 열면 게임 안 함 |
| 충전·SoC 게이트 | `EvBattery.getEvBatteryStateOfCharge` / `registerEvBatteryStateOfCharge`, `getEvChargeSwitchState`, android.car `EV_CHARGE_STATE`, `EV_CHARGE_PERCENT_LIMIT` · `CAR_ENERGY`(Pleos·android.car) | R | **검증됨**. SoC 40→80 엔딩, 남은 시간 추정 | 충전 잔여시간 API 없음 → SoC 변화율 추정 | 목표 미제공 시 기본 80% |

## 2. 아이오닉 9 기준

| 항목 | 값 | 출처 |
|---|---|---|
| 좌석 | 3열, 6인승(2열 독립 시트 2석, 일부 시장은 정차 중 회전 시트) 또는 7인승(2열 벤치 3석) | [현대차 호주 보도자료](https://www.hyundai.com/au/en/news/press-room/hyundai-motor-redefines-ev-mobility-with-ioniq-9-electric-suv) |
| 배터리 | 110.3 kWh, 400V/800V 멀티 충전 | 같은 보도자료, [현대차 영국](https://www.hyundai.com/uk/en/models/ioniq9.html) |
| 급속 10→80% | 350 kW 충전기 약 24분(보도자료), 50 kW 약 109분(호주 사양표) | 같은 보도자료, [호주 사양표](https://www.hyundai.com/content/dam/hyundai/au/en/models/ioniq-9-2025/docs/Hyundai-IONIQ-9-Specification-Sheet.pdf) |
| 디스플레이 | 12.3인치 클러스터 + 12.3인치 인포테인먼트 파노라믹 커브드(국내 출시 기준) | [스마트투데이 출시 기사](https://www.smarttoday.co.kr/ko-kr/articles/72509) |
| 휠베이스 | 3,130 mm | 현대차 호주 보도자료 |

| 좌석·창문 | SDK 영역 | 쓰는 미션 |
|---|---|---|
| 운전석 / 조수석 | `ROW_1_LEFT` / `ROW_1_RIGHT` (Seat·Window) | 창문 금고, 쌍둥이 창문, 열선 |
| 2열 좌 / (7인승) 중 / 우 | `ROW_2_LEFT` / `ROW_2_CENTER` / `ROW_2_RIGHT` | 모두 찰칵, 릴레이 창문(좌우 창문만) |
| 3열 좌 / 우 | `ROW_3_LEFT` / `ROW_3_RIGHT` (벨트만, 창문 영역 없음) | 3열까지 모두 찰칵, 열 순서 찰칵 |

에피소드 길이 대응: 15분(급속 보충), 20분(350 kW 10→80%), 30분(저출력 급속·완속). 6인승 2열 회전 시트(일부 시장)는 2열과 3열이 마주 앉는 정차 중 협동에 맞습니다.

**플랫폼 전제(정직하게)**: 플레오스 커넥트 첫 적용 차는 더 뉴 그랜저이며, ccNC 기반 기존 차량에는 플레오스 커넥트 적용이 어렵다고 보도됐습니다([머니투데이방송](https://news.mtn.co.kr/news-detail/2026043016372162316)). 아이오닉 9의 Pleos Connect 탑재는 **공식 근거를 찾지 못한 목표 플랫폼 가정**이며, 향후 부분변경 탑재를 전제로 합니다. 실제 지원 SeatArea·WindowArea도 차에서 capability 조회로 확정합니다.

## 3. 타 차종 대응 (조건부)

| 상황 | 처리 |
|---|---|
| 5인승(3열 없음) | `checkSeatBeltBuckledCapabilities` 결과에 `ROW_3_*` 없음 → 3열 미션 제외, 목표 인원을 지원 좌석 수로 축소 |
| 벨트·탑승 센서 없음 | SEATBELT 미션을 `substitute()`로 기존 입력 퍼즐로 교체. 탑승 감지만 없으면 인원 화면 입력 |
| 조수석·2열 창문 미지원 또는 차일드락 | 협동 창문 미션을 운전석 창문 퍼즐로 교체 |
| 열선·와이퍼 미지원 | 기존과 같이 지원 입력으로 교체. 지원 입력이 하나도 없으면 시연 불가 안내 |

## 4. 왜 차에서만 가능한가

폰이 가질 수 없는 입력을 씁니다: 실제 창문 높이, 좌석별 벨트 체결과 탑승, 열선 단계, 와이퍼·방향지시등 레버, 독서등, 그리고 출력인 실내 앰비언트 라이트. 게임 시간도 차의 충전 SoC와 목표 충전량에 묶여 있어, 충전이 끝나는 순간 엔딩이 열리고 바로 출발합니다. 3열 7명이 각자 자리에서 자기 벨트와 창문으로 참여하는 협동은 차 안에서만 성립합니다.
