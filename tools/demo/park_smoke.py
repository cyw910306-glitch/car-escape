"""
Smoke test of 출발 금고 on the Pleos emulator (car build installed) via Virtual Console REST, no browser.
Usage: adb forward tcp:8088 tcp:8088 && python3 tools/demo/park_smoke.py <outdir>
- VC answers 503 emulator_inactive without a live console session: a thread posts the page's heartbeat.
- uiautomator cannot dump while the title logo breathes: animator_duration_scale is 0 during the run, then reset.
"""
import re, subprocess, sys, threading, time, urllib.request
from pathlib import Path
ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
APP = "com.carescape.app"
VC = "http://127.0.0.1:8088/vehicle/"
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
results = []

def heartbeat():
    while True:
        try: urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8088/browser/session?client=park-smoke", method="POST"), timeout=3).read()
        except Exception: pass
        time.sleep(1)
threading.Thread(target=heartbeat, daemon=True).start()
time.sleep(2)

def adb(*a, capture=True):
    return subprocess.run([ADB, *a], check=True, capture_output=capture).stdout

def vc(p):
    urllib.request.urlopen(urllib.request.Request(VC + p, method="PUT"), timeout=5).read()
    time.sleep(0.8)

def dump():
    adb("shell", "rm", "-f", "/sdcard/ui.xml")
    subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
    return subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"], capture_output=True).stdout.decode("utf-8", "replace")

def texts():
    return re.findall(r'text="([^"]+)"', dump())

def find(label):
    x = dump()
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
        if m.group(1) == label:
            a, b, c, d = map(int, m.groups()[1:])
            return (a + c) // 2, (b + d) // 2
    return None

def tap(label):
    xy = find(label)
    if not xy:
        raise SystemExit(f"no '{label}' on screen: {texts()}")
    adb("shell", "input", "tap", str(xy[0]), str(xy[1]))
    time.sleep(1.0)

def shot(name):
    (out / name).write_bytes(adb("exec-out", "screencap", "-p"))

def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("PASS " if cond else "FAIL ") + name, detail, flush=True)

def has(t): return any(t in s for s in texts())

subprocess.run([ADB, "shell", "settings", "put", "global", "animator_duration_scale", "0"], check=True)
import atexit
atexit.register(lambda: subprocess.run([ADB, "shell", "settings", "delete", "global", "animator_duration_scale"]))
# fresh install state, car in P, values from the demo script
adb("shell", "pm", "clear", "--user", "current", APP)
for perm in ["android.car.permission.CAR_SPEED", "android.car.permission.CAR_ENERGY"]:
    adb("shell", "pm", "grant", "--user", "current", APP, perm)
for p in ["gear?gear=Park", "body?doors_locked=false", "body?door_fl=0", "ev-battery?field=charge_port_open&value=false",
          "ev-battery?field=soc&value=42", "odometer?remaining_range_m=300000", "ev-battery?field=charge_switch&value=false"]:
    vc(p)
adb("logcat", "-c")
adb("shell", "am", "start", "-n", APP + "/.MainActivity")
time.sleep(7)
log = adb("logcat", "-d", "-s", "PleosGateway").decode("utf-8", "replace")
check("doorLock capability logged", "doorLock" in log, next((l for l in log.splitlines() if "doorLock" in l), ""))
check("title, no fork at start", has("시작") and not has("어떻게 할까요?"))

vc("gear?gear=Drive"); time.sleep(2)
check("D: no parking card", not has("어떻게 할까요?"))
vc("gear?gear=Park"); time.sleep(2)
check("D->P: fork card", has("어떻게 할까요?")); shot("30-park-fork.png")

tap("다녀올게요")
check("entry shown", has("어른이 눌러 주세요. 아이는 듣고 외워요."))
tap("지하 4"); tap("C"); tap("1"); tap("2")
check("entry value", has("지하 4층 · C 12")); shot("30-park-entry.png")
tap("저장")
check("confirm shown", has("이렇게 남길까요?")); shot("30-park-confirm.png")
tap("맞아요"); time.sleep(1)
check("chip after save", has("주차 암호 보관 중"))

vc("body?door_fl=1"); vc("body?door_fl=0")
vc("ev-battery?field=charge_port_open&value=true")
vc("body?doors_locked=true"); time.sleep(1)
check("locked: chip, no return card", has("주차 암호 보관 중") and not has("누가 기억해요?")); shot("30-park-away.png")
pid_locked = adb("shell", "pidof", APP).decode().strip()
check("app alive after lock", bool(pid_locked), pid_locked)

vc("ev-battery?field=soc&value=78"); vc("odometer?remaining_range_m=312000")
vc("body?doors_locked=false"); time.sleep(1)
check("unlock alone: no card yet", not has("누가 기억해요?"))
vc("body?door_fl=1"); vc("body?door_fl=0"); time.sleep(1)
check("return card", has("우리 차 주차 암호, 누가 기억해요?")); shot("30-return-ask.png")
tap("정답 보기")
check("reveal anchor", has("지하 4층 · C 12"))
check("away line", has("우리가 없는 동안 42%에서 78%까지 충전했어요. 이제 312km 갈 수 있어요."), str([t for t in texts() if "없는 동안" in t or "km" in t]))
shot("30-return-reveal.png")
tap("출발 준비")

# relaunch recovery: save again, force-stop, relaunch
adb("shell", "am", "force-stop", APP); adb("shell", "am", "start", "-n", APP + "/.MainActivity"); time.sleep(7)
check("relaunch with memo: return card", has("누가 기억해요?")); shot("30-return-relaunch.png")
tap("출발 준비")

vc("ev-battery?field=charge_port_open&value=false")  # unplugged before driving off
vc("gear?gear=Drive"); time.sleep(2)
vc("gear?gear=Park"); time.sleep(2)
prefs = adb("shell", "run-as", APP, "--user", "10", "cat", "shared_prefs/progress.xml").decode("utf-8", "replace")
check("D deleted the memo", 'name="park"' not in prefs and not has("주차 암호 보관 중"))
check("fork again after the new stop", has("어떻게 할까요?"))
tap("차에서 기다려요")
check("stay closes fork", not has("어떻게 할까요?") and has("시작"))
vc("ev-battery?field=charge_port_open&value=false"); vc("ev-battery?field=charge_port_open&value=true"); time.sleep(1)
check("waiting side: N화 card", has("1화가 준비됐어요"))
vc("ev-battery?field=charge_port_open&value=false")
print("SUMMARY", sum(r[1] for r in results), "/", len(results))
