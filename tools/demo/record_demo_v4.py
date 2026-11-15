#!/usr/bin/env python3
"""Records the v4 demo clips (v3 "충전 한 번, 이야기 한 화" + 융합판 + 마이 차고) on the Pleos Connect Emulator.

Usage:
  adb forward tcp:8088 tcp:8088
  python3 tools/demo/record_demo_v4.py <out_dir> <scene ...> [--apk app-car.apk] [--quick] [--cal-only] [--no-cal]
Scenes (one clip each: <scene>.webm + <scene>_events.jsonl, seconds from record start):
  plug    title, charge port opens, "1화가 준비됐어요" card, "1화 시작", intro
  loop    오늘의 금고 seed 35, 1화: 창문 50 / 핸들 열선 1 / (기어 D, P) / 독서등 -.-. / 시트 열선 1 / 80 % 엔딩 / 원래대로
  park    기어 D, P 갈림길, 주차 암호 지하 4층 C 12, 떠남, 귀환 카드, 정답 보기
  garage  꾸미기: 페인트, 휠, 스티커, 테마, 드라이브 모드 (keeps the progress the loop scene earned; needs a 10/27+ build)
  mode    debug build only: 시연 패널 주행 모드 에코, 스포츠, 노멀 and the skin follows (label it 시연 패널 in the film)

Final shoot order: plug park loop garage. plug, park and loop start from a wiped app (pm clear); garage must come
right after loop so the stars and badges of that play are still there. loop prints "LOOP RESULT ... 3,<sec>" for a clean take.

How it stays robust: every tap is by on-screen label. uiautomator cannot dump while Compose animates, so each scene
runs twice: a calibration pass (animator_duration_scale 0, same timeline, labels -> coords_v4.json, PNG per tap in
<out>/cal_<scene>/) and then the recorded pass (animations on, taps from the cache). --no-cal reuses the cache.
The car is driven by Virtual Console REST; a heartbeat thread keeps the console session alive (no browser needed).
`--el seed 35` only works on non-release builds (car, debug). Never changes `wm size`. Kills nothing.
"""
import argparse, atexit, html, json, re, shutil, subprocess, tempfile, threading, time, urllib.request
from pathlib import Path

ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
APP = "com.carescape.app"
BASE = "http://127.0.0.1:8088/"
SEED = "35"  # game/DemoSeedTest: 오늘의 금고 map-15 = Window 50, SteeringHeat 1, Morse -.-., SeatHeat 1
COORDS = Path(__file__).with_name("coords_v4.json")

ap = argparse.ArgumentParser()
ap.add_argument("out"); ap.add_argument("scenes", nargs="+")
ap.add_argument("--apk", help="install this APK first (copy it out of app/build so a running gradle cannot swap it)")
ap.add_argument("--quick", action="store_true", help="rehearsal: skip the 95 s SoC pre-roll (ETA stays 계산 중)")
ap.add_argument("--cal-only", action="store_true"); ap.add_argument("--no-cal", action="store_true")
ap.add_argument("--fresh", action="store_true", help="garage: pm clear first (default keeps earned stars and badges)")
args = ap.parse_args()
out = Path(args.out).resolve(); out.mkdir(parents=True, exist_ok=True)
coords = json.loads(COORDS.read_text()) if COORDS.exists() else {}
cal = False; scene = ""; t0 = 0.0; log = None; shots = 0


def adb(*a, check=True):
    return subprocess.run([ADB, *a], check=check, capture_output=True).stdout


def heartbeat():
    while True:
        try: urllib.request.urlopen(urllib.request.Request(BASE + "browser/session?client=demo-v4", method="POST"), timeout=3).read()
        except Exception: pass
        time.sleep(1)


def vc(path, name=None, **kw):
    urllib.request.urlopen(urllib.request.Request(BASE + "vehicle/" + path, method="PUT"), timeout=5).read()
    if name: ev(name, **kw)


def ev(name, **kw):
    rec = {"t": round(time.monotonic() - t0, 2), "event": name, **kw}
    if log: log.write(json.dumps(rec, ensure_ascii=False) + "\n"); log.flush()
    print(("cal " if cal else "rec ") + json.dumps(rec, ensure_ascii=False), flush=True)


