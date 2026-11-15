#!/usr/bin/env python3
"""Records the 15-minute-episode demo on the Pleos Connect Emulator.

Drives the app with adb taps and the car with the Virtual Console REST API
(adb forward tcp:8088 tcp:8088 first; keep http://127.0.0.1:8088/ open in a browser,
without an open console session the REST API answers 503 emulator_inactive), records the emulator screen on the host,
and writes events.jsonl (seconds from record start) for caption timing.

Usage: python3 record_demo.py <out_dir>
Tap coordinates assume the 17" Connect-S landscape app window (853,90 .. 2535,1300).
"""
import json
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
VC = "http://127.0.0.1:8088/vehicle/"
PKG = "com.carescape.app/.MainActivity"

TAP_TITLE_START = (2140, 800)
TAP_EPISODE_15 = (1293, 790)
TAP_INTRO_START = (1694, 862)

out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
log = (out / "events.jsonl").open("w")
t0 = 0.0


def adb(*args):
    subprocess.run([ADB, *args], check=True, capture_output=True)


def vc(path):
    urllib.request.urlopen(urllib.request.Request(VC + path, method="PUT"), timeout=5).read()


def ev(name, **kw):
    rec = {"t": round(time.monotonic() - t0, 2), "event": name, **kw}
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


# Reset car and app to a clean parked, charging state.
for p in ["gear?gear=Park", "body?window_fl=0", "turn-signal?mode=Off", "reading-lights?value=false&area=front_left",
          "body?front_wiper=Off", "hvac?driver_seat=0", "steering-wheel-heat?level=0",
          "ev-battery?field=percent_limit&value=80", "ev-battery?field=charge_switch&value=true",
          "ev-battery?field=soc&value=40"]:
    vc(p)
adb("shell", "am", "force-stop", "com.carescape.app")
adb("shell", "am", "start", "-n", PKG)
time.sleep(6)

video = out / "demo_raw.webm"
# The emulator console splits its command on spaces, so record to a space-free path and move it after.
tmp = Path(tempfile.gettempdir()) / "carescape_demo_raw.webm"
tmp.unlink(missing_ok=True)
adb("emu", "screenrecord", "start", "--time-limit", "300", "--fps", "30", str(tmp))
t0 = time.monotonic()
ev("record_start", file=video.name)

# Charging at 2 %/min: three samples over 90 s give ChargeClock its first estimate.
at(1); soc(40)
at(30); soc(41)
at(60); soc(42)
at(95); soc(43)

at(100); adb("shell", "input", "tap", *map(str, TAP_TITLE_START)); ev("tap_start")
at(107); adb("shell", "input", "tap", *map(str, TAP_EPISODE_15)); ev("tap_episode_15")
at(113); adb("shell", "input", "tap", *map(str, TAP_INTRO_START)); ev("tap_intro_start")

# Puzzle 1: window vault, driver window to 50 %.
at(119); vc("body?window_fl=50"); ev("window", value=50)
at(125); soc(44)

# Puzzle 2: turn signals left, right, left.
at(134)
for side, mode in [("left", "BlinkLeft"), ("right", "BlinkRight"), ("left", "BlinkLeft")]:
    pulse(f"turn-signal?mode={mode}", "turn-signal?mode=Off", 1.2, "signal_" + side)
    time.sleep(0.8)

# Safety: drive away mid-game, then park again.
at(152); vc("gear?gear=Drive"); ev("gear", value="D")
at(158); soc(45)
at(160); vc("gear?gear=Park"); ev("gear", value="P")

# Puzzle 3: reading light short, long.
at(166)
pulse("reading-lights?value=true&area=front_left", "reading-lights?value=false&area=front_left", 0.25, "reading_short")
time.sleep(0.9)
pulse("reading-lights?value=true&area=front_left", "reading-lights?value=false&area=front_left", 1.3, "reading_long")

# Puzzle 4: wiper on and off twice.
at(176)
for i in range(2):
    pulse("body?front_wiper=Low", "body?front_wiper=Off", 1.2, f"wiper_{i + 1}")
    time.sleep(0.8)

# Charge reaches the 80 % target: ending.
at(190)
for v in [50, 60, 70, 78, 80]:
    soc(v)
    time.sleep(1.0)

at(210)
adb("emu", "screenrecord", "stop")
ev("record_stop")
for _ in range(30):  # the emulator finishes writing the file after stop returns
    time.sleep(1)
    if tmp.exists() and tmp.stat().st_size > 0:
        time.sleep(2)
        break
shutil.move(str(tmp), video)
log.close()
