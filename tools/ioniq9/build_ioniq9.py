"""IONIQ 9 3D asset v2: lofted body + projected trim, export GLB, render sprites.

Run (re-runnable, starts from an empty scene):
  /opt/homebrew/bin/blender -b -P tools/ioniq9/build_ioniq9.py -- [--no-render] [--samples N] [--quick] [--preview]
Then: python3 tools/ioniq9/sheets.py  (masks / contact sheets / web copies / Android webp)

Our own model from public dimensions (L5060 W1980 H1790 WB3130, 21 inch wheels, tyre OD 790).
Photos were used only as shape reference (side silhouette traced into the tables below); nothing official ships.
How it is built:
  body    = smooth cross-sections (rounded polygon with crowned spans) lofted along the car, sheared at nose and tail,
            wheel arches cut with a boolean, fender flares pushed out of the side.
  trim    = glass, pillars, lamps, cladding, seams: thin patches ray-projected onto the body (always flush with it).
  wheels  = lathed tyre and barrel, concave spokes, 4 designs in groups wheels_BASIC|STAR|PINWHEEL|GOLD.
Axes: +X = car front, +Z up, ground z=0, car's right side = -Y (the side the cameras see).
"""
import bpy, bmesh, json, math, os, sys
import numpy as np
from mathutils import Vector
from mathutils.bvhtree import BVHTree
from bpy_extras.object_utils import world_to_camera_view

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
WEB = os.path.abspath(os.path.join(HERE, "..", "..", "web", "assets"))
argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
RENDER = "--no-render" not in argv
PREVIEW = "--preview" in argv
SAMPLES = int(argv[argv.index("--samples") + 1]) if "--samples" in argv else 96

L, W = 5.06, 1.98
XF, XR = L / 2, -L / 2
AXLE_F, AXLE_R = XF - 0.86, XR + 1.07          # 1.67, -1.46 -> wheelbase 3.13
WHEEL_R, WHEEL_Y, RIM_R = 0.395, 0.828, 0.282   # tyre OD 790, 21 inch rim
ARCH = (0.492, 0.375, 0.838, 0.17)              # wheel-arch opening: half width at the sill, at the top, top height, corner radius
CLAD = (0.585, 0.455, 0.925, 0.20)              # outer edge of the black arch cladding, same four numbers
FLARE = dict(a=0.650, b=0.610, n=4.0)           # body-colour fender plateau around the cladding

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene


# ---------- materials ----------
def mat(name, rgb, metal=0.0, rough=0.5, emit=0.0, coat=0.0, coat_rough=0.03):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = next(n for n in m.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
    b.inputs["Base Color"].default_value = (*rgb, 1)
    b.inputs["Metallic"].default_value = metal
    b.inputs["Roughness"].default_value = rough
    b.inputs["Coat Weight"].default_value = coat
    b.inputs["Coat Roughness"].default_value = coat_rough
    if emit:
        b.inputs["Emission Color"].default_value = (*rgb, 1)
        b.inputs["Emission Strength"].default_value = emit
    return m


M = {
    "body_paint": mat("body_paint", (0.70, 0.70, 0.70), rough=0.30, coat=1.0, coat_rough=0.02),   # neutral: the app tints it
    "glass": mat("glass", (0.024, 0.032, 0.044), rough=0.06, coat=1.0),
    "black_gloss": mat("black_gloss", (0.012, 0.012, 0.014), rough=0.12, coat=0.6),
    "cladding": mat("cladding", (0.022, 0.022, 0.024), rough=0.62),
    "chrome": mat("chrome", (0.58, 0.59, 0.61), metal=1.0, rough=0.32),
    "lamp_front": mat("lamp_front", (0.93, 0.96, 1.0), emit=7.0),
    "lamp_rear": mat("lamp_rear", (0.95, 0.05, 0.03), emit=5.0),
    "lamp_lens": mat("lamp_lens", (0.05, 0.055, 0.065), rough=0.10, coat=1.0),
    "lamp_pixel": mat("lamp_pixel", (0.30, 0.32, 0.36), rough=0.15, emit=0.35, coat=1.0),
    "plate": mat("plate", (0.80, 0.80, 0.78), rough=0.45),
    "reflector": mat("reflector", (0.55, 0.03, 0.02), rough=0.25, coat=0.5),
    "tire": mat("tire", (0.022, 0.022, 0.024), rough=0.82),
    "rim": mat("rim", (0.74, 0.75, 0.77), metal=1.0, rough=0.22),
    "rim_dark": mat("rim_dark", (0.045, 0.048, 0.052), metal=0.7, rough=0.42),
    "brake": mat("brake", (0.42, 0.42, 0.44), metal=1.0, rough=0.45),
    "rim_gold": mat("rim_gold", (0.83, 0.62, 0.25), metal=1.0, rough=0.2),
    "rim_pin_red": mat("rim_pin_red", (0.85, 0.12, 0.12), rough=0.35, coat=0.5),
    "rim_pin_yellow": mat("rim_pin_yellow", (0.98, 0.78, 0.1), rough=0.35, coat=0.5),
    "rim_pin_blue": mat("rim_pin_blue", (0.12, 0.4, 0.9), rough=0.35, coat=0.5),
    "rim_pin_green": mat("rim_pin_green", (0.15, 0.7, 0.3), rough=0.35, coat=0.5),
}
MAT_NAMES = list(M)


def link(obj):
    scene.collection.objects.link(obj)
    return obj


def mesh_obj(name, verts, faces, mats, face_mats=None, recalc=True, smooth=40):
    me = bpy.data.meshes.new(name)
    me.from_pydata([tuple(v) for v in verts], [], faces)
    for m in mats:
        me.materials.append(M[m])
    if face_mats:
        me.polygons.foreach_set("material_index", face_mats)
    me.validate()
    if recalc:
        bm = bmesh.new(); bm.from_mesh(me)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        bm.to_mesh(me); bm.free()
    o = link(bpy.data.objects.new(name, me))
    if smooth:
        shade(o, smooth)
    return o


def shade(o, angle):
    bpy.ops.object.select_all(action="DESELECT")
    bpy.context.view_layer.objects.active = o; o.select_set(True)
    bpy.ops.object.shade_smooth_by_angle(angle=math.radians(angle))


def join(objs, name):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    o = bpy.context.active_object
    o.name = name
    return o


# ---------- shape tables (metres; x is the station along the car) ----------
def pchip(pts):
    """Monotone cubic through the points: smooth, never overshoots a traced silhouette."""
    pts = sorted(pts)
    xs = np.array([p[0] for p in pts], float); ys = np.array([p[1] for p in pts], float)
    h = np.diff(xs); d = np.diff(ys) / h
    m = np.zeros_like(xs); m[0], m[-1] = d[0], d[-1]
    for i in range(1, len(xs) - 1):
        if d[i - 1] * d[i] > 0:
            w1, w2 = 2 * h[i] + h[i - 1], h[i] + 2 * h[i - 1]
            m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])

    def f(x):
        x = min(max(x, xs[0]), xs[-1])
        i = int(min(max(np.searchsorted(xs, x) - 1, 0), len(xs) - 2))
        t = (x - xs[i]) / h[i]
        return float((2 * t**3 - 3 * t**2 + 1) * ys[i] + (t**3 - 2 * t**2 + t) * h[i] * m[i]
                     + (-2 * t**3 + 3 * t**2) * ys[i + 1] + (t**3 - t**2) * h[i] * m[i + 1])
    return f


