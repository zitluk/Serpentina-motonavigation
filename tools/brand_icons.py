"""Použití: python tools/brand_icons.py Roboto-Regular.ttf app/src/main/res/drawable

Generuje zástupné ikony výrobců (název značky v obrysech písma Roboto Black) jako vector drawable."""
import sys, re
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.transformPen import TransformPen

font_path, out_dir = sys.argv[1], sys.argv[2]
font = TTFont(font_path)
if 'fvar' in font:
    font = instantiateVariableFont(font, {"wght": 900, **({"wdth": 75} if any(a.axisTag == 'wdth' for a in font['fvar'].axes) else {})})
gs = font.getGlyphSet(); cmap = font.getBestCmap()

def line_glyphs(text):
    x = 0; items = []
    for ch in text:
        g = cmap[ord(ch)]
        items.append((g, x)); x += gs[g].width
    return items, x

BRANDS = [("aprilia","APRILIA"),("benelli","BENELLI"),("bmw","BMW"),("cfmoto","CFMOTO"),("ducati","DUCATI"),
    ("harley_davidson","HARLEY-\nDAVIDSON"),("honda","HONDA"),("husqvarna","HUSQ-\nVARNA"),("indian","INDIAN"),
    ("jawa","JAWA"),("kawasaki","KAWA-\nSAKI"),("ktm","KTM"),("moto_guzzi","MOTO\nGUZZI"),("mv_agusta","MV\nAGUSTA"),
    ("piaggio","PIAGGIO"),("royal_enfield","ROYAL\nENFIELD"),("suzuki","SUZUKI"),("triumph","TRIUMPH"),
    ("vespa","VESPA"),("yamaha","YAMAHA")]

upm = font['head'].unitsPerEm
cap = font['OS/2'].sCapHeight or int(upm * 0.71)
for key, label in BRANDS:
    lines = label.split('\n')
    widths = [line_glyphs(l)[1] for l in lines]
    gap = cap * 0.35
    block_h = cap * len(lines) + gap * (len(lines) - 1)
    # Vejde se do bezpečné zóny: šířka max 60, výška max 40 (viewport 108)
    scale = min(60 / max(widths), 40 / block_h, 22 / cap)
    pen = SVGPathPen(gs)
    top = 54 - block_h * scale / 2
    for i, l in enumerate(lines):
        items, w = line_glyphs(l)
        x0 = 54 - w * scale / 2
        baseline = top + (cap * (i + 1) + gap * i) * scale
        for g, gx in items:
            # y v písmu roste nahoru, ve viewportu dolů
            tp = TransformPen(pen, (scale, 0, 0, -scale, x0 + gx * scale, baseline))
            gs[g].draw(tp)
    d = pen.getCommands()
    d = re.sub(r'(\d+\.\d{2})\d+', r'\1', d)
    open(f"{out_dir}/ic_brand_{key}.xml", "w").write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Zástupná ikona výrobce: jen název značky písmem Roboto (Apache 2.0), ne logo.
     Vlastní logo: smaž tento soubor a vlož res/drawable-nodpi/ic_brand_{key}.png (viz README). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFF" android:pathData="{d}" />
</vector>
''')
    print(key, round(scale,4))
