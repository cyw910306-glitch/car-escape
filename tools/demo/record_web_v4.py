#!/usr/bin/env python3
"""Records the 웹 체험판 cuts of the v4 demo with headless Playwright at 1920x1080.

Usage: python3 tools/demo/record_web_v4.py <out_dir> [garage] [screens] [--light] [--played] [--base http://...]
  garage   마이 차고: drag the 3D car around, then pick every open item on the paint / wheel / theme tabs
  screens  the 12 Pleos screen sizes, one after another
Writes <clip>.webm + <clip>_events.jsonl (seconds from clip start) + <clip>_last.png.
Serves web/ itself on a free local port (no deploy, nothing external). --base records another server instead.

web/ is being reworked: everything the script needs to know about the page is in SEL and Q below. Tabs and
items are found by role (every open item on a tab), never by id, so new paints or themes need no change here.
"""
import argparse, functools, http.server, json, shutil, tempfile, threading, time
from pathlib import Path

from playwright.sync_api import sync_playwright

WEB = Path(__file__).resolve().parents[2] / "web"
SEL = {
    "ready": "#game",                         # the car screen is drawn
    "open_garage": '[data-act="garage"]',     # title: 우리 차 꾸미기
    "car3d": '[data-car3d="garage"] canvas',  # drag to turn (the canvas only appears with a real GPU, see launch args)
    "tab": '[data-act="tab:{id}"]',           # paint | wheel | sticker | theme | mode
    "opt": ".opt:not(.locked)",               # one open item on the current tab
    "back": '[data-act="garageback"]',
    "screen_select": "#pl-screen",            # 화면 규격 (hidden with ?clean=1)
}
Q = {"clean": "clean=1", "screen": "screen={id}", "light": "mode=light"}  # query options of web/ui.js
SCREENS = ["S17_LAND", "S17_PORT", "S146_LAND", "S146_PORT", "S129_LAND", "S129_PORT",
           "WS_LAND", "WS_PORT", "WE_LAND", "WE_PORT", "WF_LAND", "WF_PORT"]  # fallback when the select is gone
# --played: a family that finished three 화 (3 stars each) with four badges, so the garage has more than the free items.
PLAYED = {"carescape.best.map-15": "3,412", "carescape.best.family-20": "3,655", "carescape.best.letter-30": "3,901",
          "carescape.badges": json.dumps(["WINDOW", "MORSE", "STEERING_HEAT", "SEAT_HEAT"])}  # the kinds 1화 seed 35 clears

ap = argparse.ArgumentParser()
ap.add_argument("out"); ap.add_argument("clips", nargs="*", default=["garage", "screens"])
ap.add_argument("--light", action="store_true"); ap.add_argument("--played", action="store_true"); ap.add_argument("--base")
args = ap.parse_args()
out = Path(args.out).resolve(); out.mkdir(parents=True, exist_ok=True)

if args.base:
    base = args.base.rstrip("/")
else:
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(WEB))
    handler.log_message = lambda *a: None
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{srv.server_port}"


def url(screen="S17_LAND", clean=True):
    q = ([Q["clean"]] if clean else []) + [Q["screen"].format(id=screen)] + ([Q["light"]] if args.light else [])
    return f"{base}/?{'&'.join(q)}"


def drag(page, sel, frac, secs):
    """Hold the mouse on `sel` and move it sideways by frac of its width over secs."""
    try: page.wait_for_selector(sel, timeout=8000)
    except Exception: return ev("drag_skipped", sel=sel, why="no 3D canvas: the page fell back to the still picture")
    box = page.locator(sel).first.bounding_box()
    x, y = box["x"] + box["width"] * 0.5, box["y"] + box["height"] * 0.5
    page.mouse.move(x, y); page.mouse.down()
    n = max(2, int(secs * 30))
    for i in range(1, n + 1):
        page.mouse.move(x + box["width"] * frac * i / n, y); time.sleep(secs / n)
    page.mouse.up()
    ev("drag", frac=frac, secs=secs)


def clip_garage(page):
    page.goto(url()); page.wait_for_selector(SEL["ready"]); time.sleep(2.0)
    page.click(SEL["open_garage"]); ev("open_garage"); time.sleep(1.5)
    drag(page, SEL["car3d"], 0.45, 3.0); time.sleep(0.4)
    t = page.locator(SEL["tab"].format(id="paint"))
    if t.count(): t.first.click(); ev("tab", tab="paint"); time.sleep(0.6)
    opts = page.locator(SEL["opt"])
    for i in list(range(1, min(opts.count(), 4))):  # the page re-renders on every pick: index, not handle
        opts.nth(i).click(); ev("pick", tab="paint", index=i, label=opts.nth(i).inner_text().split("\n")[0]); time.sleep(1.2)
    drag(page, SEL["car3d"], -0.5, 3.0); time.sleep(0.8)


def clip_screens(page):
    page.goto(url()); page.wait_for_selector(SEL["ready"]); time.sleep(1.5)
    sel = page.locator(SEL["screen_select"])
    if sel.count():  # ?clean=1 hides the select but it still drives the page: set it from script, no reload between sizes
        ids = sel.first.locator("option").evaluate_all("os => os.map(o => o.value)")
        for sid in ids:
            sel.first.evaluate("(el, v) => { el.value = v; el.dispatchEvent(new Event('change', { bubbles: true })); }", sid)
            ev("screen", id=sid); time.sleep(1.3)
    else:  # no select on the page: one clean page per size
        for sid in SCREENS:
            page.goto(url(sid)); page.wait_for_selector(SEL["ready"]); ev("screen", id=sid, by="url"); time.sleep(1.3)
    time.sleep(0.7)


CLIPS = {"garage": clip_garage, "screens": clip_screens}
with sync_playwright() as pw:
    # ponytail: Playwright's own recorder (VP8, about 1 Mbit/s). If the final cut looks soft, switch to a CDP
    # screencast or record at 2x and scale down; the clip functions stay the same.
    browser = pw.chromium.launch(headless=True, args=["--use-angle=metal", "--enable-gpu", "--ignore-gpu-blocklist"])  # web/car3d.js shows the 3D car on a hardware renderer only (SwiftShader gets the PNG)
    for name in args.clips:
        tmp = Path(tempfile.mkdtemp(prefix="carescape_web_"))
        ctx = browser.new_context(viewport={"width": 1920, "height": 1080}, device_scale_factor=1, locale="ko-KR",
                                  record_video_dir=str(tmp), record_video_size={"width": 1920, "height": 1080})
        if args.played:
            ctx.add_init_script("const p=%s; for (const k in p) if (!localStorage.getItem(k)) localStorage.setItem(k, p[k]);" % json.dumps(PLAYED))
        page = ctx.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        log = (out / f"{name}_events.jsonl").open("w"); t0 = time.monotonic()

        def ev(event, **kw):
            rec = {"t": round(time.monotonic() - t0, 2), "event": event, **kw}
            log.write(json.dumps(rec, ensure_ascii=False) + "\n"); print(name, rec, flush=True)

        ev("record_start", url=url(), webgl=page.evaluate("!!document.createElement('canvas').getContext('webgl')"))
        try:
            CLIPS[name](page)
        finally:
            page.screenshot(path=str(out / f"{name}_last.png"))
            ev("record_stop", page_errors=errors[:5])
            video = page.video.path(); ctx.close(); log.close()
            shutil.move(video, out / f"{name}.webm"); shutil.rmtree(tmp, ignore_errors=True)
    browser.close()