def smooth(t):
    t = min(max(t, 0.0), 1.0)
    return t * t * (3 - 2 * t)


# centreline top: hood, fast windshield, roof highest over the B-pillar, then falling ~13 cm to the tail (boat tail)
z_top = pchip([(XR, 1.644), (-2.3, 1.668), (-2.1, 1.678), (-1.82, 1.702), (-1.38, 1.741), (-0.95, 1.765), (-0.5, 1.772),
               (-0.236, 1.768), (0.05, 1.727), (0.336, 1.638), (0.764, 1.455), (1.0, 1.338), (1.25, 1.215),
               (1.65, 1.15), (2.2, 1.09), (XF, 1.055)])
# shoulder crease: runs the whole car, climbing gently to the tail lamps
z_sh = pchip([(XR, 1.215), (-2.1, 1.215), (-1.22, 1.20), (0.0, 1.168), (1.0, 1.14), (1.25, 1.13),
              (1.65, 1.068), (2.2, 1.008), (XF, 0.975)])
z_bot = pchip([(XR, 0.30), (-2.25, 0.26), (-1.95, 0.19), (2.05, 0.19), (2.3, 0.21), (XF, 0.23)])
plan = pchip([(XR, 0.955), (-2.3, 0.962), (-1.8, 0.975), (-1.0, 0.988), (-0.3, 0.99), (1.9, 0.99), (2.2, 0.985), (XF, 0.975)])
inset = pchip([(XR, 0.06), (-2.3, 0.12), (-1.6, 0.195), (-1.0, 0.225), (0.15, 0.235), (0.7, 0.185), (1.25, 0.13), (1.5, 0.11), (XF, 0.10)])
crown = pchip([(XR, 0.035), (-1.0, 0.04), (-0.3, 0.035), (0.05, 0.03), (0.336, 0.03), (0.764, 0.04), (1.0, 0.04),
               (1.25, 0.035), (1.5, 0.015), (XF, 0.015)])
roof_r = pchip([(XR, 0.055), (-0.3, 0.07), (0.4, 0.05), (1.0, 0.05), (1.3, 0.035), (XF, 0.03)])
roof_bulge = pchip([(XR, 0.018), (0.0, 0.022), (0.7, 0.032), (1.25, 0.022), (XF, 0.02)])
BELT = 0.058                      # window line sits this far above the shoulder crease
CORNER_F = (0.50, 0.33, 2.3)      # plan-view corner: length along x, width it takes off, superellipse power
CORNER_R = (0.14, 0.085, 2.8)
# side-view corners: the hood rolls down into the nose, the roof edge into the tailgate (length, drop, power, heights it fades in over)
ROLL_F, ROLL_R = (0.26, 0.085, 2.3, 0.78, 1.02), (0.07, 0.03, 2.2, 1.45, 1.62)
TUCK_F, TUCK_R = (0.30, 0.07, 2.2), (0.32, 0.08, 2.2)   # bumper undersides rise toward the ends
# nose and tail faces: how far each height sits back from the bumper plane
lean_f = pchip([(0.2, 0.0), (0.5, 0.0), (0.85, 0.02), (1.0, 0.05), (2.0, 0.05)])
lean_r = pchip([(0.3, 0.0), (0.56, 0.0), (0.80, 0.012), (1.10, 0.065), (1.20, 0.095), (1.60, 0.24), (1.66, 0.25), (2.0, 0.25)])
BOW_F, BOW_R = 0.07, 0.03         # the faces bow in plan: centre ahead of the corners


def roll(u, n):
    u = min(max(u, 0.0), 1.0)
    return 1 - (1 - u ** n) ** (1 / n)


def half_w(x):
    w = plan(x)
    for end, sgn, (a, b, n) in ((XF, 1, CORNER_F), (XR, -1, CORNER_R)):
        u = (sgn * (x - end) + a) / a
        if u > 0:
            w -= b * (1 - (1 - min(u, 1.0) ** n) ** (1 / n))
    return w


def sup(dx, dz, e):  # superellipse metric: 1 on the curve
    return (abs(dx / e["a"]) ** e["n"] + abs(dz / e["b"]) ** e["n"]) ** (1 / e["n"])


def flare(x, z):  # fender plateau: the side swells out ~16 mm around each arch
    d = min(sup(x - ax, z - WHEEL_R, FLARE) for ax in (AXLE_F, AXLE_R))
    return 0.016 * (1 - smooth((d - 0.84) / 0.16)) * smooth((z - 0.20) / 0.08)


def rounded(V, R, NS, NA, B):
    """Polyline V with a fillet (radius R[i], NA[i] points) at each inner vertex and NS[i] points on each span;
    spans bow outward by B[i] with zero end slope, so everything stays tangent-continuous. Fixed point count."""
    V = [np.array(v, float) for v in V]
    n = len(V)
    arcs = [None] * n
    for i in range(1, n - 1):
        d1, d2 = V[i - 1] - V[i], V[i + 1] - V[i]
        l1, l2 = np.linalg.norm(d1), np.linalg.norm(d2)
        d1, d2 = d1 / l1, d2 / l2
        ang = math.acos(float(np.clip(d1 @ d2, -1, 1)))
        bis = d1 + d2
        if np.linalg.norm(bis) < 1e-6 or ang < 1e-3:
            arcs[i] = [V[i].copy() for _ in range(NA[i])]
            continue
        t = min(R[i] / math.tan(ang / 2), 0.45 * min(l1, l2))
        r = t * math.tan(ang / 2)
        cen = V[i] + bis / np.linalg.norm(bis) * (r / math.sin(ang / 2))
        u0, u1 = (V[i] + d1 * t - cen) / r, (V[i] + d2 * t - cen) / r
        phi = math.acos(float(np.clip(u0 @ u1, -1, 1)))
        arcs[i] = [cen + r * ((math.sin((1 - s) * phi) * u0 + math.sin(s * phi) * u1) / math.sin(phi) if phi > 1e-5 else u0)
                   for s in np.linspace(0, 1, NA[i])]
    out = [V[0]]
    for i in range(n - 1):
        a = V[i] if i == 0 else arcs[i][-1]
        b = V[i + 1] if i == n - 2 else arcs[i + 1][0]
        d = b - a
        ln = np.linalg.norm(d)
        nrm = np.array([d[1], -d[0]]) / ln if ln > 1e-9 else np.zeros(2)
        for k in range(NS[i]):
            t = (k + 1) / (NS[i] + 1)
            out.append(a + d * t + nrm * B[i] * (4 * t * (1 - t)) ** 1.5)
        if i < n - 2:
            out += arcs[i + 1]
    out.append(V[-1])
    return out


