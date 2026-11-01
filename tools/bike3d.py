"""Použití: uv run --with numpy --with pillow python tools/bike3d.py <typ> <výstupní složka> [náhled.png]

3D ikona polohy pro nakloněnou mapu: jednoduchý model motocyklu (metry; x vpravo, y dopředu,
z nahoru) vykreslený vlastním malým rasterizérem z FRAMES směrů dokola. Kamera je skloněná
stejně jako 3D mapa v aplikaci (MapController.TILT_3D = 55° od svislice → 35° nad obzorem).

Výstup jsou dva pruhy snímků vedle sebe (snímek i = motorka natočená o i·360/FRAMES° po
směru hodinových ručiček vůči obrazovce, 0 = jede „do obrazovky“):
  bike3d_<typ>_body.png  – lak ve stupních šedi (aplikace ho vynásobí zvolenou barvou)
  bike3d_<typ>_fixed.png – vše ostatní ve skutečných barvách, stín a odlesky laku
"""
import math, sys
import numpy as np
from PIL import Image, ImageFilter

FRAMES = 24
OUT = 192          # velikost snímku v px
SS = 3             # vyhlazování: kreslíme 3× větší a zmenšíme
PX_PER_M = 62      # měřítko při OUT
ELEV = math.radians(35)
TARGET_Z = 0.62    # bod modelu ve středu snímku
GROUND_Y = 0.5 + TARGET_Z * math.cos(ELEV) * PX_PER_M / OUT  # kde leží bod (0,0,0) – kotva ikony

BODY, TYRE, DARK, METAL, GLASS, SEAT, LIGHT, RED = range(8)
BASE = {
    BODY: (1.0, 1.0, 1.0), TYRE: (0.09, 0.09, 0.10), DARK: (0.16, 0.16, 0.17), METAL: (0.62, 0.64, 0.67),
    GLASS: (0.20, 0.27, 0.34), SEAT: (0.11, 0.11, 0.11), LIGHT: (1.0, 0.97, 0.86), RED: (0.85, 0.08, 0.08),
}
SPEC = {BODY: 0.55, METAL: 0.6, GLASS: 0.8}
EMISSIVE = {LIGHT, RED}


# ------------------------------------------------------------------ primitiva (trojúhelníky s normálami ve vrcholech)
def ellipsoid(c, r, mat, nu=28, nv=14):
    u = np.linspace(0, 2 * np.pi, nu + 1)
    v = np.linspace(0, np.pi, nv + 1)
    uu, vv = np.meshgrid(u, v, indexing='ij')
    n = np.stack([np.cos(uu) * np.sin(vv), np.sin(uu) * np.sin(vv), np.cos(vv)], -1)
    p = n * np.array(r) + np.array(c)
    nn = n / np.array(r)
    nn /= np.linalg.norm(nn, axis=-1, keepdims=True) + 1e-9
    return grid(p, nn, mat)


def cylinder(a, b, rad, mat, n=22, caps=True):
    a, b = np.array(a, float), np.array(b, float)
    ax = b - a
    ax /= np.linalg.norm(ax)
    t = np.array([1.0, 0, 0]) if abs(ax[0]) < 0.9 else np.array([0, 1.0, 0])
    e1 = np.cross(ax, t); e1 /= np.linalg.norm(e1)
    e2 = np.cross(ax, e1)
    ang = np.linspace(0, 2 * np.pi, n + 1)
    ring = np.cos(ang)[:, None] * e1 + np.sin(ang)[:, None] * e2
    p = np.stack([a + rad * ring, b + rad * ring], 1)
    nn = np.stack([ring, ring], 1)
    tris = grid(p, nn, mat)
    if caps:
        for c, s in ((a, -1), (b, 1)):
            for i in range(n):
                tris.append(([c, c + rad * ring[i], c + rad * ring[i + 1]], [ax * s] * 3, mat))
    return tris


