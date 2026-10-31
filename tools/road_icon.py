"""Použití: python tools/road_icon.py xml > app/src/main/res/drawable/ic_launcher_foreground.xml
         python tools/road_icon.py png nahled.png

Výchozí ikona Serpentiny: klikatá silnice v perspektivě (zespodu široká, k obzoru se zužuje)."""
import math, sys
from PIL import Image, ImageDraw

N = 120
def center(t):
    # t = 0 dole u jezdce, 1 na obzoru; dvě zatáčky, které se k obzoru zmenšují
    # Perspektiva: blízké zatáčky jsou vysoké, vzdálené nízké
    y = 108 - 66 * t ** 0.7
    amp = 16 * (1 - t) ** 0.5
    x = 54 + amp * math.sin(t * 2.8 * math.pi + 1.0)
    return x, y
def width(t): return 21 * (1 - t) ** 1.2 + 1.2

pts = [center(i / N) for i in range(N + 1)]
left, right = [], []
for i, (x, y) in enumerate(pts):
    a, b = pts[max(i - 1, 0)], pts[min(i + 1, N)]
    dx, dy = b[0] - a[0], b[1] - a[1]; n = math.hypot(dx, dy)
    nx, ny = -dy / n, dx / n; w = width(i / N) / 2
    left.append((x + nx * w, y + ny * w)); right.append((x - nx * w, y - ny * w))
road = left + right[::-1]

# Přerušovaná středová čára: úseky podél středu, šířka úměrná silnici
dashes = []
for k in range(9):
    t0, t1 = 0.02 + k * 0.1, 0.02 + k * 0.1 + 0.05
    seg = [i for i in range(N + 1) if t0 <= i / N <= t1]
    if len(seg) < 2: continue
    l, r = [], []
    for i in seg:
        x, y = pts[i]; a, b = pts[max(i - 1, 0)], pts[min(i + 1, N)]
        dx, dy = b[0] - a[0], b[1] - a[1]; n = math.hypot(dx, dy)
        nx, ny = -dy / n, dx / n; w = width(i / N) * 0.06
        l.append((x + nx * w, y + ny * w)); r.append((x - nx * w, y - ny * w))
    dashes.append(l + r[::-1])

# Hory na obzoru (tmavší červená), silnice vede k nim
mountains = [(10, 41), (27, 30), (37, 36), (53, 21), (67, 35), (77, 28), (98, 41), (98, 43), (10, 43)]

def path(poly): return "M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in poly) + "Z"

if sys.argv[1] == "xml":
    print(f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <!-- Klikatá silnice v perspektivě (generuje tools/road_icon.py) -->
    <path android:fillColor="#B71C1C" android:pathData="{path(mountains)}" />
    <path android:fillColor="#FFFFFF" android:pathData="{path(road)}" />
    <path android:fillColor="#E53935" android:pathData="{''.join(path(d) for d in dashes)}" />
</vector>''')
else:
    sc = 4; im = Image.new("RGB", (108 * sc, 108 * sc), "#E53935"); d = ImageDraw.Draw(im)
    d.polygon([(x * sc, y * sc) for x, y in mountains], fill="#B71C1C")
    d.polygon([(x * sc, y * sc) for x, y in road], fill="white")
    for p in dashes: d.polygon([(x * sc, y * sc) for x, y in p], fill="#E53935")
    mask = Image.new("L", im.size, 0); ImageDraw.Draw(mask).ellipse((18 * sc, 18 * sc, 90 * sc, 90 * sc), fill=255)
    out = Image.new("RGB", im.size, (230, 230, 230)); out.paste(im, (0, 0), mask)
    out.crop((18 * sc, 18 * sc, 90 * sc, 90 * sc)).save(sys.argv[2])