def arch(ax, wb, wt, zt, r, z0):
    """Wheel-arch outline as (x, z), front foot -> rear foot: flat top, slanted sides, rounded corners. z0 = foot height."""
    xf = wb + (wb - wt) * (0.19 - z0) / (zt - 0.19)
    V = [(ax + xf, z0), (ax + wt, zt), (ax - wt, zt), (ax - xf, z0)]
    return [(float(q[0]), float(q[1])) for q in rounded(V, [0, r, r, 0], [10, 12, 10], [None, 20, 20, None], [0, 0, 0])]


NS, NA = [4, 16, 1, 9, 10], [None, 7, 6, 4, 12, None]
FLOOR_SEGS = NS[0] + 2                           # ring quads that are underbody (matte black)
SIDE = (1 + NS[0], 1 + NS[0] + NA[1] + NS[1] + NA[2])   # ring indices of the body side (sill fillet .. shoulder fillet)


def section(x):
    """Half cross-section at station x, floor centre -> roof centre, as (y, z)."""
    w, zb, zs, zt = half_w(x), z_bot(x), z_sh(x), z_top(x)
    zbelt = zs + BELT
    wr = max(w - inset(x), 0.08)
    zc = max(zt - crown(x), zbelt + 0.006)
    V = [(0, zb), (w - 0.040, zb), (w - 0.012, zs), (w - 0.052, zbelt), (wr, zc), (0, max(zt, zc + 0.004))]
    pts = rounded(V, [0, 0.075, 0.022, 0.014, roof_r(x), 0], NS, NA, [0, 0.026, 0.0, 0.012, roof_bulge(x)])
    return [(float(p[0]) + (flare(x, float(p[1])) if SIDE[0] <= i < SIDE[1] else 0.0), float(p[1])) for i, p in enumerate(pts)]


def final(x, y, z):
    """Station coordinates -> car coordinates: roll the top and bottom edges in at both ends, lean the faces back
    with height, bow them in plan."""
    cf, cr = smooth((x - (XF - 0.55)) / 0.55), smooth(((XR + 0.60) - x) / 0.60)
    zb = z_bot(x)
    for end, sgn, (a, b, n, z0, z1), (ta, tb, tn) in ((XF, 1, ROLL_F, TUCK_F), (XR, -1, ROLL_R, TUCK_R)):
        d = sgn * (x - end)
        z = z - b * roll((d + a) / a, n) * smooth((z - z0) / (z1 - z0)) + tb * roll((d + ta) / ta, tn) * (1 - smooth((z - zb) / 0.30))
    yy = (y / 0.99) ** 2
    return (x - (lean_f(z) + BOW_F * yy) * cf + (lean_r(z) + BOW_R * yy) * cr, y, z)


def stations():
    xs = set(np.round(np.arange(XR + CORNER_R[0], XF - CORNER_F[0] + 1e-6, 0.024), 4))
    for end, sgn, (a, _, _) in ((XF, 1, CORNER_F), (XR, -1, CORNER_R)):
        xs |= {round(end - sgn * a * (1 - math.sin(t)), 5) for t in np.linspace(0, math.pi / 2, 22)}
    return sorted(xs, reverse=True)