def at(sec):
    time.sleep(max(0.0, t0 + sec - time.monotonic()))


def nodes():
    for _ in range(3):
        adb("shell", "rm", "-f", "/sdcard/ui.xml", check=False)
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        x = adb("shell", "cat", "/sdcard/ui.xml", check=False).decode("utf-8", "replace")
        found = [(html.unescape(m.group(1)).replace("\n", ""), (int(m.group(2)) + int(m.group(4))) // 2, (int(m.group(3)) + int(m.group(5))) // 2)
                 for m in re.finditer(r'<node [^>]*?text="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)]
        if found: return found
        time.sleep(0.5)
    return []


def tap(label, nth=0, optional=False, xy=None):
    """Tap the nth node whose text is `label` (exact first, then contains). Calibration finds it, recording replays it.
    xy = fixed screen pixels for the few places uiautomator never gets idle (the open 시연 패널)."""
    global shots, t0
    key = f"{label}#{nth}"
    if xy:
        pass
    elif cal:
        began = time.monotonic()
        ns = nodes()
        hit = [n for n in ns if n[0] == label] or [n for n in ns if label in n[0]]
        xy = list(hit[nth][1:]) if len(hit) > nth else None
        coords.setdefault(scene, {})[key] = xy
        shots += 1
        (out / f"cal_{scene}").mkdir(exist_ok=True)
        (out / f"cal_{scene}" / f"{shots:02d}_{re.sub(r'[^0-9A-Za-z가-힣]+', '_', label)}.png").write_bytes(adb("exec-out", "screencap", "-p"))
        if xy is None and not optional:
            raise SystemExit(f"[{scene}] no '{label}' on screen: {[n[0] for n in ns]}")
        t0 += time.monotonic() - began  # a dump takes seconds: stretch the timeline so later steps keep their distance
    else:
        if key not in coords.get(scene, {}): raise SystemExit(f"[{scene}] '{key}' not calibrated: run without --no-cal")
        xy = coords[scene][key]
    if xy: adb("shell", "input", "tap", str(xy[0]), str(xy[1]))
    ev("tap", label=label, xy=xy)


def pulse(on, off, hold, name):
    vc(on, name + "_on"); time.sleep(hold); vc(off, name + "_off")


def reset_car(soc=42, charging=True):
    for p in ["gear?gear=Park", "body?doors_locked=false", "body?door_fl=0", "body?window_fl=0", "turn-signal?mode=Off",
              "reading-lights?value=false&area=front_left", "body?front_wiper=Off", "hvac?driver_seat=0",
              "steering-wheel-heat?level=0", "ev-battery?field=charge_port_open&value=false",
              "ev-battery?field=percent_limit&value=80", f"ev-battery?field=charge_switch&value={'true' if charging else 'false'}",
              f"ev-battery?field=soc&value={soc}", "odometer?remaining_range_m=300000"]:
        vc(p); time.sleep(0.3)


def fresh():
    adb("shell", "am", "force-stop", APP)
    adb("shell", "pm", "clear", "--user", "current", APP)  # the app runs as user 10
    for perm in ["android.car.permission.CAR_SPEED", "android.car.permission.CAR_ENERGY"]:
        adb("shell", "pm", "grant", "--user", "current", APP, perm, check=False)


def launch(seed=False):
    adb("shell", "am", "force-stop", APP)
    adb("shell", "am", "start", "-n", APP + "/.MainActivity", *(["--el", "seed", SEED] if seed else []))
    time.sleep(7)


def preroll(start=40):
    """ChargeClock wants 3 samples over 90 s. +1 % every 31 s is about 2 %/min: 약 19분 to 80 %, so the 15분 화 fits."""
    if args.quick: return vc(f"ev-battery?field=soc&value={start + 3}")
    # unplug and replug so the clock starts empty (the switch is re-read on each SoC change)
    vc("ev-battery?field=charge_switch&value=false"); vc(f"ev-battery?field=soc&value={start - 1}"); time.sleep(3)
    vc("ev-battery?field=charge_switch&value=true")
    for i in range(4):
        vc(f"ev-battery?field=soc&value={start + i}"); print("preroll soc", start + i, flush=True)
        if i < 3: time.sleep(31)
    time.sleep(6)


def begin(limit):
    global t0, log, shots
    shots = 0
    tmp = Path(tempfile.gettempdir()) / f"carescape_v4_{scene}.webm"  # the emulator console splits on spaces
    if not cal:
        tmp.unlink(missing_ok=True)
        log = (out / f"{scene}_events.jsonl").open("w")
        adb("emu", "screenrecord", "start", "--time-limit", str(limit), "--fps", "30", str(tmp))
    t0 = time.monotonic()
    ev("record_start", scene=scene, apk=args.apk or "installed")
    return tmp


def end(tmp):
    global log
    ev("record_stop")
    if cal: return
    adb("emu", "screenrecord", "stop")
    for _ in range(30):  # the emulator finishes writing after stop returns
        time.sleep(1)
        if tmp.exists() and tmp.stat().st_size > 0: time.sleep(2); break
    shutil.move(str(tmp), out / f"{scene}.webm")
    log.close(); log = None


def scene_plug():
    fresh(); reset_car(40); launch(); preroll(40)
    tmp = begin(60)
    at(8); vc("ev-battery?field=charge_port_open&value=true", "charge_port", open=True)  # card: 충전구 열림 · 1화가 준비됐어요
    at(15); tap("1화 시작")  # startNext(): this play's puzzles are random, so the clip ends on the intro + first puzzle
    at(28); end(tmp)
    vc("ev-battery?field=charge_port_open&value=false")


def scene_loop():
    fresh(); reset_car(40); launch(seed=True); preroll(40)
    tmp = begin(150)
    at(3); tap("오늘의 금고")
    at(8); tap("꼬마 탐정")  # 1화 card on the 오늘의 금고 matching screen; intro runs 5 s on its own
    at(19); vc("body?window_fl=50", "window", value=50)  # 1/4 창문 금고: hold 2 s, 잠금 해제 3 s
    at(30); vc("steering-wheel-heat?level=1", "steering_heat", level=1)  # 2/4 hold 1.5 s
    at(38); vc("gear?gear=Drive", "gear", value="D")  # 3/4 is up: the OS covers the app
    at(43); vc("gear?gear=Park", "gear", value="P")  # same puzzle resumes
    at(49)
    for i, c in enumerate("-.-."):  # 3/4 독서등 등대
        # short must stay under 0.6 s in the app: a REST call alone took 0.2 s on a busy machine and a 0.25 s hold was read as long
        pulse("reading-lights?value=true&area=front_left", "reading-lights?value=false&area=front_left", 1.3 if c == "-" else 0.08, f"reading_{i}_{'long' if c == '-' else 'short'}")
        time.sleep(0.9)
    at(62); vc("hvac?driver_seat=1", "seat_heat", level=1)  # 4/4, then 퍼즐 완료 · 충전 중
    at(71)
    for v in [55, 65, 74, 78, 80]:
        vc(f"ev-battery?field=soc&value={v}", "soc", value=v); time.sleep(1.0)
    at(83); vc("body?window_fl=0", "restore_window")  # 마지막 미션 · 원래대로 출발
    at(84.5); vc("steering-wheel-heat?level=0", "restore_steering")
    at(86); vc("hvac?driver_seat=0", "restore_seat")
    at(94); tap("처음으로")
    at(99); end(tmp)
    # a clean take ends with 3 stars; anything else (a puzzle missed, 보너스 엔딩) means shoot it again
    prefs = adb("shell", "run-as", APP, "--user", "10", "cat", "shared_prefs/progress.xml", check=False).decode("utf-8", "replace")
    best = re.search(r'name="best[^"]*map-15[^"]*">([^<]*)<', prefs)
    print("LOOP RESULT", "best map-15 =", best.group(1) if best else prefs[:300].replace("\n", " "), "(want 3 stars)", flush=True)


def scene_park():
    fresh(); reset_car(42, charging=False); launch()
    tmp = begin(90)
    at(3); vc("gear?gear=Drive", "gear", value="D")
    at(7); vc("gear?gear=Park", "gear", value="P")  # 갈림길: 주차했어요 · 어떻게 할까요?
    at(13); tap("다녀올게요")
    for i, k in enumerate(["지하 4", "C", "1", "2"]):
        at(16 + i * 1.3); tap(k)
    at(22); tap("저장")
    at(25); tap("맞아요")  # chip: 주차 암호 보관 중 · 지하 4층 · C 12
    at(30); vc("body?door_fl=1", "door", open=True); time.sleep(0.8); vc("body?door_fl=0", "door", open=False); time.sleep(0.8)
    vc("ev-battery?field=charge_port_open&value=true", "charge_port", open=True); time.sleep(0.8)
    vc("body?doors_locked=true", "locked")  # 떠남
    at(35); vc("ev-battery?field=soc&value=78", "soc", value=78); time.sleep(0.8); vc("odometer?remaining_range_m=312000", "range_km", value=312)
    at(37); vc("body?doors_locked=false", "unlocked"); time.sleep(0.8)
    vc("body?door_fl=1", "door", open=True); time.sleep(0.8); vc("body?door_fl=0", "door", open=False)  # 다시 탔어요
    at(44); tap("정답 보기")
    at(53); tap("출발 준비")
    at(57); end(tmp)
    vc("ev-battery?field=charge_port_open&value=false")


def scene_garage():
    if args.fresh: fresh()
    reset_car(42, charging=False); launch()
    tmp = begin(90)
    at(3); tap("꾸미기")
    for i, k in enumerate(["밤 검정", "바다 파랑", "달빛 은색"]):
        at(5.5 + i * 2); tap(k, optional=True)
    at(11.5); tap("휠")
    at(13.5); tap("스티커")
    at(16); tap("테마")
    for i, k in enumerate(["숲속 캠핑", "불꽃 서킷", "밤하늘"]):
        at(18 + i * 2); tap(k, optional=True)
    at(24.5); tap("드라이브 모드")
    for i, k in enumerate(["에코", "스포츠"]):
        at(26.5 + i * 1.5); tap(k, optional=True)
    at(30.5); tap("‹ 돌아가기", optional=True)
    at(33); end(tmp)


def scene_mode():
    """debug build only (시연 패널 = simulator): the skin follows the drive mode. Virtual Console has no 노멀/에코/스포츠 card."""
    fresh(); launch()
    tmp = begin(60)
    at(3); tap("‹시연")  # the panel handle reads ‹ 시 연 top to bottom
    # 주행 모드 row of the open panel (17 inch landscape, 2560x1440): 모름 2231, 노멀 2313, 에코 2394, 스포츠 2477, y 316
    for i, (k, x) in enumerate([("에코", 2394), ("스포츠", 2477), ("노멀", 2313)]):
        at(6 + i * 2.5); tap(k, xy=(x, 316))
    at(14); end(tmp)


SCENES = {"plug": scene_plug, "loop": scene_loop, "park": scene_park, "garage": scene_garage, "mode": scene_mode}
threading.Thread(target=heartbeat, daemon=True).start(); time.sleep(2)
atexit.register(lambda: (subprocess.run([ADB, "shell", "settings", "delete", "global", "animator_duration_scale"], capture_output=True),
                         COORDS.write_text(json.dumps(coords, ensure_ascii=False, indent=1))))
if args.apk: print(adb("install", "-r", "-g", args.apk).decode().strip().splitlines()[-1], flush=True)
for scene in args.scenes:
    for cal in ([] if args.no_cal else [True]) + ([] if args.cal_only else [False]):
        adb("shell", "settings", "put", "global", "animator_duration_scale", "0" if cal else "1")
        SCENES[scene]()
    COORDS.write_text(json.dumps(coords, ensure_ascii=False, indent=1))
adb("shell", "am", "force-stop", APP)
