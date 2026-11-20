"""Add-ons for the home screens: widgets, content rows, full-screen pages and quick actions.

An add-on is a folder with an addon.json (what it is, its settings) and, for everything but quick actions, an
addon.py with a fetch(settings, ctx) function that returns its data. Built-in ones are in addons/ (in git); your own
go in data/addons/ (not in git). See addons/README.md for the format.

The helper runs fetch() itself, in the background, every refresh_minutes, and keeps the last result; the launchers
only ever read that. A broken add-on shows its error in the admin panel and never stops the others.
"""
import html
import importlib.util
import json
import re
import threading
import time
import traceback
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

TYPES = ("widget", "row", "screen", "action", "icons")
ICON_FILE = re.compile(r"^[A-Za-z0-9_.-]{1,80}\.(png|jpg|jpeg|webp|svg)$")
ICON_TYPES = {"png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "webp": "image/webp", "svg": "image/svg+xml"}
ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,40}$")
UA = {"User-Agent": "cleanyourtv-home-launcher/1.0 (personal TV home screen)"}


class Ctx:
    """What an add-on's fetch() gets besides its settings: small, safe helpers."""

    def __init__(self, addon_id: str, log, place: dict, files_url):
        self.id, self._log, self.place, self._files_url = addon_id, log, place, files_url

    def log(self, msg: str):
        self._log(f"add-on {self.id}: {msg}")

    @staticmethod
    def get(url: str, timeout: float = 20) -> bytes:
        req = urllib.request.Request(url, headers=UA)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read()

    def json(self, url: str, timeout: float = 20):
        return json.loads(self.get(url, timeout))

    def rss(self, url: str, limit: int = 20) -> list:
        """Items of an RSS or Atom feed: title, link, summary (plain text), image, published, id."""
        root = ET.fromstring(self.get(url))
        ns = {"a": "http://www.w3.org/2005/Atom", "media": "http://search.yahoo.com/mrss/",
              "yt": "http://www.youtube.com/xml/schemas/2015"}
        out = []
        items = root.findall(".//item") or root.findall("a:entry", ns)
        for it in items[:limit]:
            def text(tag):
                el = it.find(tag, ns)
                return (el.text or "").strip() if el is not None and el.text else ""
            link = text("link")
            if not link:
                el = it.find("a:link", ns)
                link = el.get("href", "") if el is not None else ""
            image = ""
            for tag in ("media:thumbnail", "media:content", "media:group/media:thumbnail", "enclosure"):
                el = it.find(tag, ns)
                if el is not None and el.get("url"):
                    image = el.get("url")
                    break
            summary = text("description") or text("media:group/media:description") or text("a:summary")
            out.append({"title": html.unescape(text("title") or text("a:title")), "link": link,
                        "summary": html.unescape(re.sub(r"<[^>]+>", " ", summary)).strip()[:400], "image": image,
                        "published": text("pubDate") or text("a:published"), "id": text("yt:videoId") or text("guid")})
        return out

    def file_url(self, name: str) -> str:
        """The address of a file the add-on serves itself (from files() in its addon.py)."""
        return self._files_url(self.id, name)


class Addon:
    def __init__(self, folder: Path, builtin: bool):
        self.folder, self.builtin = folder, builtin
        self.meta = json.loads((folder / "addon.json").read_text(encoding="utf-8"))
        self.id = str(self.meta.get("id") or folder.name)
        if not ID.match(self.id) or self.meta.get("type") not in TYPES:
            raise ValueError("needs an id (a-z, 0-9, -) and a type: " + ", ".join(TYPES))
        self.type = self.meta["type"]
        self.module = None
        if (folder / "addon.py").exists():
            spec = importlib.util.spec_from_file_location(f"cyt_addon_{self.id.replace('-', '_')}", folder / "addon.py")
            self.module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(self.module)
        elif self.type not in ("action", "icons") and not self.meta.get("slot"):
            raise ValueError("addon.py with a fetch(settings, ctx) function is missing")
        self.slot = self.meta.get("slot", "")   # "quote" or "weather": drawn by the launchers themselves
        self.result, self.error, self.at = None, "", 0.0

    def defaults(self) -> dict:
        return {s["key"]: s.get("default") for s in self.meta.get("settings", []) if "key" in s}

    def public(self) -> dict:
        m = self.meta
        return {"id": self.id, "name": m.get("name", self.id), "type": self.type, "description": m.get("description", ""),
                "emoji": m.get("emoji", "✨"), "color": m.get("color", "#3a3a44"), "settings": m.get("settings", []),
                "version": m.get("version", ""), "builtin": self.builtin, "refresh_minutes": m.get("refresh_minutes", 30),
                "slot": m.get("slot", "")}