def box(c, size, mat):
    c, h = np.array(c, float), np.array(size, float) / 2
    tris = []
    for axis in range(3):
        for s in (-1, 1):
            nrm = np.zeros(3); nrm[axis] = s
            o = [i for i in range(3) if i != axis]
            corners = []
            for du, dv in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
                p = c.copy(); p[axis] += s * h[axis]; p[o[0]] += du * h[o[0]]; p[o[1]] += dv * h[o[1]]
                corners.append(p)
            tris.append(([corners[0], corners[1], corners[2]], [nrm] * 3, mat))
            tris.append(([corners[0], corners[2], corners[3]], [nrm] * 3, mat))
    return tris


def quad(p0, p1, p2, p3, mat):
    nrm = np.cross(np.subtract(p1, p0), np.subtract(p3, p0)); nrm = nrm / np.linalg.norm(nrm)
    return [([p0, p1, p2], [nrm] * 3, mat), ([p0, p2, p3], [nrm] * 3, mat)]


def grid(p, nn, mat):
    tris = []
    for i in range(p.shape[0] - 1):
        for j in range(p.shape[1] - 1):
            tris.append(([p[i, j], p[i + 1, j], p[i + 1, j + 1]], [nn[i, j], nn[i + 1, j], nn[i + 1, j + 1]], mat))
            tris.append(([p[i, j], p[i + 1, j + 1], p[i, j + 1]], [nn[i, j], nn[i + 1, j + 1], nn[i, j + 1]], mat))
    return tris


def smooth_normals(p):
    """Normály ve vrcholech mřížky z okolních ploch (pro loft a zaoblené kvádry)."""
    du = np.roll(p, -1, 0) - np.roll(p, 1, 0)
    dv = np.zeros_like(p)
    dv[:, 1:-1] = p[:, 2:] - p[:, :-2]
    dv[:, 0] = p[:, 1] - p[:, 0]
    dv[:, -1] = p[:, -1] - p[:, -2]
    n = np.cross(du, dv)
    return n / (np.linalg.norm(n, axis=-1, keepdims=True) + 1e-12)


def squircle(n_pts, exp):
    """Obrys zaobleného obdélníku (superelipsa) – jednotkový, proti směru hodin."""
    a = np.linspace(0, 2 * np.pi, n_pts + 1)
    c, s = np.cos(a), np.sin(a)
    return np.sign(c) * np.abs(c) ** (2 / exp), np.sign(s) * np.abs(s) ** (2 / exp)


def loft(stations, mat, exp=2.6, n=32):
    """Hladký tvar protažený řezy (y, střed z, polovina šířky, polovina výšky), konce uzavřené."""
    ux, uz = squircle(n, exp)
    rings = [np.stack([hw * ux, np.full_like(ux, y), zc + hh * uz], -1) for y, zc, hw, hh in stations]
    p = np.stack(rings, 1)                                 # (obvod, řezy, 3)
    nn = smooth_normals(p)
    out = (p[:, :, 0] * nn[:, :, 0] + (p[:, :, 2] - p[:, :, 2].mean(0)) * nn[:, :, 2]).sum()
    if out < 0:
        nn = -nn
    tris = grid(p, nn, mat)
    for k, sgn in ((0, 1), (-1, -1)):                      # víčka: přední +y, zadní −y
        y, zc, _, _ = stations[k]
        c = np.array([0, y, zc]); cap_n = np.array([0, sgn * (1 if stations[0][0] > stations[-1][0] else -1), 0.0])
        for i in range(n):
            tris.append(([c, rings[k][i], rings[k][i + 1]], [cap_n] * 3, mat))
    return tris


def rounded_box(c, size, mat, exp=6.0, nu=28, nv=14):
    """Kvádr se zaoblenými hranami (superelipsoid)."""
    u = np.linspace(-np.pi, np.pi, nu + 1)
    v = np.linspace(-np.pi / 2, np.pi / 2, nv + 1)
    uu, vv = np.meshgrid(u, v, indexing='ij')
    f = lambda w, e: np.sign(w) * np.abs(w) ** (2 / e)
    x = f(np.cos(vv), exp) * f(np.cos(uu), exp)
    y = f(np.cos(vv), exp) * f(np.sin(uu), exp)
    z = f(np.sin(vv), exp)
    p = np.stack([x, y, z], -1) * (np.array(size) / 2) + np.array(c)
    nn = -smooth_normals(p)
    if ((p - np.array(c)) * nn).sum() < 0:
        nn = -nn
    return grid(p, nn, mat)


