# Run: python tools/screenshots/icon_gen.py app/src/main/res <out-dir>  (needs pillow and cairosvg). Writes the
# two launcher layers into res/drawable, and icon-512.png plus preview sheets into <out-dir> (copy
# icon-512.png to docs/images/icon.png).
"""Roastbook adaptive icon: one geometry, emitted as Android VectorDrawables and as SVG previews.

Art follows merrimentlabs.com: the Bag component (viewBox 120x150) on the --roast tile, with the
site's Bean on the label. Coordinates below are in the bag's own 120x150 space; a group transform
places the bag in the 108 dp adaptive-icon canvas, inside the 66 dp safe circle.
"""
import io, sys, pathlib
import cairosvg
from PIL import Image, ImageDraw

ROAST = "#5A3320"
BODY = "#E9855F"
TOP = "#F6B23C"
LABEL = "#FFF4E4"
BEAN = "#7A4A2E"
CREASE = "#3E2215"
LINE = "#5A3320"

S = 0.42          # bag scale into the 108 canvas
CX, CY = 60, 81.5 # bag center in its own space
# Bean in bag space: the site's 48x48 bean, scaled and centered on the label.
BS = 0.68
BX, BY = 60, 77

def ell(cx, cy, rx, ry):
    return f"M{cx-rx},{cy} a{rx},{ry} 0 1,0 {2*rx},0 a{rx},{ry} 0 1,0 {-2*rx},0 z"

def rrect(x, y, w, h, r):
    return (f"M{x+r},{y} h{w-2*r} a{r},{r} 0 0 1 {r},{r} v{h-2*r} a{r},{r} 0 0 1 {-r},{r} "
            f"h{-(w-2*r)} a{r},{r} 0 0 1 {-r},{-r} v{-(h-2*r)} a{r},{r} 0 0 1 {r},{-r} z")

TOP_D = "M22,18 h76 l6,14 H16 z"
BODY_D = "M16,32 h88 l-4,104 c0,5 -4,9 -9,9 H29 c-5,0 -9,-4 -9,-9 z"
SHADE_D = "M16,32 h88 l-1,10 H17 z"
LABEL_D = rrect(32, 58, 56, 54, 8)
LINE1_D = rrect(42, 96, 36, 4, 2)
LINE2_D = rrect(47, 104, 26, 3, 1.5)
# Bean (site component): ellipse rx15 ry20 at (24,24) rotated 28 deg; crease stroke.
BEAN_D = ell(24, 24, 15, 20)
CREASE_D = "M17,9 c7,7 7,23 14,30"

def bag_group_open_vd():
    tx = 54 - CX * S
    ty = 54 - CY * S
    return f'<group android:translateX="{tx:.3f}" android:translateY="{ty:.3f}" android:scaleX="{S}" android:scaleY="{S}">'

def bean_group_vd(inner):
    # place 48x48 bean so its center (24,24) lands on (BX,BY), scaled BS, rotated 28 about its center
    return (f'<group android:translateX="{BX - 24*BS:.3f}" android:translateY="{BY - 24*BS:.3f}" '
            f'android:scaleX="{BS}" android:scaleY="{BS}">'
            f'<group android:pivotX="24" android:pivotY="24" android:rotation="28">{inner}</group></group>')

HDR = ('<?xml version="1.0" encoding="utf-8"?>\n'
       '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
       '    android:width="108dp" android:height="108dp"\n'
       '    android:viewportWidth="108" android:viewportHeight="108">\n')

def fg_vd():
    p = lambda d, c, a=None: (f'<path android:fillColor="{c}"' + (f' android:fillAlpha="{a}"' if a else '') + f' android:pathData="{d}" />')
    bean = (p(BEAN_D, BEAN) +
            f'<path android:strokeColor="{CREASE}" android:strokeWidth="3" android:strokeLineCap="round" android:pathData="{CREASE_D}" />')
    body = "\n        ".join([
        p(TOP_D, TOP), p(BODY_D, BODY), p(SHADE_D, "#000000", "0.08"), p(LABEL_D, LABEL),
        bean_group_vd(bean), p(LINE1_D, LINE, "0.8"), p(LINE2_D, LINE, "0.45"),
    ])
    return (HDR + "    <!-- Roastbook mark: a coffee bag with a bean on its label, after the merrimentlabs.com art. -->\n"
            f"    {bag_group_open_vd()}\n        {body}\n    </group>\n</vector>\n")

