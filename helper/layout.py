"""The Home layout: where the clock, quote, weather, widgets and app name sit above the dock, and how big the
dock's and the rows' tiles are.

A grid of 12 columns by 6 rows covers the screen above the dock, inside the edges some TVs cut off. Each block has
a few allowed sizes (so its text always fits) and can be hidden; the top right (columns 7-11, rows 0-1) is kept for
the buttons and the photo credit. Saved in config.json as "home_layout"; the Chromecast and the TV draw it (the TV keeps its
own dock), the admin panel's Chromecast tab edits it. The same presets and sizes are used by both, from here.

There are three widget blocks. Each shows the widget add-ons picked for it ("addons"; none picked: every widget no
other block has picked); a four-row one shows two cards stacked.
"""
import re
COLS, ROWS = 12, 6
RESERVED = {"x": 7, "y": 0, "w": 5, "h": 2}   # the buttons and the photo credit

# The clock's look: one of these, or a different one each day from a chosen few ("rotate"); "weather" puts the
# temperature on the clock's date line.
CLOCK_STYLES = [["classic", "Classic"], ["bold", "Bold"], ["stacked", "Stacked"], ["tiles", "Tiles"], ["analog", "Analog"],
                ["words", "Words"]]
WIDGET_SIZES = [[4, 2, "Normal"], [5, 2, "Wide"], [4, 4, "Two stacked"], [5, 4, "Two stacked, wide"]]
WIDGET_BLOCKS = ("widgets", "widgets2", "widgets3")
TILE_SIZES = {"big": ["Big", 4], "normal": ["Normal", 5], "small": ["Small", 6]}   # tiles across the screen

BLOCKS = {
    "clock":   {"name": "Clock", "emoji": "🕒", "sizes": [[6, 2, "Normal"], [3, 1, "Small"], [7, 3, "Big"]]},
    "quote":   {"name": "Quote", "emoji": "❝", "sizes": [[8, 2, "Wide"], [6, 2, "Normal"], [5, 2, "Narrow"]]},
    "weather": {"name": "Weather", "emoji": "⛅", "sizes": [[3, 2, "Normal"], [2, 1, "Small"]]},
    "widgets": {"name": "Widgets", "emoji": "🧩", "sizes": WIDGET_SIZES},
    "widgets2": {"name": "Widgets 2", "emoji": "🧩", "sizes": WIDGET_SIZES},
    "widgets3": {"name": "Widgets 3", "emoji": "🧩", "sizes": WIDGET_SIZES},
    "hero":    {"name": "App name", "emoji": "🔤", "sizes": [[7, 2, "Normal"], [5, 2, "Narrow"]]},
    "nowplaying": {"name": "Now playing", "emoji": "🎵", "sizes": [[5, 2, "Normal"], [4, 1, "Small"], [6, 2, "Wide"]]},
}


def _b(x, y, w, h, on=True):
    return {"x": x, "y": y, "w": w, "h": h, "on": on}


PRESETS = {
    "classic": {"name": "Classic", "blocks": {
        "clock": _b(0, 0, 6, 2), "quote": _b(0, 2, 8, 2), "weather": _b(9, 2, 3, 2),
        "hero": _b(0, 4, 7, 2), "widgets": _b(8, 4, 4, 2)}},
    "widgets-left": {"name": "Widgets left", "blocks": {
        "clock": _b(0, 0, 6, 2), "widgets": _b(0, 2, 5, 2), "weather": _b(9, 2, 3, 2),
        "quote": _b(0, 4, 6, 2), "hero": _b(7, 4, 5, 2)}},
    "big-clock": {"name": "Big clock", "blocks": {
        "clock": _b(0, 1, 7, 3), "weather": _b(9, 2, 3, 2), "widgets": _b(8, 4, 4, 2),
        "quote": _b(0, 4, 6, 2, False), "hero": _b(0, 4, 7, 2, False)}},
    "dashboard": {"name": "Dashboard", "blocks": {
        "clock": _b(0, 0, 3, 1), "weather": _b(3, 0, 2, 1), "widgets": _b(0, 2, 5, 4), "widgets2": _b(7, 2, 5, 4),
        "quote": _b(0, 2, 8, 2, False), "hero": _b(0, 4, 7, 2, False)}},
    "photo-frame": {"name": "Photo frame", "blocks": {
        "clock": _b(0, 0, 3, 1), "weather": _b(3, 0, 2, 1), "quote": _b(0, 2, 8, 2, False),
        "widgets": _b(8, 4, 4, 2, False), "hero": _b(0, 4, 7, 2, False)}},
}
_SPARE = {"widgets2": _b(0, 4, 4, 2, False), "widgets3": _b(4, 4, 4, 2, False),
          "nowplaying": _b(0, 4, 5, 2, False)}   # hidden until shown