def curved_shield(y0, z0, y1, z1, w0, w1, bow, mat, n=12):
    """Štít: zakřivená plocha, dole širší, nahoře užší, uprostřed vypouklá dopředu."""
    rows = []
    for t in np.linspace(0, 1, 6):
        w = w0 + (w1 - w0) * t
        xs = np.linspace(-w, w, n + 1)
        ys = y0 + (y1 - y0) * t + bow * (1 - (xs / w) ** 2)
        rows.append(np.stack([xs, ys, np.full_like(xs, z0 + (z1 - z0) * t)], -1))
    p = np.stack(rows, 1)
    return grid(p, smooth_normals(p), mat)


def wheel(y, r, half, mat_rim_x):
    t = cylinder((-half, y, r), (half, y, r), r, TYRE)
    for s in (-1, 1):
        t += cylinder((s * half, y, r), (s * (half + 0.006), y, r), r * 0.62, METAL, n=18)
    return t


# ------------------------------------------------------------------ modely
def touring():
    t = []
    t += wheel(-0.74, 0.32, 0.085, None)
    t += wheel(0.82, 0.31, 0.06, None)
    for s in (-1, 1):  # vidlice
        t += cylinder((s * 0.08, 0.82, 0.31), (s * 0.10, 0.62, 0.80), 0.025, METAL, n=10)
    t += box((0, -0.42, 0.36), (0.09, 0.64, 0.09), DARK)                     # kyvná vidlice
    t += rounded_box((0, 0.10, 0.42), (0.40, 0.52, 0.34), DARK, exp=4)       # motor
    for s in (-1, 1):                                                        # válce boxeru
        t += rounded_box((s * 0.34, 0.22, 0.44), (0.16, 0.20, 0.15), METAL, exp=3)
    t += cylinder((0.22, -0.20, 0.30), (0.26, -0.98, 0.44), 0.055, METAL)    # výfuk
    # Kapotáž s nádrží v jednom hladkém tvaru (řezy zepředu dozadu)
    t += loft([
        (1.00, 0.84, 0.06, 0.06),
        (0.93, 0.84, 0.24, 0.17),
        (0.80, 0.83, 0.38, 0.27),
        (0.62, 0.80, 0.44, 0.33),
        (0.42, 0.76, 0.41, 0.34),
        (0.24, 0.80, 0.31, 0.27),
        (0.06, 0.86, 0.25, 0.19),
        (-0.08, 0.88, 0.20, 0.12),
    ], BODY)
    t += loft([                                                              # sedlo pro dva
        (0.02, 0.97, 0.15, 0.05), (-0.16, 0.98, 0.20, 0.07), (-0.40, 1.00, 0.21, 0.07),
        (-0.60, 1.04, 0.18, 0.06), (-0.70, 1.06, 0.12, 0.04),
    ], SEAT)
    t += loft([                                                              # záď pod sedlem
        (-0.30, 0.88, 0.20, 0.10), (-0.55, 0.92, 0.21, 0.12), (-0.80, 0.96, 0.17, 0.10), (-0.92, 0.97, 0.10, 0.06),
    ], BODY)
    for s in (-1, 1):
        t += rounded_box((s * 0.33, -0.66, 0.62), (0.18, 0.58, 0.38), BODY)  # boční kufry
        t += cylinder((s * 0.38, 0.66, 1.00), (s * 0.46, 0.64, 1.02), 0.012, DARK, n=6)  # držák zrcátka
        t += rounded_box((s * 0.49, 0.63, 1.03), (0.11, 0.035, 0.07), DARK, exp=3)        # zrcátko
        t += cylinder((s * 0.10, 0.44, 1.02), (s * 0.40, 0.40, 1.03), 0.018, DARK, n=8)   # řídítka
    t += rounded_box((0, -1.00, 1.14), (0.46, 0.36, 0.30), BODY)             # topcase
    t += box((0, -1.185, 1.10), (0.28, 0.012, 0.05), RED)                    # zadní světlo
    t += rounded_box((0, 0.97, 0.86), (0.30, 0.05, 0.10), LIGHT, exp=3)      # přední světla
    t += curved_shield(0.80, 1.06, 0.62, 1.44, 0.27, 0.19, 0.05, GLASS)      # štít
    return t