def mono_vd():
    # One color, alpha only: bag silhouette with the label punched out (evenOdd), the bean and the
    # two label lines drawn back inside the hole.
    sil = f'<path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{TOP_D} {BODY_D} {LABEL_D}" />'
    bean = f'<path android:fillColor="#FFFFFFFF" android:pathData="{BEAN_D}" />'
    lines = f'<path android:fillColor="#FFFFFFFF" android:pathData="{LINE1_D} {LINE2_D}" />'
    return (HDR + "    <!-- Themed-icon layer: the same mark as one silhouette. -->\n"
            f"    {bag_group_open_vd()}\n        {sil}\n        {bean_group_vd(bean)}\n        {lines}\n    </group>\n</vector>\n")

def svg(mono=False, bg=True):
    tx, ty = 54 - CX * S, 54 - CY * S
    bean_t = f"translate({BX - 24*BS} {BY - 24*BS}) scale({BS}) rotate(28 24 24)"
    if mono:
        inner = (f'<path fill="#fff" fill-rule="evenodd" d="{TOP_D} {BODY_D} {LABEL_D}"/>'
                 f'<g transform="{bean_t}"><path fill="#fff" d="{BEAN_D}"/></g>'
                 f'<path fill="#fff" d="{LINE1_D} {LINE2_D}"/>')
    else:
        inner = (f'<path fill="{TOP}" d="{TOP_D}"/><path fill="{BODY}" d="{BODY_D}"/>'
                 f'<path fill="#000" fill-opacity=".08" d="{SHADE_D}"/><path fill="{LABEL}" d="{LABEL_D}"/>'
                 f'<g transform="{bean_t}"><path fill="{BEAN}" d="{BEAN_D}"/>'
                 f'<path d="{CREASE_D}" fill="none" stroke="{CREASE}" stroke-width="3" stroke-linecap="round"/></g>'
                 f'<path fill="{LINE}" fill-opacity=".8" d="{LINE1_D}"/><path fill="{LINE}" fill-opacity=".45" d="{LINE2_D}"/>')
    back = f'<rect width="108" height="108" fill="{ROAST}"/>' if bg else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">{back}'
            f'<g transform="translate({tx} {ty}) scale({S})">{inner}</g></svg>')

def render(svg_text, px):
    return Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg_text.encode(), output_width=px, output_height=px))).convert("RGBA")

if __name__ == "__main__":
    res = pathlib.Path(sys.argv[1])  # app/src/main/res
    out = pathlib.Path(sys.argv[2])  # preview dir
    out.mkdir(parents=True, exist_ok=True)
    (res / "drawable/ic_launcher_foreground.xml").write_text(fg_vd())
    (res / "drawable/ic_launcher_monochrome.xml").write_text(mono_vd())
    full = render(svg(), 1080)
    # The 72 dp visible area (crop 18 dp each side) as a 512 px square: the README and store icon.
    crop = full.crop((180, 180, 900, 900)).resize((512, 512), Image.LANCZOS).convert("RGBA")
    crop.save(out / "icon-512.png")
    # Previews: circle, squircle-ish rounded square, and the themed (monochrome) look.
    vis = full.crop((180, 180, 900, 900)).resize((256, 256), Image.LANCZOS)
    sheet = Image.new("RGBA", (256 * 4 + 50, 296), "#fff7ec")
    for i, shape in enumerate(["circle", "rounded", "square"]):
        m = Image.new("L", (256, 256), 0); d = ImageDraw.Draw(m)
        if shape == "circle": d.ellipse((0, 0, 255, 255), fill=255)
        elif shape == "rounded": d.rounded_rectangle((0, 0, 255, 255), radius=72, fill=255)
        else: d.rectangle((0, 0, 255, 255), fill=255)
        sheet.paste(vis, (10 + i * 266, 20), m)
    mono = render(svg(mono=True, bg=False), 1080).crop((180, 180, 900, 900)).resize((256, 256), Image.LANCZOS)
    tile = Image.new("RGBA", (256, 256), "#E3E8D8"); tint = Image.new("RGBA", (256, 256), "#3A4A2A")
    tile.paste(tint, (0, 0), mono.split()[3])
    m = Image.new("L", (256, 256), 0); ImageDraw.Draw(m).ellipse((0, 0, 255, 255), fill=255)
    sheet.paste(tile, (10 + 3 * 266, 20), m)
    sheet.save(out / "icon-preview.png")
    # Small sizes, to check legibility at launcher size.
    small = Image.new("RGBA", (300, 80), "#fff7ec")
    x = 5
    for px in (48, 64):
        v = vis.resize((px, px), Image.LANCZOS); m = Image.new("L", (px, px), 0); ImageDraw.Draw(m).ellipse((0, 0, px - 1, px - 1), fill=255)
        small.paste(v, (x, 5), m); x += px + 10
    small.save(out / "icon-small.png")
    print("ok")