class Addons:
    def __init__(self, dirs: list, log, get_config, place, files_url):
        self.dirs, self.log, self.get_config, self.place, self.files_url = dirs, log, get_config, place, files_url
        self.items: dict[str, Addon] = {}
        self.broken: dict[str, str] = {}   # folder name -> why it didn't load
        self.lock = threading.Lock()
        self.load()

    def load(self):
        items, broken = {}, {}
        for d, builtin in self.dirs:
            if not d.exists():
                continue
            for folder in sorted(p for p in d.iterdir() if p.is_dir() and (p / "addon.json").exists()):
                try:
                    a = Addon(folder, builtin)
                    if a.id in items and builtin:
                        continue   # your own copy (data/addons) wins over the built-in one
                    items[a.id] = a
                except Exception as e:
                    broken[folder.name] = str(e)
                    self.log(f"add-on {folder.name} not loaded: {e}")
        with self.lock:
            old = self.items
            for a in items.values():   # keep what was fetched before a reload
                if a.id in old:
                    a.result, a.error, a.at = old[a.id].result, old[a.id].error, old[a.id].at
            self.items, self.broken = items, broken

    # ---------- settings (config.json "addons": {"order": [...], "<id>": {"enabled": bool, "settings": {...}}}) ----------
    def conf(self) -> dict:
        return self.get_config().get("addons") or {}

    def enabled(self) -> list:
        """The enabled add-ons, in the order chosen in the admin panel."""
        c = self.conf()
        order = [i for i in c.get("order", []) if i in self.items] + sorted(i for i in self.items if i not in c.get("order", []))
        return [self.items[i] for i in order if (c.get(i) or {}).get("enabled")]

    def settings_of(self, a: Addon) -> dict:
        s = a.defaults()
        s.update(((self.conf().get(a.id) or {}).get("settings")) or {})
        return s

    # ---------- fetching ----------
    def refresh(self, a: Addon, force: bool = False):
        if not a.module or not hasattr(a.module, "fetch"):
            return
        every = max(1, int(a.meta.get("refresh_minutes", 30))) * 60
        if not force and a.result is not None and time.time() - a.at < every:
            return
        if not force and a.error and time.time() - a.at < 300:   # failed: try again in 5 minutes
            return
        try:
            data = a.module.fetch(self.settings_of(a), Ctx(a.id, self.log, self.place(), self.files_url))
            a.result, a.error = data, ""
        except Exception as e:
            a.error = f"{type(e).__name__}: {e}"
            self.log(f"add-on {a.id}: {a.error}")
            if self.get_config().get("addons_debug"):
                self.log(traceback.format_exc())
        a.at = time.time()

    def loop(self):
        """Background refresher: every minute, whatever enabled add-on is due."""
        while True:
            for a in self.enabled():
                self.refresh(a)
            time.sleep(60)

    # ---------- what the launchers get ----------
    def home(self) -> dict:
        widgets, rows, tiles = [], [], []
        for a in self.enabled():
            p = a.public()
            if a.slot:
                continue
            if a.type == "widget" and a.result:
                widgets.append(dict(a.result, id=a.id, emoji=p["emoji"], color=p["color"]))
            elif a.type == "row" and a.result and a.result.get("items"):
                rows.append({"id": a.id, "title": a.result.get("title") or p["name"], "items": a.result["items"][:20]})
            elif a.type in ("screen", "action"):
                tiles.append({"id": "addon:" + a.id, "name": p["name"], "emoji": p["emoji"], "color": p["color"],
                              "kind": a.type})
        secs = self.conf().get("widget_seconds", 10)
        return {"widgets": widgets, "rows": rows, "tiles": tiles, "widget_seconds": secs if isinstance(secs, int) else 10,
                "icon_theme": self.icon_theme()}

    def screen(self, addon_id: str) -> dict | None:
        a = self.items.get(addon_id)
        if not a or a.type != "screen":
            return None
        self.refresh(a, force=time.time() - a.at > 120)   # a page someone opens now: fresh unless it just was
        return dict(a.result or {}, id=a.id, error=a.error)

    # ---------- icon themes: the first enabled "icons" add-on restyles the apps' icons in the Home layouts ----------
    @staticmethod
    def icon_style(st) -> dict:
        """A theme's "style", checked (shared/experiences/engine.js and NativeHome.kt draw it)."""
        st = st if isinstance(st, dict) else {}
        color = lambda v: v if isinstance(v, str) and re.match(r"^#[0-9A-Fa-f]{6}$", v) else ""
        bg = st.get("background")
        try:
            pad = max(0.0, min(0.4, float(st.get("padding", 0.18))))
        except (TypeError, ValueError):
            pad = 0.18
        return {"shape": st.get("shape") if st.get("shape") in ("round", "squircle", "square", "keep") else "squircle",
                "background": bg if bg in ("brand", "none") else (color(bg) or "brand"),
                "monochrome": bool(st.get("monochrome")), "tint": color(st.get("tint")), "padding": round(pad, 3)}

    def icon_theme(self) -> dict | None:
        for a in self.enabled():
            if a.type != "icons":
                continue
            icons = {}
            for key, name in (a.meta.get("icons") or {}).items():
                if isinstance(name, str) and ICON_FILE.match(name) and (a.folder / "icons" / name).is_file():
                    icons[str(key).lower()] = self.files_url(a.id, name)
            return {"id": a.id, "name": a.meta.get("name", a.id), "style": self.icon_style(a.meta.get("style")), "icons": icons}
        return None

    def file(self, addon_id: str, name: str) -> tuple[bytes, str] | None:
        a = self.items.get(addon_id)
        if a and a.type == "icons":   # an icon theme's pictures: from its icons/ folder, nothing else
            f = a.folder / "icons" / name
            return (f.read_bytes(), ICON_TYPES[name.rsplit(".", 1)[1].lower()]) if ICON_FILE.match(name) and f.is_file() else None
        if not a or not a.module or not hasattr(a.module, "file"):
            return None
        return a.module.file(name, self.settings_of(a))

    def steps(self, addon_id: str) -> list | None:
        a = self.items.get(addon_id)
        if not a or a.type != "action":
            return None
        return list(a.meta.get("steps", []))

    def status(self) -> list:
        c = self.conf()
        out = []
        for a in self.items.values():
            p = a.public()
            p.update(enabled=bool((c.get(a.id) or {}).get("enabled")), values=self.settings_of(a), error=a.error,
                     updated=int(a.at) if a.at else 0, has_data=a.result is not None)
            out.append(p)
        order = c.get("order", [])
        out.sort(key=lambda x: (order.index(x["id"]) if x["id"] in order else len(order), x["name"].lower()))
        return out
