# Pleos Connect UI 컴포넌트 스펙 (실측 + 공식 가이드)

수집일 2026-10-27. 단위는 dp. 에뮬레이터는 2560x1440 @160dpi 라서 1px = 1dp.
기계용 동일 내용: `tokens.json`. 캡처: `screens/*.png`.

출처 표기
- [emu] Pleos Connect Emulator v2.1.0 실측 (screencap 픽셀 최빈색, uiautomator bounds, dumpsys window)
- [doc] document.pleos.ai 디자인 가이드 본문과 색 토큰 표 이미지
- [svg] 가이드 스펙 SVG 의 도형 좌표 (1:1 dp 로 그려져 있음, 글자는 path 라 숫자 라벨은 없음)
- [fig] 공개 다운로드 `Pleos_Connect_design_assets_215.fig` 안의 이름 문자열
- [est] 추정 (글자 크기를 글자 높이로 역산한 경우 등)

## 0. 먼저 알아둘 것: 테마가 두 벌이다

- 가이드 [doc] 의 토큰은 Pleos 기본 팔레트(#131417 / #F7F8FA 계열, 둥근 모서리 16~48).
- 에뮬레이터 기본 테마 "그래파이트" [emu] 는 순수 흑백, 모서리 0(직각), 선택 상태는 반전(라이트: 검정 바탕 흰 글자, 다크: #DDDDDD 바탕 검정 글자). 실제 차에서 기본으로 보이는 모습은 이쪽.
- "팝" 테마 [emu] 는 청록(#132930) 바탕, 둥근 앱 창(r32), 알약형 세그먼트, GNB 원형 타일, 인디케이터 #F4F58A.
- 화면 모드: 자동 / 라이트 / 다크 (차량 설정 > 디스플레이). 앱 라이브러리 패널과 GNB 는 라이트 모드에서도 검정 [emu].

실물과 똑같이 보이게 하려면 그래파이트 값을, 가이드 컴포넌트 치수는 [svg] 값을 쓰는 조합을 권한다.

## 1. 화면 레이아웃 (Connect-S 17", 2560x1440) [emu]

| 영역 | x, y | 크기 | 비고 |
|---|---|---|---|
| 상태바 | 0, 0 | 2560 x 90 | TopCarSystemBar. 드라이빙뷰 위에는 드라이빙뷰 자체 상단 행이 겹쳐 그려짐 |
| 드라이빙뷰 | 0, 0 | 853 x 1300 | 상태바 높이까지 위로 뻗음(별도 activity) |
| 앱 창(단독, Landscape) | 853, 90 | 1682 x 1210 | 우측 25dp(2535~2560) 는 창 바 손잡이(점 3개) |
| GNB | 0, 1300 | 2560 x 140 | BottomCarSystemBar |
| 공조 패널 | 460, 720 | 1640 x 580 | GNB 온도 탭 시 위로 열림, GNB 는 그대로 |
| 앱 라이브러리 패널 | 568, 651 | 1424 x 629 | 탭 4개(앱/공조/빠른 설정/더 보기) |
| 헤드업 알림 영역 | 0, 0 | 2560 x 464 | HeadsUpNotification window |

팝 테마: 앱 창이 드라이빙뷰에서 24 띄워지고 모서리 r32, 하단은 GNB 에 붙음 [emu].
분할(Portrait 1:1) 창 크기는 기존 `../form-factor-official-dp.md` 참고(840x1184 등).

## 2. 상태바 (높이 90) [emu]+[doc]

- 배경: 라이트 #F7F7F7 + 하단 1dp #A6A6A6 선 / 다크 #000000 + 하단 1dp #353535.
- 왼쪽(앱 창 시작 853 기준): 잠금 아이콘 x891(약 36x36), 검색 x979(약 34). 간격 약 52. 알림이 생기면 검색 오른쪽에 종 아이콘(빨간 점 배지) 추가 [emu 23].
- 오른쪽: 프로필 아바타 원 56 (x2128~2183, y17~72, 라이트에선 흰 원+옅은 테두리, 글자 보라 #BF74CC 계열), 블루투스(22x32), 네트워크, 세로 구분선 2x26, 시간("06:16 오전") 글자 높이 22 => 약 30sp [est], 오른쪽 여백 20.
- 가이드 구성: SP0 Static(도어 잠금, 검색) / SP0 Dynamic(알림 센터, 통화, 프라이버시) / SP1 Dynamic(다운로드·업데이트·WPC·하이패스·사운드) / SP1 Static(프로필, 블루투스, 네트워크) / 시간 / PAB / More. SP0 과 SP1 사이 오른쪽 safe bounds 40 [doc].

## 3. GNB (높이 140, 배경 #000000 두 모드 공통) [emu]+[doc]

- 왼쪽(운전석): 내림 화살표(파랑 #0051FF) / "❄22.0°" 온도(글자 높이 약 62 => 약 48sp [est]) + 아래 "A/C" 초록 #00DC16(약 20sp [est]) / 올림 화살표(빨강 #D3232A) / 앞유리 성에 / 운전석 시트 열선 / 핸들 열선.
- 가운데: 앱 타일 76x76, 간격(pitch) 116. 차량 설정, 내비(고정), 전화, 앱 라이브러리(그리드 아이콘, 타일 없음), 세로 구분선 #333333, 최근 앱 2개(현재 앱 포함).
  - 그래파이트: 타일 #4D4D4D 직각 사각. 팝: 원형(지름 76).
  - 실행 중/선택 표시: 타일 바로 아래 76x8 막대, 그래파이트 #FF9900, 팝 #F4F58A. GNB 상단에서 y124.
- 오른쪽(동승석): 시트 열선, 내/외기, AUTO 바람, 내림/온도/올림 (운전석과 대칭).
- 가이드 [doc]: 운전석 공조, 앱 영역(차량 설정 고정, 내비 고정, 편집 슬롯 최대 3, 앱 라이브러리, 최근 앱 2), 동승석 공조. 길게 눌러 편집.

## 4. 드라이빙뷰 (853 x 1300) [emu]

정차(P):
- 상단 행 y 21~68: 배터리 막대 72x32(x24) + "312km"(글자 높이 22 => 약 30sp), 가운데 "0km/h"(옅은 회색 #ADADAD), 오른쪽 기어 PRND: 선택 칸 48x48 직각(라이트 #333333 바탕 흰 글자 / 다크 #CCCCCC 바탕 검정 글자), 나머지 글자 #ADADAD(다크 #797979), 글자 간격 32.
- 그 아래 AD(운전대) 아이콘과 "- -".
- 안전벨트 안내 이미지 + "안전 벨트를 매세요." (회색, 약 24sp [est]).
- 중앙: 차량 3D 렌더(4분의 3 뒤쪽 시점, 은색 아이오닉 9), 도어/창 조작 원형 버튼 약 100 지름(라이트 흰 원 + 옅은 그림자, 다크 #2C2C2C).
- 그 아래 아이콘 4개(트렁크·프렁크·충전구·창문 열기), 하단 빠른 바(y 약 1120~1300): 아이콘 4개 + 세로 구분선(1dp #A6A6A6, 높이 약 70), 선택 아이템 아래 짧은 막대, 맨 아래 페이지 점(y 1283).

주행(D, 60km/h) [emu 21]:
- 속도 숫자 "60": 글자 높이 96 => 약 132sp [est], 가운데 x≈427, 색 다크 #C8C8C8. 아래 "km/h" 글자 높이 26 => 약 30sp, #565656.
- 기어 D 칸 선택(다크 #CCCCCC). 3D 차량이 작게(후면 시점) 중앙 하단으로 바뀌고 도어/창 버튼, 안전벨트 안내가 사라짐.
- 경고등(telltale) [emu 24]: 왼쪽 열 x≈20, 첫 y≈95, 아이콘 약 40, 간격 55. 색: 초록 #00BA13, 주황 #FF8A00, 빨강 #F62E24 (= regulation 토큰과 일치).
- 차량 경고 알림 [emu 23]: 속도 아래 카드 510x199, 직각, 다크 #9F3228 바탕, 경고 삼각형 아이콘 + 흰 글자 2줄 가운데 정렬.
- AD longitudinal 전환은 이 버전 드라이빙뷰에서 눈에 띄는 변화 없음 [emu 22].

## 5. 차량 설정 앱(시스템 앱의 실제 컴포넌트) [emu]

카테고리 목록(왼쪽, 폭 400):
- 행 400x100, 텍스트 x 941(아이콘 영역 포함 왼쪽 여백 88), 아이콘 약 36 + 글자 36 높이(약 28sp [est]).
- 선택: 그래파이트 라이트 #000 바탕 흰 글자 / 다크 #DDDDDD 바탕 #121212 글자. 직각. 팝: #E6ECED 알약(좌우 여백 8, r 50).
- 오른쪽 경계 1dp #A6A6A6(라이트) / #333333(다크).

콘텐츠 영역(폭 1282, x 1318 부터, 좌우 여백 약 64):
- 페이지 제목 TextView 높이 64 (약 48sp Regular [est]), 설명 29 높이(약 22sp, #7C7C7C).
- 섹션 라벨 38 높이(약 28sp), 섹션 사이 구분선 1dp.
- 세그먼트(가속 모드 등) [emu]: 높이 100, 칸 폭 균등(3칸이면 383~384, 2칸 576), 칸 사이 간격 0, 직각, 외곽 1dp 검정 테두리(라이트). 글자 높이 34(약 26sp). 선택 반전. 비활성: 라이트 #F5F5F5 바탕 #A7A7A7 글자 / 다크 #2F2F2F 바탕 #6B6B6B 글자, 선택칸은 #EBEBEB.
  - 팝: 칸 366x100 알약(r50), 칸 사이 16, 선택 #E6ECED 바탕 검정 글자, 나머지 #234B58 바탕 흰 글자.
- 아이콘 포함 세그먼트(전조등): 칸 안에 아이콘 36 + 글자.
- 스위치(토글) [emu]: 터치 영역 100x90, 트랙 약 90x54 알약, 꺼짐 트랙 #A5A5A5, 켜짐 트랙 #000000(라이트), 손잡이 흰 원. 스위치 오른쪽 32 띄우고 제목(38 높이) + 설명(29 높이). 팝: 손잡이가 둥근 사각(가이드 스위치 모양).
- 라디오 [emu]: 80x80 터치, 원 약 44, 선택 시 안쪽 점. 이미지 카드(테마 선택) 306x286.
- 슬라이더(밝기) [emu]: 높이 80, 좌우 < > 사각 버튼 80x80(흰 바탕 1dp 테두리), 가운데 채움 #000 + 해 아이콘 + 손잡이 두 줄 표시. 직각.
- 아웃라인 버튼("화면 클리닝 모드") [emu]: 높이 약 100, 1dp 검정 테두리, 직각.
- 빠른 설정 타일 [emu]: 427x260 카드, 아이콘 48 좌상단, 제목 38 높이 + 상태 29 높이(회색), 타일 사이 1dp 구분선. 이동형 타일은 오른쪽 위 → 화살표.
- 비활성 항목 전체: 제목/설명 #A7A7A7(라이트) / #515151(다크).

## 6. 공조 패널 (1640x580) [emu]

- 배경: 라이트 #F7F7F7 + 실내 렌더 사진 / 다크 #010101 + 어두운 실내 렌더.
- 버튼 120x100 직각, 버튼 사이 1 간격으로 붙은 묶음(ON/Auto/A/C/Sync), 묶음 사이 16.
- ON 선택: 라이트 #000 바탕 흰 글자 / 다크 #F3F3F3 바탕 검정. 일반 버튼: 라이트 #F3F3F3 / 다크 #252525. 켜진 기능(A/C): 라이트 #FFFFFF / 다크 #353535.
- 온도 열 120x276: 위 화살표(빨강) 100, 값 76(글자 48 높이 => 약 36sp), 아래 화살표(파랑) 100.
- 풍량 열: 동일 구조, 팬 아이콘 28 + 숫자.
- 앞좌석/뒷좌석 탭 113x100, 선택 밑줄 113x8(라이트 #000, 다크 #EBEBEB).
- 하단 행: 핸들 열선, 시트 열선, 송풍 방향 등 120x100 아이콘 버튼.

## 7. 앱 라이브러리 (1424x629) [emu]

- 배경 #000000(그래파이트 라이트/다크 공통), 팝 #1F3E47.
- 탭 4개 각 217x100, 글자 36 높이, 선택 밑줄 흰색 약 170x4~8, 탭 아래 구분선 #363636.
- 앱 아이콘 60x60(그래파이트 각진 사각, 팝 원형), 아래 이름 약 20sp, 8열 그리드, 열 간격 약 168.
- 빠른 설정 탭: 아이콘 48 + 라벨(약 20sp) 8열 2행.

## 8. 가이드 컴포넌트 치수 [svg] (Pleos 기본 스타일, 둥근 모서리)

| 컴포넌트 | 치수 | 색/상태 |
|---|---|---|
| Basic button | L 높이 100 r32, M 80 r24, S 64 r16. 좌우 패딩 약 40 [est], 아이콘 48 | 기본 #131417 5%, 눌림 10%, 비활성 투명+테두리+라벨 32%, Loading 은 로더만. Secondary(채움) #44464E 흰 글자 |
| Toggle button | 높이 80 r24 | 기본 5% 회색, Selected 흰 바탕 + 그림자 level2, Disabled 테두리 |
| Text button | 글자 버튼 | #0064FF, 눌림 시 밑줄 2, 비활성 #131417 32% |
| Icon button | 80x80, 사각 r20 또는 원 r40 | 5% / 10% 눌림 / 어두운 바탕 위 흰 20% |
| Tabs(Segmented) | 컨테이너 높이 약 100, 선택 칸 흰 바탕 안쪽 8 띄움, 칸 구분선 2x36 #131417 10% | Box / Line 두 타입, Fitted 옵션 |
| Switch | 트랙 80x48 r16, 손잡이 32x32 r8, 안쪽 여백 8 | on informative_active, off #131417 30% |
| Checkbox | 박스 44 r10, 테두리 #131417 20%, 눌림 후광 48 r12 10% | |
| Radio | 원 44, 눌림 후광 48 | |
| Text field | 540x100 r24, 커서 3x32 | |
| Number field | 140x180 r32, 커서 4x48 | |
| Dropdown | 필드 478x98 r24 테두리 10%, 메뉴 r32 안쪽 15, 항목 높이 100 r24, 선택 5%, 앞 아이콘 48 r12.8 | |
| Stepper | 398x98 r24 테두리 10%, 구분선 높이 52 20% | |
| Slider | 컨테이너 높이 97 r30.5 테두리 20%, 트랙 높이 8 r4, 손잡이 42(눌림 50) #313236 흰 테두리, 양끝 아이콘 18x32 | |
| Spinner(Picker) | 600x384 r32 5%, 선택 행 높이 88 r24, 위아래 60 페이드 | |
| System popup | Large 폭 1200, Medium 760, r48, 안쪽 48, 아이콘 → 제목 → 설명 → 버튼(높이 100 r32, 사이 24) | 주 버튼 #44464E, 보조 5% |
| Toast | 높이 80 r16, 바탕 #131417 84% | 앱 패널 안 좌우 24·아래 24, 최대 폭 792, 글자 최대 712 [doc] |
| System notification | 700x276 r48, 안쪽 32, 아이콘 100, 버튼 318x80 r24 사이 16 | |
| Page indicator | 점 8, 간격 16(피치 24), 비활성 흰 32% | |
| Page navigation | 1048x240 r40, 썸네일 80 r16 | |
| Window bar | 손잡이 4x160 r2 흰 20%(가로형 4x80 50%) | |
| Iconography | 48x48 그리드, Filled/Outlined [doc] | |

그림자 [doc]: L1 (0,6,24) 8% / L2 (0,8,32) 16% + (0,2,2) 8% / L3 (0,20,40) 24% + (0,2,4) 20%. 색은 #000000.

## 9. 타이포그래피 [doc]+[fig]

글꼴: Asta Sans (Google Fonts, 한/영). fig 에서 AstaSans-Bold/Regular/ExtraBold/Medium 사용 확인. 최소 12sp, 실제 높이 18px 이상.

| 위계 | Large | Medium | Small | 굵기 |
|---|---|---|---|---|
| Headline | 60 | 56 | 48 | ExtraBold, Bold |
| Title | 40 | 36 | 32 | ExtraBold, Bold, Regular |
| Body | 30 | 28 | 26 | Strong, Normal |
| Label | - | 24 | 20 | Normal |

에뮬레이터 그래파이트 실측(TextView 높이 → 추정 sp): 페이지 제목 64 → 48, 항목 제목 38 → 28~30, 설명 29 → 22, 카테고리 36 → 28, 세그먼트 34 → 26. 팝 테마는 약 20% 큼(79/46/35/44).

## 10. 색 토큰 [doc] (fig 에서 이름 298개 확인)

기본 그레이 basic (라이트 → 다크는 순서 반전):
00 #FFFFFF, 50 #F7F8FA, 100 #EDEEF2, 200 #E0E1E6, 300 #C7C9CE, 400 #A4A8AE, 500 #686A72, 600 #44464E, 700 #313236, 800 #1D1E21, 900 #131417.
다크: 00 #131417, 50 #1D1E21, 100 #313236, 200 #44464E, 300 #686A72, 400 #A4A8AE, 500 #C7C9CE, 600 #E0E1E6, 700 #EDEEF2, 800 #F7F8FA, 900 #FFFFFF.

알파: basic_alpha_dark_50~500 = #131417 5/10/20/30/40/50%. basic_alpha_light_50~500 = (다크) #FFFFFF 5~50%, (라이트) #131417 5~50%.
Static(테마 무관): static_light_100/200/300/400 = #FFFFFF 100/84/64/32%, static_dark_* = #131417 100/84/64/32%.

시스템 색 매핑(라이트 / 다크):
- background_primary basic_50 / basic_50, background_secondary basic_00 / basic_00, background_popup basic_00 / basic_100
- surface_basic basic_00 / basic_50, surface_low basic_50 / basic_00, surface_high basic_100 / basic_100, surface_accent basic_200 / basic_200
- text_dark_primary..quaternary static_dark_100..400 / static_light_100..400 (icon_dark_* 동일)
- button_basic enabled/pressed/disabled alpha_dark_50 / alpha_dark_100 / alpha_light_200 (두 모드 동일)
- button_switch(토글 선택) enabled basic_00 / alpha_dark_200, pressed alpha_light_500 / alpha_dark_200, disabled alpha_light_300 / alpha_dark_300
- button_filled enabled basic_600 / basic_600, pressed basic_700 / basic_500, disabled alpha_dark_50
- switch_on informative_active, switch_off alpha_dark_300 / alpha_dark_100, switch_disabled alpha_dark_100, knob_on basic_00 / basic_900, knob_off basic_00 / basic_400, knob_disabled basic_00 / alpha_dark_200
- controller_slider normal alpha_dark_200 / alpha_dark_100, pressed alpha_dark_300 / alpha_dark_200, knob basic_00 / alpha_dark_300, tab_background alpha_dark_50, stepper_background basic_00 / alpha_light_100
- dim_dark_primary..tertiary alpha_dark_50/100/200, dim_light_primary..tertiary alpha_light_500/400/300
- informative: active #02C265 / #00E074, positive #0064FF / #0082FF, negative #FE3D16 / #FF4C28, autonomous_driving #5A46FA / #6A57FF
- regulation(경고등): blue #0064FF / #0082FF, green #00BA13 / #55F165, yellow #FFC224, orange #FF8A00, red #F62E24 / #FF4339, bluetooth #0082FC
- driving: gearshift_background basic_900 / basic_100, charging_normal #00DB25, charging_deep #00B975 / (표기 #0082FC), gradient_start #FFFFFF 0% / #131417 0% → gradient_end basic_00
- phone: call #32B957(눌림 #279E47), end #FE3D16(눌림 #E73612). media 견본 #C911E7, #4781FF.

그래파이트 실측 색 전체는 `tokens.json` 의 `emulator_graphite`, 팝은 `emulator_pop`.

## 11. 캡처 목록 (`screens/`)

| 파일 | 내용 |
|---|---|
| 00-carescape-app-in-light-system.png | 우리 앱 실행 상태(시스템 라이트) |
| 01-home-light.png | 홈(앱 창 = 마지막 앱) |
| 02-vehicle-settings-light.png | 차량 설정 > 빠른 설정 타일, 탑뷰 차량 |
| 03-settings-light-category.png | 라이트 카테고리: 아이콘 세그먼트, 스위치, 3칸 세그먼트, 비활성 칸 |
| 04-settings-drive.png | 주행: 3칸/2칸 세그먼트, 스위치, 비활성 섹션 |
| 05-settings-climate.png | 공조 설정: 스위치 목록, 섹션 구분선 |
| 06-settings-display.png | 테마(그래파이트/팝), 화면 모드(자동/라이트/다크) 라디오 카드 |
| 07-settings-display-scrolled-slider.png | 밝기 슬라이더, 비활성 스위치, 아웃라인 버튼 |
| 08-settings-seat.png | 시트: 세그먼트, 프리셋 버튼 |
| 09-settings-toggle-on-light.png | 스위치 켜짐(에스코트 조명) |
| 10-climate-panel-light.png | 공조 패널 라이트 |
| 11-app-library-light.png | 앱 라이브러리(앱 탭) |
| 12-app-library-quick-settings.png | 앱 라이브러리 빠른 설정 탭 |
| 13-app-library-climate-tab.png | 앱 라이브러리 공조 탭 |
| 14-app-library-more.png | 앱 라이브러리 더 보기 탭 |
| 15-settings-display-dark.png | 디스플레이 설정 다크 |
| 16-quick-settings-dark.png | 빠른 설정 다크 |
| 17-settings-drive-dark.png | 주행 설정 다크(세그먼트 다크 색) |
| 18-settings-light-category-dark.png | 라이트 설정 다크 |
| 19-climate-panel-dark.png | 공조 패널 다크 |
| 20-app-library-dark.png | 앱 라이브러리 다크 |
| 21-driving-view-D-60kph-dark.png | 드라이빙뷰 주행 중(D, 60km/h) |
| 22-driving-view-AD-longitudinal-dark.png | AD longitudinal 상태 |
| 23-system-notification-dark.png | 차량 경고 알림 카드 + 상태바 알림 종 |
| 24-driving-view-telltales-dark.png | 경고등(전조등 초록, ABS 주황, 안전벨트 빨강, 좌회전 초록) |
| 25-theme-pop-dark-settings.png | 팝 테마 디스플레이 설정 |
| 26-theme-pop-dark-drive.png | 팝 테마 주행 설정(알약 세그먼트) |
| 27-theme-pop-dark-climate-panel.png | 팝 테마 공조 패널 |
| 28-theme-pop-dark-app-library.png | 팝 테마 앱 라이브러리(원형 아이콘) |

에뮬레이터 상태는 캡처 후 원복함: 화면 모드 자동, 테마 그래파이트, 기어 P, 속도 0, 경고등 끔, 알림 숨김, 에스코트 조명 끔, 애니메이션 배율 기본값, 우리 앱 다시 전면.

## 12. 확보 못한 것

- 토스트, 시스템 팝업, 드롭다운, 텍스트 필드, 키패드의 실제 시스템 화면 캡처(설정 앱에서 띄울 경로를 못 찾음). 치수는 [svg] 로 대체.
- 분할 화면(2앱) 캡처: 3손가락 제스처/창 바 드래그를 adb 로 재현하지 않음. 크기는 기존 form-factor 문서 값.
- fig 파일 안 토큰 실제 값(바이너리 kiwi 해석 필요). 이름만 추출, 값은 가이드 표 이미지로 확보.
- 가이드 그림 속 숫자 라벨은 path 로 되어 있어 텍스트 추출 불가. 치수는 도형 좌표에서 계산했고 라벨이 있는 것(팝업 1200/760)만 확인.
- 글자 크기 sp 는 에뮬레이터에서 직접 못 읽어 TextView 높이로 추정한 값이 섞여 있음([est]).
- 드라이빙뷰 3D 차량은 시스템 렌더(현대차 자산)라 캡처는 참고용만, 배포물에 넣으면 안 됨.
