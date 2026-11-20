#!/usr/bin/env python3
"""The Home layouts (shared/experiences), without a server:

1. The catalog is valid, and every layout in it is drawn by both renderers: the TV's (layouts.js) and the
   Chromecast's native one (android/.../NativeLayouts.kt). A layout one of them lacks fails here, not on a screen.
2. helper/experience.py keeps what it should and drops what it shouldn't.
3. The TV's engine, in headless Chrome (shared/experiences/harness.html?test=1): every layout draws, takes all six
   keys with exactly one focus on screen, opens things, gets focus back after a reload, and handles empty data,
   offline and reduced motion (about 190 checks). Skipped, with a note, when there is no Chrome (CHROME=… to set).

    python3 tests/experiences_test.py
"""
import html
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "helper"))
import experience  # noqa: E402

fails = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail and not ok else ""))
    if not ok:
        fails.append(name)


# ---------- 1. the catalog, and both renderers implement all of it ----------
C = experience.CATALOG
ids = [e["id"] for e in C["experiences"]]
check("catalog: 15 layouts (Classic, 10 concepts, 4 follow-ups)", len(ids) == 15 and ids[0] == "classic", str(ids))
check("catalog: ids are unique", len(set(ids)) == len(ids))
for e in C["experiences"]:
    if e["id"] == "classic":
        continue
    check(f"catalog {e['id']}: says what all six keys do", all(e["map"].get(k) for k in C["keys"]))
    for o in e.get("options", []):
        if o["type"] == "choice":
            check(f"catalog {e['id']}.{o['key']}: its default is one of its choices", o["def"] in [c[0] for c in o["choices"]])
js = (ROOT / "shared" / "experiences" / "layouts.js").read_text(encoding="utf-8")
kt = (ROOT / "android" / "src" / "com" / "cleanyourtv" / "home" / "NativeLayouts.kt").read_text(encoding="utf-8")
tv = set(re.findall(r"E\.register\(\{\s*id: '([a-z]+)'", js))
make = kt[kt.index("fun make("):kt.index("abstract class Layout")]
cc = set(re.findall(r'"([a-z]+)"', make)) | {"theater"}   # theater is make()'s else
for i in ids[1:]:
    check(f"renderers: {i} is drawn on the TV (layouts.js)", i in tv)
    check(f"renderers: {i} is drawn on the Chromecast (NativeLayouts.kt)", i in cc)

# ---------- 2. validation ----------
v = experience.validate({"tv": "channel", "cc": "gallery", "scenes": ["movie", "BAD ID", "x" * 30],
                         "opts": {"channel": {"order": "apps", "apps": "everything", "junk": 1},
                                  "typography": {"question": "   What   now?   ", "accent": "purple"},
                                  "worlds": {"groups": {"play": ["a", "b c", "com.x.y"], "other": ["z"]}},
                                  "nope": {"x": 1}, "aurora": {"motion": "reduced"}}})
check("validate: devices kept", v["tv"] == "channel" and v["cc"] == "gallery")
check("validate: a choice outside its list is dropped", v["opts"]["channel"] == {"order": "apps"}, str(v["opts"].get("channel")))
check("validate: text is tidied", v["opts"]["typography"] == {"question": "What now?"}, str(v["opts"].get("typography")))
check("validate: groups keep only app ids", v["opts"]["worlds"]["groups"]["play"] == ["a", "com.x.y"], str(v["opts"].get("worlds")))
check("validate: unknown layouts' options dropped", "nope" not in v["opts"])
check("validate: common options (motion) kept", v["opts"]["aurora"] == {"motion": "reduced"})
check("validate: scene ids checked", v["scenes"] == ["movie"], str(v["scenes"]))
try:
    experience.validate({"tv": "no-such"})
    check("validate: an unknown layout is refused", False)
except ValueError:
    check("validate: an unknown layout is refused", True)
check("current: a removed layout becomes Classic, the rest kept",
      experience.current({"home_experience": {"tv": "gone", "cc": "deck", "scenes": ["a"]}}) == {"tv": "classic", "cc": "deck", "opts": {}, "scenes": ["a"], "icon_pack": ""})
