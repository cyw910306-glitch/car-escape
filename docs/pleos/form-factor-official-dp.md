# Pleos 폼팩터별 앱 창 크기 (공식, 2026-10-23 실측)

출처: Pleos Connect Emulator v2.1.0 내장 FormFactorConsole(ai.pleos.connectwebconsole/.FormFactorConsoleActivity)의
"테스트 화면 크기" 표시값. 프리셋·방향을 uiautomator로 바꿔 가며 읽음. 창 하나 기준(1:1 분할 = Portrait, 단독 = Landscape).

| 프리셋 | DPI | Portrait dp (px) | Landscape dp (px) |
|---|---|---|---|
| Connect-S 17" 2560x1440 | 160 | 840x1184 (840x1184) | 1700x1184 (1700x1184) |
| Connect-S 14.6" 2048x1152 | 200 | 672x948 (840x1185) | 1360x948 (1700x1185) |
| Connect-S 12.9" 1920x1080 | 160 | 628x855 (628x855) | 1274x855 (1274x855) |
| Connect-WS 24.6" 3840x850 | 160 | 800x810 (800x810) | 1640x810 (1640x810) |
| Connect-WE 24.6" 3840x1440 | 160 | 800x1400 (800x1400) | 1640x1400 (1640x1400) |
| Connect-WF 18.7" 3072x816 | 200 | 648x800 (810x1000) | 1256x800 (1570x1000) |

참고: 실제 에뮬레이터(17") 단독 창 실측 Rect(853,90-2535,1300) = 1682x1210 (창 바 포함 여부에 따른 차이).
wm size/density 로 메인 디스플레이를 바꾸는 방식은 Pleos 시스템 UI가 재배치되지 않아 측정에 쓸 수 없음.