def tube(points, rad, mat, n=8):
    """Trubka lomenou čarou (řídítka, rám)."""
    t = []
    for a, b in zip(points, points[1:]):
        t += cylinder(a, b, rad, mat, n=n)
    return t


def fender(hub_y, hub_z, radius, y_from, y_to, hw, mat=BODY, hh=0.022, steps=9):
    """Blatník: tenký pás kopírující kolo po oblouku."""
    st = []
    for y in np.linspace(y_from, y_to, steps):
        dz = math.sqrt(max(radius ** 2 - (y - hub_y) ** 2, 0.0004))
        st.append((y, hub_z + dz, hw, hh))
    return loft(st, mat, exp=4)


def mirror_on_stalk(s, base, top, size=(0.10, 0.03, 0.065), mat=DARK):
    x, y, z = top
    return cylinder((s * base[0], base[1], base[2]), (s * x, y, z - size[2] / 2), 0.011, DARK, n=6) + \
        rounded_box((s * x, y, z), size, mat, exp=3)


def sport():
    t = []
    t += wheel(-0.70, 0.31, 0.095, None)
    t += wheel(0.74, 0.30, 0.06, None)
    for s in (-1, 1):
        t += cylinder((s * 0.08, 0.74, 0.30), (s * 0.09, 0.53, 0.84), 0.025, METAL, n=10)
        t += cylinder((s * 0.10, 0.42, 0.93), (s * 0.30, 0.40, 0.91), 0.018, DARK, n=8)   # nízká řídítka
        t += rounded_box((s * 0.30, 0.64, 0.93), (0.09, 0.03, 0.05), DARK, exp=3)        # zrcátka na kapotáži
        t += rounded_box((s * 0.09, 0.95, 0.73), (0.09, 0.03, 0.04), LIGHT, exp=3)       # světla
    t += box((0, -0.38, 0.40), (0.14, 0.62, 0.08), METAL)                                 # kyvná vidlice
    t += rounded_box((0, 0.08, 0.42), (0.30, 0.44, 0.30), DARK, exp=4)                    # motor
    t += loft([                                                                           # kapotáž
        (1.00, 0.70, 0.04, 0.04), (0.91, 0.72, 0.17, 0.12), (0.76, 0.75, 0.27, 0.20),
        (0.56, 0.72, 0.30, 0.26), (0.36, 0.66, 0.29, 0.27), (0.18, 0.72, 0.22, 0.22),
        (0.02, 0.84, 0.19, 0.13), (-0.10, 0.88, 0.15, 0.07),
    ], BODY)
    t += loft([(0.42, 0.95, 0.12, 0.05), (0.24, 1.00, 0.21, 0.10), (0.04, 0.97, 0.18, 0.08), (-0.06, 0.94, 0.12, 0.05)], BODY)  # nádrž
    t += loft([(-0.04, 0.95, 0.12, 0.04), (-0.18, 0.97, 0.14, 0.05), (-0.32, 1.00, 0.12, 0.04)], SEAT)
    t += loft([(-0.22, 0.93, 0.14, 0.08), (-0.48, 1.00, 0.12, 0.07), (-0.70, 1.04, 0.08, 0.05), (-0.80, 1.05, 0.04, 0.03)], BODY)  # záď
    t += curved_shield(0.66, 0.94, 0.50, 1.08, 0.17, 0.11, 0.03, GLASS)
    t += cylinder((0.17, -0.20, 0.32), (0.20, -0.62, 0.58), 0.055, METAL)                 # výfuk
    t += box((0, -0.80, 1.03), (0.10, 0.012, 0.03), RED)
    return t


