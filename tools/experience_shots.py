#!/usr/bin/env python3
"""Screenshots of every Home layout, from shared/experiences/harness.html (made-up data, no server), in headless
Chrome at 1920 x 1080: the phone panel's previews (shared/experiences/previews/<id>.jpg, 640 x 360) and, with
--full, full-size PNGs for the docs.

    python3 tools/experience_shots.py               # the previews
    python3 tools/experience_shots.py --full DIR    # also 1920 x 1080 PNGs in DIR

Needs Google Chrome or Chromium (CHROME=/path to choose) and, for the JPEGs, macOS's sips or Pillow.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXP = ROOT / "shared" / "experiences"


def chrome() -> str:
    for c in [os.environ.get("CHROME", ""), "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
              shutil.which("google-chrome") or "", shutil.which("chromium") or "", shutil.which("chromium-browser") or ""]:
        if c and Path(c).exists():
            return c
    sys.exit("Chrome not found (set CHROME=/path/to/chrome)")


def ids() -> list:
    src = (EXP / "catalog.js").read_text(encoding="utf-8")
    return [i for i in re.findall(r'"id": "([a-z]+)"', src[src.index("/*JSON*/"):src.index("/*END*/")]) if i != "classic"]


def shot(ch: str, eid: str, out: Path, tmp: Path):
    subprocess.run([ch, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--virtual-time-budget=3000",
                    "--window-size=1920,1080", f"--user-data-dir={tmp / eid}", f"--screenshot={out}",
                    (EXP / "harness.html").as_uri() + f"?layout={eid}"], check=True, capture_output=True, timeout=60)


def to_jpeg(png: Path, jpg: Path):
    if shutil.which("sips"):
        subprocess.run(["sips", "-Z", "640", "-s", "format", "jpeg", "-s", "formatOptions", "72", str(png), "--out", str(jpg)],
                       check=True, capture_output=True)
        return
    from PIL import Image   # elsewhere
    Image.open(png).convert("RGB").resize((640, 360)).save(jpg, quality=72)


def main():
    full = Path(sys.argv[sys.argv.index("--full") + 1]) if "--full" in sys.argv else None
    ch = chrome()
    (EXP / "previews").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        for eid in ids():
            png = tmp / f"{eid}.png"
            shot(ch, eid, png, tmp)
            to_jpeg(png, EXP / "previews" / f"{eid}.jpg")
            if full:
                full.mkdir(parents=True, exist_ok=True)
                shutil.copy(png, full / f"{eid}.png")
            print(eid)
    classic = ROOT / "docs" / "screens" / "chromecast-home.jpg"   # Classic: the real one
    if classic.exists():
        to_jpeg(classic, EXP / "previews" / "classic.jpg")


if __name__ == "__main__":
    main()
