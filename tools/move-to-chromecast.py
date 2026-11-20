"""Moves the Chromecast's settings from this computer's helper to the Chromecast itself, then switches its Home over.

After this the Chromecast runs on its own: its Home, the phone panel (http://<Chromecast>:8765/admin, the same
password), the VPN (built into the launcher), scenes, the sleep timer, shortcuts and usage, with this computer off.
The TV still uses this computer's helper. Run it again any time to copy the helper's settings over again.

    .venv/bin/python tools/move-to-chromecast.py            # copy everything and switch Home to the Chromecast
    .venv/bin/python tools/move-to-chromecast.py --back     # Home uses this computer's helper again
    .venv/bin/python tools/move-to-chromecast.py --copy     # copy only, to try the Chromecast's own panel first:
                                                            # Home keeps using this computer's helper

Needs: the launcher installed with its own server (android/build.sh install) and its pointer service on (it is, once
the trackpad was used or after build.sh install). Sends only to the Chromecast's address in config.json, with the key
the helper gave the launcher (data/pointer-token).
"""
import base64
import json
import os
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
CONFIG = json.loads((ROOT / "config.json").read_text(encoding="utf-8"))
ip = (CONFIG.get("chromecast") or {}).get("ip")
ip = ip[0] if isinstance(ip, list) else ip
if not ip:
    sys.exit("No Chromecast in config.json")
TOKEN = (DATA / "pointer-token").read_text().strip()
BASE = os.environ.get("HUB") or f"http://{ip}:8765"   # HUB: another address (tests: an emulator through adb forward)


def post(path: str, body: dict) -> dict:
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "X-Token": TOKEN})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read() or b"{}")


def send(name: str, data: bytes):
    post("/api/hub/file", {"name": name, "data": base64.b64encode(data).decode()})


if "--back" in sys.argv:
    post("/api/hub/mode", {"on": False})
    print("Home uses this computer's helper again (from the next time it opens).")
    sys.exit()

try:
    urllib.request.urlopen(BASE + "/api/admin/hello", timeout=8).read()
except Exception as e:
    sys.exit(f"The Chromecast's own server doesn't answer at {BASE} ({e}). Install the launcher: android/build.sh install")

sent = []
send("config.json", (ROOT / "config.json").read_bytes()); sent.append("settings")
for name in ("admin.json", "scenes.json", "websites.json", "quick-token", "watch-stats.json", "cc-apps.json"):
    f = DATA / name
    if f.exists():
        send(name, f.read_bytes()); sent.append(name)
icons = list((DATA / "cc-icons").glob("*.png")) if (DATA / "cc-icons").exists() else []
for f in icons:
    send("cc-icons/" + f.name, f.read_bytes())
photos = list((DATA / "wallpapers").glob("*.jpg")) if (DATA / "wallpapers").exists() else []
for f in photos:
    send("wallpapers/" + f.name, f.read_bytes())
sent += [f"{len(icons)} app icons", f"{len(photos)} photo files"]

key_file = Path.home() / ".config/tv-vpn/nordlynx.key"
vpn = {"apps": ""}   # every app on the Chromecast but the launcher goes through the VPN when it's on
if key_file.exists():
    vpn["key"] = key_file.read_text().strip()
post("/api/hub/vpn", vpn); sent.append("VPN" + (" (with the NordLynx key)" if "key" in vpn else ""))

if "--copy" in sys.argv:
    print("Copied: " + ", ".join(sent) + ".")
    print(f"Try the Chromecast's own panel: {BASE}/admin (the same password). Home still uses this computer's helper.")
    print("When it works for you: run this again without --copy to switch Home over.")
    sys.exit()
post("/api/hub/mode", {"on": True})
print("Moved: " + ", ".join(sent) + ".")
print(f"The Chromecast now runs on its own. Its panel: {BASE}/admin (the same password).")
print("Home switches over the next time it opens (press Home on the Chromecast's remote).")