def naked():
    t = []
    t += wheel(-0.70, 0.31, 0.09, None)
    t += wheel(0.74, 0.30, 0.06, None)
    for s in (-1, 1):
        t += cylinder((s * 0.08, 0.74, 0.30), (s * 0.09, 0.53, 0.94), 0.026, METAL, n=10)
        t += tube([(0, 0.50, 0.96), (s * 0.14, 0.20, 0.70), (s * 0.12, -0.15, 0.45)], 0.022, METAL)  # rám
        t += rounded_box((s * 0.25, 0.38, 0.84), (0.06, 0.20, 0.15), BODY)                # boční kryty
        t += mirror_on_stalk(s, (0.33, 0.48, 1.10), (0.37, 0.50, 1.30))
    t += box((0, -0.38, 0.40), (0.12, 0.62, 0.08), METAL)
    t += rounded_box((0, 0.10, 0.42), (0.36, 0.48, 0.36), DARK, exp=4)                     # motor
    t += rounded_box((0, 0.22, 0.66), (0.34, 0.22, 0.16), METAL, exp=4)                    # hlava válců
    t += loft([(0.50, 0.98, 0.12, 0.07), (0.38, 1.00, 0.24, 0.14), (0.16, 0.98, 0.26, 0.15),
               (-0.02, 0.94, 0.20, 0.10), (-0.10, 0.92, 0.14, 0.06)], BODY)              # nádrž
    t += loft([(-0.06, 0.93, 0.14, 0.05), (-0.25, 0.95, 0.15, 0.05), (-0.42, 0.97, 0.13, 0.05)], SEAT)
    t += loft([(-0.36, 0.92, 0.12, 0.06), (-0.58, 1.00, 0.08, 0.05), (-0.70, 1.02, 0.03, 0.03)], BODY)  # záď
    t += ellipsoid((0, 0.68, 0.96), (0.10, 0.06, 0.10), DARK)                              # kulaté světlo
    t += ellipsoid((0, 0.735, 0.96), (0.078, 0.02, 0.078), LIGHT)
    t += tube([(-0.40, 0.47, 1.10), (-0.12, 0.49, 1.08), (0.12, 0.49, 1.08), (0.40, 0.47, 1.10)], 0.019, DARK)  # široká řídítka
    t += cylinder((0.12, 0.02, 0.20), (0.22, -0.38, 0.40), 0.06, METAL)                    # krátký výfuk
    t += box((0, -0.72, 1.01), (0.10, 0.012, 0.03), RED)
    return t


def adventure():
    t = []
    t += wheel(-0.76, 0.34, 0.08, None)
    t += wheel(0.88, 0.36, 0.05, None)
    for s in (-1, 1):
        t += cylinder((s * 0.08, 0.88, 0.36), (s * 0.10, 0.64, 1.00), 0.027, METAL, n=10)
        t += rounded_box((s * 0.36, -0.62, 0.74), (0.20, 0.52, 0.40), METAL, exp=10)      # hliníkové kufry
        t += rounded_box((s * 0.42, 0.52, 1.25), (0.06, 0.15, 0.08), BODY, exp=3)         # chrániče rukou
        t += mirror_on_stalk(s, (0.32, 0.50, 1.25), (0.36, 0.52, 1.42))
        t += rounded_box((s * 0.33, 0.22, 0.46), (0.16, 0.20, 0.15), METAL, exp=3)        # boxer
    t += box((0, -0.42, 0.42), (0.10, 0.66, 0.09), METAL)
    t += rounded_box((0, 0.10, 0.48), (0.34, 0.50, 0.34), DARK, exp=4)
    t += loft([(0.96, 0.88, 0.03, 0.02), (0.84, 0.92, 0.12, 0.05), (0.70, 0.98, 0.15, 0.06)], BODY)  # zobák
    t += loft([(0.80, 1.06, 0.10, 0.08), (0.66, 1.08, 0.30, 0.18), (0.45, 1.03, 0.36, 0.25),
               (0.22, 1.01, 0.30, 0.22), (0.02, 1.03, 0.22, 0.14), (-0.10, 1.03, 0.17, 0.08)], BODY)
    t += loft([(0.0, 1.10, 0.15, 0.05), (-0.25, 1.13, 0.17, 0.06), (-0.55, 1.16, 0.15, 0.05), (-0.66, 1.17, 0.10, 0.04)], SEAT)
    t += loft([(-0.40, 1.02, 0.17, 0.08), (-0.70, 1.06, 0.15, 0.08), (-0.86, 1.08, 0.08, 0.05)], BODY)
    t += box((0, -0.86, 1.15), (0.36, 0.30, 0.03), METAL)                                  # nosič
    t += curved_shield(0.74, 1.20, 0.62, 1.56, 0.20, 0.15, 0.04, GLASS)                    # vysoký štít
    t += tube([(-0.40, 0.48, 1.24), (0.40, 0.48, 1.24)], 0.019, DARK)
    t += rounded_box((0, 0.86, 0.99), (0.16, 0.03, 0.08), LIGHT, exp=3)
    t += cylinder((0.20, -0.10, 0.40), (0.26, -0.80, 0.78), 0.06, METAL)                   # výfuk vysoko
    t += box((0, -1.02, 1.08), (0.12, 0.012, 0.03), RED)
    return t


