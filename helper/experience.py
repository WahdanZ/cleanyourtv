"""The Home layouts ("experiences"): which one the TV and the Chromecast show, and their options.

Saved in config.json as "home_experience":
    {"tv": "<layout id>", "cc": "<layout id>",          # "classic" is the Home that was there before
     "opts": {"<layout id>": {"<option>": value, …}},   # per layout, as shared/experiences/catalog.js lists them
     "scenes": ["<scene id>", …]}                      # the scenes the TV pages may start (Scene Stage, Time River)

The list of layouts and their options is shared/experiences/catalog.js (its JSON part), the one copy the screens,
the phone panel and the Chromecast's own server also use. Anything unknown is dropped, never an error that would
lose the rest of a save; a layout that no longer exists is shown as Classic.
"""
import json
import re
from pathlib import Path

_SRC = (Path(__file__).resolve().parent.parent / "shared" / "experiences" / "catalog.js").read_text(encoding="utf-8")
CATALOG = json.loads(_SRC[_SRC.index("/*JSON*/") + 8:_SRC.index("/*END*/")])
IDS = [e["id"] for e in CATALOG["experiences"]]
_BY_ID = {e["id"]: e for e in CATALOG["experiences"]}
_ID = re.compile(r"^[A-Za-z0-9_.:-]{1,120}$")
DEFAULT = {"tv": "classic", "cc": "classic", "opts": {}, "scenes": [], "icon_pack": ""}


def _option(o: dict, v):
    """One option's value, checked; None drops it (the screens then use the default)."""
    t = o["type"]
    if t == "choice":
        return str(v) if str(v) in [c[0] for c in o["choices"]] else None
    if t == "text":
        s = re.sub(r"\s+", " ", str(v or "")).strip()[:int(o.get("max", 40))]
        return s or None
    if t == "app":
        return str(v) if v and _ID.match(str(v)) else ""
    if t == "groups":
        if not isinstance(v, dict):
            return None
        return {g: [str(x) for x in (v.get(g) or [])[:60] if _ID.match(str(x))] for g in ("watch", "play", "listen")}
    return None


def validate(v) -> dict:
    """A home_experience from the phone panel, cleaned."""
    if not isinstance(v, dict):
        raise ValueError("bad home layout choice")
    out = {"tv": "classic", "cc": "classic", "opts": {}, "scenes": [], "icon_pack": ""}
    for dev in ("tv", "cc"):
        if v.get(dev) is not None:
            if v[dev] not in IDS:
                raise ValueError(f"unknown Home layout {v[dev]}")
            out[dev] = v[dev]
    for eid, given in (v.get("opts") or {}).items():
        if eid not in _BY_ID or not isinstance(given, dict):
            continue
        kept = {}
        for o in _BY_ID[eid].get("options", []) + CATALOG["common"]:
            if o["key"] in given and o["type"] != "scenes":
                val = _option(o, given[o["key"]])
                if val is not None:
                    kept[o["key"]] = val
        if kept:
            out["opts"][eid] = kept
    out["scenes"] = [str(s) for s in (v.get("scenes") or [])[:20] if re.match(r"^[a-z0-9]{1,24}$", str(s))]
    # an Android icon pack installed on the Chromecast (its package), or "" for the apps' own icons
    pack = str(v.get("icon_pack") or "")
    out["icon_pack"] = pack if re.match(r"^[A-Za-z0-9_.]{1,120}$", pack) else ""
    return out


def current(cfg: dict) -> dict:
    h = cfg.get("home_experience")
    if not isinstance(h, dict):
        return dict(DEFAULT)
    try:
        return validate(h)
    except ValueError:   # a layout removed since: Classic, the rest kept
        return validate({**h, "tv": h.get("tv") if h.get("tv") in IDS else "classic", "cc": h.get("cc") if h.get("cc") in IDS else "classic"})


def for_screen(cfg: dict, dev: str) -> dict:
    """What a screen gets in /api/state (the TV) or /api/cc (the Chromecast)."""
    h = current(cfg)
    out = {"id": h[dev], "opts": h["opts"], "api": CATALOG["version"]}
    if dev == "cc":
        out["icon_pack"] = h["icon_pack"]
    return out


STEP_WORDS = {"cc-wake": "Wake the Chromecast", "cc-sleep": "Chromecast to sleep", "tv-on": "TV on", "tv-off": "TV off",
              "vpn-off": "VPN off", "sleep-off": "Sleep timer off", "sleep-end": "Sleep at the end"}


def summary(steps: list) -> str:
    """A scene's steps in words, for its card: "Wake the Chromecast → VPN: US → Open Netflix"."""
    out = []
    for st in steps:
        if st.startswith("wait-"):
            continue
        if st in STEP_WORDS:
            out.append(STEP_WORDS[st])
        elif st.startswith("vpn-"):
            out.append("VPN: " + st[4:].upper())
        elif st.startswith("sleep-"):
            out.append(f"Sleep in {st[6:]} min")
        elif "-open-" in st:
            out.append(("Chromecast: " if st.startswith("cc") else "TV: ") + "open an app")
        elif st.startswith("scene-"):
            out.append("Another scene")
        else:
            out.append(st.replace("-", " "))
    return " → ".join(out[:4])


def home_scenes(cfg: dict, scenes: list, names: dict | None = None) -> list:
    """The scenes allowed on Home, for the TV pages: id, name, icon, summary (never the raw steps)."""
    allowed = set(current(cfg)["scenes"])
    out = []
    for sc in scenes:
        if sc.get("id") in allowed:
            words = summary(sc.get("steps") or [])
            if names:   # "Chromecast: open an app" → the app's name, when known
                for st in sc.get("steps") or []:
                    if "-open-" in st and st.split("-open-", 1)[1] in names:
                        words = words.replace(("Chromecast: " if st.startswith("cc") else "TV: ") + "open an app", "Open " + names[st.split("-open-", 1)[1]], 1)
            out.append({"id": sc["id"], "name": sc.get("name", ""), "icon": sc.get("icon", ""), "summary": words})
    return out


def scene_allowed(cfg: dict, sid: str) -> bool:
    return sid in current(cfg)["scenes"]
