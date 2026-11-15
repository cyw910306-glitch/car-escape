"""Post-process Blender renders: paint masks, contact sheet, reference compare, web copies, Android WebP.
Run after build_ioniq9.py:  python3 tools/ioniq9/sheets.py [reference_dir]
While modelling:            python3 tools/ioniq9/sheets.py --iter N reference_dir   (after build_ioniq9.py -- --preview)
reference_dir holds real-car photos (side.png, f34.jpg, r34.jpg, front.jpg, rear.jpg). They are shape reference only:
compare.png and out/progress/ contain them, so neither is shipped or committed.
"""
import os, re, shutil, sys
from PIL import Image, ImageChops, ImageDraw, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
WEB = os.path.abspath(os.path.join(HERE, "..", "..", "web", "assets"))
RES = os.path.abspath(os.path.join(HERE, "..", "..", "ui", "src", "main", "res", "drawable-nodpi"))
ARGS = sys.argv[1:]
ITER = int(ARGS.pop(ARGS.index("--iter") + 1)) if "--iter" in ARGS else None
REF = next((a for a in ARGS if not a.startswith("--")), None)
NEUTRAL = 0.70  # body_paint albedo in the base renders

# Customization.kt Paint rgb, read straight from source so the sheet never drifts.
SRC = os.path.join(HERE, "..", "..", "game", "src", "main", "kotlin", "com", "carescape", "game", "Customization.kt")
PAINTS = [(n, int(h, 16)) for n, h in re.findall(r'^\s+(\w+)\("[^"]+", 0x([0-9A-Fa-f]{6})', open(SRC).read(), re.M)][:8]


def make_mask(view):
    raw = Image.open(os.path.join(OUT, f"ioniq9_{view}_paint_mask_raw.png")).convert("RGBA")
    la = Image.merge("LA", (raw.convert("L"), raw.getchannel("A")))
    la.save(os.path.join(OUT, f"ioniq9_{view}_paint_mask.png"), optimize=True)
    os.remove(os.path.join(OUT, f"ioniq9_{view}_paint_mask_raw.png"))
    return la


def paint(view, rgb, wheel="basic"):
    """What the app does at runtime: base, then mask * color / NEUTRAL over the paint area."""
    base = Image.open(os.path.join(OUT, f"ioniq9_{view}_base_{wheel}.png")).convert("RGBA")
    mask = Image.open(os.path.join(OUT, f"ioniq9_{view}_paint_mask.png"))
    lum, a = mask.split()
    col = [((rgb >> s) & 255) / 255 / NEUTRAL for s in (16, 8, 0)]
    tint = Image.merge("RGB", [lum.point(lambda v, c=c: min(255, int(v * c))) for c in col])
    out = base.copy()
    out.paste(tint, (0, 0), a)
    return out


def compare(path):
    """Real car beside our model, same angles: side, front 3/4, rear 3/4, front, rear. Not shipped."""
    W, BG = 700, (236, 238, 242)
    def fit(im, w):
        im = im.convert("RGBA")
        flat = Image.new("RGB", im.size, BG); flat.paste(im, (0, 0), im)
        return flat.resize((w, int(im.height * w / im.width)), Image.LANCZOS)
    rows = []
    for name, ref, w in (("side", "side.png", W), ("f34", "f34.jpg", W), ("r34", "r34.jpg", W), ("front", "front.jpg", W // 2), ("rear", "rear.jpg", W // 2)):
        ours = os.path.join(OUT, "progress", f"_{name}.png")
        if os.path.exists(ours) and os.path.exists(os.path.join(REF, ref)):
            rows.append((fit(Image.open(os.path.join(REF, ref)), w), fit(Image.open(ours), w)))
    wide, small = [r for r in rows if r[0].width == W], [r for r in rows if r[0].width != W]
    h = sum(max(a.height, b.height) for a, b in wide) + (max(max(a.height, b.height) for a, b in small) if small else 0)
    sheet = Image.new("RGB", (2 * W, h), BG)
    y = 0
    for a, b in wide:
        sheet.paste(a, (0, y)); sheet.paste(b, (W, y)); y += max(a.height, b.height)
    for i, (a, b) in enumerate(small):
        sheet.paste(a, (i * W, y)); sheet.paste(b, (i * W + W // 2, y))
    sheet.thumbnail((1400, 1400), Image.LANCZOS)   # one glance, no bigger
    sheet.save(path)


def main():
    if ITER is not None:
        return compare(os.path.join(OUT, "progress", f"iter_{ITER:02d}.png"))
    for v in ("side", "three_quarter", "rear_quarter"):
        if os.path.exists(os.path.join(OUT, f"ioniq9_{v}_paint_mask_raw.png")):
            make_mask(v)
    # contact sheet: 8 paints x side (wheel cycles through the 4 designs)
    wheels = ["basic", "star", "pinwheel", "gold"]
    tiles = [paint("side", rgb, wheels[i % 4]) for i, (_, rgb) in enumerate(PAINTS)]
    tw, th = tiles[0].size
    sheet = Image.new("RGB", (tw * 2, th * 4), (236, 238, 242))
    d = ImageDraw.Draw(sheet)
    for i, (t, (n, _)) in enumerate(zip(tiles, PAINTS)):
        x, y = (i % 2) * tw, (i // 2) * th
        sheet.paste(t, (x, y), t)
        d.text((x + 12, y + 10), n, fill=(40, 40, 50))
    sheet.save(os.path.join(OUT, "contact.png"))
    # 3/4 strip
    tq = [paint("three_quarter", rgb, wheels[i % 4]) for i, (_, rgb) in enumerate(PAINTS[:4])]
    w3, h3 = tq[0].size
    strip = Image.new("RGB", (w3 * 2, h3 * 2), (236, 238, 242))
    for i, t in enumerate(tq):
        strip.paste(t, ((i % 2) * w3, (i // 2) * h3), t)
    strip.save(os.path.join(OUT, "contact_three_quarter.png"))
    if REF:
        compare(os.path.join(OUT, "compare.png"))
    os.makedirs(WEB, exist_ok=True)
    for f in os.listdir(OUT):
        if f.startswith("ioniq9_") and f.endswith(".png"):
            shutil.copy(os.path.join(OUT, f), os.path.join(WEB, f))
    shutil.copy(os.path.join(OUT, "sticker_slots.json"), os.path.join(WEB, "ioniq9_sticker_slots.json"))
    # Android CarAvatar uses the side view only, as WebP (RGBA so the mask's alpha survives)
    for f in os.listdir(OUT):
        if f.startswith("ioniq9_side_") and f.endswith(".png"):
            Image.open(os.path.join(OUT, f)).convert("RGBA").save(os.path.join(RES, f[:-4] + ".webp"), quality=80, method=6)


if __name__ == "__main__":
    main()
