"""Složí stažená loga do popředí adaptivní ikony 432×432 (logo v bezpečné zóně uprostřed)."""
import sys, glob, os
from PIL import Image, ImageDraw
src, out, sheet = sys.argv[1], sys.argv[2], sys.argv[3]
tiles = []
for path in sorted(glob.glob(f"{src}/*.png")):
    key = os.path.basename(path)[:-4]
    im = Image.open(path).convert("RGBA")
    # Bílé pozadí v PNG bez průhlednosti bereme jako průhledné
    if im.getextrema()[3][0] == 255:
        px = [(r, g, b, 0 if r > 245 and g > 245 and b > 245 else a) for r, g, b, a in im.getdata()]
        im.putdata(px)
    im = im.crop(im.getbbox())
    w, h = im.size
    # Rohy loga musí ležet v bezpečném kruhu o průměru 264 px (66 dp z 108)
    s = min(264 / (w * w + h * h) ** 0.5, 200 / h)
    im = im.resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)
    canvas = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    canvas.paste(im, ((432 - im.width) // 2, (432 - im.height) // 2), im)
    canvas.save(f"{out}/ic_brand_{key}.png", optimize=True)
    # Náhled: bílé pozadí a kruhová maska launcheru
    prev = Image.new("RGBA", (432, 432), "white"); prev.alpha_composite(canvas)
    mask = Image.new("L", (432, 432), 0); ImageDraw.Draw(mask).ellipse((72, 72, 360, 360), fill=255)
    tile = Image.new("RGB", (432, 432), (200, 200, 200)); tile.paste(prev, (0, 0), mask)
    tiles.append(tile.crop((72, 72, 360, 360)).resize((156, 156)))
cols = 6; rows = (len(tiles) + cols - 1) // cols
sh = Image.new("RGB", (cols * 160, rows * 160), (200, 200, 200))
for i, t in enumerate(tiles): sh.paste(t, ((i % cols) * 160 + 2, (i // cols) * 160 + 2))
sh.save(sheet)
print(len(tiles))
