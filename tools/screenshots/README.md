# README images

Every image in `docs/images/` is made from invented data: six coffees from fictional roasters
(Larkmoor Coffee, Northwind Roasters, Stillwick Roasters, Sable & Rye Coffee) with bags drawn by a
script. No real journal, photo or account appears in any of them.

| Script | What it does | Needs |
|---|---|---|
| `demo_backup.py <out.zip> [preview-dir]` | Writes a real Roastbook backup zip (format 1, schema 4) holding the demo journal and its drawn bag photos | Python 3, Pillow, the DejaVu Serif and Lato fonts |
| `readme_images.py [name.png ...]` | Rebuilds `docs/images/` (three phone shots, the tablet shot, the hero) from the screenshots in `input/` | Python 3, Pillow |
| `icon_gen.py app/src/main/res <out-dir>` | Writes the launcher icon's vector layers into `res/drawable` and a 512 px icon plus preview sheets into `<out-dir>` | Python 3, Pillow, CairoSVG |

With [uv](https://docs.astral.sh/uv/), no install is needed:

```sh
uv run --no-project --with pillow python tools/screenshots/demo_backup.py build/demo.zip
uv run --no-project --with pillow python tools/screenshots/readme_images.py
uv run --no-project --with pillow --with cairosvg python tools/screenshots/icon_gen.py app/src/main/res build/icon
```

`demo_backup.py` looks for its fonts under `/usr/share/fonts/truetype` (Debian and Ubuntu:
`fonts-dejavu-core`, `fonts-lato`). Set `ROASTBOOK_FONT_DIR` to look somewhere else.

## Taking the screenshots in `input/`

1. Build and install the app on an emulator (a release build is closest to what people run).
2. Push the demo backup to a folder on the emulator (not the Download root), then Settings,
   Restore. Choose a backup folder and make one backup, so the journal does not show the
   "not backed up yet" banner.
3. Put the status bar in demo mode (9:30, full battery, Wi-Fi) and clear notifications.
4. Phone shots at `wm size 1080x1920` and `wm density 420`; the tablet journal at
   `wm size 2560x1440` and `wm density 320`. Reset both afterwards.
5. Save them as `input/phone-journal.png`, `phone-detail.png`, `phone-search.png` and
   `tablet-journal.png`, strip any metadata, and run `readme_images.py`.

The inputs in this folder were taken from a release build of version 0.3.5 this way.