for _p in PRESETS.values():
    for _k, _v in _SPARE.items():
        _p["blocks"].setdefault(_k, dict(_v))
    _p.setdefault("dock", "big")
    _p.setdefault("rows", "big")
    _p.setdefault("dock_full", False)


def spec() -> dict:
    """Everything the admin panel's Layout tab needs to draw and check a layout."""
    return {"cols": COLS, "rows": ROWS, "reserved": RESERVED, "blocks": BLOCKS, "presets": PRESETS,
            "widget_blocks": list(WIDGET_BLOCKS), "tile_sizes": TILE_SIZES, "clock_styles": CLOCK_STYLES}


def dock_max(lay) -> int:
    """How many apps the Chromecast's dock holds with this layout's dock tiles."""
    size = (lay or {}).get("dock") if isinstance(lay, dict) else None
    return TILE_SIZES.get(size, TILE_SIZES["big"])[1]


def _overlap(a, b) -> bool:
    return a["x"] < b["x"] + b["w"] and b["x"] < a["x"] + a["w"] and a["y"] < b["y"] + b["h"] and b["y"] < a["y"] + a["h"]


def validate(v) -> dict:
    """A layout from the admin panel, checked: known blocks, allowed sizes, inside the grid, no overlaps."""
    if not isinstance(v, dict) or not isinstance(v.get("blocks"), dict):
        raise ValueError("bad layout")
    out = {}
    for bid, b in v["blocks"].items():
        if bid not in BLOCKS:
            raise ValueError(f"unknown block {bid}")
        x, y, w, h, on = int(b["x"]), int(b["y"]), int(b["w"]), int(b["h"]), bool(b.get("on", True))
        if [w, h] not in [s[:2] for s in BLOCKS[bid]["sizes"]]:
            raise ValueError(f"{BLOCKS[bid]['name']}: that size isn't one of its sizes")
        if not (0 <= x and x + w <= COLS and 0 <= y and y + h <= ROWS):
            raise ValueError(f"{BLOCKS[bid]['name']} is outside the grid")
        out[bid] = {"x": x, "y": y, "w": w, "h": h, "on": on}
        if bid == "clock":
            names = [c[0] for c in CLOCK_STYLES]
            style = str(b.get("style") or "classic")
            out[bid]["style"] = style if style in names else "classic"
            rot = b.get("rotate") or []
            if not isinstance(rot, list):
                raise ValueError("bad clock rotation")
            out[bid]["rotate"] = [r for r in names if r in rot]
            out[bid]["weather"] = bool(b.get("weather"))
        if bid in WIDGET_BLOCKS:
            picks = b.get("addons") or []
            if not isinstance(picks, list):
                raise ValueError("bad add-on list")
            out[bid]["addons"] = [str(i) for i in picks[:20] if re.match(r"^[a-z0-9][a-z0-9-]{0,40}$", str(i))]
    shown = [(k, b) for k, b in out.items() if b["on"]]
    for k, b in shown:
        if _overlap(b, RESERVED):
            raise ValueError(f"{BLOCKS[k]['name']} is over the buttons' corner")
    for i, (k1, b1) in enumerate(shown):
        for k2, b2 in shown[i + 1:]:
            if _overlap(b1, b2):
                raise ValueError(f"{BLOCKS[k1]['name']} and {BLOCKS[k2]['name']} overlap")
    for bid in BLOCKS:   # a block the panel didn't send keeps its Classic place, hidden
        out.setdefault(bid, dict(PRESETS["classic"]["blocks"][bid], on=False))
    sizes = {}
    for k in ("dock", "rows"):
        sizes[k] = v.get(k) or "big"
        if sizes[k] not in TILE_SIZES:
            raise ValueError(f"bad {k} size")
    return {"blocks": out, "preset": str(v.get("preset", ""))[:30], **sizes, "dock_full": bool(v.get("dock_full"))}