check("icon pack: a package name is kept", experience.validate({"icon_pack": "com.example.arcticons"})["icon_pack"] == "com.example.arcticons")
check("icon pack: anything else is dropped", experience.validate({"icon_pack": "../x; rm"})["icon_pack"] == "")
check("icon pack: sent to the Chromecast only", experience.for_screen({"home_experience": {"icon_pack": "a.b"}}, "cc")["icon_pack"] == "a.b"
      and "icon_pack" not in experience.for_screen({"home_experience": {"icon_pack": "a.b"}}, "tv"))
check("current: nothing saved is Classic", experience.current({})["tv"] == "classic")
scenes = [{"id": "a", "name": "A", "icon": "", "steps": ["cc-wake", "wait-5", "vpn-us", "cc-open-com.netflix"]}, {"id": "b", "name": "B", "steps": ["tv-off"]}]
hs = experience.home_scenes({"home_experience": {"scenes": ["a"]}}, scenes, {"com.netflix": "Netflix"})
check("scenes: only the allowed ones, steps in words", [s["id"] for s in hs] == ["a"] and hs[0]["summary"] == "Wake the Chromecast → VPN: US → Open Netflix"
      and "steps" not in hs[0], str(hs))
check("scenes: run only if allowed", experience.scene_allowed({"home_experience": {"scenes": ["a"]}}, "a")
      and not experience.scene_allowed({"home_experience": {"scenes": ["a"]}}, "b"))

# ---------- icon themes: an add-on type anyone can make ----------
import addons  # noqa: E402
ad = addons.Addons([(ROOT / "addons", True)], lambda m: None, lambda: {"addons": {"icons-glass": {"enabled": True}, "icons-mono": {"enabled": True}}},
                   lambda: {}, lambda i, n: f"/api/addons/{i}/file/{n}")
themes = [a for a in ad.items.values() if a.type == "icons"]
check("icon themes: the built-in ones load", len(themes) >= 3 and not ad.broken, str(ad.broken))
check("icon themes: one at a time (the first enabled)", (ad.icon_theme() or {}).get("id") == "icons-glass")
st = addons.Addons.icon_style({"shape": "blob", "background": "red", "padding": 9, "tint": "#abc", "monochrome": 1})
check("icon themes: a bad style is cleaned", st == {"shape": "squircle", "background": "brand", "monochrome": True, "tint": "", "padding": 0.4}, str(st))
check("icon themes: in /api/addons/home", "icon_theme" in ad.home())
check("icon themes: only files from icons/ are served", ad.file("icons-glass", "../addon.json") is None and ad.file("icons-glass", "nope.png") is None)

# ---------- 3. the engine in a browser ----------
chrome = next((c for c in [os.environ.get("CHROME", ""), "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                           shutil.which("google-chrome") or "", shutil.which("chromium") or "", shutil.which("chromium-browser") or ""]
               if c and Path(c).exists()), None)
if not chrome:
    print("SKIP the browser checks: no Chrome found (set CHROME=/path/to/chrome)")
else:
    with tempfile.TemporaryDirectory() as tmp:
        out = subprocess.run([chrome, "--headless=new", "--disable-gpu", "--no-sandbox", "--virtual-time-budget=10000",
                              "--window-size=1920,1080", f"--user-data-dir={tmp}", "--dump-dom",
                              (ROOT / "shared" / "experiences" / "harness.html").as_uri() + "?test=1"],
                             capture_output=True, text=True, timeout=120).stdout
    m = re.search(r'<pre id="results">(.*?)</pre>', out, re.S)
    lines = html.unescape(m.group(1)).splitlines() if m else []
    check("browser: the harness ran", bool(lines), out[:200])
    if lines:
        print("     " + lines[0])
        for line in lines[1:]:
            if line.startswith("FAIL"):
                print("     " + line)
        check("browser: every harness check passed", lines[0].startswith("PASS"), lines[0])

print(f"\n{'FAILED: ' + ', '.join(fails) if fails else 'all passed'}")
sys.exit(1 if fails else 0)