def cruiser():
    t = []
    t += wheel(-0.85, 0.32, 0.10, None)
    t += wheel(0.92, 0.33, 0.07, None)
    t += fender(0.92, 0.33, 0.37, 0.66, 1.18, 0.10)                                       # přední blatník
    t += fender(-0.85, 0.32, 0.37, -0.52, -1.20, 0.14)                                     # zadní blatník
    for s in (-1, 1):
        t += cylinder((s * 0.09, 0.92, 0.33), (s * 0.10, 0.62, 1.00), 0.03, METAL, n=10)  # vidlice s velkým sklonem
        t += tube([(s * 0.08, 0.62, 1.05), (s * 0.28, 0.60, 1.22), (s * 0.45, 0.42, 1.20)], 0.02, METAL)  # zahnutá řídítka
        t += box((s * 0.27, 0.32, 0.30), (0.12, 0.30, 0.025), DARK)                       # stupačky
        t += cylinder((s * 0.44, 0.45, 1.20), (s * 0.46, 0.47, 1.36), 0.01, METAL, n=6)
        t += ellipsoid((s * 0.46, 0.47, 1.40), (0.05, 0.02, 0.05), METAL)                 # kulatá zrcátka
    t += rounded_box((0, 0.05, 0.38), (0.26, 0.42, 0.22), METAL, exp=4)                    # klikovka
    t += cylinder((0, 0.12, 0.42), (0, 0.36, 0.80), 0.10, METAL)                           # válce do V
    t += cylinder((0, 0.00, 0.42), (0, -0.20, 0.78), 0.10, METAL)
    t += tube([(0, 0.62, 1.00), (0, 0.10, 0.88), (0, -0.40, 0.70)], 0.03, DARK)            # rám
    t += loft([(0.55, 0.98, 0.10, 0.06), (0.40, 1.00, 0.21, 0.11), (0.15, 0.97, 0.22, 0.11),
               (-0.05, 0.92, 0.14, 0.07), (-0.15, 0.90, 0.06, 0.03)], BODY)               # kapkovitá nádrž
    t += loft([(-0.10, 0.84, 0.14, 0.05), (-0.30, 0.79, 0.19, 0.06), (-0.48, 0.83, 0.17, 0.06), (-0.56, 0.86, 0.10, 0.04)], SEAT)
    for dz in (0.0, 0.10):
        t += cylinder((0.18, 0.15, 0.30 + dz), (0.22, -1.02, 0.36 + dz), 0.045, METAL)    # dvojitý výfuk
    t += ellipsoid((0, 0.74, 0.96), (0.13, 0.08, 0.13), METAL)                             # chromované světlo
    t += ellipsoid((0, 0.815, 0.96), (0.10, 0.02, 0.10), LIGHT)
    t += box((0, -1.20, 0.62), (0.10, 0.012, 0.04), RED)
    return t


