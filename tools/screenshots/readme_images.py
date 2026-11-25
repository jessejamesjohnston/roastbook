#!/usr/bin/env python3
"""Rebuild the README images in docs/images/ from the screenshots in tools/screenshots/input/.

    uv run --no-project --with pillow python tools/screenshots/readme_images.py [name.png ...]

Inputs: full-size emulator screenshots of the demo journal (see tools/screenshots/README.md):
phone shots at 1080 x 1920 and the 10-inch tablet journal at 2560 x 1440. Outputs keep the names
and sizes the README uses:

  phone-journal.png, phone-detail.png, phone-search.png  phone shots at 540 x 960
  tablet.png      the 10-inch journal at 1600 x 900
  hero.png        1760 x 1000: journal, detail and search on the site's cream
                  with the sunrise in the top-right corner, rounded, soft shadow

icon.png is not made here (icon_gen.py makes the icon layers).
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

HERE = Path(__file__).resolve().parent
INPUT = HERE / "input"
OUT = HERE.parent.parent / "docs" / "images"

# Hero layout: three phone shots on cream, with a soft shadow.
HERO_SIZE = (1760, 1000)
CREAM, SUN = (255, 247, 236), (251, 215, 153)
SLOTS = [(80, 72, 560, 926), (620, 72, 1100, 926), (1160, 72, 1640, 926)]
RADIUS = 24
SHADOW_RGB, SHADOW_OFFSET, SHADOW_BLUR, SHADOW_ALPHA = (70, 45, 20), 20, 18, 90


def rgb(path: Path) -> Image.Image:
    im = Image.open(path)
    if im.mode in ("RGBA", "LA", "P"):
        im = im.convert("RGBA")
        flat = Image.new("RGB", im.size, (255, 255, 255))
        flat.paste(im, mask=im.getchannel("A"))
        return flat
    return im.convert("RGB")


def fit(im: Image.Image, size: tuple[int, int]) -> Image.Image:
    return im.resize(size, Image.LANCZOS)


def sunrise(size: tuple[int, int]) -> Image.Image:
    """Cream with an elliptical marigold glow from the top-right corner."""
    w, h = size
    a, b = float(w), w / 1.375
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = math.hypot((w - x) / a, y / b)
            s = min(max((t - 0.33) / 0.39, 0.0), 1.0)
            s = s * s * (3 - 2 * s)
            px[x, y] = tuple(round(c + (k - c) * s) for c, k in zip(SUN, CREAM))
    return img


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return m


def place(canvas: Image.Image, shot: Image.Image, box: tuple[int, int, int, int]) -> None:
    x0, y0, x1, y1 = box
    size = (x1 - x0, y1 - y0)
    mask = rounded_mask(size, RADIUS)
    shadow = Image.new("L", canvas.size, 0)
    shadow.paste(Image.new("L", size, SHADOW_ALPHA), (x0, y0 + SHADOW_OFFSET), mask)
    shadow = shadow.filter(ImageFilter.GaussianBlur(SHADOW_BLUR))
    canvas.paste(Image.new("RGB", canvas.size, SHADOW_RGB), (0, 0), shadow)
    canvas.paste(fit(shot, size), (x0, y0), mask)


def main() -> int:
    journal = rgb(INPUT / "phone-journal.png")
    detail = rgb(INPUT / "phone-detail.png")
    search = rgb(INPUT / "phone-search.png")
    tablet = rgb(INPUT / "tablet-journal.png")

    outputs = {
        "phone-journal.png": fit(journal, (540, 960)),
        "phone-detail.png": fit(detail, (540, 960)),
        "phone-search.png": fit(search, (540, 960)),
        "tablet.png": fit(tablet, (1600, 900)),
    }
    hero = sunrise(HERO_SIZE)
    for shot, box in zip((journal, detail, search), SLOTS):
        place(hero, shot, box)
    outputs["hero.png"] = hero

    only = set(sys.argv[1:])
    for name, im in outputs.items():
        if only and name not in only:
            continue
        im.save(OUT / name, optimize=True)
        print(f"wrote docs/images/{name} {im.size[0]}x{im.size[1]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