def build_body():
    xs = stations()
    secs = [section(x) for x in xs]
    K = len(secs[0])
    ring = list(range(K)) + list(range(K - 2, 0, -1))
    R = len(ring)
    verts, faces, fm = [], [], []
    for x, s in zip(xs, secs):
        for k, j in enumerate(ring):
            verts.append(final(x, s[j][0] if k < K else -s[j][0], s[j][1]))
    paint, under = MAT_NAMES.index("body_paint"), MAT_NAMES.index("cladding")
    for si in range(len(xs) - 1):
        for k in range(R):
            k2 = (k + 1) % R
            faces.append((si * R + k, si * R + k2, (si + 1) * R + k2, (si + 1) * R + k))
            seg = k if k < K - 1 else (2 * K - 3 - k)
            fm.append(under if seg < FLOOR_SEGS else paint)
    C, j0 = 2 * (NS[0] + 1), 1 + NS[0]
    for si in (0, len(xs) - 1):  # end faces: a grid with one row per ring height, so the lean and bow shape them smoothly
        x, s, base = xs[si], secs[si], si * R
        row = [base + (0 if c == C // 2 else c - C // 2 if c > C // 2 else R - (C // 2 - c)) for c in range(C + 1)]  # floor edge
        for j in range(j0 + 1, K - 1):
            cur = [base + R - j] + list(range(len(verts), len(verts) + C - 1)) + [base + j]
            verts += [final(x, s[j][0] * (2 * c / C - 1), s[j][1]) for c in range(1, C)]
            faces += [(row[c], row[c + 1], cur[c + 1], cur[c]) for c in range(C)]; fm += [paint] * C
            row = cur
        faces += [(row[c], row[c + 1], base + K - 1) for c in range(C)]; fm += [paint] * C
    o = mesh_obj("body", verts, faces, MAT_NAMES, fm, smooth=0)
    # wheel arches: cut real wells, the cutter's faces become the black liner
    cv, cfaces = [], []
    for ax in (AXLE_F, AXLE_R):
        prof = arch(ax, *ARCH, -0.2)
        for s in (-1, 1):
            b, n = len(cv), len(prof)
            cv += [(px, s * 0.56, pz) for px, pz in prof] + [(px, s * 1.3, pz) for px, pz in prof]
            cfaces += [tuple(range(b, b + n)), tuple(range(b + n, b + 2 * n))]
            cfaces += [(b + i, b + (i + 1) % n, b + n + (i + 1) % n, b + n + i) for i in range(n)]
    cut = mesh_obj("cutter", cv, cfaces, ["cladding"], smooth=0)
    md = o.modifiers.new("arch", "BOOLEAN")
    md.object, md.operation, md.solver = cut, "DIFFERENCE", "EXACT"
    md.material_mode = "TRANSFER"
    bpy.context.view_layer.objects.active = o
    bpy.ops.object.modifier_apply(modifier=md.name)
    bpy.data.objects.remove(cut)
    shade(o, 62)
    return o


# ---------- trim: patches projected onto the body ----------
body = build_body()
BVH = BVHTree.FromPolygons([v.co.copy() for v in body.data.vertices], [tuple(p.vertices) for p in body.data.polygons])
CFX, CRX, FACE_R = 1.55, -1.55, 0.98   # vertical axes the nose and tail patches wrap around


def cast(o, d, t0):
    o, d = Vector(o), Vector(d)
    loc, n, _, _ = BVH.ray_cast(o, d)
    if loc is None:
        loc, n, _, _ = BVH.find_nearest(o + d * t0)
    return loc, n


def P_side(s):   # (x, z) on the car's side s (+1 left, -1 right)
    return lambda x, z: cast((x, s * 3, z), (0, -s, 0), 2.05)


def P_top(x, y):
    return cast((x, y, 3), (0, 0, -1), 1.5)


def P_front(th, z):  # th: angle around the nose axis, 0 = straight ahead
    c, s = math.cos(th), math.sin(th)
    return cast((CFX + 6 * c, 6 * s, z), (-c, -s, 0), 5.0)


def P_rear(th, z):
    c, s = math.cos(th), math.sin(th)
    return cast((CRX - 6 * c, 6 * s, z), (c, -s, 0), 5.0)


def face(proj, axis):   # (y, z) on the nose or tail: finds the wrap angle that lands at that y (the faces lean and bow)
    def f(y, z):
        t = math.atan2(y, FACE_R)
        for _ in range(3):
            t = math.atan2(y, max(abs(proj(t, z)[0].x - axis), 0.2))
        return proj(t, z)
    return f


PF, PR = face(P_front, CFX), face(P_rear, CRX)


def th(y):       # face position y -> wrap angle
    return math.atan2(y, FACE_R)


def th_side(x, axis):  # a point on the body side at station x -> wrap angle
    return math.atan2(0.985, abs(x - axis))


class Trim:
    def __init__(self):
        self.v, self.f, self.m = [], [], []

    def ruled(self, mat, A, B, proj, rows=2, off=0.005):
        """Surface between polylines A and B (same count, in the projector's 2D space), laid on the body."""
        base, nj = len(self.v), rows + 1
        nr = []
        for a, b in zip(A, B):
            for j in range(nj):
                t = j / rows
                p, n = proj(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
                self.v.append(p + n * off); nr.append(n)
        self.quads(mat, base, len(A), nj, nr)

    def bridge(self, mat, A, B, rows=4, off=0.0045):
        """Surface between two 3D polylines lying on the body, snapped to it: for strips that turn a corner."""
        base, nj = len(self.v), rows + 1
        nr = []
        for a, b in zip(A, B):
            for j in range(nj):
                loc, n, _, _ = BVH.find_nearest(a.lerp(b, j / rows))
                self.v.append(loc + n * off); nr.append(n)
        self.quads(mat, base, len(A), nj, nr)

    def quads(self, mat, base, ni, nj, nr):
        mi = MAT_NAMES.index(mat)
        for i in range(ni - 1):
            for j in range(nj - 1):
                a, b = base + i * nj + j, base + (i + 1) * nj + j
                q = (a, b, b + 1, a + 1)
                fn = (self.v[b] - self.v[a]).cross(self.v[a + 1] - self.v[a])
                self.f.append(q if fn.dot(nr[a - base]) >= 0 else q[::-1]); self.m.append(mi)

    def rect(self, mat, proj, u0, u1, v0, v1, off=0.008, cols=1):
        us = np.linspace(u0, u1, cols + 1)
        self.ruled(mat, [(u, v0) for u in us], [(u, v1) for u in us], proj, 1, off)

    def obj(self, name):
        return mesh_obj(name, self.v, self.f, MAT_NAMES, self.m, recalc=False, smooth=40)


def lin(pts):
    xs, ys = zip(*sorted(pts))
    return lambda x: float(np.interp(x, xs, ys))


# side windows (daylight opening), traced from the side photo
dlo_top = lin([(-1.875, 1.405), (-1.80, 1.632), (-1.5, 1.660), (-1.18, 1.682), (-0.66, 1.696), (-0.3, 1.695), (-0.1, 1.687),
               (0.05, 1.668), (0.17, 1.634), (0.30, 1.585), (1.10, 1.20)])
dlo_bot = lin([(-1.875, 1.405), (-1.22, 1.268), (1.10, 1.20)])


def trim():
    T = Trim()
    for s in (1, -1):
        P = P_side(s)
        # --- greenhouse: gloss black opening, three panes of glass, satin belt moulding, brushed D-pillar garnish
        xs = np.linspace(-1.875, 1.10, 140)
        T.ruled("black_gloss", [(x, dlo_bot(x)) for x in xs], [(x, max(dlo_top(x), dlo_bot(x) + 0.003)) for x in xs], P, 8, 0.004)
        for x0, x1 in ((-0.055, 1.03), (-1.155, -0.145), (-1.83, -1.245)):
            xs = np.linspace(x0, x1, 48)
            T.ruled("glass", [(x, dlo_bot(x) + 0.024) for x in xs],
                    [(x, max(dlo_top(x) - 0.030, dlo_bot(x) + 0.027)) for x in xs], P, 6, 0.0065)
        xs = np.linspace(-1.875, 1.10, 70)
        T.ruled("chrome", [(x, dlo_bot(x) - 0.014) for x in xs], [(x, dlo_bot(x) + 0.004) for x in xs], P, 1, 0.007)
        gx = np.linspace(0, 1, 7)
        T.ruled("chrome", [(-1.878 - 0.262 * t, 1.405 + 0.005 * t) for t in gx], [(-1.803 - 0.222 * t, 1.642 - 0.008 * t) for t in gx], P, 5, 0.007)
        # --- black sill band between the arches, arch cladding
        xs = np.linspace(AXLE_R + 0.50, AXLE_F - 0.50, 40)
        T.ruled("cladding", [(x, 0.295) for x in xs], [(x, 0.405) for x in xs], P, 2, 0.007)
        for ax in (AXLE_F, AXLE_R):
            T.ruled("cladding", arch(ax, ARCH[0] + 0.004, ARCH[1] + 0.004, ARCH[2] + 0.004, ARCH[3], 0.21), arch(ax, *CLAD, 0.21), P, 3, 0.011)
        # --- shut lines (matte: a gloss line would mirror the softboxes): front door, B-pillar, rear door (follows the arch), tailgate
        def seam(pts, w=0.007):
            for (x0, z0), (x1, z1) in zip(pts, pts[1:]):
                d = Vector((x1 - x0, z1 - z0)).normalized() * (w / 2)
                n = 12
                T.ruled("cladding", [(x0 + (x1 - x0) * t - d.y, z0 + (z1 - z0) * t + d.x) for t in np.linspace(0, 1, n)],
                        [(x0 + (x1 - x0) * t + d.y, z0 + (z1 - z0) * t - d.x) for t in np.linspace(0, 1, n)], P, 1, 0.003)
        seam([(1.105, 1.19), (1.105, 0.62), (1.08, 0.41)])
        seam([(-0.10, 1.225), (-0.10, 0.41)])
        seam([(-1.215, 1.262), (-1.215, 0.98), (-1.12, 0.80), (-0.975, 0.62), (-0.93, 0.41)])
        seam([(-2.33, 1.20), (-2.36, 0.72)], 0.006)
        # --- flush door handles: a body-colour blade in a dark outline, just under the shoulder
        for hx in (0.18, -0.94):
            hz = z_sh(hx) - 0.105
            T.rect("cladding", P, hx - 0.118, hx + 0.118, hz - 0.024, hz + 0.024, 0.003, 6)
            T.rect("body_paint", P, hx - 0.110, hx + 0.110, hz - 0.017, hz + 0.017, 0.006, 6)
    # --- top: windshield, black A-pillars, glass roof
    ys = np.linspace(-1, 1, 33)
    cowl = [(1.305 - 0.20 * y * y, 0.80 * y) for y in ys]
    head = [(0.175 - 0.05 * y * y, 0.705 * y) for y in ys]
    T.ruled("glass", cowl, head, P_top, 26, 0.0045)
    roof_edge = lin([(-1.78, 0.50), (0.125, 0.705)])   # glass roof side edge |y|: one sheet of glass from the windshield back
    for s in (1, -1):
        xs = np.linspace(0.125, 1.105, 48)   # A-pillar: from the windshield edge round the corner to the side glass
        edge = lin([(0.125, 0.705), (1.105, 0.80)])
        T.bridge("black_gloss", [P_top(x, s * (edge(x) - 0.006))[0] for x in xs], [P_side(s)(x, dlo_top(x) - 0.006)[0] for x in xs], 8)
        # ...and on into the roof, thinning to nothing: the body-colour roof rail grows out of it, no step at the header
        A, B = [], []
        for x in np.linspace(-0.50, 0.125, 30):
            o = P_side(s)(x, dlo_top(x) - 0.006)[0]
            A.append(P_top(x, s * (roof_edge(x) - 0.006))[0].lerp(o, ((0.125 - x) / 0.625) ** 0.8)); B.append(o)
        T.bridge("black_gloss", A, B, 8)
    T.ruled("glass", [(x - 0.004, y) for x, y in head], [(-1.78, 0.50 * y) for y in ys], P_top, 26, 0.0045)
    for s in (1, -1):  # hood shut lines along the fender tops
        xs = np.linspace(1.29, 2.24, 70)
        T.ruled("cladding", [(x, s * (half_w(x) - inset(x) + 0.004)) for x in xs], [(x, s * (half_w(x) - inset(x) + 0.010)) for x in xs], P_top, 1, 0.004)
    # --- nose: pixel light bar right under the hood edge (two pixels tall at the corners), gloss black fascia holding the
    #     upright pixel headlamps, body-colour bumper with a satin skid garnish
    te = th_side(2.08, CFX)
    ts = np.linspace(-te, te, 96)
    drl = th(0.52)
    T.ruled("black_gloss", [(t, 0.912 - 0.026 * smooth((abs(t) - drl) / 0.03)) for t in ts], [(t, 0.974) for t in ts], P_front, 2, 0.004)
    step = th(0.036)
    for i in range(-int(te / step) + 1, int(te / step)):
        t = i * step
        T.rect("lamp_front", P_front, t - step * 0.36, t + step * 0.36, 0.934, 0.958, 0.007)
        if abs(t) > drl + 0.02:
            T.rect("lamp_front", P_front, t - step * 0.36, t + step * 0.36, 0.900, 0.924, 0.007)
    te = th_side(2.16, CFX)
    ts = np.linspace(-te, te, 100)
    T.ruled("black_gloss", [(t, 0.50 + 0.05 * smooth((abs(t) - 0.62) / 0.3)) for t in ts], [(t, 0.82) for t in ts], P_front, 7, 0.004)
    for s in (1, -1):
        T.rect("lamp_lens", PF, s * 0.695, s * 0.885, 0.525, 0.79, 0.006, 4)
        for c in range(3):
            for r in range(6):
                y, z = s * (0.735 + c * 0.055), 0.552 + r * 0.041
                T.rect("lamp_pixel", PF, y - 0.020, y + 0.020, z - 0.0155, z + 0.0155, 0.0085)
    T.rect("plate", PF, -0.26, 0.26, 0.52, 0.63, 0.007, 6)
    ts = np.linspace(-0.56, 0.56, 30)       # bumper: black intake slot over a broad satin skid garnish whose ends sweep up
    T.ruled("cladding", [(t, 0.392) for t in ts], [(t, 0.432) for t in ts], P_front, 1, 0.005)
    ts = np.linspace(-0.70, 0.70, 48)
    up = lambda t: 0.16 * smooth((abs(t) - 0.50) / 0.20)
    T.ruled("chrome", [(t, 0.308 + up(t)) for t in ts], [(t, 0.388 + up(t) * 0.75) for t in ts], P_front, 2, 0.006)
    # --- tail: big glass, slim pixel lamp columns standing on the body corners and joined by a thin line over the glass,
    #     plate recess, black bumper with a satin skid garnish under it
    ys = np.linspace(-1, 1, 29)
    T.ruled("glass", [(0.825 * y, 1.10) for y in ys], [(0.765 * y, 1.54) for y in ys], PR, 14, 0.0045)
    col = lin([(0.60, 0.885), (1.62, 0.80)])     # lamp column centre |y| by height: follows the body corner
    for s in (1, -1):
        zs = np.linspace(0.60, 1.564, 32)
        T.ruled("black_gloss", [(s * (col(z) - 0.027), z) for z in zs], [(s * (col(z) + 0.027), z) for z in zs], PR, 2, 0.0055)
        for z in np.arange(0.62, 1.552, 0.04):
            y = s * col(z)
            T.rect("lamp_rear", PR, y - 0.016, y + 0.016, z - 0.015, z + 0.015, 0.008)
    ys = np.linspace(-0.775, 0.775, 30)
    T.ruled("black_gloss", [(y, 1.547) for y in ys], [(y, 1.574) for y in ys], PR, 1, 0.0055)
    for y in np.arange(-0.76, 0.761, 0.04):
        T.rect("lamp_rear", PR, y - 0.014, y + 0.014, 1.554, 1.568, 0.008)
    T.rect("black_gloss", PR, -0.30, 0.30, 0.79, 0.955, 0.004, 6)
    T.rect("plate", PR, -0.26, 0.26, 0.817, 0.927, 0.007, 6)
    te = th_side(-2.20, CRX)
    ts = np.linspace(-te, te, 90)
    T.ruled("cladding", [(t, 0.39) for t in ts], [(t, 0.66) for t in ts], P_rear, 4, 0.005)
    T.ruled("chrome", [(0.70 * y, 0.402) for y in np.linspace(-1, 1, 29)], [(0.80 * y, 0.508) for y in np.linspace(-1, 1, 29)], PR, 3, 0.008)   # wide skid garnish, wider at the top
    for s in (1, -1):
        for i in range(7):
            y = s * (0.40 + i * 0.045)
            T.rect("reflector", PR, y - 0.018, y + 0.018, 0.565, 0.59, 0.008)
    return T.obj("trim")


def extras():
    """Parts that stand off the body: mirrors, roof rails, shark fin."""
    parts = []
    for s in (1, -1):
        # mirror: squashed ellipsoid on a short black arm at the front of the door
        bm = bmesh.new()
        bmesh.ops.create_uvsphere(bm, u_segments=20, v_segments=10, radius=1.0)
        for v in bm.verts:
            x, y, z = v.co
            v.co = (0.975 + 0.115 * x * (1.0 if x > 0 else 0.8), s * (1.09 + 0.095 * y), 1.238 + 0.070 * z * (1.0 if z > 0 else 0.8))
        me = bpy.data.meshes.new("mirror"); bm.to_mesh(me); bm.free()
        me.materials.append(M["body_paint"]); me.materials.append(M["cladding"])   # body-colour cap on a black base
        for pl in me.polygons:
            pl.material_index = 1 if pl.center.z < 1.222 else 0
        o = link(bpy.data.objects.new("mirror", me)); shade(o, 60); parts.append(o)
        p0, _ = P_side(s)(1.02, 1.185)
        a = [(0.94, s * 1.04, 1.175), (1.06, s * 1.04, 1.175), (1.06, s * 1.04, 1.225), (0.94, s * 1.04, 1.225)]
        b = [(0.96, p0.y - s * 0.02, 1.16), (1.09, p0.y - s * 0.02, 1.16), (1.09, p0.y - s * 0.02, 1.215), (0.96, p0.y - s * 0.02, 1.215)]
        parts.append(mesh_obj("arm", a + b, [(0, 1, 2, 3), (4, 5, 6, 7)] + [(i, (i + 1) % 4, 4 + (i + 1) % 4, 4 + i) for i in range(4)], ["cladding"], smooth=0))
        # roof rail: low satin bar on the roof edge, ends fairing into the roof
        v, f = [], []
        xs = np.linspace(-2.02, -0.28, 30)
        for i, x in enumerate(xs):
            y = s * (half_w(x) - inset(x) - 0.012)
            h = 0.054 * smooth(min(i, len(xs) - 1 - i) / 2.5) + 0.002
            for dy in (-0.017, 0.017):
                p, _ = P_top(x, y + dy)
                v += [(p.x, p.y, p.z - 0.004), (p.x, p.y, p.z + h)]
            if i:
                k = 4 * i
                f += [(k - 4 + a, k - 4 + b, k + b, k + a) for a, b in ((0, 1), (1, 3), (3, 2), (2, 0))]
        f += [(0, 1, 3, 2), tuple(len(v) - 4 + i for i in (0, 1, 3, 2))]
        parts.append(mesh_obj("rail", v, f, ["chrome"], smooth=50))
    p, _ = P_top(-1.86, 0)
    fin = [(-1.78, 0.022, p.z - 0.01), (-1.78, -0.022, p.z - 0.01), (-1.97, -0.026, p.z - 0.012), (-1.97, 0.026, p.z - 0.012),
           (-1.90, 0.006, p.z + 0.052), (-1.90, -0.006, p.z + 0.052), (-1.96, -0.008, p.z + 0.055), (-1.96, 0.008, p.z + 0.055)]
    parts.append(mesh_obj("fin", fin, [(0, 1, 2, 3), (4, 5, 6, 7)] + [(i, (i + 1) % 4, 4 + (i + 1) % 4, 4 + i) for i in range(4)], ["black_gloss"], smooth=0))
    return join(parts, "extras")


# ---------- wheels (built facing +Y at the origin) ----------
def lathe(name, prof, m, seg=64):
    """Closed (radius, y) profile spun around the axle."""
    n = len(prof)
    v = [(r * math.cos(a), y, r * math.sin(a)) for a in np.linspace(0, 2 * math.pi, seg, endpoint=False) for r, y in prof]
    f = [(i * n + j, i * n + (j + 1) % n, ((i + 1) % seg) * n + (j + 1) % n, ((i + 1) % seg) * n + j) for i in range(seg) for j in range(n)]
    return mesh_obj(name, v, f, [m], smooth=35)


def spoke(name, a0, m, r0=0.05, r1=0.277, y0=0.078, y1=0.106, w0=0.02, w1=0.026, thick=0.03, sweep=0.0, n=5):
    """Bar from hub to rim that dishes inward toward the hub (concave face) and can sweep around the axle."""
    v = []
    for t in np.linspace(0, 1, n):
        r, a = r0 + (r1 - r0) * t, a0 + sweep * t
        y, w = y0 + (y1 - y0) * t ** 1.7, (w0 + (w1 - w0) * t) / 2
        c, s = math.cos(a), math.sin(a)
        for dw, dy in ((-w, 0), (w, 0), (w, -thick), (-w, -thick)):
            v.append((r * c - dw * s, y + dy, r * s + dw * c))
    f = [(4 * i + k, 4 * i + (k + 1) % 4, 4 * i + 4 + (k + 1) % 4, 4 * i + 4 + k) for i in range(n - 1) for k in range(4)]
    f += [(0, 1, 2, 3), tuple(4 * (n - 1) + k for k in range(4))]
    return mesh_obj(name, v, f, [m], smooth=30)


def blade(name, a, m, y):  # pinwheel petal
    pts = [(0.05, 0)] + [(RIM_R * 0.95, t) for t in np.linspace(0, 1.25, 9)] + [(0.10, 1.4)]
    v = [(r * math.cos(a + t), y, r * math.sin(a + t)) for r, t in pts]
    v += [(x, y - 0.03, z) for x, _, z in v]
    k = len(pts)
    f = [tuple(range(k)), tuple(range(k, 2 * k))] + [(i, (i + 1) % k, k + (i + 1) % k, k + i) for i in range(k)]
    return mesh_obj(name, v, f, [m], smooth=30)


def rim(design):
    face = "rim_gold" if design == "GOLD" else "rim"
    parts = [lathe("barrel", [(0.296, 0.121), (0.296, 0.104), (0.284, 0.099), (0.284, -0.105), (0.266, -0.105), (0.266, 0.106), (0.276, 0.121)], face)]
    hub = [(0.004, 0.030), (0.072, 0.030), (0.072, 0.080), (0.058, 0.094), (0.030, 0.098), (0.004, 0.098)]
    two_pi = 2 * math.pi
    if design == "BASIC":   # the stock 21 inch look: many slim turbine spokes over a dark dish
        parts += [spoke(f"s{i}", i * two_pi / 20, "rim", w0=0.012, w1=0.021, thick=0.032, sweep=0.16) for i in range(20)]
        parts.append(lathe("dish", [(0.06, 0.040), (0.266, 0.060), (0.266, 0.050), (0.06, 0.030)], "rim_dark"))
        parts.append(lathe("hub", hub, "rim_dark", 24))
    elif design == "STAR":  # five twin spokes
        for i in range(5):
            a = math.pi / 2 + i * two_pi / 5
            parts += [spoke(f"s{i}{d}", a + d * 0.10, "rim", w0=0.030, w1=0.034, thick=0.034, sweep=d * 0.20) for d in (-1, 1)]
        parts.append(lathe("hub", hub, "rim", 24))
    elif design == "PINWHEEL":
        parts += [blade(f"b{i}", i * math.pi / 2, m, 0.104) for i, m in enumerate(["rim_pin_red", "rim_pin_yellow", "rim_pin_blue", "rim_pin_green"])]
        parts.append(lathe("hub", [(r, y + 0.012) for r, y in hub], "rim", 24))
    elif design == "GOLD":  # twelve spokes with a short one between each pair
        parts += [spoke(f"s{i}", i * two_pi / 12, "rim_gold", w0=0.020, w1=0.028) for i in range(12)]
        parts += [spoke(f"t{i}", (i + 0.5) * two_pi / 12, "rim_gold", r0=0.15, y0=0.094, w0=0.010, w1=0.016, thick=0.024) for i in range(12)]
        parts.append(lathe("hub", hub, "rim_gold", 24))
    return join(parts, f"rim_{design}")


def tire():
    half = [(0.290, 0.105), (0.308, 0.138), (0.338, 0.146), (0.369, 0.137), (0.387, 0.114), (0.395, 0.078)]
    parts = [lathe("tire", half + [(r, -y) for r, y in reversed(half)], "tire", 72),
             lathe("back", [(0.004, -0.02), (0.276, -0.02), (0.276, -0.04), (0.004, -0.04)], "rim_dark", 32),
             lathe("disc", [(0.06, 0.0), (0.205, 0.0), (0.205, 0.024), (0.06, 0.024)], "brake", 40)]
    return join(parts, "tire")


WHEEL_POS = {"FL": (AXLE_F, 1), "FR": (AXLE_F, -1), "RL": (AXLE_R, 1), "RR": (AXLE_R, -1)}
DESIGNS = ["BASIC", "STAR", "PINWHEEL", "GOLD"]


def place(o, key):
    x, s = WHEEL_POS[key]
    o.location = (x, s * WHEEL_Y, WHEEL_R)
    o.rotation_euler = (0, 0, 0 if s > 0 else math.pi)


def build_wheels():
    groups = {}
    t0 = tire()
    for k in WHEEL_POS:
        t = t0.copy(); t.data = t0.data; link(t); t.name = f"tire_{k}"; place(t, k)
    bpy.data.objects.remove(t0)
    for d in DESIGNS:
        base = rim(d)
        g = bpy.data.objects.new(f"wheels_{d}", None); link(g)
        for k in WHEEL_POS:
            r = base.copy(); r.data = base.data; link(r); r.name = f"rim_{d}_{k}"
            place(r, k); r.parent = g
        bpy.data.objects.remove(base)
        groups[d] = g
    return groups


# ---------- build ----------
det = trim()
ext = extras()
wheel_groups = build_wheels()
car_root = bpy.data.objects.new("ioniq9", None); link(car_root)
for o in [body, det, ext] + [o for o in scene.objects if o.name.startswith("tire_")] + list(wheel_groups.values()):
    o.parent = car_root

os.makedirs(OUT, exist_ok=True); os.makedirs(WEB, exist_ok=True)
bpy.context.view_layer.update()
tris = sum(len(p.vertices) - 2 for o in scene.objects if o.type == "MESH" for p in o.data.polygons)
bb = [o.matrix_world @ Vector(c) for o in scene.objects if o.type == "MESH" for c in o.bound_box]
print(f"IONIQ9 triangles={tris} size=" + " x ".join(f"{max(v[i] for v in bb) - min(v[i] for v in bb):.3f}" for i in range(3)))
if not PREVIEW:
    bpy.ops.object.select_all(action="DESELECT")
    for o in scene.objects:
        o.select_set(True)
    bpy.ops.export_scene.gltf(filepath=os.path.join(WEB, "ioniq9.glb"), export_format="GLB", use_selection=True,
                              export_apply=True, export_draco_mesh_compression_enable=False, export_yup=True,
                              export_texcoords=False)
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, "ioniq9.blend"))

# ---------- render ----------
STICKERS = {  # car's right side (-Y), the side the cameras see
    "FRONT_DOOR": (0.62, 0.74), "REAR_DOOR": (-0.62, 0.74), "TRUNK": (-2.12, 0.92),
}


def area(name, loc, size, energy, target=(0, 0, 0.8)):
    li = bpy.data.lights.new(name, "AREA")
    li.shape, li.size, li.size_y, li.energy = "RECTANGLE", size[0], size[1], energy
    o = link(bpy.data.objects.new(name, li))
    o.location = loc
    o.rotation_euler = (Vector(target) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
    return o


def setup_render():
    r = scene.render
    r.engine = "CYCLES"
    scene.cycles.samples = SAMPLES
    scene.cycles.use_denoising = True
    try:
        prefs = bpy.context.preferences.addons["cycles"].preferences
        prefs.compute_device_type = "METAL"; prefs.get_devices()
        for d in prefs.devices:
            d.use = True
        scene.cycles.device = "GPU"
    except Exception as e:
        print("GPU unavailable, CPU render:", e)
    r.film_transparent = True
    r.image_settings.file_format = "PNG"; r.image_settings.color_mode = "RGBA"
    scene.view_settings.view_transform = "Standard"
    world = bpy.data.worlds.new("w"); scene.world = world; world.use_nodes = True
    bg = next(n for n in world.node_tree.nodes if n.type == "BACKGROUND")
    bg.inputs[0].default_value = (0.86, 0.89, 0.95, 1); bg.inputs[1].default_value = 0.26
    try:  # studio cove: dark below the horizon, bright above, so the paint and glass have something to reflect
        nt = world.node_tree
        tc, sep, mr, ramp = (nt.nodes.new(t) for t in ("ShaderNodeTexCoord", "ShaderNodeSeparateXYZ", "ShaderNodeMapRange", "ShaderNodeValToRGB"))
        mr.inputs["From Min"].default_value = -1.0
        nt.links.new(tc.outputs["Generated"], sep.inputs[0]); nt.links.new(sep.outputs["Z"], mr.inputs["Value"])
        nt.links.new(mr.outputs["Result"], ramp.inputs["Fac"]); nt.links.new(ramp.outputs["Color"], bg.inputs["Color"])
        e = ramp.color_ramp.elements
        e[0].position, e[0].color = 0.49, (0.20, 0.21, 0.23, 1)
        e[1].position, e[1].color = 0.58, (0.92, 0.95, 1.0, 1)
    except Exception as ex:
        print("flat world:", ex)
    # a big overhead softbox, a long narrow strip that draws one highlight down the whole body side, soft fills
    area("key", (0.3, -0.5, 7.0), (11, 6), 640)
    area("side", (0.5, -10.0, 3.6), (12, 2.6), 110, (0, 0, 0.9))
    area("strip", (0.3, -9.0, 2.9), (14, 0.35), 140, (0.3, 0, 1.0))
    area("front", (9.5, -2.0, 3.0), (5, 3), 80, (0, 0, 0.9))
    area("rear", (-9.5, -2.0, 3.0), (5, 3), 70, (0, 0, 0.9))
    bpy.ops.mesh.primitive_plane_add(size=40, location=(0, 0, 0))
    g = bpy.context.active_object; g.name = "shadow"; g.is_shadow_catcher = True


VIEWS = {
    "side": dict(res=(1200, 480), ortho=5.5, loc=(0, -25, 0.92), rot=(math.pi / 2, 0, 0)),
    "three_quarter": dict(res=(1200, 800), lens=50, loc=(6.4, -7.4, 2.35), target=(0.15, 0, 0.78)),
    "rear_quarter": dict(res=(1200, 800), lens=50, loc=(-6.4, -7.4, 2.35), target=(-0.15, 0, 0.78)),  # the driving panel's parked view
}
PREVIEWS = {  # comparison angles for tools/ioniq9/out/progress (side, front 3/4, rear 3/4, front, rear)
    "side": VIEWS["side"],
    "f34": dict(res=(1000, 560), lens=62, loc=(7.6, -5.4, 1.35), target=(0.1, 0, 0.82)),
    "r34": dict(res=(1000, 560), lens=62, loc=(-8.3, -3.9, 1.45), target=(-0.2, 0, 0.85)),
    "front": dict(res=(700, 560), lens=70, loc=(11.0, 0, 1.0), target=(0, 0, 0.9)),
    "rear": dict(res=(700, 560), lens=70, loc=(-11.0, 0, 1.2), target=(0, 0, 0.9)),
}


def camera(v):
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam")); link(cam)
    cam.location = v["loc"]
    if "ortho" in v:
        cam.data.type, cam.data.ortho_scale = "ORTHO", v["ortho"]
        cam.rotation_euler = v["rot"]
    else:
        cam.data.lens = v["lens"]
        d = Vector(v["target"]) - Vector(v["loc"])
        cam.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    scene.camera = cam
    bpy.context.view_layer.update()
    scene.render.resolution_x, scene.render.resolution_y = v["res"]
    return cam


def show_wheels(design):
    for d, g in wheel_groups.items():
        for c in g.children:
            c.hide_render = d != design


def render(path):
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
    print("wrote", path)


if PREVIEW:
    setup_render()
    show_wheels("BASIC")
    os.makedirs(os.path.join(OUT, "progress"), exist_ok=True)
    for name, v in PREVIEWS.items():
        cam = camera(v)
        render(os.path.join(OUT, "progress", f"_{name}.png"))
        bpy.data.objects.remove(cam)
elif RENDER:
    setup_render()
    holdout = bpy.data.materials.new("holdout_tmp"); holdout.use_nodes = True
    nt = holdout.node_tree; nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    nt.links.new(nt.nodes.new("ShaderNodeHoldout").outputs[0], out.inputs[0])
    slots = {"views": {}, "world": {}}
    for k, (x, z) in STICKERS.items():
        p, _ = P_side(-1)(x, z)
        slots["world"][k] = {"pos": [x, round(p.y - 0.002, 4), z], "normal": [0, -1, 0]}
    for name, v in VIEWS.items():
        cam = camera(v)
        slots["views"][name] = {k: [round(c, 4) for c in (lambda p: (p.x, 1 - p.y))(
            world_to_camera_view(scene, cam, Vector(slots["world"][k]["pos"])))] for k in STICKERS}
        for d in (DESIGNS[:1] if "--quick" in argv else DESIGNS):
            show_wheels(d)
            render(os.path.join(OUT, f"ioniq9_{name}_base_{d.lower()}.png"))
        # paint mask: everything except body_paint becomes holdout
        show_wheels("BASIC")
        scene.objects["shadow"].hide_render = True
        saved = []
        for o in scene.objects:
            if o.type == "MESH" and o.name != "shadow":
                for s in o.material_slots:
                    if s.material and s.material.name != "body_paint":
                        saved.append((s, s.material)); s.material = holdout
        render(os.path.join(OUT, f"ioniq9_{name}_paint_mask_raw.png"))
        for s, m in reversed(saved):  # rims/tires share mesh data: undo in reverse
            s.material = m
        scene.objects["shadow"].hide_render = False
        bpy.data.objects.remove(cam)
    with open(os.path.join(OUT, "sticker_slots.json"), "w") as f:
        json.dump(slots, f, indent=2)