def scooter():
    t = []
    t += wheel(-0.55, 0.22, 0.07, None)
    t += wheel(0.64, 0.22, 0.06, None)
    t += fender(0.64, 0.22, 0.27, 0.46, 0.84, 0.08)
    t += loft([(0.74, 0.66, 0.10, 0.30), (0.64, 0.70, 0.28, 0.42), (0.52, 0.66, 0.30, 0.40), (0.42, 0.60, 0.26, 0.34)], BODY)  # štít na nohy
    t += box((0, 0.12, 0.30), (0.34, 0.56, 0.04), DARK)                                    # podlaha
    t += rounded_box((0, 0.12, 0.21), (0.30, 0.58, 0.16), BODY, exp=4)
    t += loft([(0.06, 0.52, 0.18, 0.24), (-0.16, 0.58, 0.30, 0.32), (-0.42, 0.60, 0.31, 0.30),
               (-0.66, 0.62, 0.23, 0.24), (-0.80, 0.64, 0.10, 0.13)], BODY)               # zadní karoserie
    t += loft([(-0.04, 0.90, 0.15, 0.05), (-0.32, 0.93, 0.18, 0.06), (-0.60, 0.95, 0.14, 0.05)], SEAT)
    t += rounded_box((0, 0.56, 1.12), (0.52, 0.16, 0.10), BODY, exp=4)                     # kryt řídítek
    t += rounded_box((0, 0.645, 1.12), (0.14, 0.02, 0.05), LIGHT, exp=3)
    t += curved_shield(0.60, 1.17, 0.52, 1.32, 0.15, 0.12, 0.02, GLASS)
    for s in (-1, 1):
        t += cylinder((s * 0.26, 0.56, 1.12), (s * 0.34, 0.54, 1.11), 0.017, DARK, n=8)  # gripy
        t += mirror_on_stalk(s, (0.22, 0.56, 1.16), (0.30, 0.58, 1.30), size=(0.09, 0.03, 0.06))
    t += cylinder((0.16, -0.30, 0.22), (0.18, -0.72, 0.30), 0.045, METAL)
    t += box((0, -0.83, 0.70), (0.12, 0.012, 0.04), RED)
    return t


MODELS = {'touring': touring, 'sport': sport, 'naked': naked, 'adventure': adventure, 'cruiser': cruiser, 'scooter': scooter}


# ------------------------------------------------------------------ vykreslení
def render(tris, rel_deg):
    size = OUT * SS
    scale = PX_PER_M * SS
    th = math.radians(rel_deg)
    ct, st = math.cos(th), math.sin(th)
    ce, se = math.cos(ELEV), math.sin(ELEV)
    light = np.array([-0.45, 0.55, 0.70]); light /= np.linalg.norm(light)
    half = light + np.array([0, 0, 1.0]); half /= np.linalg.norm(half)

    def view(p):  # svět → (x vpravo, y nahoru, z ke kameře)
        x = p[..., 0] * ct + p[..., 1] * st
        y = -p[..., 0] * st + p[..., 1] * ct
        z = p[..., 2]
        return np.stack([x, y * se + z * ce, -y * ce + z * se], -1)

    color = np.zeros((size, size, 3)); depth = np.full((size, size), -1e9)
    mat_buf = np.full((size, size), -1); spec_buf = np.zeros((size, size))
    for pts, nrms, mat in tris:
        v = view(np.array(pts, float)); n = view(np.array(nrms, float))
        if mat != GLASS and (n[:, 2] < 0).all():
            continue  # odvrácená strana (podle normál, pořadí vrcholů primitiv není jednotné)
        sx = size / 2 + v[:, 0] * scale
        sy = size / 2 - (v[:, 1] - TARGET_Z * ce) * scale
        x0, x1 = max(int(sx.min()), 0), min(int(sx.max()) + 1, size)
        y0, y1 = max(int(sy.min()), 0), min(int(sy.max()) + 1, size)
        if x0 >= x1 or y0 >= y1:
            continue
        gx, gy = np.meshgrid(np.arange(x0, x1) + 0.5, np.arange(y0, y1) + 0.5)
        d = (sy[1] - sy[2]) * (sx[0] - sx[2]) + (sx[2] - sx[1]) * (sy[0] - sy[2])
        if abs(d) < 1e-9:
            continue
        w0 = ((sy[1] - sy[2]) * (gx - sx[2]) + (sx[2] - sx[1]) * (gy - sy[2])) / d
        w1 = ((sy[2] - sy[0]) * (gx - sx[2]) + (sx[0] - sx[2]) * (gy - sy[2])) / d
        w2 = 1 - w0 - w1
        inside = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
        if not inside.any():
            continue
        z = w0 * v[0, 2] + w1 * v[1, 2] + w2 * v[2, 2]
        nn = w0[..., None] * n[0] + w1[..., None] * n[1] + w2[..., None] * n[2]
        nn /= np.linalg.norm(nn, axis=-1, keepdims=True) + 1e-9
        if mat == GLASS:
            nn *= np.sign(nn[..., 2:3] + 1e-9)  # štít je oboustranný
        sub = depth[y0:y1, x0:x1]
        hit = inside & (z > sub)
        if not hit.any():
            continue
        lam = np.clip((nn * light).sum(-1), 0, 1)
        shade = 1.0 if mat in EMISSIVE else 0.38 + 0.62 * lam
        col = np.array(BASE[mat]) * (shade if np.isscalar(shade) else shade[..., None])
        sp = SPEC.get(mat, 0) * np.clip((nn * half).sum(-1), 0, 1) ** 40
        sub[hit] = z[hit]
        color[y0:y1, x0:x1][hit] = np.broadcast_to(col, hit.shape + (3,))[hit] if col.ndim == 3 else col
        mat_buf[y0:y1, x0:x1][hit] = mat
        spec_buf[y0:y1, x0:x1][hit] = sp[hit]

    covered = mat_buf >= 0
    is_body = mat_buf == BODY
    # Stín: obrys půdorysu na zemi, rozmazaný
    foot = np.array([[x, y, 0] for x, y in ((-0.45, -1.2), (0.45, -1.2), (0.45, 1.1), (-0.45, 1.1))], float)
    fv = view(foot)
    shadow = Image.new('L', (size, size), 0)
    from PIL import ImageDraw
    ImageDraw.Draw(shadow).polygon([(size / 2 + p[0] * scale, size / 2 - (p[1] - TARGET_Z * ce) * scale) for p in fv], fill=110)
    shadow = np.array(shadow.filter(ImageFilter.GaussianBlur(10 * SS))) / 255.0

    body = np.zeros((size, size, 4))
    body[..., :3] = np.clip(color, 0, 1); body[..., 3] = is_body
    fixed = np.zeros((size, size, 4))
    fixed[..., :3] = np.where(covered[..., None], np.clip(color, 0, 1), 0)
    fixed[..., 3] = np.where(covered & ~is_body, 1.0, 0.0)
    # Odlesky laku jako bílá s průhledností, stín pod motorkou
    gloss = np.where(is_body, np.clip(spec_buf * 1.6, 0, 0.85), 0)
    fixed[..., :3] = np.where(is_body[..., None], 1.0, fixed[..., :3])
    fixed[..., 3] = np.maximum(fixed[..., 3], gloss)
    under = ~covered
    fixed[..., 3] = np.where(under, shadow, fixed[..., 3])

    def down(a):
        img = Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8), 'RGBA')
        return img.resize((OUT, OUT), Image.LANCZOS)
    return down(body), down(fixed)


