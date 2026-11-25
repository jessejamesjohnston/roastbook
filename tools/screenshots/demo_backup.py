"""Run: python tools/screenshots/demo_backup.py <out.zip> [preview-dir]  (needs pillow).

Builds a Roastbook backup zip (format 1, schema 4) full of made-up coffees and generated bag photos.

Nothing here is real: fictional roasters, drawn bags. Restored through Settings > Restore, it gives
the emulator a demo journal for screenshots and for the release walk.
"""
import hashlib, io, json, math, os, random, sqlite3, sys, time, uuid, zipfile
from PIL import Image, ImageDraw, ImageFont, ImageFilter

SCHEMA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../app/schemas/dev.roastbook.app.data.db.RoastbookDatabase/4.json")
# The bag art uses DejaVu Serif and Lato (Debian/Ubuntu: fonts-dejavu-core, fonts-lato). Set
# ROASTBOOK_FONT_DIR to look elsewhere (for example ~/Library/Fonts on macOS).
FONT_DIR = os.path.expanduser(os.environ.get("ROASTBOOK_FONT_DIR", "/usr/share/fonts/truetype"))

def font(name, size):
    for root, _, files in os.walk(FONT_DIR):
        if name in files:
            return ImageFont.truetype(os.path.join(root, name), size)
    raise SystemExit(f"font {name} not found")

# --- OKLCH -> sRGB, matching color/TintRule.kt -------------------------------------------------
def oklch_to_argb(L, C, h):
    a = C * math.cos(math.radians(h)); b = C * math.sin(math.radians(h))
    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
    r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
    g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
    bb = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
    def enc(x):
        x = max(0.0, min(1.0, x))
        return round(255 * (12.92 * x if x <= 0.0031308 else 1.055 * x ** (1 / 2.4) - 0.055))
    v = (0xFF << 24) | (enc(r) << 16) | (enc(g) << 8) | enc(bb)
    return v - (1 << 32)  # signed Int, as Room stores Kotlin Int

def tints(h, c=0.09):
    cl = lambda v, lo, hi: max(lo, min(hi, v))
    return dict(
        tint_light=oklch_to_argb(0.915, cl(0.5 * c, 0.028, 0.055), h),
        tint_dark=oklch_to_argb(0.30, cl(0.55 * c, 0.025, 0.06), h),
        accent_light=oklch_to_argb(0.52, cl(1.2 * c, 0.06, 0.12), h),
        accent_dark=oklch_to_argb(0.80, cl(1.1 * c, 0.06, 0.11), h),
    )

# --- demo coffees ------------------------------------------------------------------------------
DAY = 86_400_000
NOW = int(time.time() * 1000)
COFFEES = [
    dict(roaster="Larkmoor Coffee", coffee_name="Hills of Lumen", origin_country="Ethiopia", region="Guji", producer="Lumen Hill washing station",
         process="Washed", varietal=["Heirloom"], elevation_m=2100, roast_level="Light", bag_size_g=340,
         tasting_notes=["peach", "jasmine", "honey"], ratings=(5, 5, 4, 3, 5), buy_again=True, price=22.0, source="seed:roaster-direct",
         bag="#E9855F", hue=45, days=3, notes="Bright and floral. Best at 93 degrees."),
    dict(roaster="Northwind Roasters", coffee_name="Night Owl Decaf", origin_country="Colombia", region="Huila", producer=None,
         process="Sugarcane decaf", varietal=["Caturra", "Castillo"], elevation_m=1700, roast_level="Medium", bag_size_g=340,
         tasting_notes=["cocoa", "fig", "brown sugar"], ratings=(4, 4, 3, 4, 4), buy_again=True, price=19.5, source="seed:local-café",
         bag="#5E8C6A", hue=150, days=9, notes=None),
    dict(roaster="Stillwick Roasters", coffee_name="Blue Door Natural", origin_country="Kenya", region="Nyeri", producer="Bluegate growers' cooperative",
         process="Natural", varietal=["SL28", "SL34"], elevation_m=1850, roast_level="Light", bag_size_g=250,
         tasting_notes=["blueberry", "cacao nib", "black tea"], ratings=(5, 4, 5, 4, 4), buy_again=False, price=24.0, source="seed:roaster-direct",
         bag="#5872B8", hue=265, days=16, notes="Jammy. Split opinions at the table."),
    dict(roaster="Sable & Rye Coffee", coffee_name="Marigold Honey", origin_country="Costa Rica", region="Tarrazú", producer="Finca Caléndula",
         process="Honey", varietal=["Catuaí"], elevation_m=1650, roast_level="Medium light", bag_size_g=340,
         tasting_notes=["apricot", "caramel", "black tea"], ratings=(4, 4, 4, 3, 3), buy_again=False, price=18.0, source="seed:gift",
         bag="#E8B04A", hue=80, days=24, notes=None),
    dict(roaster="Larkmoor Coffee", coffee_name="Hayloft Espresso", origin_country="Brazil", region="Cerrado Mineiro", producer=None,
         process="Pulped natural", varietal=["Mundo Novo", "Yellow Bourbon"], elevation_m=1100, roast_level="Medium dark", bag_size_g=454,
         tasting_notes=["cherry", "dark chocolate", "almond"], ratings=(4, 4, 2, 5, 4), buy_again=True, price=17.0, source="seed:roaster-direct",
         bag="#C4483E", hue=25, days=33, notes="Pulls well at 1:2 in 28 s."),
    dict(roaster="Northwind Roasters", coffee_name="Morning Fog Blend", origin_country="Guatemala", region="Huehuetenango", producer=None,
         process="Washed", varietal=["Bourbon", "Typica"], elevation_m=1600, roast_level="Medium", bag_size_g=340,
         tasting_notes=["almond", "milk chocolate", "orange"], ratings=None, buy_again=False, price=16.0, source="seed:local-café",
         bag="#8A7F76", hue=60, days=1, notes=None),
]

