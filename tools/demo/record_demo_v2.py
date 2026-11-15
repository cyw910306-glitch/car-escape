#!/usr/bin/env python3
"""Records the v2 demo (오늘의 금고, 15-minute episode) on the Pleos Connect Emulator with the `car` build.

Same approach as record_demo.py: adb taps drive the app, the Virtual Console REST API drives the car,
`adb emu screenrecord` records on the host, events.jsonl holds seconds from record start.
This script opens the console page itself in a headless Chromium (without an open console session the
REST API answers 503 emulator_inactive) and keeps it open for the whole run.

Deterministic play: the app is launched with `--el seed 35` (non-release builds only, see README-dev.md),
which pins 오늘의 금고 to: window 50 % | 기억 금고 R,R,L | 독서등 .- | 와이퍼 1 (DemoSeedTest locks it).

Before the take an unrecorded warm-up play (pm clear, window solved, the rest skipped, 1 star) leaves a
best record and one badge, so the title shows 최고 기록·배지 and the take's ending shows 새 기록 / 새 배지.

Usage: python3 record_demo_v2.py <out_dir> [--belt-clip]
Tap coordinates assume the 17" Connect-S landscape app window (853,90 .. 2535,1300) on 2560x1440.
"""
import json
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
CONSOLE = "http://127.0.0.1:8088/"
VC = CONSOLE + "vehicle/"
APP = "com.carescape.app"
SEED = "35"

TAP_TITLE_TODAY_FRESH = (2290, 800)  # 오늘의 금고, no records yet
TAP_TITLE_TODAY = (2290, 748)  # 오늘의 금고 moves up once the 최고 기록 row shows
TAP_EPISODE_15 = (1160, 780)
TAP_INTRO_START = (1694, 862)
TAP_SKIP = (2294, 888)  # 건너뛰기 under a two-line prompt (puzzles 2 to 4)
TAP_ENDING_HOME = (1694, 1122)  # 처음으로
# --belt-clip (debug build): 20분 card, its intro 시작, the 시연 tab, 운전석 belt after scrolling the panel.
TAP_EPISODE_20 = (1694, 763)
TAP_INTRO_START_20 = (1662, 856)
TAP_PANEL_OPEN = (2500, 700)
TAP_BELT_DRIVER = (2272, 1134)

out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
log = None
t0 = 0.0


def adb(*args):
    subprocess.run([ADB, *args], check=True, capture_output=True)


def tap(xy):
    adb("shell", "input", "tap", *map(str, xy))


def vc(path):
    urllib.request.urlopen(urllib.request.Request(VC + path, method="PUT"), timeout=5).read()


def ev(name, **kw):
    rec = {"t": round(time.monotonic() - t0, 2), "event": name, **kw}
    if log:
        log.write(json.dumps(rec, ensure_ascii=False) + "\n")
        log.flush()
    print(rec, flush=True)


def at(sec):
    """Sleep until `sec` seconds after record start."""
    time.sleep(max(0.0, t0 + sec - time.monotonic()))


def soc(v):
    vc(f"ev-battery?field=soc&value={v}")
    ev("soc", value=v)


def pulse(on_path, off_path, hold, name):
    vc(on_path)
    ev(name + "_on")
    time.sleep(hold)
    vc(off_path)
    ev(name + "_off")


def reset_car(soc_value=40):
    for p in ["gear?gear=Park", "body?window_fl=0", "turn-signal?mode=Off", "reading-lights?value=false&area=front_left",
              "body?front_wiper=Off", "hvac?driver_seat=0", "steering-wheel-heat?level=0",
              "ev-battery?field=percent_limit&value=80", "ev-battery?field=charge_switch&value=true",
              f"ev-battery?field=soc&value={soc_value}"]:
        vc(p)


def launch():
    adb("shell", "am", "force-stop", APP)
    adb("shell", "am", "start", "-n", APP + "/.MainActivity", "--el", "seed", SEED)
    time.sleep(6)


def warm_up():
    """Unrecorded play: 15-minute 오늘의 금고, window solved, the rest skipped, charge to target, back to title."""
    adb("shell", "pm", "clear", "--user", "current", APP)  # the app runs as user 10
    for perm in ["android.car.permission.CAR_SPEED", "android.car.permission.CAR_ENERGY"]:
        adb("shell", "pm", "grant", "--user", "current", APP, perm)
    reset_car(70)
    launch()
    tap(TAP_TITLE_TODAY_FRESH); time.sleep(2)
    tap(TAP_EPISODE_15); time.sleep(2)
    tap(TAP_INTRO_START); time.sleep(3)
    vc("body?window_fl=50"); time.sleep(6)  # 2 s hold + 3 s unlock screen
    for _ in range(3):
        tap(TAP_SKIP); time.sleep(1.5)
    for v in [75, 80]:
        vc(f"ev-battery?field=soc&value={v}"); time.sleep(1)
    time.sleep(3)  # the record is saved on the ending; no tap needed
    adb("shell", "am", "force-stop", APP)