def main():
    kind, out = sys.argv[1], sys.argv[2]
    tris = MODELS[kind]()
    bodies, fixeds = [], []
    for i in range(FRAMES):
        b, f = render(tris, i * 360 / FRAMES)
        bodies.append(b); fixeds.append(f)
        print(f'snímek {i + 1}/{FRAMES}', end='\r', flush=True)
    for name, frames in (('body', bodies), ('fixed', fixeds)):
        sheet = Image.new('RGBA', (OUT * FRAMES, OUT))
        for i, im in enumerate(frames):
            sheet.paste(im, (i * OUT, 0))
        sheet.save(f'{out}/bike3d_{kind}_{name}.png', optimize=True)
    print(f'\n{len(tris)} trojúhelníků, kotva y = {GROUND_Y:.3f}')
    if len(sys.argv) > 3:  # náhled: obarvení červenou na šachovnici a na mapě
        prev = Image.new('RGBA', (OUT * 8, OUT * 2), (242, 239, 233, 255))
        for k, i in enumerate(range(0, FRAMES, 3)):
            for row, rgb in enumerate(((229, 57, 53), (250, 250, 250))):
                b = np.array(bodies[i]).astype(float) / 255
                tint = b.copy(); tint[..., :3] = b[..., :3] * np.array(rgb) / 255
                layer = Image.fromarray((tint * 255).astype(np.uint8), 'RGBA')
                layer.alpha_composite(fixeds[i])
                prev.alpha_composite(layer, (k * OUT, row * OUT))
        prev.save(sys.argv[3])


if __name__ == '__main__':
    main()