# --- bag pictures ------------------------------------------------------------------------------
def hexrgb(h): return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))

def draw_bag(c, side, seed):
    rnd = random.Random(seed)
    W, H = 1200, 1600
    # A tabletop: warm linen with a soft vignette.
    base = (238 - rnd.randint(0, 12), 228 - rnd.randint(0, 12), 212 - rnd.randint(0, 10))
    img = Image.new("RGB", (W, H), base)
    d = ImageDraw.Draw(img)
    for y in range(0, H, 6):
        shade = rnd.randint(-4, 4)
        d.line([(0, y), (W, y)], fill=tuple(max(0, min(255, v + shade)) for v in base), width=3)
    body = hexrgb(c["bag"]); top = tuple(int(v * 0.72) for v in body)
    # Shadow
    sh = Image.new("L", (W, H), 0); ImageDraw.Draw(sh).rounded_rectangle((250, 330, 990, 1490), 60, fill=110)
    sh = sh.filter(ImageFilter.GaussianBlur(40))
    img.paste((70, 50, 35), (0, 0), sh)
    d = ImageDraw.Draw(img)
    # Bag: the site's silhouette (a folded top band, a slightly tapered body, rounded base).
    d.polygon([(330, 200), (870, 200), (920, 330), (280, 330)], fill=top)
    d.rounded_rectangle((280, 330, 920, 1440), 50, fill=body)
    d.polygon([(280, 330), (920, 330), (912, 400), (288, 400)], fill=tuple(int(v * 0.9) for v in body))
    for x in (360, 840):  # side gussets
        d.line([(x, 410), (x, 1420)], fill=tuple(int(v * 0.88) for v in body), width=6)
    ink = (52, 34, 24)
    serif = lambda s: font("DejaVuSerif-Bold.ttf", s)
    sans = lambda s: font("Lato-Regular.ttf", s)
    sansb = lambda s: font("Lato-Bold.ttf", s)
    d.rounded_rectangle((380, 520, 820, 1260), 36, fill=(255, 246, 232))
    def centered(y, text, f, fill=ink):
        w = d.textlength(text, font=f); d.text(((W - w) / 2, y), text, font=f, fill=fill)
    if side == "front":
        centered(570, c["roaster"].upper(), sansb(34))
        # bean mark
        d.ellipse((560, 640, 640, 750), fill=(122, 74, 46)); d.arc((575, 640, 640, 760), 110, 250, fill=(62, 34, 21), width=8)
        words = c["coffee_name"].split()
        k = (len(words) + 1) // 2
        lines = [" ".join(words[:k]), " ".join(words[k:])] if len(words) > 1 and len(c["coffee_name"]) > 11 else [c["coffee_name"]]
        y = 790
        for ln in lines:
            f = serif(58 if len(ln) < 12 else 50); centered(y, ln, f); y += 74
        centered(y + 20, c["origin_country"].upper(), sansb(30), (110, 80, 60))
        centered(y + 70, c["process"].lower(), sans(30), (110, 80, 60))
        centered(1180, f"{c['bag_size_g']} g", sans(26), (110, 80, 60))
    else:
        centered(560, c["roaster"], sansb(30))
        rows = [("ORIGIN", f"{c['region']}, {c['origin_country']}"), ("PROCESS", c["process"]),
                ("VARIETY", ", ".join(c["varietal"])), ("ELEVATION", f"{c['elevation_m']} m"),
                ("ROAST", c["roast_level"]), ("TASTES LIKE", ", ".join(c["tasting_notes"]))]
        if c.get("producer"): rows.insert(1, ("PRODUCER", c["producer"]))
        y = 640
        for k, v in rows:
            d.text((420, y), k, font=sansb(22), fill=(130, 96, 70))
            vv = v if d.textlength(v, font=sans(30)) < 380 else v[:24] + "…"
            d.text((420, y + 28), vv, font=sans(30), fill=ink)
            y += 82
    img = img.filter(ImageFilter.GaussianBlur(0.6))
    return img

def jpg(img, q=88):
    b = io.BytesIO(); img.save(b, "JPEG", quality=q); return b.getvalue()