def belt_clip():
    """찰칵 has no Virtual Console card: short clip on the DEBUG build, belt tapped in the 시연 패널 (simulator).

    Same seed 35, 오늘의 금고 20분 편: puzzle 1 is 찰칵 금고 (one front belt unbuckled -> buckled). Writes
    belt_clip.webm + belt_events.jsonl (seconds from clip start) and reinstalls the car build afterwards.
    """
    global log, t0
    root = Path(__file__).resolve().parents[2] / "app/build/outputs/apk"
    adb("install", "-r", str(root / "debug/app-debug.apk"))
    launch()
    tap(TAP_TITLE_TODAY); time.sleep(2)
    tap(TAP_EPISODE_20); time.sleep(2)
    tap(TAP_INTRO_START_20); time.sleep(4)  # 1/5 찰칵 금고 on screen
    log = (out / "belt_events.jsonl").open("w")
    tmp = Path(tempfile.gettempdir()) / "carescape_belt_clip.webm"
    tmp.unlink(missing_ok=True)
    adb("emu", "screenrecord", "start", "--time-limit", "60", "--fps", "30", str(tmp))
    t0 = time.monotonic()
    ev("record_start", file="belt_clip.webm", build="debug", label="시연 패널(시뮬레이터)", seed=int(SEED), episode="family-20", daily=True)
    at(3); tap(TAP_PANEL_OPEN); ev("panel_open")
    at(5); adb("shell", "input", "swipe", "2350", "1150", "2350", "450", "600"); ev("panel_scroll_to_belts")
    at(8); tap(TAP_BELT_DRIVER); ev("belt_driver_buckled")
    at(14)
    adb("emu", "screenrecord", "stop")
    ev("record_stop")
    for _ in range(30):
        time.sleep(1)
        if tmp.exists() and tmp.stat().st_size > 0:
            time.sleep(2)
            break
    shutil.move(str(tmp), out / "belt_clip.webm")
    log.close()
    adb("shell", "am", "force-stop", APP)
    adb("install", "-r", str(root / "car/app-car.apk"))


if "--belt-clip" in sys.argv:
    belt_clip()
    sys.exit(0)

with sync_playwright() as pw:
    browser = pw.chromium.launch(headless=True)
    browser.new_page().goto(CONSOLE)
    time.sleep(3)

    warm_up()
    reset_car(40)
    launch()
    # The fresh app may still take the warm-up's 80 % as its first sample, and a falling SoC keeps the ETA at
    # "계산 중". Unplug and replug (the switch is re-read on each SoC change) so ChargeClock starts empty.
    vc("ev-battery?field=charge_switch&value=false"); vc("ev-battery?field=soc&value=39"); time.sleep(3)
    vc("ev-battery?field=charge_switch&value=true"); vc("ev-battery?field=soc&value=40"); time.sleep(3)

    log = (out / "events.jsonl").open("w")
    video = out / "demo_raw.webm"
    # The emulator console splits its command on spaces, so record to a space-free path and move it after.
    tmp = Path(tempfile.gettempdir()) / "carescape_demo_v2_raw.webm"
    tmp.unlink(missing_ok=True)
    adb("emu", "screenrecord", "start", "--time-limit", "300", "--fps", "30", str(tmp))
    t0 = time.monotonic()
    ev("record_start", file=video.name, build="car", seed=int(SEED), episode="map-15", daily=True)

    # Title: records + badge from the warm-up. Charging at 3 %/min, samples over 90 s give the first ETA
    # (about 12 min left, so the matching screen recommends the 15-minute episode we pick).
    at(1); soc(40)
    at(30); soc(42)
    at(60); soc(43)
    at(95); soc(45)

    at(100); tap(TAP_TITLE_TODAY); ev("tap_today")
    at(107); tap(TAP_EPISODE_15); ev("tap_episode_15")
    at(113); tap(TAP_INTRO_START); ev("tap_intro_start")

    # Puzzle 1 (classic) 창문 금고: driver window to 50 %, held 2 s.
    at(119); vc("body?window_fl=50"); ev("window", value=50)
    at(125); soc(46)

    # Puzzle 2 (v2) 기억 금고: R, R, L shown about 10 s from ~124.5 s, then hidden. Enter it after the hide.
    at(138)
    for side, mode in [("right", "BlinkRight"), ("right", "BlinkRight"), ("left", "BlinkLeft")]:
        pulse(f"turn-signal?mode={mode}", "turn-signal?mode=Off", 1.2, "signal_" + side)
        time.sleep(0.8)

    # Safety: drive away during puzzle 3, then park again; the same puzzle resumes.
    at(152); vc("gear?gear=Drive"); ev("gear", value="D")
    at(157); soc(47)
    at(160); vc("gear?gear=Park"); ev("gear", value="P")

    # Puzzle 3 (classic) 독서등 등대: short, long.
    at(166)
    pulse("reading-lights?value=true&area=front_left", "reading-lights?value=false&area=front_left", 0.25, "reading_short")
    time.sleep(0.9)
    pulse("reading-lights?value=true&area=front_left", "reading-lights?value=false&area=front_left", 1.3, "reading_long")

    # Puzzle 4 (classic) 와이퍼 인사: once.
    at(176)
    pulse("body?front_wiper=Low", "body?front_wiper=Off", 1.2, "wiper_1")

    # Waiting room, then charge reaches the 80 % target: ending (stars, 새 기록, 새 배지, close-windows reminder).
    at(188)
    for v in [50, 60, 70, 78, 80]:
        soc(v)
        time.sleep(1.0)

    at(206); tap(TAP_ENDING_HOME); ev("tap_home")
    at(214)
    adb("emu", "screenrecord", "stop")
    ev("record_stop")
    for _ in range(30):  # the emulator finishes writing the file after stop returns
        time.sleep(1)
        if tmp.exists() and tmp.stat().st_size > 0:
            time.sleep(2)
            break
    shutil.move(str(tmp), video)
    log.close()
    browser.close()
