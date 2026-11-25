#!/usr/bin/env python3
"""Regenerate app/src/main/res/raw/third_party_notices.txt from the release build's dependencies.

Some bundled libraries carry notice files that must travel with the app: LiteRT-LM ships a
THIRD_PARTY_NOTICE.txt for the native code in its AAR, Google Play services and ML Kit ship
third_party_licenses.{json,txt} pairs, and a few jars carry a NOTICE. This script resolves the
release runtime classpath with Gradle, finds those artifacts in the local Gradle cache and writes
one plain-text file that Settings > About > Open-source notices displays.

Run it from the repository root after any dependency change, then commit the result:

    python3 scripts/third_party_notices.py

It fails (non-zero) if an expected source is missing, so a stale or partial file is never written.
"""
import glob
import json
import os
import re
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/main/res/raw/third_party_notices.txt")
CACHE = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")


def resolved_coordinates():
    out = subprocess.run(
        ["./gradlew", "-q", ":app:dependencies", "--configuration", "releaseRuntimeClasspath"],
        cwd=ROOT, check=True, capture_output=True, text=True,
    ).stdout
    coords = set()
    for line in out.splitlines():
        if "(c)" in line:
            continue
        m = re.search(r"--- ([^\s:]+):([^\s:]+):?([^\s]*)(?: -> ([^\s]+))?", line)
        if not m:
            continue
        group, artifact, version, forced = m.groups()
        version = forced or version
        if version:
            coords.add((group, artifact, version))
    if not coords:
        sys.exit("no dependencies resolved")
    return sorted(coords)


def artifact_files(group, artifact, version):
    files = []
    for ext in ("aar", "jar"):
        files += glob.glob(f"{CACHE}/{group}/{artifact}/{version}/*/{artifact}-{version}.{ext}")
    return [f for f in files if not f.endswith("-sources.jar")]


def text(data):
    return data.decode("utf-8", "replace").replace("\r\n", "\n").strip("\n")


def main():
    litert = []
    google = {}
    notices = []
    for group, artifact, version in resolved_coordinates():
        for path in artifact_files(group, artifact, version):
            with zipfile.ZipFile(path) as z:
                names = z.namelist()
                if "THIRD_PARTY_NOTICE.txt" in names:
                    litert.append((f"{artifact} {version}", text(z.read("THIRD_PARTY_NOTICE.txt"))))
                for meta_name in [n for n in names if n.endswith("third_party_licenses.json")]:
                    body = z.read(meta_name.replace(".json", ".txt"))
                    for name, span in json.loads(z.read(meta_name)).items():
                        chunk = text(body[span["start"]:span["start"] + span["length"]])
                        if chunk:
                            google.setdefault(name, chunk)
                for n in names:
                    base = os.path.basename(n)
                    if re.fullmatch(r"NOTICE(\.md|\.txt)?", base):
                        notices.append((f"{group}:{artifact} {version} ({n})", text(z.read(n))))

    if not litert:
        sys.exit("LiteRT-LM THIRD_PARTY_NOTICE.txt not found")
    if not google:
        sys.exit("no Play services / ML Kit third_party_licenses found")

    parts = ["Notices from libraries bundled in Roastbook"]
    for title, body in litert:
        parts.append(f"==== LiteRT-LM ({title}): third-party components ====\n\n{body}")
    parts.append("==== Google Play services and ML Kit: third-party components ====")
    for name in sorted(google, key=str.lower):
        parts.append(f"---- {name} ----\n\n{google[name]}")
    for title, body in notices:
        parts.append(f"==== NOTICE: {title} ====\n\n{body}")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n\n".join(parts) + "\n")
    print(f"wrote {OUT}: {len(litert)} LiteRT-LM notice, {len(google)} Google components, "
          f"{len(notices)} NOTICE files, {os.path.getsize(OUT):,} bytes")


if __name__ == "__main__":
    main()