# --- database --------------------------------------------------------------------------------
def build_db(path, rows, photos, sources):
    schema = json.load(open(SCHEMA))["database"]
    con = sqlite3.connect(path)
    for e in schema["entities"]:
        con.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for ix in e.get("indices", []):
            con.execute(ix["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    for q in schema["setupQueries"]:
        con.execute(q)
    con.execute(f"PRAGMA user_version = {schema['version']}")
    def ins(table, d):
        con.execute(f"INSERT INTO {table} ({','.join(d)}) VALUES ({','.join('?' * len(d))})", list(d.values()))
    for s in sources: ins("source", s)
    for r in rows: ins("coffee", r)
    for p in photos: ins("coffee_photo", p)
    con.commit(); con.close()

def main(out_zip, preview_dir=None):
    tmp = out_zip + ".dir"; os.makedirs(tmp, exist_ok=True)
    seeds = ["Roaster direct", "Local café", "Subscription", "Gift"]
    sources = [dict(id="seed:" + s.lower().replace(" ", "-"), name=s, is_seed=1, created_at=NOW - 60 * DAY) for s in seeds]
    rows, photos, files = [], [], {}
    for i, c in enumerate(COFFEES):
        cid = str(uuid.UUID(int=random.Random(f"coffee{i}").getrandbits(128), version=4))
        t = NOW - c["days"] * DAY
        pids = []
        for j, side in enumerate(["front", "back"]):
            pid = str(uuid.UUID(int=random.Random(f"photo{i}{j}").getrandbits(128), version=4))
            img = draw_bag(c, side, i * 10 + j)
            files[f"photos/{cid}/{pid}.jpg"] = jpg(img)
            p = dict(id=pid, coffee_id=cid, role="FRONT" if side == "front" else "BACK", position=j,
                     original=f"photos/{cid}/{pid}.jpg", square=None, thumb=None, taken_at=t, added_at=t)
            if side == "front":
                sq = img.crop((100, 200, 1100, 1200)).resize((1024, 1024), Image.LANCZOS)
                files[f"photos/{cid}/{pid}.square.jpg"] = jpg(sq, 90)
                files[f"photos/{cid}/{pid}.thumb.jpg"] = jpg(sq.resize((256, 256), Image.LANCZOS), 85)
                p["square"] = f"photos/{cid}/{pid}.square.jpg"; p["thumb"] = f"photos/{cid}/{pid}.thumb.jpg"
                if preview_dir: img.save(os.path.join(preview_dir, f"bag{i}.png"))
            photos.append(p); pids.append(pid)
        r = c["ratings"]
        row = dict(id=cid, created_at=t, updated_at=t + DAY, logged_at=t, state="RATED" if r else "LOGGED",
                   roaster=c["roaster"], coffee_name=c["coffee_name"], origin_country=c["origin_country"], region=c["region"],
                   producer=c["producer"], process=c["process"], varietal=json.dumps(c["varietal"]), elevation_m=c["elevation_m"],
                   roast_level=c["roast_level"], roast_date=None, bag_size_g=c["bag_size_g"], tasting_notes=json.dumps(c["tasting_notes"]),
                   bag_description=None, seller_description=None, notes=c["notes"],
                   aroma=r[0] if r else None, flavor=r[1] if r else None, acidity=r[2] if r else None, body=r[3] if r else None, finish=r[4] if r else None,
                   overall=(r[0] + r[1] + r[4]) / 3 if r else None, buy_again=1 if c["buy_again"] else 0,
                   rated_at=t + DAY if r else None, last_tasted_at=t + DAY if r else None,
                   hero_photo_id=pids[0], palette_fallback=0, hero_segmented=1, hero_attempts=1,
                   extraction_source="NANO", extraction_meta="nano: read from 2 photos", extraction_attempts=1,
                   ocr_text=f"{c['roaster'].upper()} {c['coffee_name']} {c['origin_country']} {c['process']}",
                   next_attempt_at=None, extraction_trail=None, roaster_url=None, product_url=None, roaster_city=None,
                   enrichment_status=None, source_id=c["source"], price_usd=c["price"], remind_at=None, second_look_at=None,
                   second_look_done=1 if r else 0, user_edited="[]", **tints(c["hue"]))
        rows.append(row)
    dbp = os.path.join(tmp, "roastbook.db")
    if os.path.exists(dbp): os.remove(dbp)
    build_db(dbp, rows, photos, sources)
    files = {"roastbook.db": open(dbp, "rb").read(), **files}
    manifest = dict(format=1, schemaVersion=4, appVersion="0.2.0", createdAt=NOW, coffees=len(rows), photos=len(photos),
                    files=[dict(path=k, sha256=hashlib.sha256(v).hexdigest(), size=len(v)) for k, v in files.items()])
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as z:
        for k, v in files.items(): z.writestr(k, v)
        z.writestr("manifest.json", json.dumps(manifest))
    print(out_zip, len(rows), "coffees", len(photos), "photos")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
