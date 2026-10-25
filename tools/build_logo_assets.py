"""
Composes the official logo from the client's own layer PNGs (branding/client-animation-src/layers)
using the positions in tools/meta.json, then exports the logo to the Android app, admin dashboard
and distribution website. Nothing is redrawn: layers are placed exactly as the client's file defines.

Usage: python3 tools/build_logo_assets.py
"""
import json, pathlib
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "branding" / "client-animation-src"
meta = json.load(open(SRC / "tools" / "meta.json"))

canvas = Image.new("RGBA", (meta["W"], meta["H"]), (0, 0, 0, 0))
# Back-to-front order as rendered by the animation: rays, tower, lions, text.
order = ["ray0", "ray1", "ray2", "ray3", "ray4", "ray5", "tower", "lionL", "lionR", "text1", "text2"]
for name in order:
    l = meta["layers"][name]
    im = Image.open(SRC / "layers" / f"L_{name}.png").convert("RGBA")
    canvas.alpha_composite(im, (l["x"], l["y"]))
logo = canvas.crop(canvas.getbbox())
out = ROOT / "branding"
logo.save(out / "logo.png", optimize=True)
print("logo size", logo.size)

def fit(img, box_w, box_h, bg=None, scale=1.0):
    """Scales img to fit inside (box_w*scale, box_h*scale) keeping proportions and centres it."""
    w, h = img.size
    k = min(box_w * scale / w, box_h * scale / h)
    r = img.resize((max(1, round(w * k)), max(1, round(h * k))), Image.LANCZOS)
    c = Image.new("RGBA", (box_w, box_h), bg or (0, 0, 0, 0))
    c.alpha_composite(r, ((box_w - r.width) // 2, (box_h - r.height) // 2))
    return c

res = ROOT / "android-app" / "app" / "src" / "main" / "res"
(res / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
w = 900
logo.resize((w, round(logo.height * w / logo.width)), Image.LANCZOS).save(res / "drawable-nodpi" / "logo.webp", "WEBP", quality=92, method=6)

WHITE = (255, 255, 255, 255)
densities = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
for d, k in densities.items():
    folder = res / f"mipmap-{d}"
    folder.mkdir(parents=True, exist_ok=True)
    # Adaptive icon foreground: 108dp canvas, logo kept inside the 66dp safe zone.
    fg = fit(logo, round(108 * k), round(108 * k), scale=62 / 108)
    fg.save(folder / "ic_launcher_foreground.webp", "WEBP", lossless=True)
    # Legacy icons (pre-API 26): logo on white, square and round.
    sz = round(48 * k)
    sq = fit(logo, sz, sz, WHITE, scale=0.86)
    sq.convert("RGB").save(folder / "ic_launcher.webp", "WEBP", quality=95)
    mask = Image.new("L", (sz, sz), 0)
    from PIL import ImageDraw
    ImageDraw.Draw(mask).ellipse((0, 0, sz - 1, sz - 1), fill=255)
    rnd = fit(logo, sz, sz, WHITE, scale=0.70)
    rnd.putalpha(mask)
    rnd.save(folder / "ic_launcher_round.webp", "WEBP", lossless=True)

# Play-store style 512 icon + web assets
fit(logo, 512, 512, WHITE, scale=0.86).convert("RGB").save(out / "icon-512.png")
for web in [ROOT / "admin-dashboard" / "public", ROOT / "distribution-website" / "assets"]:
    web.mkdir(parents=True, exist_ok=True)
    logo.resize((640, round(logo.height * 640 / logo.width)), Image.LANCZOS).save(web / "logo.webp", "WEBP", quality=90)
    logo.resize((640, round(logo.height * 640 / logo.width)), Image.LANCZOS).save(web / "logo.png", optimize=True)
    fit(logo, 64, 64, WHITE, scale=0.92).save(web / "favicon.png")
    fit(logo, 180, 180, WHITE, scale=0.86).convert("RGB").save(web / "apple-touch-icon.png")
print("done")
