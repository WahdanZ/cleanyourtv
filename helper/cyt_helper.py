"""Clean Your TV for VIDAA: the helper that serves the launcher to the TV and launches apps for it.

A web page on a VIDAA TV may not launch apps itself (VIDAA ignores its platform message and DIAL refuses web
origins), but a paired remote-control client may. This helper is that client: it keeps one MQTT connection to
the TV, using Sidee's pairing (~/.sidee/session.json), and the launcher page asks it over HTTP.

Usage: python helper/cyt_helper.py [path/to/config.json]
"""
from __future__ import annotations

import hashlib
import html
import json
import mimetypes
import os
import random
import re
import shlex
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlsplit

try:
    # Trust the computer's own certificate store, so antivirus that inspects HTTPS (Kaspersky, ESET...) doesn't
    # break channel streams and downloads with "self-signed certificate in certificate chain".
    import truststore
    truststore.inject_into_ssl()
except ImportError:
    pass

ROOT = Path(__file__).resolve().parent.parent
CONFIG_PATH = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "config.json"
if not CONFIG_PATH.exists():
    sys.exit(f"{CONFIG_PATH} is missing: copy config.example.json to config.json and fill it in (see README.md)")
CONFIG = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
# "vidaa": false is a Chromecast-only home: no VIDAA TV, so no Sidee pairing is needed (nor Sidee itself).
VIDAA = CONFIG.get("vidaa", True) is not False
sys.path.insert(0, str((CONFIG_PATH.parent / CONFIG.get("sidee_dir", "../sidee")).resolve()))
try:
    from core import client, protocol  # noqa: E402  (Sidee)
except ImportError:
    if VIDAA:
        raise
    client = protocol = None
sys.path.insert(0, str(Path(__file__).resolve().parent))
import extras  # noqa: E402
import admin  # noqa: E402
import qr  # noqa: E402
import addons  # noqa: E402
import layout  # noqa: E402
import experience  # noqa: E402
import secure  # noqa: E402

API_VERSION = 1                       # shared/API.md: the requests both back ends answer the same way
WEB = ROOT / "web"                    # the TV's own pages (launcher, YouTube, Live TV)
PANEL = ROOT / "shared" / "panel"     # the phone panel, the same page the Chromecast's own server serves
DATA = ROOT / "data"
ICONS = DATA / "icons"
APPS_CACHE = DATA / "apps.json"
HIDDEN_FILE = DATA / "hidden.json"
ORDER_FILE = DATA / "order.json"   # the tile order chosen on the TV (Move in the tile menu)
TAKEOVER_FILE = DATA / "takeover.json"


def takeover_on() -> bool:
    """Whether the launcher takes over from VIDAA's home; switched from the launcher's VIDAA menu, kept on disk."""
    if TAKEOVER_FILE.exists():
        return bool(json.loads(TAKEOVER_FILE.read_text(encoding="utf-8")).get("on", True))
    return CONFIG.get("home_takeover", True)


def set_takeover(on: bool):
    DATA.mkdir(exist_ok=True)
    TAKEOVER_FILE.write_text(json.dumps({"on": on}), encoding="utf-8")
ICON_HOSTS = ("img.vidaahub.com", "noobygains.github.io")
# Remote keys the launcher's buttons may send; nothing else is forwarded.
ALLOWED_KEYS = {"KEY_MENU", "KEY_HOME", "KEY_EXIT", "KEY_RETURNS", "KEY_INPUT", "KEY_SETTINGS"}


LOG_LINES: list = []   # the last 600 lines, for the panel's Logs (Settings > Logs)


def log(msg: str) -> None:
    line = time.strftime("%H:%M:%S ") + msg
    print(line, flush=True)
    LOG_LINES.append(line)
    del LOG_LINES[:-600]


def dlog(msg: str) -> None:
    """Detail for the panel's Logs only (trackpad steps and the like), not the log file."""
    LOG_LINES.append(time.strftime("%H:%M:%S ") + "· " + msg)
    del LOG_LINES[:-600]


YT = extras.YouTube(CONFIG.get("youtube") or {}, ROOT / "data", log)
LIVETV = extras.LiveTV(CONFIG.get("livetv") or {}, ROOT / "data", log)
# Screens this helper serves itself, shown on the launcher like apps. Their ids go in config "dock" like any app's.
PAGES = [{"appId": "cyt-yt", "name": "YouTube", "page": "/yt/", "icon": "/pages/yt.png"},
         {"appId": "cyt-tv", "name": "Live TV", "page": "/livetv/", "icon": "/pages/livetv.png"}]


class TvLink:
    """One long-lived MQTT connection to the TV, reopened when it drops or the token is refreshed."""

    def __init__(self):
        self.lock = threading.RLock()
        self.sess = None
        self.token = None

    def _connect(self):
        session = client._ensure_ready(client.Session.load())
        if self.sess and self.token == session.access_token and self.sess.client.is_connected():
            return self.sess
        self.close()
        for attempt in range(2):
            sess = client._saved_connection(session, None if session.tokenless else session.access_token)
            try:
                rc = sess.start()
            except OSError:
                rc = None
            if rc is None:
                # Nothing answered at the saved address: the router may have given the TV a new one.
                sess.stop()
                if attempt == 0 and rediscover_tv():
                    session = client._ensure_ready(client.Session.load())
                    continue
                raise ConnectionError("the TV is not reachable (is it on?)")
            if rc == 0:
                sess.subscribe([sess.topics["applist_reply"], sess.topics["mobile"] + "ui_service/data/sourcelist"])
                self.sess, self.token = sess, session.access_token
                log("connected to the TV")
                return sess
            sess.stop()
            if attempt == 0 and not session.tokenless:
                log(f"TV refused the token (rc={rc}); refreshing")
                session = client.refresh(session)
        raise ConnectionError("the TV refused the connection: pair again with Sidee")

    def close(self):
        if self.sess:
            self.sess.stop()
            self.sess = None

    def publish(self, path: str, payload: str = ""):
        """path is relative to the ui service topic, e.g. 'actions/launchapp'."""
        with self.lock:
            sess = self._connect()
            del sess.messages[:-50]
            sess.publish(sess.topics["ui"] + path, payload)

    def tv_state(self) -> dict | None:
        """What the TV is doing, e.g. {"statetype": "app", ...}, or "fake_sleep_0" in standby on a TV that keeps its
        network up there (Fast Start). The TV doesn't answer "gettvstate" here, but it sends its state to every new
        subscriber of the broadcast state topic, so subscribing again is the question."""
        with self.lock:
            sess = self._connect()
            del sess.messages[:-50]
            topic = sess.topics["broadcast"] + "ui_service/state"
            since = len(sess.messages)
            sess.client.unsubscribe(topic)
            sess.subscribe([topic])
            raw = sess.wait_for("ui_service/state", 2, since)
        try:
            return json.loads(raw) if raw else None
        except ValueError:
            return None

    def request(self, path: str, payload: str, reply_suffix: str, timeout: float = 8.0):
        with self.lock:
            sess = self._connect()
            since = len(sess.messages)
            sess.publish(sess.topics["ui"] + path, payload)
            return sess.wait_for(reply_suffix, timeout, since)

    def send_key(self, key: str):
        with self.lock:
            sess = self._connect()
            sess.publish(f"/remoteapp/tv/remote_service/{sess.client_id}/actions/sendkey", key)

    def launch(self, app: dict):
        self.publish("actions/launchapp", protocol.launch_payload(app["appId"], app["name"], app["url"]))

    def sources(self) -> list:
        raw = self.request("actions/sourcelist", "", "ui_service/data/sourcelist")
        sources = json.loads(raw) if raw else None
        if not isinstance(sources, list):
            raise ConnectionError("the TV did not send its input list")
        return sources

    def change_source(self, source: dict):
        self.publish("actions/changesource", json.dumps(
            {"sourceid": source["sourceid"], "sourcename": source.get("sourcename", "")}))

    def app_list(self) -> list:
        raw = self.request("actions/applist", "0", "ui_service/data/applist")
        apps = protocol.parse_applist_message(raw) if raw else None
        if not apps:
            raise ConnectionError("the TV did not send its app list")
        return apps


def tv_host() -> str:
    """The TV's address from the pairing, or "" with no TV (Chromecast only) or no pairing yet."""
    if not VIDAA:
        return ""
    try:
        return client.Session.load().host
    except Exception:
        return ""


def rediscover_tv() -> bool:
    """Find the TV again on the network by its name and save its new address in the pairing."""
    name = CONFIG.get("tv_name", "")
    try:
        found = client.discover(timeout=4)
    except Exception as e:
        log(f"discovery failed: {e}")
        return False
    for tv in found:
        if tv.is_vidaa and (not name or tv.friendly_name == name):
            session = client.Session.load()
            if session.host != tv.host:
                log(f"the TV moved to {tv.host}; updating the pairing")
                session.host = tv.host
                session.save()
            return True
    log("the TV was not found on the network")
    return False


TV = TvLink()


class Apps:
    """The TV's own app list, cached on disk so the launcher works while the TV is still waking up."""

    def __init__(self):
        self.lock = threading.Lock()
        self.apps = json.loads(APPS_CACHE.read_text(encoding="utf-8")) if APPS_CACHE.exists() else []
        self.fetched = 0.0

    def refresh(self):
        apps = TV.app_list()
        DATA.mkdir(exist_ok=True)
        APPS_CACHE.write_text(json.dumps(apps, indent=2, ensure_ascii=False), encoding="utf-8")
        with self.lock:
            self.apps, self.fetched = apps, time.time()
        log(f"app list refreshed: {len(apps)} apps")

    def refresh_if_stale(self):
        # Short, so an app uninstalled on the TV leaves the launcher the next time it opens.
        if time.time() - self.fetched > CONFIG.get("app_list_refresh_minutes", 5) * 60:
            try:
                self.refresh()
            except Exception as e:  # the TV may be off; keep the cached list
                log(f"app list refresh failed: {e}")

    def get(self, app_id: str) -> dict | None:
        with self.lock:
            return next((a for a in self.apps if str(a.get("appId")) == app_id), None)

    @staticmethod
    def removed() -> list:
        """Apps removed from Home in the launcher (hold OK on a tile). They stay installed on the TV."""
        return json.loads(HIDDEN_FILE.read_text(encoding="utf-8")) if HIDDEN_FILE.exists() else []

    def set_removed(self, app_id: str, removed: bool):
        ids = [i for i in self.removed() if i != app_id] + ([app_id] if removed else [])
        DATA.mkdir(exist_ok=True)
        HIDDEN_FILE.write_text(json.dumps(ids), encoding="utf-8")

    def restore_all(self):
        HIDDEN_FILE.unlink(missing_ok=True)

    @staticmethod
    def order() -> list:
        return json.loads(ORDER_FILE.read_text(encoding="utf-8")) if ORDER_FILE.exists() else []

    @staticmethod
    def set_order(ids: list | None):
        DATA.mkdir(exist_ok=True)
        if ids:
            ORDER_FILE.write_text(json.dumps([str(i) for i in ids][:200]), encoding="utf-8")
        else:
            ORDER_FILE.unlink(missing_ok=True)

    def for_launcher(self) -> list:
        hidden = set(CONFIG.get("hidden", [])) | set(self.removed())
        with self.lock:
            apps = [a for a in self.apps if str(a.get("appId")) not in hidden and not str(a.get("appId")).startswith("cyt")]
        # The helper's screens come first among the apps that aren't in the dock (the sort below is stable).
        apps = [p for p in PAGES if p["appId"] not in hidden] + apps
        dock = [str(x) for x in CONFIG.get("dock", [])]
        # The order arranged on the TV wins; apps it hasn't seen yet (newly installed) go at the end.
        order = {app_id: i for i, app_id in enumerate(self.order() or dock)}
        apps.sort(key=lambda a: order.get(str(a.get("appId")), len(order)))
        out = []
        for a in apps:
            if a.get("page"):
                out.append(dict(a))
                continue
            name = CONFIG.get("rename", {}).get(str(a["appId"])) or a.get("name", "").replace("▶", "").strip()
            entry = {"appId": str(a["appId"]), "name": name, "icon": f"/icon/{a['appId']}"}
            if a.get("url") == "browser":  # the launcher's Browser screen, which hands its searches to this app
                entry.update(name="Browser", browser=True)
            if str(a["appId"]) in APP_VPN:
                entry["vpn"] = APP_VPN[str(a["appId"])]
            out.append(entry)
        hidden = set(map(str, CONFIG.get("hidden", [])))
        out += [{"appId": "web-" + w["id"], "name": w["name"], "icon": f"/api/web-icon/{w['id']}", "web": True}
                for w in websites() if w["tv"] and "web-" + w["id"] not in hidden]
        return out


APPS = Apps()


class Sources:
    """The TV's inputs for the Inputs panel, asked for once and kept."""

    def __init__(self):
        self.list = []

    def get(self) -> list:
        if not self.list:
            try:
                self.list = [{"id": s["sourceid"], "name": s.get("displayname", s["sourceid"]).replace("▶", "").strip()}
                             for s in TV.sources()]
            except Exception as e:
                log(f"input list failed: {e}")
        return self.list


SOURCES = Sources()


# The launcher's Browser screen searches with Brave and opens the results in the TV Browser: a page opened as an app
# only gets arrow-key focus, but the TV Browser has a pointer for clicking around websites.
BROWSER = CONFIG.get("browser") or {}
# Suggestions as you type come from DuckDuckGo: Brave's feed answers only Brave's pages and turns scripts away (429).
SUGGEST = "https://duckduckgo.com/ac/?type=list&q="


# ---------- websites as apps: a tile on the TV (its browser) and on the Chromecast (its browser) ----------
WEBSITES_FILE = DATA / "websites.json"
WEB_COLORS = ["#2a78d6", "#1baf7a", "#6250d6", "#eb6834", "#e87ba4", "#008300", "#eda100"]


def websites() -> list:
    try:
        v = json.loads(WEBSITES_FILE.read_text(encoding="utf-8"))
        return v if isinstance(v, list) else []
    except (OSError, ValueError):
        return []


def save_websites(v) -> list:
    if not isinstance(v, list) or len(v) > 30:
        raise ValueError("at most 30 websites")
    out, ids = [], set()
    for i, w in enumerate(v):
        if not isinstance(w, dict):
            raise ValueError("bad website")
        name = str(w.get("name", "")).strip()[:40]
        url = str(w.get("url", "")).strip()
        if url and not re.match(r"^[a-z][a-z0-9+.-]*://", url, re.I):
            url = "https://" + url
        if not name or urlsplit(url).scheme not in ("http", "https") or not urlsplit(url).netloc:
            raise ValueError(f"{name or 'A website'}: needs a name and a web address")
        wid = re.sub(r"[^a-z0-9]+", "", str(w.get("id") or name).lower())[:24] or "site"
        base, n = wid, 2
        while wid in ids:
            wid, n = f"{base}{n}", n + 1
        ids.add(wid)
        out.append({"id": wid, "name": name, "url": url, "tv": w.get("tv", True) is not False, "cc": w.get("cc", True) is not False,
                    "color": str(w.get("color") or WEB_COLORS[i % len(WEB_COLORS)])[:9]})
    WEBSITES_FILE.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    return out


def website(wid: str) -> dict | None:
    return next((w for w in websites() if w["id"] == wid), None)


def web_icon_svg(w: dict) -> bytes:
    """A tile for a website: its colour, its first letter and its name (no emoji: the TV's browser may lack the font)."""
    name = html.escape(w["name"][:22])
    letter = html.escape(w["name"][:1].upper())
    host = html.escape(urlsplit(w["url"]).netloc.replace("www.", "")[:28])
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 180" width="320" height="180">'
            f'<rect width="320" height="180" rx="18" fill="{html.escape(w.get("color", "#2a78d6"))}"/>'
            f'<circle cx="62" cy="90" r="38" fill="rgba(255,255,255,.22)"/>'
            f'<text x="62" y="104" font-family="Helvetica,Arial,sans-serif" font-size="40" font-weight="700" fill="#fff" text-anchor="middle">{letter}</text>'
            f'<text x="116" y="88" font-family="Helvetica,Arial,sans-serif" font-size="27" font-weight="700" fill="#fff">{name}</text>'
            f'<text x="116" y="118" font-family="Helvetica,Arial,sans-serif" font-size="17" fill="rgba(255,255,255,.85)">{host}</text></svg>').encode()


def web_tiles_cc() -> list:
    return [{"id": "addon:web-" + w["id"], "name": w["name"], "emoji": "🌐", "color": w["color"], "kind": "action"}
            for w in websites() if w["cc"]]


def cc_web_browser() -> str:
    """The Chromecast's browser for websites: "web_browser" in its settings, else BrowseHere when it's there."""
    if CHROMECAST.get("web_browser"):
        return str(CHROMECAST["web_browser"])
    have = {a["id"] for a in ADMIN.cc_apps().get("apps", [])}
    return next((p for p in ("com.tcl.browser", "com.cloudmosa.puffinTV", "com.android.chrome") if p in have), "")


def browse(url: str):
    if urlsplit(url).scheme not in ("http", "https"):
        raise ValueError("only web addresses can be opened")
    app = next((a for a in APPS.apps if a.get("url") == "browser"), {"appId": "16", "name": "TV Browser"})
    TV.launch(dict(app, url=url))


def suggestions(q: str, region: str = "") -> list:
    """Search suggestions for the Browser screen, which may not ask the feed itself (no CORS)."""
    if not q.strip():
        return []
    region = region or BROWSER.get("region", "")  # a DuckDuckGo region code, such as za-en
    req = urllib.request.Request(SUGGEST + quote(q[:200]) + "&kl=" + quote(region[:10]), headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(req, timeout=3) as r:
            found = json.load(r)[1]
    except Exception:
        return []
    return [s for s in found if isinstance(s, str)][:8]


# Weather for the launcher's hero, from Open-Meteo (free, no key). Fetched here and kept for 30 minutes, so the TV
# never waits on it; if the internet is away, the last reading is served, or nothing, and the page hides the panel.
# Quote and weather are add-ons (addons/quote, addons/weather) drawn by the launchers themselves. A config from
# before add-ons ("quotes", "weather") is read as their settings until the admin panel saves.
_addon_conf = CONFIG.setdefault("addons", {})
_addon_conf.setdefault("quote", {"enabled": CONFIG.get("quotes", True) is not False})
_addon_conf.setdefault("weather", {"enabled": bool(CONFIG.get("weather")), "settings": {"place": CONFIG.get("weather") or None}})
QUOTES_ON = bool(_addon_conf["quote"].get("enabled"))
WEATHER = ((_addon_conf["weather"].get("settings") or {}).get("place") or {}) if _addon_conf["weather"].get("enabled") else {}
WEATHER_URL = ("https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&timezone=auto&forecast_days=1"
               "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min")
_weather = {"at": 0.0, "data": None}


def weather() -> dict | None:
    if "lat" not in WEATHER or "lon" not in WEATHER:
        return None
    if time.time() - _weather["at"] < 1800:
        return _weather["data"]
    _weather["at"] = time.time()   # one try per half hour, even when it fails
    url = WEATHER_URL.format(lat=float(WEATHER["lat"]), lon=float(WEATHER["lon"]))
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=5) as r:
            d = json.load(r)
        _weather["data"] = {"city": WEATHER.get("city", ""), "temp": round(d["current"]["temperature_2m"]),
                            "code": int(d["current"]["weather_code"]), "day": bool(d["current"].get("is_day", 1)),
                            "high": round(d["daily"]["temperature_2m_max"][0]), "low": round(d["daily"]["temperature_2m_min"][0])}
    except Exception as e:
        log(f"weather: {e}")
    return _weather["data"]


# Quote of the day from the internet: ZenQuotes' quote of the day (it asks for a credit line, which the page shows)
# and its author's portrait, fetched once a day and kept here, so the TV only ever talks to this helper. When it
# can't be reached, the page falls back to its built-in list (web/quotes.json).
QUOTE_URL = "https://zenquotes.io/api/today"
_quote = {"day": "", "data": None, "image": None, "tried": 0.0}


def _fetch(url: str) -> bytes:
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=10) as r:
        return r.read()


def _quote_settings() -> dict:
    a = ADDONS.items.get("quote")
    return ADDONS.settings_of(a) if a else {}


def parse_quotes(text: str) -> list:
    """Your own quotes, one per line: "The quote — Author" (also " - ", " – " or " ~ "); no author is fine."""
    out = []
    for line in str(text or "").splitlines():
        line = line.strip()
        if not line:
            continue
        m = re.match(r"^(.*\S)\s+[—–~-]\s*([^—–~-]{1,80})$", line) or re.match(r"^(.*\S)\s*[—–]\s*([^—–]{1,80})$", line)
        text_, by = (m.group(1), m.group(2).strip()) if m else (line, "")
        out.append({"text": text_.strip().strip('"“”').strip(), "by": by})
    return [q for q in out if q["text"]]


def _quote_from_list(items: list, pick: str) -> dict | None:
    """From a list: one a day (in order, or at random but the same all day), or a new one each time."""
    if not items:
        return None
    if pick == "each":   # a new one on every ask: the screens ask each time Home opens
        return dict(random.choice(items), each=True)
    day = int(time.time() // 86400)
    i = random.Random(day).randrange(len(items)) if pick == "random" else day % len(items)
    return dict(items[i])


def quote_of_day() -> dict | None:
    if not QUOTES_ON:
        return None
    qs = _quote_settings()
    source = qs.get("source") or "zenquotes"
    if source == "mine":   # your own list: from the admin panel, no internet
        return _quote_from_list(parse_quotes(qs.get("mine")), qs.get("pick"))
    if source == "builtin":
        try:
            items = [{"text": t, "by": b} for t, b in json.loads((WEB / "quotes.json").read_text(encoding="utf-8")).get("en", [])]
        except Exception:
            items = []
        return _quote_from_list(items, qs.get("pick"))
    today = time.strftime("%Y-%m-%d")
    if _quote["day"] == today:
        # The portrait didn't come with the quote: try it again every 10 minutes.
        if not _quote["image"] and _quote.get("image_url") and time.time() - _quote["tried"] > 600:
            _quote["tried"] = time.time()
            threading.Thread(target=_quote_portrait, args=(today,), daemon=True).start()   # the TV doesn't wait
        return _quote["data"]
    if time.time() - _quote["tried"] < 600:   # failed a moment ago: don't keep the TV waiting on it
        return None
    _quote["tried"] = time.time()
    q, err = None, None
    for attempt in (0, 1):   # ZenQuotes now and then doesn't answer one request: once more at once
        try:
            q = json.loads(_fetch(QUOTE_URL))[0]
            break
        except Exception as e:
            err = e
    if q is None:
        log(f"quote of the day: ZenQuotes didn't answer ({err}); a built-in quote for now, trying again in 10 minutes")
        try:
            items = [{"text": t, "by": b} for t, b in json.loads((WEB / "quotes.json").read_text(encoding="utf-8")).get("en", [])]
            return _quote_from_list(items, "order")
        except Exception:
            return None
    data = {"text": html.unescape(q["q"]).strip(), "by": html.unescape(q["a"]).strip(), "source": "ZenQuotes.io"}
    url = str(q.get("i", ""))
    _quote.update(day=today, data=data, image=None, image_url=url if url.startswith("https://zenquotes.io/") else "")
    _quote_portrait(today)
    return data


def _quote_portrait(today: str):
    try:
        _quote["image"] = _fetch(_quote["image_url"])
        _quote["data"]["image"] = f"/api/quote-image?d={today}"
    except Exception as e:
        log(f"quote portrait: {e}")


# Wallpaper of the day: Bing's photo of the day, downloaded in the background once a day and kept in data/, so the
# launcher opens at once and still has yesterday's photo when offline. 1920 x 1080 by default (about 300 KB): the TV
# draws the page at that size, and a 4K photo ("wallpaper_size": "UHD", about 3-4 MB) made moving between tiles lag.
WALLPAPER_ON = CONFIG.get("wallpaper", True)
# Where the photo comes from: Bing's photo of the day, Wikipedia's picture of the day, NASA's astronomy picture of the
# day, or your own photos (uploaded in the admin panel, kept in data/wallpapers, one a day or one chosen for good).
WALLPAPER_SOURCES = {"bing": "Bing photo of the day", "wikimedia": "Wikipedia picture of the day",
                     "nasa": "NASA astronomy picture of the day", "own": "My photos"}
WALLPAPER_SOURCE = str(CONFIG.get("wallpaper_source", "bing"))
WALLPAPER_MARKET = str(CONFIG.get("wallpaper_market", "en-US"))
WALLPAPER_SIZE = str(CONFIG.get("wallpaper_size", "1920x1080"))
WALLPAPER_PICK = str(CONFIG.get("wallpaper_pick") or "")
WALLPAPER_IMG = DATA / "wallpaper.jpg"
WALLPAPER_META = DATA / "wallpaper.json"
WALLPAPER_STATE = DATA / "wallpaper-state.json"   # {"day": ..., "skip": n}: "Show another photo" for today
OWN_WALLS = DATA / "wallpapers"
# NASA's shared DEMO_KEY allows only a few requests an hour for everyone; a free key from api.nasa.gov is reliable.
NASA_KEY = str(CONFIG.get("nasa_api_key") or "DEMO_KEY")
WALL_UA = {"User-Agent": "cleanyourtv-home-launcher/1.0 (personal TV home screen)"}
_wall = {"busy": False, "tried": 0.0, "lock": threading.Lock()}


def wall_width() -> int:
    return 3840 if WALLPAPER_SIZE == "UHD" else 1920


def wall_skip() -> int:
    try:
        st = json.loads(WALLPAPER_STATE.read_text(encoding="utf-8"))
        return int(st.get("skip", 0)) if st.get("day") == time.strftime("%Y-%m-%d") else 0
    except (OSError, ValueError):
        return 0


def wall_key() -> str:
    pick = WALLPAPER_PICK if WALLPAPER_SOURCE == "own" else ""
    market = WALLPAPER_MARKET if WALLPAPER_SOURCE == "bing" else ""
    # The last part changes when the way photos are prepared changes (v2: tall photos framed), so they're made again.
    return f"{WALLPAPER_SOURCE}|{market}|{pick}|{wall_skip()}|{WALLPAPER_SIZE}|v2"


def own_wallpapers() -> list:
    if not OWN_WALLS.exists():
        return []
    return sorted(f.name for f in OWN_WALLS.glob("*.jpg") if not f.name.endswith(".thumb.jpg"))


def wallpaper_meta() -> dict | None:
    """What the launcher shows now; starts a download when the photo isn't today's or the settings changed."""
    if not WALLPAPER_ON:
        return None
    try:
        meta = json.loads(WALLPAPER_META.read_text(encoding="utf-8")) if WALLPAPER_IMG.exists() else None
    except Exception:
        meta = None
    stale = not meta or meta.get("day") != time.strftime("%Y-%m-%d") or meta.get("key") != wall_key()
    if stale and not _wall["busy"] and time.time() - _wall["tried"] > 600:
        _wall.update(busy=True, tried=time.time())
        threading.Thread(target=_refresh_wallpaper, daemon=True).start()
    return meta


def _get(url: str, timeout: float = 30) -> bytes:
    req = urllib.request.Request(url, headers=WALL_UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def _sips_size(path: Path) -> tuple[int, int]:
    out = subprocess.run(["/usr/bin/sips", "-g", "pixelWidth", "-g", "pixelHeight", str(path)],
                         capture_output=True, text=True, timeout=30).stdout
    nums = re.findall(r"pixel(?:Width|Height): (\d+)", out)
    return (int(nums[0]), int(nums[1])) if len(nums) == 2 else (0, 0)


def fit_photo(src: Path, dest: Path, width: int, quality: int = 82):
    """Any photo (JPEG, PNG, HEIC from an iPhone) as a JPEG at most `width` wide, with macOS's own sips."""
    w, _ = _sips_size(src)
    cmd = ["/usr/bin/sips", "-s", "format", "jpeg", "-s", "formatOptions", str(quality)]
    if w > width:
        cmd += ["--resampleWidth", str(width)]
    r = subprocess.run(cmd + [str(src), "--out", str(dest)], capture_output=True, text=True, timeout=120)
    if r.returncode != 0 or not dest.exists():
        raise ValueError("could not read that photo")


def frame_photo(path: Path, width: int):
    """A tall photo, whole, in the middle of a 16:9 picture; the sides take a dark tint of the photo's own colour.
    (Filling the screen with it would zoom into a thin strip.) Only sips and the standard library."""
    height = width * 9 // 16
    tmp = path.with_suffix(".frame.jpg")
    subprocess.run(["/usr/bin/sips", "--resampleHeight", str(height), str(path), "--out", str(tmp)],
                   capture_output=True, timeout=60, check=True)
    # The photo's average colour: shrink it to one pixel as a BMP and read that pixel (BGR, after the header).
    bmp = path.with_suffix(".px.bmp")
    subprocess.run(["/usr/bin/sips", "-s", "format", "bmp", "--resampleHeightWidth", "1", "1", str(path), "--out", str(bmp)],
                   capture_output=True, timeout=60, check=True)
    raw = bmp.read_bytes()
    bmp.unlink(missing_ok=True)
    off = int.from_bytes(raw[10:14], "little")
    b, g, r = raw[off], raw[off + 1], raw[off + 2]
    pad = "%02X%02X%02X" % (int(r * .22), int(g * .22), int(b * .22))
    subprocess.run(["/usr/bin/sips", "--padToHeightWidth", str(height), str(width), "--padColor", pad, str(tmp), "--out", str(path)],
                   capture_output=True, timeout=60, check=True)
    tmp.unlink(missing_ok=True)


def _wall_bing(skip: int) -> tuple[bytes, str, str]:
    api = ("https://www.bing.com/HPImageArchive.aspx?format=js&n=1&idx=" + str(min(skip, 7)) + "&mkt=" + quote(WALLPAPER_MARKET))
    img = json.loads(_fetch(api))["images"][0]
    size = "UHD" if WALLPAPER_SIZE == "UHD" else "1920x1080"
    return _fetch("https://www.bing.com" + img["urlbase"] + "_" + size + ".jpg"), img.get("title", ""), img.get("copyright", "")


def _wide(w: int, h: int) -> bool:
    return w > 0 and h > 0 and w / h >= 1.3   # a landscape photo, which fills a TV without heavy cropping


def _wall_wikimedia(skip: int) -> tuple[bytes, str, str]:
    day = time.time() - skip * 86400
    for back in range(12):   # the day's picture may be a tall one: then the day before, and so on
        d = time.localtime(day - back * 86400)
        feed = json.loads(_get(time.strftime("https://api.wikimedia.org/feed/v1/wikipedia/en/featured/%Y/%m/%d", d)))
        img = (feed.get("image") or {})
        orig = img.get("image") or {}
        src = str(orig.get("source", "")).split("?")[0]   # the feed adds tracking parameters
        if not _wide(orig.get("width", 0), orig.get("height", 0)) or not src.lower().endswith((".jpg", ".jpeg", ".png")):
            continue
        m = re.match(r"(https://upload\.wikimedia\.org/wikipedia/commons/)(\w/\w\w/)([^/]+)$", src)
        width = wall_width()
        body = None
        if m and orig["width"] > width:   # a ready-made smaller copy, rather than an original of tens of megabytes
            try:
                body = _get(f"{m.group(1)}thumb/{m.group(2)}{m.group(3)}/{width}px-{m.group(3)}")
            except Exception:
                body = None
        if body is None and orig["width"] * orig["height"] <= 60_000_000:
            body = _get(src, timeout=90)
        if not body:
            continue
        desc = html.unescape(re.sub(r"<[^>]+>", "", (img.get("description") or {}).get("text", ""))).strip()
        artist = html.unescape(re.sub(r"<[^>]+>", "", (img.get("artist") or {}).get("text", ""))).strip()
        title = desc.split(". ")[0][:120]
        return body, title, f"{title} — {artist}, Wikimedia Commons" if artist else f"{title}, Wikimedia Commons"
    raise ValueError("no wide picture in the last days")


APOD_FEED = "https://science.nasa.gov/feed/?post_type=image-article"   # APOD's home since 2026: the last week or so


def _apod_feed() -> list:
    """The latest Astronomy Pictures of the Day from NASA Science's feed: (title, original image address), newest first.
    The pictures are NASA's own copies (about 1000 pixels wide); the "_annotated" versions are left out."""
    feed = ""
    for page in (1, 2):   # about two weeks
        try:
            feed += _get(APOD_FEED + (f"&paged={page}" if page > 1 else "")).decode("utf-8", "replace")
        except Exception:
            if page == 1:
                raise
    out = []   # read as text: NASA's feed isn't always well-formed XML
    for blob in re.findall(r"<item>(.*?)</item>", feed, re.S):
        m = re.search(r"<title>(.*?)</title>", blob, re.S)
        title = html.unescape(re.sub(r"<!\[CDATA\[|\]\]>", "", m.group(1)).strip()) if m else ""
        if not title.startswith("APOD"):
            continue
        for m in re.finditer(r"https://assets\.science\.nasa\.gov/content/dam/science/cds/apod/[^\"\s<>&]+?\.(?:jpe?g|png)", blob, re.I):
            if "_annotated" not in m.group(0):
                out.append((re.sub(r"^APOD:\s*\d{4} \w+ \d{1,2}\s*[-\u2013]\s*", "", title), m.group(0)))
                break
    return out


def _wall_nasa(skip: int) -> tuple[bytes, str, str]:
    """NASA's Astronomy Picture of the Day: the newest wide one (some days are tall or a video), from APOD's new home on
    science.nasa.gov. NASA's old APOD API (api.nasa.gov, with the key) is the fallback; since the move it often
    answers with NASA's logo instead of the picture, which is skipped."""
    wide = []
    try:
        for title, url in _apod_feed():
            body = _get(url, timeout=90)
            tmp = DATA / "wallpaper-nasa.tmp"
            tmp.write_bytes(body)
            w, h = _sips_size(tmp)
            tmp.unlink(missing_ok=True)
            if _wide(w, h):
                wide.append((body, title, f"{title}, NASA APOD"))
                if len(wide) > skip:   # "Show another photo" asks for the next wide one back
                    return wide[-1]
        if wide:
            return wide[skip % len(wide)]
    except Exception as e:
        log(f"wallpaper (nasa): APOD feed: {e}")
    day = time.time() - skip * 86400
    for back in range(6):
        d = time.strftime("%Y-%m-%d", time.localtime(day - back * 86400))
        apod = json.loads(_get(f"https://api.nasa.gov/planetary/apod?api_key={quote(NASA_KEY)}&date={d}", timeout=20))
        url = apod.get("hdurl") or apod.get("url") or ""
        if apod.get("media_type") != "image" or "nasa-logo" in url or apod.get("title") == "NASA Science":
            continue
        body = _get(url, timeout=90)
        tmp = DATA / "wallpaper-nasa.tmp"
        tmp.write_bytes(body)
        w, h = _sips_size(tmp)
        tmp.unlink(missing_ok=True)
        if not _wide(w, h):
            continue
        who = (apod.get("copyright") or "NASA").replace("\n", " ").strip()
        return body, apod.get("title", ""), f"{apod.get('title', '')} — {who}, NASA APOD"
    raise ValueError("no wide picture in the last days")


def _wall_own(skip: int) -> tuple[bytes, str, str]:
    files = own_wallpapers()
    if not files:
        raise ValueError("no photos uploaded yet")
    name = WALLPAPER_PICK if WALLPAPER_PICK in files else files[(int(time.time() // 86400) + skip) % len(files)]
    return (OWN_WALLS / name).read_bytes(), "", ""


def _refresh_wallpaper(raise_errors: bool = False):
    with _wall["lock"]:
        try:
            key, skip = wall_key(), wall_skip()
            get = {"wikimedia": _wall_wikimedia, "nasa": _wall_nasa, "own": _wall_own}.get(WALLPAPER_SOURCE, _wall_bing)
            body, title, credit = get(skip)
            if len(body) < 20_000:
                raise ValueError("no usable image")
            DATA.mkdir(exist_ok=True)
            raw, tmp = WALLPAPER_IMG.with_suffix(".raw"), WALLPAPER_IMG.with_suffix(".tmp.jpg")
            raw.write_bytes(body)
            fit_photo(raw, tmp, wall_width())   # the TV moved between tiles with a lag behind a large photo
            raw.unlink(missing_ok=True)
            w, h = _sips_size(tmp)
            if w and h and w / h < 1.3:   # tall or square: framed, not cropped
                frame_photo(tmp, wall_width())
            tmp.replace(WALLPAPER_IMG)
            day = time.strftime("%Y-%m-%d")
            tag = hashlib.sha1(key.encode()).hexdigest()[:8]
            WALLPAPER_META.write_text(json.dumps({"day": day, "title": title, "credit": credit, "size": WALLPAPER_SIZE,
                                                  "key": key, "source": WALLPAPER_SOURCE,
                                                  "image": f"/api/wallpaper.jpg?d={day}-{tag}"}), encoding="utf-8")
            log(f"wallpaper ({WALLPAPER_SOURCE}): {title or 'your photo'}")
        except Exception as e:
            log(f"wallpaper ({WALLPAPER_SOURCE}): {e}")
            if raise_errors:
                raise
        finally:
            _wall["busy"] = False


def wallpaper_next() -> dict | None:
    """Another photo for today (the admin panel's "Show another photo"); back to the usual one tomorrow."""
    DATA.mkdir(exist_ok=True)
    WALLPAPER_STATE.write_text(json.dumps({"day": time.strftime("%Y-%m-%d"), "skip": wall_skip() + 1}), encoding="utf-8")
    _wall["busy"] = True
    _refresh_wallpaper(raise_errors=True)
    return json.loads(WALLPAPER_META.read_text(encoding="utf-8"))


def wallpaper_upload(name: str, body: bytes) -> str:
    """A photo from the admin panel, kept as a JPEG (4K wide at most) with a small preview beside it."""
    OWN_WALLS.mkdir(parents=True, exist_ok=True)
    stem = re.sub(r"[^A-Za-z0-9_-]+", "-", Path(name).stem)[:40].strip("-") or "photo"
    stem = f"{time.strftime('%Y%m%d-%H%M%S')}-{stem}"
    raw = OWN_WALLS / f"{stem}.upload"
    raw.write_bytes(body)
    try:
        fit_photo(raw, OWN_WALLS / f"{stem}.jpg", 3840, 85)
        fit_photo(OWN_WALLS / f"{stem}.jpg", OWN_WALLS / f"{stem}.thumb.jpg", 480, 75)
    finally:
        raw.unlink(missing_ok=True)
    log(f"wallpaper photo added: {stem}.jpg")
    return f"{stem}.jpg"


def wallpaper_delete(name: str) -> bool:
    if name not in own_wallpapers():
        return False
    (OWN_WALLS / name).unlink(missing_ok=True)
    (OWN_WALLS / name.replace(".jpg", ".thumb.jpg")).unlink(missing_ok=True)
    return True


# Region for the TV: all its internet traffic goes out through a VPN in a chosen country, or straight out ("off").
# The VPN is vpn/macos/tv-vpn.sh (NordVPN, or your own WireGuard files), installed root-owned in /usr/local/libexec; a sudoers rule lets this user run only its
# "up <country>" and "down" without a password. Without the script installed, the Region button is hidden.
VPN_SCRIPT = "/usr/local/libexec/tv-vpn.sh"
COUNTRY_NAMES = {"us": "USA", "gb": "UK", "ca": "Canada", "eg": "Egypt", "ae": "UAE", "fr": "France", "jp": "Japan",
                 "de": "Germany", "nl": "Netherlands", "it": "Italy", "es": "Spain", "au": "Australia", "se": "Sweden",
                 "ch": "Switzerland", "sa": "Saudi Arabia", "tr": "Turkey", "in": "India", "br": "Brazil"}
VPN_COUNTRIES = [str(c).lower() for c in CONFIG.get("vpn_countries", ["us", "gb"]) if len(str(c)) == 2 and str(c).isalpha()]
VPN_HOME_NAME = CONFIG.get("vpn_home_name", "Home")
# Every country NordVPN has servers in (about 150), so the admin panel can offer them all, not just the short list
# above. Kept in data/ so it works offline; refreshed from Nord's public API once a week in the background. The short
# names above (USA, UK, UAE...) win over Nord's long ones.
NORD_COUNTRIES = DATA / "nord-countries.json"
_SHORT_NAMES = dict(COUNTRY_NAMES)


def _merge_country_names(names: dict):
    clean = {str(k).lower(): str(v)[:40] for k, v in names.items()
             if len(str(k)) == 2 and str(k).isalpha() and str(v).strip()}
    COUNTRY_NAMES.update({**clean, **_SHORT_NAMES})   # same dict object: /api/state and admin see it at once


def _refresh_nord_countries():
    try:
        with urllib.request.urlopen("https://api.nordvpn.com/v1/servers/countries", timeout=20) as r:
            names = {c["code"]: c["name"] for c in json.load(r)}
        if len(names) < 20:
            raise ValueError(f"only {len(names)} countries")
        NORD_COUNTRIES.write_text(json.dumps(names, ensure_ascii=False, sort_keys=True), encoding="utf-8")
        _merge_country_names(names)
        log(f"vpn: {len(names)} NordVPN countries loaded")
    except Exception as e:   # offline, or Nord changed its API: keep the saved or built-in list
        log(f"vpn: could not refresh NordVPN countries ({e})")


try:
    _merge_country_names(json.loads(NORD_COUNTRIES.read_text(encoding="utf-8")))
except (OSError, ValueError):
    pass
if not NORD_COUNTRIES.exists() or time.time() - NORD_COUNTRIES.stat().st_mtime > 7 * 86400:
    threading.Thread(target=_refresh_nord_countries, daemon=True).start()


STARTED = int(time.time())


def vpn_available() -> bool:
    return os.access(VPN_SCRIPT, os.X_OK)


def regions() -> list:
    return [{"id": "off", "name": f"{VPN_HOME_NAME} (no VPN)"}] + \
           [{"id": c, "name": COUNTRY_NAMES.get(c, c.upper())} for c in VPN_COUNTRIES]


def region_now() -> str | None:
    """The country the VPN was started with, while its tunnel interface is up; both readable without root."""
    if not vpn_available():
        return None
    try:
        code = Path("/Library/Application Support/tv-vpn/country").read_text().strip().lower()
    except OSError:
        return "off"
    try:
        iface = Path("/var/run/tv-vpn/iface").read_text().strip()
    except OSError:
        iface = ""
    up = bool(iface) and iface in subprocess.run(["/sbin/ifconfig", "-l"], capture_output=True, text=True, timeout=10).stdout.split()
    return code if code and up else "off"


def region_set(key: str) -> str:
    cmd = ["sudo", "-n", VPN_SCRIPT] + (["down"] if key == "off" else ["up", key])
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
    if r.returncode != 0:
        lines = (r.stdout + r.stderr).strip().splitlines()
        raise RuntimeError(lines[-1].replace("tv-vpn: ", "") if lines else "the VPN did not start")
    name = next((x["name"] for x in regions() if x["id"] == key), key)
    log(f"TV VPN: {name}")
    return key


# Per app: open in the TV Browser (a website that needs a pointer, like osnplus.com) and/or switch the TV's VPN to a
# country first. Opening any other app afterwards switches the VPN back to what it was; choosing a region by hand
# (Region button, phone page) ends that, so a manual choice is never undone.
APP_BROWSER = {str(k): str(v) for k, v in (CONFIG.get("app_browser") or {}).items()}
APP_VPN = {str(k): str(v).lower() for k, v in (CONFIG.get("app_vpn") or {}).items()}
_auto_vpn = {"prev": None}   # the region before an app switched it, while that switch stands

# The Chromecast's own launcher (android/, an app that shows this same page) gets its apps from Android and only reads
# its settings, the weather, quote and wallpaper here, plus the VPN switch, which it shares with the TV. Its per-app
# countries ("app_vpn", by Android package name) join the TV's: package names and VIDAA ids never clash.
CHROMECAST = CONFIG.get("chromecast") or {}
_cc_ip = CHROMECAST.get("ip", [])
CC_IPS = {str(x) for x in (_cc_ip if isinstance(_cc_ip, list) else [_cc_ip]) if x}
CC_PATHS = {"/api/home-scenes", "/api/home-scene-run", "/api/tv-sources", "/api/tv-source", "/api/hub/tap", "/api/hub/pointer", "/api/hub/text", "/api/hub/live", "/", "/index.html", "/quotes.json", "/api/rev", "/api/admin-link", "/api/home-edit", "/api/now-playing", "/api/media", "/api/cc", "/api/cc-open", "/api/cc-apps", "/api/weather", "/api/quote",
            "/api/quote-image", "/api/wallpaper", "/api/wallpaper.jpg", "/api/region", "/vpn"}
APP_VPN.update({str(k): str(v).lower() for k, v in (CHROMECAST.get("app_vpn") or {}).items()})


def chromecast_settings() -> dict:
    return {"dock": CHROMECAST.get("dock", []), "rows": CHROMECAST.get("rows", []), "rename": CHROMECAST.get("rename", {}),
            "hidden": CHROMECAST.get("hidden", []), "app_vpn": CHROMECAST.get("app_vpn", {}),
            "greeting_name": CONFIG.get("greeting_name", ""), "quotes": QUOTES_ON, "weather": bool(WEATHER),
            "wallpaper": bool(WALLPAPER_ON), "region": vpn_available(), "clock_24h": CONFIG.get("clock_24h", False),
            "rev": CHROMECAST.get("rev", 0), "layout": CONFIG.get("home_layout") or layout.PRESETS["classic"],
            "live_refresh": CONFIG.get("live_refresh", True) is not False,
            "cursor": {"port": POINTER_PORT, "token": pointer_token()}, "api": API_VERSION,
            "experience": experience.for_screen(CONFIG, "cc"),
            "started": STARTED, "saved": SAVED["at"]}


# ---------- add-ons (helper/addons.py, addons/, data/addons/) ----------
ADDONS = addons.Addons([(ROOT / "addons", True), (DATA / "addons", False)], log, lambda: CONFIG,
                       lambda: WEATHER or (CONFIG.get("weather") or {}),
                       lambda addon_id, name: f"/api/addons/{addon_id}/file/{quote(name)}")


def addon_run(addon_id: str) -> dict:
    """A quick action: the VPN step here (it waits for the tunnel), the rest for the Chromecast to do."""
    if addon_id.startswith("web-"):   # a website: in the Chromecast's browser
        w = website(addon_id[4:])
        if not w:
            return {"ok": False, "error": "unknown website"}
        pkg = cc_web_browser()
        return {"ok": True, "steps": [{"open": pkg, "uri": w["url"]} if pkg else {"url": w["url"]}]}
    steps = ADDONS.steps(addon_id)
    if steps is None:
        return {"ok": False, "error": "unknown add-on"}
    client = []
    for st in steps:
        if "vpn" in st and vpn_available():
            key = str(st["vpn"]).lower()
            if key in [r["id"] for r in regions()] or len(key) == 2:
                _auto_vpn["prev"] = None   # like a choice by hand: no app switches it back
                if region_now() != key:
                    region_set(key)
        else:
            client.append(st)
    return {"ok": True, "steps": client}


def validate_addons(v: dict) -> dict:
    """The admin panel's add-on settings, checked against each add-on's own list of settings."""
    if not isinstance(v, dict):
        raise ValueError("bad add-on settings")
    out = {"order": [i for i in v.get("order", []) if i in ADDONS.items]}
    if "widget_seconds" in v:
        n = int(v["widget_seconds"])
        if not 4 <= n <= 120:
            raise ValueError("widgets change every 4 to 120 seconds")
        out["widget_seconds"] = n
    for addon_id, conf in v.items():
        if addon_id in ("order", "widget_seconds"):
            continue
        a = ADDONS.items.get(addon_id)
        if not a or not isinstance(conf, dict):
            continue
        clean = {}
        for spec in a.meta.get("settings", []):
            key, kind = spec.get("key"), spec.get("type", "text")
            if key not in (conf.get("settings") or {}):
                continue
            val = conf["settings"][key]
            if kind == "number":
                val = float(val) if "." in str(val) else int(val)
            elif kind == "bool":
                val = bool(val)
            elif kind == "select":
                if str(val) not in [str(o[0]) for o in spec.get("options", [])]:
                    raise ValueError(f"{a.id}: {key} must be one of its choices")
                val = str(val)
            elif kind == "lines":   # a list, one item per line (e.g. your own quotes)
                lines = [ln.strip() for ln in str(val).replace("\r", "").split("\n") if ln.strip()]
                if len(lines) > 500:
                    raise ValueError(f"{a.id}: at most 500 lines")
                val = "\n".join(ln[:400] for ln in lines)
            elif kind == "place":
                val = None if not val else {"city": str(val.get("city", ""))[:60], "lat": round(float(val["lat"]), 4),
                                            "lon": round(float(val["lon"]), 4)}
            else:
                val = str(val)[:500]
            clean[key] = val
        out[addon_id] = {"enabled": bool(conf.get("enabled")), "settings": clean}
    return out


# ---------- admin panel (shared/panel/admin.html, helper/admin.py) ----------
ADMIN = admin.Admin(CONFIG_PATH, DATA, log)
ADMIN.validate_addons = validate_addons
ADMIN.load_sessions()
SAFE_ID = re.compile(r"^[A-Za-z0-9_.-]{1,120}$")


# Saves that only change what the home screens draw are applied here at once, without a restart; the screens
# check /api/rev every few seconds and see "saved" change. Everything else (VPN, TV apps, wallpaper…) restarts.
LIVE_KEYS = {"home_experience", "home_layout", "chromecast", "greeting_name", "clock_24h", "live_refresh", "addons", "widget_seconds"}
SAVED = {"at": 0}


def apply_live(cfg: dict):
    global CHROMECAST, QUOTES_ON, WEATHER
    old_place = WEATHER
    CONFIG.clear()
    CONFIG.update(cfg)
    CHROMECAST = CONFIG.get("chromecast") or {}
    conf = CONFIG.setdefault("addons", {})
    QUOTES_ON = bool((conf.get("quote") or {}).get("enabled"))
    w = conf.get("weather") or {}
    WEATHER = ((w.get("settings") or {}).get("place") or {}) if w.get("enabled") else {}
    if WEATHER != old_place:
        _weather["at"] = 0   # a new city: fetch it now
    APP_VPN.clear()
    APP_VPN.update({str(k): str(v).lower() for k, v in (CONFIG.get("app_vpn") or {}).items()})
    APP_VPN.update({str(k): str(v).lower() for k, v in (CHROMECAST.get("app_vpn") or {}).items()})
    SAVED["at"] = time.time()
    log("settings applied without a restart")

    def fetch_new():   # add-ons just switched on: their data now, then tell the screens again
        due = [a for a in ADDONS.enabled() if a.result is None]
        for a in due:
            ADDONS.refresh(a, force=True)
        if due:
            SAVED["at"] = time.time()
    threading.Thread(target=fetch_new, daemon=True).start()


def restart_soon():
    """Start this helper again (same command), so every setting is read fresh. The VPN has its own service."""
    def go():
        log("restarting for the new settings")
        os.execv(sys.executable, [sys.executable] + sys.argv)
    threading.Timer(1.0, go).start()


def admin_state() -> dict:
    cfg = ADMIN.read_config()
    with APPS.lock:
        tv = [{"id": str(a["appId"]), "name": a.get("name", "").replace("▶", "").strip()} for a in APPS.apps
              if not str(a.get("appId")).startswith("cyt")]
    tv = [{"id": p["appId"], "name": p["name"]} for p in PAGES] + tv
    tv += [{"id": "web-" + w["id"], "name": w["name"]} for w in websites() if w["tv"]]
    for a in tv:
        a["icon"] = f"/api/admin/icon/tv/{a['id']}"
    cc = ADMIN.cc_apps()
    for a in cc["apps"]:
        a["icon"] = f"/api/admin/icon/cc/{a['id']}"
    for t in ADDONS.home()["tiles"] + web_tiles_cc():   # full-screen and quick-action add-ons, websites: tiles like apps
        cc["apps"].append({"id": t["id"], "name": t["name"], "emoji": t["emoji"], "color": t["color"], "banner": True})
    try:
        server = Path("/var/run/tv-vpn/server").read_text().split()[1]
    except (OSError, IndexError):
        server = ""
    try:
        now = region_now()
    except Exception:
        now = None
    return {"settings": ADMIN.editable(cfg, takeover_on()), "tv_apps": tv, "tv_removed": APPS.removed(),
            "cc": cc, "cc_ip": CC_IPS and sorted(CC_IPS)[0] or "", "regions": regions() if vpn_available() else [],
            "region": now, "vpn_server": server if now not in (None, "off") else "", "country_names": COUNTRY_NAMES,
            "tv_on": HOME_WATCH.up, "started": STARTED,
            "devices": {"tv": VIDAA, "cc": bool(CC_IPS), "vpn": vpn_available()}, "quick_base": quick_base(),
            "scenes": scenes(), "https": https_state(), "websites": websites(),
            "addons": ADDONS.status(), "addons_broken": ADDONS.broken,
            "layout_spec": layout.spec(), "preview": layout_preview(),
            "wallpaper": {"now": wallpaper_meta(), "sources": WALLPAPER_SOURCES, "own": own_wallpapers(),
                          "active": {"source": WALLPAPER_SOURCE, "market": WALLPAPER_MARKET, "pick": WALLPAPER_PICK}}}


# "Edit Home" on the TV or the Chromecast, with the remote: the layout, what can go in it, and a save. Only those two
# screens may use it (the admin panel has its own, with a password); a save is checked like the admin panel's.
def home_edit_state() -> dict:
    conf = CONFIG.get("addons") or {}
    widgets = [{"id": a.id, "name": a.public()["name"], "emoji": a.public()["emoji"], "on": bool((conf.get(a.id) or {}).get("enabled"))}
               for a in ADDONS.items.values() if a.type == "widget" and not a.slot]
    return {"layout": CONFIG.get("home_layout") or layout.PRESETS["classic"], "spec": layout.spec(), "widgets": widgets}


def home_edit_save(data: dict) -> dict:
    cfg = ADMIN.read_config()
    addons_conf = json.loads(json.dumps(cfg.get("addons") or {}))
    known = {a.id for a in ADDONS.items.values() if a.type == "widget" and not a.slot}
    for key, on in (("on", True), ("off", False)):
        for i in data.get(key) or []:
            if str(i) in known:
                addons_conf.setdefault(str(i), {"settings": {}})["enabled"] = on
    new, _ = ADMIN.apply({"home_layout": data.get("layout"), "addons": addons_conf})
    ADMIN.save(new)
    apply_live(new)
    return {"ok": True}


def layout_preview() -> dict:
    """What the Layout tab draws in its blocks: today's quote, the weather and the widget cards as they are now."""
    out = {"widgets": [], "quote": None, "weather": None}
    try:
        out["widgets"] = ADDONS.home()["widgets"]
        out["quote"] = quote_of_day()
        out["weather"] = weather()
    except Exception as e:
        log(f"layout preview: {e}")
    return out


def _adb() -> str | None:
    for p in (shutil.which("adb"), "/opt/homebrew/bin/adb", "/usr/local/bin/adb",
              str(Path.home() / "Library/Android/sdk/platform-tools/adb")):
        if p and Path(p).exists():
            return p
    return None


_cc_link = {"lock": threading.Lock(), "tried": 0.0, "why": ""}


def _cc_find_serial(adb: str, ips: list) -> str | None:
    out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=10).stdout
    return next((ln.split()[0] for ln in out.splitlines()[1:]
                 if ln.split()[-1:] == ["device"] and any(ln.startswith(ip + ":") for ip in ips)), None)


def _cc_reconnect(adb: str, ips: list, force: bool = False) -> str:
    """Wireless debugging gets a new port after the Chromecast restarts or the Wi-Fi drops, and adb loses it.
    Find the new port over mDNS (as android/cc.sh connect does) and connect again. At most once every 20 seconds,
    unless forced (the panel's Reconnect button). Answers why it failed, or ""."""
    with _cc_link["lock"]:
        if not force and time.time() - _cc_link["tried"] < 20:
            return _cc_link["why"] or "The Chromecast isn't connected."
        _cc_link["tried"] = time.time()
        why = ""
        try:
            subprocess.run([adb, "start-server"], capture_output=True, timeout=15)
            listed = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=10).stdout
            for ln in listed.splitlines()[1:]:   # the old port, left "offline"
                w = ln.split()
                if len(w) == 2 and w[1] in ("offline", "unauthorized") and any(w[0].startswith(ip + ":") for ip in ips):
                    subprocess.run([adb, "disconnect", w[0]], capture_output=True, timeout=10)
            tries = 4 if force else 2   # mDNS needs a moment after adb's server starts
            for attempt in range(tries):
                found = subprocess.run([adb, "mdns", "services"], capture_output=True, text=True, timeout=10).stdout
                addr = next((w for ln in found.splitlines() if "_adb-tls-connect" in ln for w in ln.split()
                             if any(w.startswith(ip + ":") for ip in ips)), None)
                if addr:
                    r = subprocess.run([adb, "connect", addr], capture_output=True, text=True, timeout=12)
                    log(f"chromecast: adb connect {addr}: {(r.stdout + r.stderr).strip()[:120]}")
                    if _cc_find_serial(adb, ips):
                        why = ""
                        break
                    why = "The Chromecast refused the connection: pair it again (android/cc.sh pair)."
                else:
                    why = ("Can't find the Chromecast's Wireless debugging. On the Chromecast: Settings > System > "
                           "Developer options > Wireless debugging: on.")
                if attempt < tries - 1:
                    time.sleep(1.5)
        except Exception as e:
            why = f"Couldn't reconnect to the Chromecast: {e}"
        _cc_link["why"] = why
        if why:
            notify("cc-link", "The Chromecast's remote link dropped", why, every=6 * 3600)
        return why


def _cc_serial(force: bool = False) -> tuple[str | None, str | None, str]:
    """(adb, the Chromecast's debugging serial, why not): the link the screenshot and the remote use.
    Reconnects by itself when adb lost it."""
    adb = _adb()
    if not adb:
        return None, None, "adb isn't installed on this computer."
    ips = [CC_IPS] if isinstance(CC_IPS, str) else list(CC_IPS or [])
    serial = None if force else _cc_find_serial(adb, ips)
    if not serial:
        why = _cc_reconnect(adb, ips, force)
        serial = _cc_find_serial(adb, ips)
        if not serial:
            return adb, None, why or "The Chromecast isn't connected."
    if not _cc_link.get("kept"):
        _cc_link["kept"] = True
        threading.Thread(target=_cc_keep_debugging, args=(adb, serial), daemon=True).start()
    return adb, serial, ""


def _cc_keep_debugging(adb: str, serial: str) -> None:
    """Let the launcher switch Wireless debugging back on by itself after a restart or a Wi-Fi drop (AdbKeeper).
    Android turns it off then, and only adb can grant this permission. Once per helper run; harmless when granted."""
    try:
        r = subprocess.run([adb, "-s", serial, "shell", "pm", "grant", "com.cleanyourtv.home",
                            "android.permission.WRITE_SECURE_SETTINGS"], capture_output=True, text=True, timeout=15)
        out = (r.stdout + r.stderr).strip()
        log("chromecast: keep Wireless debugging on: " + (out[:120] or "granted"))
        # what's playing with its length and picture (MediaWatch), for the phone's Now playing
        subprocess.run([adb, "-s", serial, "shell", "cmd", "notification", "allow_listener",
                        "com.cleanyourtv.home/com.cleanyourtv.home.MediaWatch"], capture_output=True, text=True, timeout=15)
    except Exception as e:
        log(f"chromecast: keep Wireless debugging on: {e}")


# ---------- the remote (admin panel): the TV's keys over its remote channel, the Chromecast's over its debugging link ----------
TV_KEYS = {"up": "KEY_UP", "down": "KEY_DOWN", "left": "KEY_LEFT", "right": "KEY_RIGHT", "ok": "KEY_OK", "back": "KEY_RETURNS",
           "home": "KEY_HOME", "menu": "KEY_MENU", "volup": "KEY_VOLUMEUP", "voldown": "KEY_VOLUMEDOWN", "mute": "KEY_MUTE",
           "play": "KEY_PLAY", "pause": "KEY_PAUSE", "power": "KEY_POWER", "input": "KEY_INPUT", "exit": "KEY_EXIT",
           "rew": "KEY_BACKS", "ff": "KEY_FORWARDS", "stop": "KEY_STOP"}
CC_KEYS = {"up": "DPAD_UP", "down": "DPAD_DOWN", "left": "DPAD_LEFT", "right": "DPAD_RIGHT", "ok": "DPAD_CENTER",
           "back": "BACK", "home": "HOME", "menu": "MENU", "volup": "VOLUME_UP", "voldown": "VOLUME_DOWN", "mute": "VOLUME_MUTE",
           "play": "MEDIA_PLAY_PAUSE", "pause": "MEDIA_PLAY_PAUSE", "power": "SLEEP", "wake": "WAKEUP", "settings": "SETTINGS",
           "prev": "MEDIA_PREVIOUS", "next": "MEDIA_NEXT", "rew": "MEDIA_REWIND", "ff": "MEDIA_FAST_FORWARD", "stop": "MEDIA_STOP"}


# ---------- the trackpad (admin panel): a pointer over any app on the Chromecast ----------
# Google TV shows no pointer for injected mouse events, so the launcher app has a small accessibility service
# (android/.../CursorService.java) that draws one and taps where it points. It listens on POINTER_PORT for this
# helper only (a token it gets with its settings); the helper keeps one connection open, so a move costs no adb call.
POINTER_PORT = 8791
POINTER_SERVICE = "com.cleanyourtv.home/com.cleanyourtv.home.CursorService"
_pointer = {"sock": None, "rf": None, "lock": threading.Lock(), "enabled_at": 0.0, "sh": None, "sx": 0.0, "sy": 0.0,
            "scroller": None, "fg": "", "fg_at": 0.0, "used": 0.0, "kx": 0.0, "ky": 0.0, "keys": [], "joy": None, "joy_at": 0.0,
            "joy_since": 0.0}
# Apps that ignore taps and clicks and move their own pointer with the arrow keys (BrowseHere): while one is in front,
# the trackpad sends arrow keys and OK instead. BrowseHere's pointer moves only while an arrow is held (a single press
# doesn't move it), and adb can only hold a key as a "long press": about 250 px in half a second. So each KEY_STEP of
# trackpad movement is one long press that way; at most KEY_QUEUE wait, so a long drag doesn't keep it going after.
KEY_APPS = {str(a) for a in (CHROMECAST.get("trackpad_arrow_apps") or ["com.tcl.browser"])}
KEY_STEP = 250
KEY_QUEUE = 3


def pointer_token() -> str:
    f = DATA / "pointer-token"
    try:
        t = f.read_text().strip()
        if len(t) >= 16:
            return t
    except OSError:
        pass
    import secrets
    DATA.mkdir(exist_ok=True)
    t = secrets.token_hex(16)
    f.write_text(t)
    try:
        f.chmod(0o600)
    except OSError:
        pass
    return t


def _pointer_switch_on() -> str:
    """Switch the pointer service on over adb (Android lets an app tap inside others only as an accessibility service)."""
    adb, serial, why = _cc_serial()
    if not serial:
        return why
    sh = lambda *a: subprocess.run([adb, "-s", serial, "shell", *a], capture_output=True, text=True, timeout=15).stdout.strip()
    cur = sh("settings", "get", "secure", "enabled_accessibility_services")
    others = [x for x in ("" if cur in ("", "null") else cur).split(":") if x and x != POINTER_SERVICE]
    # Out and back in: after an app update Android leaves it listed but not running; changing the list starts it.
    sh("settings", "put", "secure", "enabled_accessibility_services", ":".join(others) or "null")
    sh("settings", "put", "secure", "enabled_accessibility_services", ":".join(others + [POINTER_SERVICE]))
    sh("settings", "put", "secure", "accessibility_enabled", "1")
    log("trackpad: pointer service switched on")
    return ""


def _pointer_connect():
    ip = sorted(CC_IPS)[0] if CC_IPS else ""
    if not ip:
        raise ConnectionError("no Chromecast in the settings")
    sock = socket.create_connection((ip, POINTER_PORT), timeout=2)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.sendall(f"auth {pointer_token()}\n".encode())
    sock.settimeout(3)
    answer = sock.recv(16)
    if not answer:   # nothing listening (adb's port forward, or the service just stopped)
        sock.close()
        raise OSError("the pointer service isn't running")
    if not answer.startswith(b"ok"):
        sock.close()
        raise ConnectionError("the Chromecast app doesn't have the trackpad's key yet: open Home on it once")
    sock.settimeout(5)
    _pointer["rf"] = sock.makefile("rb")
    return sock


def _cc_service(line: str) -> str:
    """One command to the launcher's pointer service (it listens without adb): "" when sent, else why not.
    The remote's way in when Wireless debugging is off."""
    if not CC_IPS or any(ch in line for ch in "\r\n"):
        return "no Chromecast"
    with _pointer["lock"]:
        for attempt in (0, 1):
            try:
                if _pointer["sock"] is None:
                    _pointer["sock"] = _pointer_connect()
                _pointer["sock"].sendall((line + "\n").encode())
                return ""
            except (OSError, ConnectionError) as e:
                try:
                    if _pointer["sock"]:
                        _pointer["sock"].close()
                except OSError:
                    pass
                _pointer["sock"] = None
                why = str(e)
    return why


def _cc_ask(cmd: str, timeout: float = 4) -> str | None:
    """A question to the launcher's service that has a one-line answer ("np", "art"): the answer, or None."""
    if not CC_IPS:
        return None
    word = cmd.split()[0]
    with _pointer["lock"]:
        for attempt in (0, 1):
            try:
                if _pointer["sock"] is None:
                    _pointer["sock"] = _pointer_connect()
                so = _pointer["sock"]
                so.sendall((cmd + "\n").encode())
                so.settimeout(timeout)
                line = _pointer["rf"].readline().decode("utf-8", "replace").rstrip("\r\n")
                so.settimeout(5)
                head, _, rest = line.partition(" ")
                return rest if head == word else None
            except (OSError, ConnectionError):
                try:
                    if _pointer["sock"]:
                        _pointer["sock"].close()
                except OSError:
                    pass
                _pointer["sock"] = None
    return None


_np_art = {"key": None, "data": None}


def cc_now_playing_art(key: str) -> bytes | None:
    """The picture of what's playing (the video's thumbnail, the album cover), from the launcher; kept per title."""
    if key and key == _np_art["key"] and _np_art["data"]:
        return _np_art["data"]
    raw = _cc_ask("art", 8)
    if not raw or raw == "-":
        return None
    import base64
    try:
        data = base64.b64decode(raw)
    except ValueError:
        return None
    _np_art.update(key=key, data=data)
    return data


def _cc_np_launcher() -> dict | None:
    """What's playing, from the launcher's MediaWatch: with the length and the picture, and without adb."""
    raw = _cc_ask("np")
    try:
        j = json.loads(raw) if raw else None
    except ValueError:
        j = None
    if not isinstance(j, dict) or j.get("error"):
        return None
    if not j.get("playing"):
        return {"ok": True, "playing": None}
    app = str(j.get("app", ""))
    names = {a["id"]: a.get("name", a["id"]) for a in ADMIN.cc_apps().get("apps", [])}
    pick = {"app": app, "state": j.get("state", ""), "title": j.get("title", ""), "subtitle": j.get("subtitle", ""),
            "position": int(j.get("position", 0) / 1000), "duration": int(j.get("duration", 0) / 1000),
            "art": bool(j.get("art")), "name": names.get(app, app.rsplit(".", 1)[-1]), "icon": f"/api/admin/icon/cc/{app}"}
    return {"ok": True, "playing": pick, "via": "launcher"}


def _pointer_shell(cmd: str):
    """One command in a long-lived adb shell on the Chromecast (no new adb process per click)."""
    sh = _pointer["sh"]
    if sh is None or sh.poll() is not None:
        adb, serial, why = _cc_serial()
        if not serial:
            raise ConnectionError(why)
        sh = _pointer["sh"] = subprocess.Popen([adb, "-s", serial, "shell"], stdin=subprocess.PIPE,
                                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, text=True)
    sh.stdin.write(cmd + "\n")
    sh.stdin.flush()


def _pointer_pos() -> tuple[int, int]:
    _pointer["sock"].sendall(b"p\n")
    line = _pointer["rf"].readline().decode().split()
    if len(line) != 3 or line[0] != "pos":
        raise OSError("no answer from the pointer")
    return int(line[1]), int(line[2])


def _pointer_foreground() -> str:
    """The app in front on the Chromecast (asked at most every 1.5 s, and only while the trackpad is in use)."""
    if time.time() - _pointer["fg_at"] > 1.5:
        _pointer["fg_at"] = time.time()
        adb, serial, _ = _cc_serial()
        if serial:
            try:
                out = subprocess.run([adb, "-s", serial, "shell", "dumpsys activity activities | grep -m1 topResumedActivity"],
                                     capture_output=True, text=True, timeout=5).stdout
                m = re.search(r"\s([\w.]+)/", out)
                _pointer["fg"] = m.group(1) if m else ""
            except (OSError, subprocess.TimeoutExpired):
                pass
    return _pointer["fg"]


def _pointer_keys_worker():
    """Arrow mode: the keys that piled up, sent together every 0.12 s (one adb command for several keys)."""
    while True:
        time.sleep(0.12)
        with _pointer["lock"]:
            keys, _pointer["keys"] = _pointer["keys"][:12], _pointer["keys"][12:]
        cmds, run = [], []
        for k in keys:   # a long press is a command of its own (its flag would apply to every key with it)
            if k.startswith("--longpress"):
                if run: cmds.append(" ".join(run)); run = []
                cmds.append(k)
            else:
                run.append(k)
        if run:
            cmds.append(" ".join(run))
        for c in cmds:
            try:
                _pointer_shell("input keyevent " + c)
            except (OSError, ConnectionError, AttributeError):
                pass
        joy = _pointer["joy"]
        if joy:   # holding a direction on the trackpad: the arrow is held again as each long press ends (~0.5 s)
            if time.time() - _pointer["joy_since"] > 20 or _pointer["fg"] not in KEY_APPS:
                _pointer["joy"] = None
            elif time.time() - _pointer["joy_at"] >= 0.5:
                _pointer["joy_at"] = time.time()
                try:
                    _pointer_shell("input keyevent --longpress " + joy)
                except (OSError, ConnectionError, AttributeError):
                    pass
        if time.time() - _pointer["used"] < 60:
            _pointer_foreground()


def _pointer_scroller():
    """Two-finger scrolling, as touch swipes from the pointer: at most one every quarter second, the rest added up."""
    threading.Thread(target=_pointer_keys_worker, daemon=True).start()
    while True:
        time.sleep(0.25)
        with _pointer["lock"]:
            dx, dy = _pointer["sx"], _pointer["sy"]
            if abs(dx) < 4 and abs(dy) < 4:
                continue
            _pointer["sx"] = _pointer["sy"] = 0.0
            try:
                x, y = _pointer_pos()
                _pointer_shell(f"input touchscreen swipe {x} {y} {max(1, round(x + dx))} {max(1, round(y + dy))} 220")
            except (OSError, ConnectionError, AttributeError):
                pass


def pointer_send(ops: list) -> dict:
    """Moves, clicks and scrolls from the trackpad, as lines for the pointer service."""
    steps = []
    for op in (ops or [])[:200]:
        if not isinstance(op, list) or not op or op[0] not in ("m", "c", "l", "s", "h", "j"):
            raise ValueError("bad trackpad step")
        steps.append([op[0]] + [max(-4000.0, min(4000.0, float(v))) for v in op[1:3]])
    if not steps:
        return {"ok": True}
    if _pointer["scroller"] is None:
        _pointer["scroller"] = threading.Thread(target=_pointer_scroller, daemon=True)
        _pointer["scroller"].start()
    _pointer["used"] = time.time()
    fg = _pointer_foreground()
    dlog("trackpad " + ("arrows " if fg in KEY_APPS else "") + " ".join(
        st[0] + ("" if len(st) == 1 else f"({st[1]:.0f},{st[2]:.0f})") for st in steps)[:200])
    if fg in KEY_APPS:   # arrow mode: no pointer of ours; its own pointer, with the arrow keys
        with _pointer["lock"]:
            for st in steps:
                if st[0] in ("m", "s"):
                    _pointer["kx"] += st[1]; _pointer["ky"] += st[2]
                    for axis, plus, minus in (("kx", "DPAD_RIGHT", "DPAD_LEFT"), ("ky", "DPAD_DOWN", "DPAD_UP")):
                        while abs(_pointer[axis]) >= KEY_STEP:
                            if len(_pointer["keys"]) < KEY_QUEUE:
                                _pointer["keys"].append("--longpress " + (plus if _pointer[axis] > 0 else minus))
                            _pointer[axis] -= KEY_STEP if _pointer[axis] > 0 else -KEY_STEP
                elif st[0] == "j":   # hold a direction (1/-1 on one axis), or let go (0 0)
                    key = {(1, 0): "DPAD_RIGHT", (-1, 0): "DPAD_LEFT", (0, 1): "DPAD_DOWN", (0, -1): "DPAD_UP"}.get(
                        (int(st[1]), int(st[2])))
                    if key != _pointer["joy"]:
                        _pointer["joy"], _pointer["joy_at"], _pointer["joy_since"] = key, 0.0, time.time()
                    _pointer["kx"] = _pointer["ky"] = 0.0
                elif st[0] == "c":
                    _pointer["keys"].append("DPAD_CENTER")
                elif st[0] == "l":
                    _pointer["keys"].append("--longpress DPAD_CENTER")
            try:
                if _pointer["sock"] is not None:
                    _pointer["sock"].sendall(b"h\n")
            except OSError:
                _pointer["sock"] = None
        return {"ok": True, "mode": "arrows", "app": _pointer["fg"]}
    with _pointer["lock"]:
        for attempt in range(3):
            try:
                if _pointer["sock"] is None:
                    _pointer["sock"] = _pointer_connect()
                # Moves (and hide) go to the pointer service; a click, a long press or a scroll is a touch at the
                # pointer over adb: Google TV ignores an accessibility service's gestures but takes adb's touches.
                for st in steps:
                    if st[0] in ("m", "h"):
                        _pointer["sock"].sendall((" ".join([st[0]] + [f"{v:.1f}" for v in st[1:]]) + "\n").encode())
                    elif st[0] == "s":
                        _pointer["sx"] += st[1]; _pointer["sy"] += st[2]
                    else:
                        x, y = _pointer_pos()
                        _pointer_shell(f"input touchscreen tap {x} {y}" if st[0] == "c"
                                       else f"input touchscreen swipe {x} {y} {x} {y} 700")
                return {"ok": True, "mode": "pointer"}
            except (OSError, ConnectionError) as e:
                log(f"trackpad: connection to the pointer service lost ({e}); attempt {attempt + 1}")
                try:
                    if _pointer["sock"]:
                        _pointer["sock"].close()
                except OSError:
                    pass
                _pointer["sock"] = None
                if "key yet" in str(e):
                    return {"ok": False, "error": str(e)}
                if attempt == 0 and time.time() - _pointer["enabled_at"] > 20:   # not listening: the service is off
                    _pointer["enabled_at"] = time.time()
                    why = _pointer_switch_on()
                    if why:
                        return {"ok": False, "error": why}
                time.sleep(1.2)
        return {"ok": False, "error": "The Chromecast's pointer didn't answer. Is the Chromecast on, with the latest Home app (android/build.sh install)?"}


_np_cache = {"at": 0.0, "data": None}


def now_playing_cached() -> dict:
    if time.time() - _np_cache["at"] > 3:
        try:
            data = cc_now_playing()
        except subprocess.TimeoutExpired:
            data = {"ok": False, "error": "The Chromecast didn't answer in time."}
        p = data.get("playing")
        if p:   # an icon address the home screens may load (the panel's own needs the sign-in)
            data = dict(data, playing=dict(p, screen_icon=f"/api/cc-icon/{p['app']}"))
        _np_cache.update(at=time.time(), data=data)
    return _np_cache["data"]


def cc_now_playing() -> dict:
    """What the Chromecast is playing, from its media sessions: the app, title, artist or channel, playing or paused,
    and how far in. The session the media buttons go to wins; else the first one playing.
    From the launcher when it may read the media sessions (it knows the length and the picture too), else over adb."""
    try:
        got = _cc_np_launcher()
        if got is not None:
            return got
    except Exception as e:
        log(f"now playing (launcher): {e}")
    adb, serial, why = _cc_serial()
    if not serial:
        return {"ok": False, "error": why}
    out = subprocess.run([adb, "-s", serial, "shell", "dumpsys media_session; echo ---; cat /proc/uptime"],
                         capture_output=True, text=True, timeout=10).stdout
    dump, _, up = out.partition("\n---\n")
    try:
        now_ms = float(up.split()[0]) * 1000
    except (ValueError, IndexError):
        now_ms = 0
    m = re.search(r"Media button session is ([\w.]+)/", dump)
    button_pkg = m.group(1) if m else ""
    sessions, cur = [], None
    for line in dump.splitlines():
        line = line.strip()
        if line.startswith("package="):
            cur = {"app": line[8:]}
            sessions.append(cur)
        elif cur is not None and line.startswith("active="):
            cur["active"] = line.endswith("true")
        elif cur is not None and line.startswith("state=PlaybackState"):
            st = re.search(r"state=([A-Z_]+)\(", line[19:])
            pos = re.search(r"position=(\d+)", line)
            spd = re.search(r"speed=([\d.]+)", line)
            upd = re.search(r"updated=(\d+)", line)
            cur["state"] = st.group(1).lower() if st else ""
            p = int(pos.group(1)) if pos else 0
            if cur["state"] == "playing" and upd and spd and now_ms:
                p += max(0, now_ms - int(upd.group(1))) * float(spd.group(1))
            cur["position"] = int(p / 1000)
        elif cur is not None and line.startswith("metadata: size="):
            d = line.partition("description=")[2]
            parts = d.split(", ", 2) if d else []   # "title, subtitle, description": the description may have commas
            clean = lambda x: "" if x in ("null", "") else x
            cur["title"] = clean(parts[0]) if parts else ""
            cur["subtitle"] = clean(parts[1]) if len(parts) > 1 else ""
    usable = [x for x in sessions if x.get("title") or x.get("state") in ("playing", "paused")]
    pick = next((x for x in usable if x["app"] == button_pkg and x.get("state") in ("playing", "paused")), None) \
        or next((x for x in usable if x.get("state") == "playing"), None) \
        or next((x for x in usable if x.get("active") and x.get("state") == "paused"), None)
    if not pick:
        return {"ok": True, "playing": None}
    names = {a["id"]: a.get("name", a["id"]) for a in ADMIN.cc_apps().get("apps", [])}
    pick["name"] = names.get(pick["app"], pick["app"].rsplit(".", 1)[-1])
    pick["icon"] = f"/api/admin/icon/cc/{pick['app']}"
    return {"ok": True, "playing": pick}


def remote_press(device: str, key: str = "", text: str = "") -> dict:
    if device == "tv":
        if key == "home":   # your Home (this launcher), not VIDAA's
            threading.Thread(target=HOME_WATCH.open_launcher, args=("the remote", True), daemon=True).start()
            return {"ok": True}
        if key not in TV_KEYS:
            raise ValueError("the TV has no such key here")
        TV.send_key(TV_KEYS[key])
        return {"ok": True}
    if device != "cc":
        raise ValueError("unknown device")
    adb, serial, why = _cc_serial()
    if not serial:
        if not text and key in CC_KEYS and not _cc_service(f"k {key}"):
            return {"ok": True, "via": "launcher"}   # Wireless debugging is off: the launcher's service did it
        return {"ok": False, "error": why}
    if text:   # typed on the phone, into whatever box has focus on the Chromecast
        clean = "".join(ch for ch in str(text)[:200] if ch.isprintable()).replace(" ", "%s")
        cmd = ["shell", "input", "text", shlex.quote(clean)]
    elif key in CC_KEYS:
        cmd = ["shell", "input", "keyevent", "KEYCODE_" + CC_KEYS[key]]
    else:
        raise ValueError("the Chromecast has no such key here")
    r = subprocess.run([adb, "-s", serial] + cmd, capture_output=True, text=True, timeout=10)
    return {"ok": r.returncode == 0, "error": "" if r.returncode == 0 else "The Chromecast didn't take it."}


def remote_open(device: str, app_id: str = "", url: str = "") -> dict:
    """Open an app, or a web address, on the TV or the Chromecast (the admin panel's remote). A per-app VPN country
    is switched to first, as when the app is opened from Home."""
    web = app_id[4:] if app_id.startswith("web-") else app_id[10:] if app_id.startswith("addon:web-") else ""
    if web:   # a website tile: its address, in that device's browser
        w = website(web)
        if not w:
            raise ValueError("no such website")
        url, app_id = w["url"], ""
        if device == "cc" and cc_web_browser():
            adb, serial, why = _cc_serial()
            if serial:
                r = subprocess.run([adb, "-s", serial, "shell", "am", "start", "-a", "android.intent.action.VIEW", "-d",
                                    shlex.quote(url), "-p", cc_web_browser()], capture_output=True, text=True, timeout=20)
                if r.returncode == 0 and "Error" not in r.stdout + r.stderr:
                    return {"ok": True}
    if url:
        url = url.strip()
        if not re.match(r"^[a-z][a-z0-9+.-]*://", url, re.I):
            url = "https://" + url
        if urlsplit(url).scheme not in ("http", "https") or not urlsplit(url).netloc:
            raise ValueError("that isn't a web address")
    if device == "tv":
        if url:
            browse(url)
            return {"ok": True}
        page = next((p for p in PAGES if p["appId"] == app_id), None)
        if page:   # one of this helper's own screens (YouTube, Live TV): opened as the launcher's tile, on that page
            state_path = DATA / "helper_state.json"
            state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {}
            TV.launch({"appId": state.get("tile", "cythome"), "name": page["name"],
                       "url": f"http://{own_ip()}:{CONFIG.get('port', 8765)}{page['page']}"})
            return {"ok": True}
        app = APPS.get(app_id)
        if not app:
            raise ValueError("the TV has no such app")
        threading.Thread(target=open_app, args=(app,), daemon=True).start()
        return {"ok": True}
    if device != "cc":
        raise ValueError("unknown device")
    adb, serial, why = _cc_serial()
    if not serial:
        ok_app = bool(app_id) and re.match(r"^[A-Za-z0-9_.]+$", app_id)
        if (url or ok_app) and not _cc_service(f"u {url}" if url else f"o {app_id}"):
            if not url:
                vpn_for_app(app_id)
            return {"ok": True, "via": "launcher"}
        return {"ok": False, "error": why}
    if url:   # its app if one takes such links (YouTube, Netflix…), else the browser
        cmd = ["shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", shlex.quote(url)]
    else:
        if not re.match(r"^[A-Za-z0-9_.]+$", app_id):
            raise ValueError("the Chromecast has no such app")
        vpn_for_app(app_id)
        cmd = ["shell", "monkey", "-p", app_id, "-c", "android.intent.category.LEANBACK_LAUNCHER", "1"]
    r = subprocess.run([adb, "-s", serial] + cmd, capture_output=True, text=True, timeout=20)
    if not url and ("No activities found" in r.stdout + r.stderr or r.returncode != 0):   # a phone-style app: its plain launcher
        r = subprocess.run([adb, "-s", serial, "shell", "monkey", "-p", app_id, "-c", "android.intent.category.LAUNCHER", "1"],
                           capture_output=True, text=True, timeout=20)
    ok = r.returncode == 0 and "Error" not in r.stdout and "No activities found" not in r.stdout
    return {"ok": ok, "error": "" if ok else "The Chromecast couldn't open it."}


def remote_status() -> dict:
    out = {"tv": {"on": HOME_WATCH.up}, "cc": {"linked": False, "awake": None, "why": ""}}
    try:
        adb, serial, why = _cc_serial()
        out["cc"]["why"] = why
        if serial:
            power = subprocess.run([adb, "-s", serial, "shell", "dumpsys power | grep -m1 mWakefulness"],
                                   capture_output=True, text=True, timeout=10).stdout
            out["cc"].update(linked=True, awake="Awake" in power, front=_pointer_foreground())
        elif CC_IPS and not _cc_service("z"):   # (a no-op) Wireless debugging is off, but the launcher answers
            out["cc"].update(linked=True, limited=True, why="")
    except subprocess.TimeoutExpired:
        out["cc"]["why"] = "The Chromecast didn't answer in time."
    out["sleep"] = sleep_state()
    return out


# ---------- phone shortcuts: one secret link per action, for the phone's lock screen, Control Center or widgets ----------
# GET http://<helper>:8765/q/<secret>/<action>  (an iPhone Shortcut's "Get Contents of URL", Android's HTTP Shortcuts).
# Only from home (this computer's network, like the admin panel); the secret is the whole sign-in, so the panel can
# make a new one, which stops the old links.
QUICK_KEYS = {"tv": ("power", "home", "mute", "volup", "voldown", "play", "back", "input"),
              "cc": ("wake", "power", "home", "play", "next", "prev", "mute", "volup", "voldown", "back")}


def quick_token(new: bool = False) -> str:
    f = DATA / "quick-token"
    if not new:
        try:
            t = f.read_text().strip()
            if len(t) >= 16:
                return t
        except OSError:
            pass
    import secrets
    DATA.mkdir(exist_ok=True)
    t = secrets.token_urlsafe(18)
    f.write_text(t)
    try:
        f.chmod(0o600)
    except OSError:
        pass
    return t


def quick_base() -> str:
    return f"http://{own_ip()}:{CONFIG.get('port', 8765)}/q/{quick_token()}/"


def _tv_mac() -> str:
    """The TV's network card, for Wake-on-LAN: "tv_mac" in config.json, or what this computer saw while it was on."""
    mac = str(CONFIG.get("tv_mac", "")).strip().lower()
    f = DATA / "tv-mac"
    if not mac:
        try:
            out = subprocess.run(["/usr/sbin/arp", "-n", tv_host()], capture_output=True, text=True, timeout=5).stdout
            m = re.search(r"(([0-9a-f]{1,2}[:-]){5}[0-9a-f]{1,2})", out, re.I)
            if m:
                mac = ":".join(x.zfill(2) for x in re.split(r"[:-]", m.group(1).lower()))
                f.write_text(mac)
        except Exception:
            pass
    if not mac:
        try:
            mac = f.read_text().strip()
        except OSError:
            pass
    return mac


def tv_wake() -> str:
    """Wake-on-LAN for the TV (it needs *Wake on LAN/Wi-Fi* on in its network settings). Answers why not, or ""."""
    mac = _tv_mac()
    if not re.fullmatch(r"([0-9a-f]{2}:){5}[0-9a-f]{2}", mac or ""):
        return "Don't know the TV's network address yet: turn it on once with its remote while the helper runs."
    packet = b"\xff" * 6 + bytes.fromhex(mac.replace(":", "")) * 16
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as so:
        so.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for to in ("255.255.255.255", own_ip().rsplit(".", 1)[0] + ".255", tv_host()):
            for port in (9, 7):
                try:
                    so.sendto(packet, (to, port))
                except OSError:
                    pass
    return ""


# ---------- scenes: several shortcut actions in a row, one tap ("Movie night": wake the Chromecast, VPN US, Netflix) ----------
SCENES_FILE = DATA / "scenes.json"
_STEP = re.compile(r"^((tv|cc)-[a-z]+|(tv|cc)-open-[A-Za-z0-9_.:-]+|vpn-[a-z]+|wait-\d{1,2}|sleep-(\d{1,3}|end|off)|scene-[a-z0-9]+)$")


def scenes() -> list:
    try:
        v = json.loads(SCENES_FILE.read_text(encoding="utf-8"))
        return v if isinstance(v, list) else []
    except (OSError, ValueError):
        return []


def save_scenes(v) -> list:
    if not isinstance(v, list) or len(v) > 20:
        raise ValueError("at most 20 scenes")
    out, ids = [], set()
    for sc in v:
        if not isinstance(sc, dict):
            raise ValueError("bad scene")
        name = str(sc.get("name", "")).strip()[:40]
        sid = re.sub(r"[^a-z0-9]+", "", str(sc.get("id") or name).lower())[:24] or "scene"
        base, n = sid, 2
        while sid in ids:
            sid, n = f"{base}{n}", n + 1
        ids.add(sid)
        steps = [str(x) for x in (sc.get("steps") or [])][:12]
        bad = [x for x in steps if not _STEP.match(x)]
        if not name or not steps or bad:
            raise ValueError(f"{name or 'A scene'}: " + ("needs a name" if not name else "needs a step" if not steps else f"unknown step {bad[0]}"))
        out.append({"id": sid, "name": name, "icon": str(sc.get("icon", ""))[:4], "steps": steps})
    SCENES_FILE.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    return out


def run_scene(sid: str) -> dict:
    sc = next((x for x in scenes() if x["id"] == sid), None)
    if not sc:
        return {"ok": False, "error": "No such scene."}

    def go():
        for st in sc["steps"]:
            try:
                if st.startswith("wait-"):
                    time.sleep(int(st[5:]))
                    continue
                r = quick_action(st)
                if not r.get("ok"):
                    log(f"scene {sc['name']}: {st}: {r.get('error')}")
            except Exception as e:
                log(f"scene {sc['name']}: {st}: {e}")
            # a device waking up takes a few seconds before it takes the next step
            time.sleep(5 if st in ("cc-wake", "tv-on") else 1.2)
        log(f"scene {sc['name']}: done")
    threading.Thread(target=go, daemon=True).start()
    return {"ok": True, "said": f"{sc['name']}: on its way."}


# ---------- HTTPS for the panel and phone notifications (helper/secure.py; needs the "cryptography" package) ----------
HTTPS_PORT = int(CONFIG.get("https_port", int(CONFIG.get("port", 8765)) + 1))
CERTS = secure.Certs(DATA, log) if secure.AVAILABLE else None
PUSH = secure.Push(DATA, log, str(CONFIG.get("push_contact", "mailto:helper@example.com"))) if secure.AVAILABLE else None
_notified = {}


def notify(key: str, title: str, body: str = "", every: float = 0, **kw):
    """A notification to the phones that asked for them; `every`: at most once in that many seconds per key."""
    if not PUSH or not PUSH.subs():
        return
    if every and time.time() - _notified.get(key, 0) < every:
        return
    _notified[key] = time.time()
    PUSH.send_later(title, body, tag=key, **kw)


def https_state() -> dict:
    return {"on": bool(CERTS), "port": HTTPS_PORT, "url": f"https://{own_ip()}:{HTTPS_PORT}/admin",
            "push_key": PUSH.public_key() if PUSH else "", "phones": len(PUSH.subs()) if PUSH else 0}


# ---------- names for addresses, in the panel's Logs ("iPhone (192.168.178.106)") ----------
_clients: dict = {}   # address -> what its browser said it is, from the admin panel's requests
_names: dict = {}     # address -> (name, when), from the router


def _ua_label(ua: str) -> str:
    u = ua or ""
    dev = ("iPhone" if "iPhone" in u else "iPad" if "iPad" in u else "Android phone" if "Android" in u and "Mobile" in u
           else "Android" if "Android" in u else "Mac" if "Macintosh" in u else "Windows PC" if "Windows" in u
           else "Linux PC" if "Linux" in u else "")
    return dev


def _ptr(ip: str) -> str:
    """The router's name for an address (a reverse DNS question straight to it: macOS's own lookup can take 30 s)."""
    import struct as _st
    q = b"".join(bytes([len(x)]) + x.encode() for x in ip.split(".")[::-1] + ["in-addr", "arpa"]) + b"\x00"
    pkt = _st.pack(">HHHHHH", 0x5a5a, 0x0100, 1, 0, 0, 0) + q + _st.pack(">HH", 12, 1)
    gw = own_ip().rsplit(".", 1)[0] + ".1"
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as so:
            so.settimeout(0.8)
            so.sendto(pkt, (gw, 53))
            data = so.recv(512)
    except OSError:
        return ""

    def name_at(off, depth=0):
        parts = []
        while off < len(data) and depth < 10:
            n = data[off]
            if n == 0:
                return ".".join(parts), off + 1
            if n & 0xC0 == 0xC0:
                sub, _ = name_at(((n & 0x3F) << 8) | data[off + 1], depth + 1)
                return ".".join(parts + ([sub] if sub else [])), off + 2
            parts.append(data[off + 1:off + 1 + n].decode("utf-8", "replace"))
            off += 1 + n
        return ".".join(parts), off
    try:
        an = _st.unpack(">H", data[6:8])[0]
        if not an:
            return ""
        _, off = name_at(12)
        off += 4                       # the question's type and class
        _, off = name_at(off)          # the answer's name
        typ, _, _, rdlen = _st.unpack(">HHIH", data[off:off + 10])
        if typ != 12:
            return ""
        name, _ = name_at(off + 10)
    except (IndexError, _st.error):
        return ""
    for tail in (".fritz.box", ".local", ".lan", ".home", ".localdomain"):
        if name.endswith(tail):
            name = name[: -len(tail)]
    return "" if re.fullmatch(r"[0-9a-f-]{20,}", name) else name   # a random-looking id is no name


def device_name(ip: str) -> str:
    if ip in ("127.0.0.1", own_ip()):
        return "this computer"
    if VIDAA and ip == tv_host():
        return "TV"
    if ip in (CC_IPS or []):
        return "Chromecast"
    if ip in _clients:
        return _clients[ip]
    hit = _names.get(ip)
    if not hit or time.time() - hit[1] > 3600:
        hit = (_ptr(ip), time.time())
        _names[ip] = hit
    return hit[0]


def name_ips(line: str) -> str:
    def sub(m):
        ip = m.group(0)
        if not re.match(r"^(10|127|192\.168|172\.(1[6-9]|2\d|3[01]))\.", ip):
            return ip
        n = device_name(ip)
        return f"{n} ({ip})" if n else ip
    return re.sub(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])", sub, line)


class QuietServer(ThreadingHTTPServer):
    """A dropped connection or a refused certificate is one line in the log, not a traceback. And it starts at once:
    HTTPServer looks up this computer's network name when it starts, which takes 30 seconds on some networks (the panel
    couldn't reach the helper all that time after each restart); nothing here needs that name."""
    def server_bind(self):
        import socketserver
        socketserver.TCPServer.server_bind(self)
        host, port = self.server_address[:2]
        self.server_name, self.server_port = str(host), port

    def handle_error(self, request, client_address):
        e = sys.exc_info()[1]
        ip = client_address[0] if client_address else "?"
        import ssl as _ssl
        if isinstance(e, _ssl.SSLError):
            log(f"https: {ip} refused the certificate ({getattr(e, 'reason', None) or e}): this home's certificate isn't installed and trusted there")
        elif isinstance(e, (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, TimeoutError)):
            dlog(f"{ip} left before the answer was sent ({type(e).__name__})")
        else:
            log(f"request from {ip} failed: {type(e).__name__}: {e}")
            super().handle_error(request, client_address)


def https_serve():
    """The same helper on HTTPS_PORT, with this home's own certificate (the TV and the Chromecast stay on http)."""
    if not CERTS:
        log("https: off (pip install cryptography to turn it on)")
        return
    try:
        ctx = CERTS.context(own_ip())
        srv = QuietServer(("0.0.0.0", HTTPS_PORT), partial(Handler, directory=str(WEB)))
        # the handshake in the request's own thread, so a slow phone doesn't hold up the others
        srv.socket = ctx.wrap_socket(srv.socket, server_side=True, do_handshake_on_connect=False)
        log(f"https: the panel is also at https://<this computer's ip>:{HTTPS_PORT}/admin")
        srv.serve_forever()
    except Exception as e:
        log(f"https: {e}")


# ---------- watch stats: what played on the Chromecast and for how long, per day (kept here only, 90 days) ----------
STATS_FILE = DATA / "watch-stats.json"
STATS_EVERY = 30
_stats = {"data": None, "dirty": False, "saved": 0.0, "lock": threading.Lock()}


def _stats_load() -> dict:
    if _stats["data"] is None:
        try:
            _stats["data"] = json.loads(STATS_FILE.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            _stats["data"] = {"days": {}}
    return _stats["data"]


def _stats_add(p: dict, secs: float):
    day = time.strftime("%Y-%m-%d")
    with _stats["lock"]:
        d = _stats_load()["days"].setdefault(day, {"apps": {}, "titles": {}})
        app = p.get("app", "")
        d["apps"][app] = round(d["apps"].get(app, 0) + secs)
        title = (p.get("title") or "").strip()
        if title:
            k = (app + "|" + title)[:300]
            t = d["titles"].setdefault(k, {"app": app, "title": title[:200], "sub": (p.get("subtitle") or "")[:120], "secs": 0})
            t["secs"] = round(t["secs"] + secs)
            t["last"] = int(time.time())
        days = _stats["data"]["days"]
        for old in sorted(days)[:-90]:
            del days[old]
        _stats["dirty"] = True
        if time.time() - _stats["saved"] > 120:
            _stats_save()


def _stats_save():
    if _stats["dirty"]:
        tmp = STATS_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(_stats["data"], ensure_ascii=False), encoding="utf-8")
        tmp.replace(STATS_FILE)
        _stats.update(dirty=False, saved=time.time())


def _tv_screen() -> tuple[str, str]:
    """(key, name) for what the TV shows now: an app, Live TV, an input, its home screen; ("", "") when off."""
    if not (VIDAA and HOME_WATCH.up):
        return "", ""
    st = HOME_WATCH.last_state or {}
    kind = str(st.get("statetype", ""))
    if kind == "sourceswitch":
        src = str(st.get("sourceid") or st.get("sourcename") or "input")
        return "src:" + src, str(st.get("displayname") or st.get("sourcename") or src)
    if kind == "livetv":
        return "livetv", "Live TV"
    if kind == "app":
        name = str(st.get("name") or st.get("url") or "app")
        if HOME_WATCH.open_apps:   # the DIAL apps say exactly which one (Netflix, YouTube, Prime Video)
            dial = sorted(HOME_WATCH.open_apps)[0]
            name = {"AmazonInstantVideo": "Prime Video"}.get(dial, dial)
        low = name.lower()
        if "cythome" in low or "launcher" in low or low in ("home", "remote_launcher"):
            return "home", "Home screen"
        return "app:" + low[:60], name[:60]
    if kind in ("remote_launcher", "launcher", "home"):
        return "home", "Home screen"
    return ("other:" + kind[:30], kind.replace("_", " ").capitalize()) if kind else ("", "")


def _stats_add_tv(key: str, name: str, secs: float):
    day = time.strftime("%Y-%m-%d")
    with _stats["lock"]:
        data = _stats_load()
        d = data["days"].setdefault(day, {"apps": {}, "titles": {}})
        tv = d.setdefault("tv", {})
        x = tv.setdefault(key, {"name": name, "secs": 0})
        x["name"], x["secs"] = name, round(x["secs"] + secs)
        _stats["dirty"] = True
        if time.time() - _stats["saved"] > 120:
            _stats_save()


def stats_loop():
    """Every 30 s: what the TV shows (while it's on), and what the Chromecast plays: 30 s more for each."""
    last = time.time()
    while True:
        time.sleep(STATS_EVERY)
        now = time.time()
        gap, last = min(now - last, STATS_EVERY * 2), now
        playing = None
        try:
            p = now_playing_cached().get("playing") if CC_IPS else None
            if p and p.get("state") == "playing":
                playing = p
                _stats_add(p, gap)
        except Exception as e:
            log(f"watch stats: {e}")
        try:
            key, name = _tv_screen()
            if key:
                if playing and key.startswith("src:"):   # the TV shows an input while the Chromecast plays: that's its input
                    with _stats["lock"]:
                        _stats_load()["cc_input"] = key
                _stats_add_tv(key, name, gap)
        except Exception as e:
            log(f"watch stats (TV): {e}")


def stats_report(days: int = 7) -> dict:
    """Totals for the last `days` days: per app, per day, and the titles watched longest."""
    days = max(1, min(90, int(days)))
    with _stats["lock"]:
        all_days = dict(_stats_load()["days"])
    want = [time.strftime("%Y-%m-%d", time.localtime(time.time() - i * 86400)) for i in range(days)][::-1]
    names = {a["id"]: a.get("name", a["id"]) for a in ADMIN.cc_apps().get("apps", [])}
    rename = CHROMECAST.get("rename") or {}
    apps, titles, per_day = {}, {}, []
    for day in want:
        d = all_days.get(day) or {"apps": {}, "titles": {}}
        per_day.append({"day": day, "secs": sum(d["apps"].values())})
        for a, v in d["apps"].items():
            apps[a] = apps.get(a, 0) + v
        for k, t in d["titles"].items():
            x = titles.setdefault(k, dict(t, secs=0))
            x["secs"] += t["secs"]
            x["last"] = max(x.get("last", 0), t.get("last", 0))
    app_list = [{"app": a, "name": rename.get(a) or names.get(a, a.rsplit(".", 1)[-1]), "secs": v,
                 "icon": f"/api/admin/icon/cc/{a}"} for a, v in sorted(apps.items(), key=lambda kv: -kv[1]) if v >= 60]
    top = sorted(titles.values(), key=lambda t: -t["secs"])[:15]
    for t in top:
        t["name"] = rename.get(t["app"]) or names.get(t["app"], t["app"].rsplit(".", 1)[-1])
    # the TV: per screen (apps, Live TV, inputs; the Chromecast's input by name), and per day
    tv, tv_day = {}, []
    # the input the Chromecast is on: "tv_input" in the chromecast settings, else what was seen (an input on while it played)
    cc_in = ("src:" + str(CHROMECAST["tv_input"])) if CHROMECAST.get("tv_input") else _stats_load().get("cc_input", "")
    for day in want:
        d = (all_days.get(day) or {}).get("tv") or {}
        tv_day.append({"day": day, "secs": sum(x["secs"] for x in d.values())})
        for k, x in d.items():
            y = tv.setdefault(k, {"key": k, "name": x["name"], "secs": 0})
            y["secs"] += x["secs"]
    tv_names = {str(a.get("name", "")).lower(): str(a.get("appId")) for a in APPS.apps} if VIDAA else {}
    tv_list = []
    for x in sorted(tv.values(), key=lambda v: -v["secs"]):
        if x["secs"] < 60:
            continue
        if x["key"] == cc_in:
            x["name"], x["icon"] = f"Chromecast ({x['name']})", ""
            x["cc"] = True
        else:
            aid = tv_names.get(x["name"].lower())
            x["icon"] = f"/api/admin/icon/tv/{aid}" if aid else ""
            if aid:   # the app's own name ("YouTube", not the TV's "youtube")
                x["name"] = (CONFIG.get("rename", {}).get(aid) or (APPS.get(aid) or {}).get("name") or x["name"]).replace("▶", "").strip()
        tv_list.append(x)
    # all together: the TV's own screens and the Chromecast, its input counted once (by what played on it)
    all_day = []
    for day, cd in zip(want, per_day):
        d = (all_days.get(day) or {}).get("tv") or {}
        own = sum(x["secs"] for k, x in d.items() if k != cc_in)
        cc_part = max(d.get(cc_in, {}).get("secs", 0) if cc_in else 0, cd["secs"]) if VIDAA else cd["secs"]
        all_day.append({"day": day, "tv": own, "cc": cc_part if CC_IPS or cc_in else 0})
    items = [dict(x, dev="tv") for x in tv_list if not x.get("cc")]
    items += [dict(a, dev="cc") for a in app_list]
    cc_input_secs = tv.get(cc_in, {}).get("secs", 0) if cc_in else 0
    extra = cc_input_secs - sum(apps.values())
    if extra >= 60:
        items.append({"name": "Chromecast: menus and other", "secs": extra, "icon": "", "dev": "cc", "key": "cc-other"})
    items.sort(key=lambda x: -x["secs"])
    return {"days": days, "total": sum(apps.values()), "apps": app_list, "per_day": per_day,
            "titles": [t for t in top if t["secs"] >= 60],
            "tv": {"on": bool(VIDAA), "total": sum(x["secs"] for x in tv.values()), "per_day": tv_day, "screens": tv_list},
            "all": {"total": sum(x["tv"] + x["cc"] for x in all_day), "per_day": all_day, "items": items},
            "cc": bool(CC_IPS)}


# ---------- sleep timer: the TV and the Chromecast off after a while, or once the video has stopped ----------
SLEEP = {"at": 0.0, "end": False, "seen": False, "since": 0.0, "thread": None, "v": ""}
SLEEP_AFTER_STOP = 90   # seconds of nothing playing (in "end of video" mode) before turning off


def sleep_state() -> dict:
    left = max(0, int(SLEEP["at"] - time.time())) if SLEEP["at"] else 0
    return {"on": bool(SLEEP["at"]), "end": SLEEP["end"] and bool(SLEEP["at"]), "left": left,
            "v": SLEEP["v"] if SLEEP["at"] else "off"}


def sleep_set(v: str) -> dict:
    v = str(v).strip().lower()
    if v in ("off", "0", ""):
        SLEEP.update(at=0.0, end=False)
        log("sleep timer: off")
        return sleep_state()
    if v == "end":
        SLEEP.update(at=time.time() + 4 * 3600, end=True, seen=False, since=0.0, v="end", warned=False)   # at the latest in 4 hours
    else:
        try:
            m = int(v)
        except ValueError:
            raise ValueError("minutes, end or off")
        if not 1 <= m <= 600:
            raise ValueError("1 to 600 minutes")
        SLEEP.update(at=time.time() + m * 60, end=False, v=str(m), warned=False)
    log("sleep timer: " + ("when the video ends" if SLEEP["end"] else f"in {v} min"))
    if not (SLEEP["thread"] and SLEEP["thread"].is_alive()):
        SLEEP["thread"] = threading.Thread(target=_sleep_loop, daemon=True)
        SLEEP["thread"].start()
    return sleep_state()


def _sleep_loop():
    while SLEEP["at"]:
        time.sleep(10)
        if not SLEEP["at"]:
            return
        now = time.time()
        if SLEEP["end"] and CC_IPS:
            try:
                p = now_playing_cached().get("playing")
            except Exception:
                p = None
            if p and p.get("state") == "playing":
                SLEEP.update(seen=True, since=0.0)
            elif SLEEP["seen"]:
                SLEEP["since"] = SLEEP["since"] or now
                if now - SLEEP["since"] >= SLEEP_AFTER_STOP:
                    SLEEP["at"] = now
        if SLEEP["at"] and not SLEEP.get("warned") and (SLEEP["at"] - now <= 70 or
                                                        (SLEEP["end"] and SLEEP["since"] and now - SLEEP["since"] >= SLEEP_AFTER_STOP - 70)):
            SLEEP["warned"] = True
            notify("sleep", "The TV turns off in about a minute", "Sleep timer", url="/admin",
                   actions=[{"id": "keep", "title": "Keep watching", "post": "/api/admin/sleep", "body": {"set": "off"}}])
        if SLEEP["at"] and now >= SLEEP["at"]:
            SLEEP.update(at=0.0, end=False)
            _sleep_now()


def _sleep_now():
    """The TV first (its power key toggles, so only while it's on), then the Chromecast to sleep."""
    log("sleep timer: turning off")
    try:
        if VIDAA and HOME_WATCH.up:
            remote_press("tv", "power")
            time.sleep(3)
    except Exception as e:
        log(f"sleep timer: TV: {e}")
    try:
        if CC_IPS:
            remote_press("cc", "power")
    except Exception as e:
        log(f"sleep timer: Chromecast: {e}")


def quick_action(action: str) -> dict:
    """What a phone shortcut asked for: tv-<key>, tv-on, tv-off, cc-<key>, tv-open-<app>, cc-open-<app>, vpn-<country>,
    scene-<id>."""
    dev, _, rest = action.partition("-")
    if dev == "scene":
        return run_scene(rest)
    if dev == "sleep":
        st = sleep_set(rest)
        return {"ok": True, "said": "Sleep timer off." if not st["on"] else "Turning off when the video ends."
                if st["end"] else f"Turning off in {round(st['left'] / 60)} min."}
    if dev == "vpn":
        if not vpn_available() or rest not in [r["id"] for r in regions()]:
            return {"ok": False, "error": "No such VPN country here."}
        _auto_vpn["prev"] = None   # a choice by hand: no app switches it back
        if region_now() != rest:
            region_set(rest)
        return {"ok": True, "said": "VPN: " + next(r["name"] for r in regions() if r["id"] == rest)}
    if dev == "tv" and not VIDAA or dev == "cc" and not CC_IPS or dev not in ("tv", "cc"):
        return {"ok": False, "error": "No such device here."}
    name = "the TV" if dev == "tv" else "the Chromecast"
    if rest.startswith("open-"):
        app = rest[5:]
        r = remote_open(dev, app_id=app)
        label = app
        try:
            if dev == "cc":
                label = (CHROMECAST.get("rename") or {}).get(app) or \
                    next((a.get("name") for a in ADMIN.cc_apps().get("apps", []) if a.get("id") == app), app)
            else:
                label = (APPS.get(app) or {}).get("name", app)
        except Exception:
            pass
        return r if r.get("ok") is False else {"ok": True, "said": f"Opening {label} on {name}."}
    if dev == "tv" and rest == "on":
        if HOME_WATCH.up:
            _tv_mac()   # learnt while it's on, for waking it later
            return {"ok": True, "said": "The TV is already on."}
        why = tv_wake()
        return {"ok": not why, "said": "Waking the TV…", "error": why}
    if dev == "tv" and rest == "off":
        if HOME_WATCH.up is False:
            return {"ok": True, "said": "The TV is already off."}
        _tv_mac()   # learnt while it's on, for "tv-on"
        remote_press("tv", "power")
        return {"ok": True, "said": "The TV is turning off."}
    if rest not in QUICK_KEYS[dev]:
        return {"ok": False, "error": "No such button."}
    r = remote_press(dev, rest)
    said = {"wake": "Waking the Chromecast.", "power": "The Chromecast is going to sleep." if dev == "cc" else "Power on the TV.",
            "home": f"Home on {name}.", "play": f"Play / pause on {name}.", "next": "Next.", "prev": "Previous.",
            "mute": f"Mute on {name}.", "volup": f"Volume up on {name}.", "voldown": f"Volume down on {name}.",
            "back": f"Back on {name}.", "input": "Input on the TV."}.get(rest, "Done.")
    return r if r.get("ok") is False else {"ok": True, "said": said}


def cc_screenshot() -> tuple[bytes | None, str]:
    """A picture of the Chromecast's screen right now, over wireless debugging. Never wakes it."""
    adb = _adb()
    if not adb:
        return None, "adb isn't installed on this computer."
    try:
        out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=10).stdout
        ips = [CC_IPS] if isinstance(CC_IPS, str) else list(CC_IPS or [])
        serial = next((ln.split()[0] for ln in out.splitlines()[1:]
                       if ln.split()[-1:] == ["device"] and any(ln.startswith(ip + ":") for ip in ips)), None)
        if not serial:
            return None, "The Chromecast isn't connected for debugging: run android/cc.sh connect."
        power = subprocess.run([adb, "-s", serial, "shell", "dumpsys power | grep -m1 mWakefulness"],
                               capture_output=True, text=True, timeout=10).stdout
        if "Awake" not in power:
            return None, "The Chromecast is asleep. Turn it on, then try again."
        png = subprocess.run([adb, "-s", serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=20).stdout
        if not png.startswith(b"\x89PNG"):
            return None, "The Chromecast didn't send a picture."
        return png, ""
    except subprocess.TimeoutExpired:
        return None, "The Chromecast didn't answer in time."


# ---------- live screen (admin panel): a frame about every second while someone looks, as a small JPEG ----------
_live = {"jpg": None, "at": 0.0, "want": 0.0, "why": "", "thread": None, "lock": threading.Lock()}


def _live_loop():
    import tempfile
    tmp = Path(tempfile.gettempdir()) / f"cyt-live-{os.getpid()}"
    try:
        while time.time() - _live["want"] < 6:   # stops 6 s after the panel's last ask
            png, why = cc_screenshot()
            if not png:
                _live.update(why=why, jpg=None, at=time.time())
                time.sleep(2)
                continue
            src, dst = tmp.with_suffix(".png"), tmp.with_suffix(".jpg")
            src.write_bytes(png)
            r = subprocess.run(["/usr/bin/sips", "-s", "format", "jpeg", "-s", "formatOptions", "70", "-Z", "960", str(src),
                                "--out", str(dst)], capture_output=True, timeout=10) if Path("/usr/bin/sips").exists() else None
            jpg = dst.read_bytes() if r is not None and r.returncode == 0 and dst.exists() else None
            _live.update(jpg=jpg or png, why="", at=time.time())
    finally:
        _live["thread"] = None


def cc_live_frame(after: float = 0) -> tuple[bytes | None, str]:
    """The newest frame (starts the capture loop when idle): one newer than `after` (the panel's last), waiting up to
    4 s for it, so the panel never fetches the same picture twice."""
    _live["want"] = time.time()
    with _live["lock"]:
        if not (_live["thread"] and _live["thread"].is_alive()):
            _live.update(jpg=None, why="")
            _live["thread"] = threading.Thread(target=_live_loop, daemon=True)
            _live["thread"].start()
    for _ in range(40):
        if (_live["jpg"] and _live["at"] > after) or _live["why"]:
            break
        time.sleep(0.1)
    return _live["jpg"], _live["why"] or ("" if _live["jpg"] else "No picture yet.")


def cc_tap(fx: float, fy: float) -> dict:
    """A tap on the live screen: the same spot on the Chromecast (its screen is 1920 by 1080 here)."""
    adb, serial, why = _cc_serial()
    if not serial:
        return {"ok": False, "error": why}
    x, y = int(max(0, min(1, fx)) * 1919), int(max(0, min(1, fy)) * 1079)
    r = subprocess.run([adb, "-s", serial, "shell", "input", "tap", str(x), str(y)], capture_output=True, text=True, timeout=10)
    return {"ok": r.returncode == 0, "error": "" if r.returncode == 0 else "The Chromecast didn't take the tap."}


def vpn_for_app(app_id: str):
    if not vpn_available():
        return
    target, now = APP_VPN.get(app_id), region_now()
    try:
        if target and now != target:
            if _auto_vpn["prev"] is None:
                _auto_vpn["prev"] = now
            region_set(target)
        elif not target and _auto_vpn["prev"] is not None:
            prev, _auto_vpn["prev"] = _auto_vpn["prev"], None
            if now != prev:
                region_set(prev)
    except Exception as e:
        log(f"TV VPN for {app_id}: {e}")   # open the app anyway; it will say if the region is wrong


def open_app(app: dict):
    app_id = str(app.get("appId"))
    vpn_for_app(app_id)
    if app_id in APP_BROWSER:
        browse(APP_BROWSER[app_id])
    else:
        TV.launch(launch_target(app))
    HOME_WATCH.opened(app_id)


STREMIO = CONFIG.get("stremio") or {}
STREMIO_PORT = 11470
STREMIO_SERVER_JS = "https://dl.strem.io/server/v4.22.3/desktop/server.js"


class AppMirror:
    """A web app kept on this computer and served from it over plain http.

    Stremio's TV build lives on an https site, and an https page may not call the streaming server on this computer,
    which is http (mixed content). Served from here, the page and the server are both plain http on the home network.
    Files are fetched from the app's site when first asked for and kept; when its index.html changes, the copy is
    dropped, since the other files may change under the same names."""

    TYPES = {".wasm": "application/wasm", ".js": "text/javascript", ".mjs": "text/javascript",
             ".json": "application/json", ".svg": "image/svg+xml", ".ttf": "font/ttf"}

    def __init__(self, upstream: str, folder: Path):
        self.upstream, self.folder = upstream.rstrip("/") + "/", folder
        self.lock = threading.Lock()

    def _fetch(self, rel: str, timeout: float) -> bytes:
        req = urllib.request.Request(self.upstream + rel, headers={"User-Agent": "cyt-helper"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read()

    def _index(self) -> bytes:
        cached = self.folder / "index.html"
        try:
            body = self._fetch("", 4)
        except OSError:
            if cached.exists():  # offline: the kept copy still works
                return cached.read_bytes()
            raise
        with self.lock:
            if not cached.exists() or cached.read_bytes() != body:
                shutil.rmtree(self.folder, ignore_errors=True)
                self.folder.mkdir(parents=True)
                cached.write_bytes(body)
                log(f"web app {self.upstream}: fetched a new version")
        return body

    def get(self, rel: str, query: str) -> tuple[bytes, str] | None:
        if rel in ("", "index.html"):
            return self._index(), "text/html; charset=utf-8"
        if ".." in rel.split("/") or rel.startswith("/") or "\\" in rel:
            return None
        path = self.folder / (rel + ("@" + quote(query, safe="") if query else ""))
        if not path.exists():
            body = self._fetch(rel + ("?" + query if query else ""), 30)
            path.parent.mkdir(parents=True, exist_ok=True)
            part = path.with_name(f"{path.name}.{threading.get_ident()}.part")
            part.write_bytes(body)
            part.replace(path)
        ctype = self.TYPES.get(Path(rel).suffix.lower()) or mimetypes.guess_type(rel)[0] or "application/octet-stream"
        return path.read_bytes(), ctype


STREMIO_APP = AppMirror(STREMIO["web_app"], DATA / "stremio-app") if STREMIO.get("web_app") else None


class StremioServer(threading.Thread):
    """Stremio's streaming server (torrents and transcoding), kept running on this computer for the TV's Stremio.

    It is Stremio's own server.js, started with NO_CORS: by default it answers only pages from Stremio's own sites,
    and the TV's Stremio comes from this helper. Stremio desktop, if installed, brings server.js and a runtime for it;
    otherwise Node runs a downloaded copy. Stremio desktop opened later finds this server running and uses it."""

    def __init__(self):
        super().__init__(daemon=True, name="stremio-server")
        self.proc = None
        self.warned = ""

    @staticmethod
    def state() -> str:
        """'open' (the TV's Stremio may use it), 'closed' (running, but for Stremio's own sites only) or 'down'."""
        req = urllib.request.Request(f"http://127.0.0.1:{STREMIO_PORT}/settings", headers={"Origin": "http://cyt.invalid"})
        try:
            with urllib.request.urlopen(req, timeout=3) as r:
                return "open" if r.headers.get("Access-Control-Allow-Origin") else "closed"
        except OSError:
            return "down"

    @staticmethod
    def command() -> list | None:
        if STREMIO.get("server_command"):
            return STREMIO["server_command"]
        desktop = Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "Stremio"
        if (desktop / "stremio-runtime.exe").exists() and (desktop / "server.js").exists():
            return [str(desktop / "stremio-runtime.exe"), str(desktop / "server.js")]
        node = shutil.which("node")
        if not node:
            return None
        script = DATA / "stremio-server" / "server.js"
        if not script.exists():
            log("downloading Stremio's streaming server")
            script.parent.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(STREMIO_SERVER_JS, timeout=120) as r:
                body = r.read()
            script.write_bytes(body)
        return [node, str(script)]

    def start_server(self):
        cmd = self.command()
        if not cmd:
            self.warn("Stremio's streaming server cannot start: install Stremio desktop, or Node.js and ffmpeg")
            return
        DATA.mkdir(exist_ok=True)
        out = open(DATA / "stremio-server.log", "ab")
        self.proc = subprocess.Popen(cmd, cwd=str(Path(cmd[-1]).parent), env=dict(os.environ, NO_CORS="1"),
                                     stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT,
                                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        log(f"started Stremio's streaming server on port {STREMIO_PORT}")

    @staticmethod
    def apply_settings():
        """The TV's player asks for every audio track of a video at once, and the server transcodes one track of a
        kind at a time by default: each request cancels the one before and playback fails. Allow more."""
        wanted = {"transcodeTrackConcurrency": 8, **STREMIO.get("server_settings", {})}
        base = f"http://127.0.0.1:{STREMIO_PORT}/settings"
        with urllib.request.urlopen(base, timeout=3) as r:
            values = json.load(r).get("values", {})
        changed = {k: v for k, v in wanted.items() if values.get(k) != v}
        if changed:
            req = urllib.request.Request(base, data=json.dumps(changed).encode(), method="POST",
                                         headers={"Content-Type": "application/json"})
            urllib.request.urlopen(req, timeout=3).close()
            log(f"Stremio server settings: {changed}")

    def warn(self, msg: str):
        if self.warned != msg:
            log(msg)
            self.warned = msg

    def run(self):
        while True:
            try:
                state = self.state()
                if state == "down" and (self.proc is None or self.proc.poll() is not None):
                    self.start_server()
                elif state == "closed":
                    self.warn("Stremio desktop's own server is running and keeps the TV out: close Stremio desktop, "
                              "and the helper starts one the TV may use")
                elif state == "open":
                    self.warned = ""
                    self.apply_settings()
            except Exception as e:
                log(f"Stremio server: {e}")
            time.sleep(30)


def launch_target(app: dict) -> dict:
    """The app as the TV should open it: Stremio from this helper, with this computer's streaming server."""
    if STREMIO_APP and str(app.get("appId")) == str(STREMIO.get("app_id")):
        ip = own_ip()
        return dict(app, url=f"http://{ip}:{CONFIG.get('port', 8765)}/stremio/?server=http://{ip}:{STREMIO_PORT}")
    return app


def icon_url(app: dict) -> str | None:
    raw = app.get("httpIcon") or ""
    url = raw[raw.find("http"):] if "http" in raw else ""
    return url if urlsplit(url).hostname in ICON_HOSTS else None


def icon_bytes(app_id: str) -> tuple[bytes, str] | None:
    """The app's icon, fetched once from VIDAA's image host and served same-origin so the page can sample it."""
    ICONS.mkdir(parents=True, exist_ok=True)
    cached = next(ICONS.glob(f"{app_id}.*"), None)
    if cached:
        return cached.read_bytes(), "image/" + cached.suffix[1:]
    app = APPS.get(app_id)
    url = app and icon_url(app)
    if not url:
        return None
    with urllib.request.urlopen(url, timeout=10) as r:
        body, ctype = r.read(), r.headers.get_content_type()
    if not ctype.startswith("image/"):
        return None
    (ICONS / f"{app_id}.{ctype.split('/')[1]}").write_bytes(body)
    return body, ctype


class Handler(SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def _allowed(self) -> bool:
        # The TV is allowed at whatever address it has now (the pairing follows it), as are the listed clients.
        ip = self.client_address[0]
        if ip in CONFIG.get("allowed_clients", []) or (VIDAA and ip == tv_host()):
            return True
        path = urlsplit(self.path).path
        if (ip == "127.0.0.1" or ip.rsplit(".", 1)[0] == own_ip().rsplit(".", 1)[0]) and \
                (path in ("/admin", "/admin-sw.js", "/ca.crt", "/home-helper-ca.crt") or path.startswith("/api/admin/")):
            return True
        if path.startswith("/experiences/") and (ip in CC_IPS or ip == "127.0.0.1" or ip.rsplit(".", 1)[0] == own_ip().rsplit(".", 1)[0]):
            return True   # the Home layouts' own files (no data): the screens, and the phone panel's previews
        if ip in CC_IPS and (path in CC_PATHS or path.startswith("/fonts/") or path.startswith("/api/addons/")
                             or path.startswith("/api/cc-icon/")):
            return True
        # A phone shortcut's link: from home only; it carries its own secret (checked in do_GET).
        if path.startswith("/q/") and (ip == "127.0.0.1" or ip.rsplit(".", 1)[0] == own_ip().rsplit(".", 1)[0]):
            return True
        # A phone or computer at home may use the VPN page and its switch, and nothing else.
        if urlsplit(self.path).path in ("/vpn", "/api/region") and ip.rsplit(".", 1)[0] == own_ip().rsplit(".", 1)[0]:
            return True
        self.send_error(403)
        return False

    def _json(self, code: int, data):
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self) -> dict:
        length = min(int(self.headers.get("Content-Length", 0)), 2_000_000)
        try:
            data = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return {}
        return data if isinstance(data, dict) else {}

    def do_GET(self):
        if not self._allowed():
            return
        path = urlsplit(self.path).path
        if path.startswith("/q/"):
            from urllib.parse import unquote
            import hmac
            _, _, tok, *act = path.split("/", 3)
            if not hmac.compare_digest(tok.encode(), quick_token().encode()) or not act:
                self.send_error(403)
                return
            action = unquote(act[0]).strip("/")
            try:
                r = quick_action(action)
            except Exception as e:
                r = {"ok": False, "error": str(e) or "It didn't work."}
            log(f"shortcut {action}: {r.get('said') or r.get('error')}")
            # Plain text: a Shortcut can show it as it is.
            body = ((r.get("said") or "Done.") if r.get("ok") else ("Didn't work: " + (r.get("error") or "?"))).encode()
            self.send_response(200 if r.get("ok") else 502)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/api/state":
            threading.Thread(target=APPS.refresh_if_stale, daemon=True).start()
            self._json(200, {"apps": APPS.for_launcher(), "dock_size": len(CONFIG.get("dock", [])),
                             "greeting_name": CONFIG.get("greeting_name", ""), "rows": CONFIG.get("rows", []),
                             "quotes": QUOTES_ON, "weather": bool(WEATHER), "wallpaper": bool(WALLPAPER_ON),
                             "region": vpn_available(), "live_refresh": CONFIG.get("live_refresh", True) is not False,
                             "clock_24h": CONFIG.get("clock_24h", False), "sources": SOURCES.get(),
                             "removed_count": len(APPS.removed()), "takeover": takeover_on(), "browser": BROWSER,
                             "order_saved": bool(APPS.order()),
                             "layout": CONFIG.get("home_layout") or layout.PRESETS["classic"],
                             "experience": experience.for_screen(CONFIG, "tv"),
                             "started": STARTED, "saved": SAVED["at"]})
            return
        if path == "/api/cc":
            self._json(200, chromecast_settings())
            return
        if path == "/api/home-edit":
            self._json(200, home_edit_state())
            return
        if path.startswith("/experiences/"):   # the Home layouts (shared/experiences): the TV, the Chromecast, the panel
            f = (ROOT / "shared" / "experiences" / path[len("/experiences/"):]).resolve()
            if not str(f).startswith(str((ROOT / "shared" / "experiences").resolve()) + "/") or not f.is_file():
                self.send_error(404)
                return
            ctype = {".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".html": "text/html; charset=utf-8",
                     ".jpg": "image/jpeg", ".png": "image/png"}.get(f.suffix, "application/octet-stream")
            self._send(f.read_bytes(), ctype)
            return
        if path == "/api/home-scenes":   # the scenes the household allowed on Home (Scene Stage, Time River)
            self._json(200, {"ok": True, "scenes": experience.home_scenes(CONFIG, scenes(), APPS.names() if hasattr(APPS, "names") else None)})
            return
        if path == "/api/tv-sources":   # the TV's inputs, for the Chromecast's Home (the TV page has them in /api/state)
            self._json(200, {"ok": True, "sources": SOURCES.get() if VIDAA else []})
            return
        if path == "/api/hub/live":   # the Chromecast's own panel's live screen: a frame from here (adb)
            import hmac
            if self.client_address[0] not in CC_IPS or not hmac.compare_digest(str(self.headers.get("X-Token", "")), pointer_token()):
                self.send_error(403)
                return
            try:
                after = float(parse_qs(urlsplit(self.path).query).get("after", ["0"])[0])
            except ValueError:
                after = 0
            frame, why = cc_live_frame(after)
            if not frame:
                self._json(409, {"ok": False, "error": why})
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg" if frame[:2] == b"\xff\xd8" else "image/png")
            self.send_header("X-Frame-At", str(_live["at"]))
            self.send_header("Content-Length", str(len(frame)))
            self.end_headers()
            self.wfile.write(frame)
            return
        if path.startswith("/api/addons/"):
            self._addons_get(path)
            return
        if path == "/api/now-playing":   # the home screens' Now playing block (the Chromecast's media)
            self._json(200, now_playing_cached())
            return
        if path.startswith("/api/cc-icon/"):
            body = ADMIN.cc_icon(path.rsplit("/", 1)[1])
            if not body:
                self.send_error(404)
                return
            self._send(body, "image/png")
            return
        if path == "/api/admin-link":
            # The TV launcher's QR button: the admin panel's address, and its QR code for a phone camera.
            # Also the remote's and the player's: the same panel, opened on them (#remote, #player).
            url = f"http://{own_ip()}:{CONFIG.get('port', 8765)}/admin"
            links = [{"label": "Admin panel", "url": url}, {"label": "Remote", "url": url + "#remote"},
                     {"label": "Player", "url": url + "#player"}]
            for link in links:
                link["qr"] = qr.matrix(link["url"])
            self._json(200, {"url": url, "qr": links[0]["qr"], "links": links})
            return
        if path == "/api/rev":
            # Polled by both home screens every 3 s: a save in the admin panel changes "saved" (or restarts the
            # helper: "started"), and a new photo changes "wall". Cheap: nothing is fetched or computed here.
            try:
                wall = json.loads(WALLPAPER_META.read_text(encoding="utf-8")).get("image", "") if WALLPAPER_ON else ""
            except (OSError, ValueError):
                wall = ""
            self._json(200, {"started": STARTED, "wall": wall, "saved": SAVED["at"], "code": page_code("index.html")})
            return
        if path == "/admin":
            self._send((PANEL / "admin.html").read_bytes(), "text/html; charset=utf-8")
            return
        if path == "/admin-sw.js":   # the panel's service worker: shows the notifications
            body = (PANEL / "admin-sw.js").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/javascript; charset=utf-8")
            self.send_header("Service-Worker-Allowed", "/")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path in ("/ca.crt", "/home-helper-ca.crt"):   # this home's certificate authority, to install on a phone (public)
            if not CERTS:
                self.send_error(404)
                return
            body = CERTS.ca_der()
            self.send_response(200)
            self.send_header("Content-Type", "application/x-x509-ca-cert")
            self.send_header("Content-Disposition", 'attachment; filename="home-helper-ca.crt"')
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path.startswith("/api/admin/"):
            self._admin_get(path)
            return
        if path == "/vpn":
            body = (WEB / "vpn.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/api/region":
            try:
                now = region_now()
            except Exception as e:
                log(f"region: {e}")
                now = None
            self._json(200, {"region": now, "regions": regions()})
            return
        if path == "/api/weather":
            self._json(200, weather())
            return
        if path == "/api/quote":
            self._json(200, quote_of_day())
            return
        if path == "/api/wallpaper":
            self._json(200, wallpaper_meta())
            return
        if path == "/api/wallpaper.jpg":
            try:
                body = WALLPAPER_IMG.read_bytes()
            except OSError:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "max-age=86400")   # the address changes with the day
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/api/quote-image":
            body = _quote["image"]
            if not body:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/api/suggest":
            query = parse_qs(urlsplit(self.path).query)
            self._json(200, suggestions(query.get("q", [""])[0], query.get("kl", [""])[0]))
            return
        if path == "/go":
            self._go()
            return
        if path.startswith("/api/yt/") or path == "/yt/stream" or path in ("/api/livetv", "/proxy"):
            self._extras(path, {k: v[0] for k, v in parse_qs(urlsplit(self.path).query).items()})
            return
        if path == "/api/sources":
            try:
                self._json(200, {"ok": True, "sources": TV.sources()})
            except Exception as e:
                TV.close()
                self._json(502, {"ok": False, "error": str(e)})
            return
        if STREMIO_APP and (path == "/stremio" or path.startswith("/stremio/")):
            if path == "/stremio":
                self.send_response(301)
                self.send_header("Location", "/stremio/" + ("?" + urlsplit(self.path).query if urlsplit(self.path).query else ""))
                self.end_headers()
                return
            try:
                got = STREMIO_APP.get(path[len("/stremio/"):], urlsplit(self.path).query)
            except Exception as e:
                log(f"{path}: {e}")
                got = None
            if not got:
                self.send_error(404)
                return
            body, ctype = got
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path.startswith("/api/web-icon/"):
            w = website(path.rsplit("/", 1)[1])
            if not w:
                self.send_error(404)
                return
            self._send(web_icon_svg(w), "image/svg+xml")
            return
        if path.startswith("/icon/"):
            try:
                got = icon_bytes(path[len("/icon/"):])
            except Exception as e:
                log(f"icon {path}: {e}")
                got = None
            if not got:
                self.send_error(404)
                return
            body, ctype = got
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        super().do_GET()

    # ---------- admin panel ----------
    def _addons_get(self, path: str):
        parts = path.split("/")   # ["", "api", "addons", id, ...]
        if path == "/api/addons/home":
            home = ADDONS.home()
            self._json(200, dict(home, tiles=home["tiles"] + web_tiles_cc()))
        elif len(parts) == 5 and parts[4] == "screen":
            page = ADDONS.screen(parts[3])
            self._json(200 if page is not None else 404, page or {"error": "unknown add-on"})
        elif len(parts) == 6 and parts[4] == "file":
            got = ADDONS.file(parts[3], urllib.parse.unquote(parts[5]))
            if not got:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", got[1])
            self.send_header("Content-Length", str(len(got[0])))
            self.send_header("Cache-Control", "max-age=86400")
            self.end_headers()
            self.wfile.write(got[0])
        else:
            self.send_error(404)

    def _admin_ok(self) -> bool:
        if ADMIN.signed_in(self.headers.get("Cookie")):
            label = _ua_label(self.headers.get("User-Agent", ""))
            if label:
                _clients[self.client_address[0]] = label
            return True
        self._json(401, {"ok": False, "error": "sign in", "password_set": ADMIN.password_set()})
        return False

    def _admin_get(self, path: str):
        if path == "/api/admin/hello":   # whether a password exists and this browser is signed in
            self._json(200, {"password_set": ADMIN.password_set(), "signed_in": ADMIN.signed_in(self.headers.get("Cookie")),
                             "code": page_code("admin.html"), "api": API_VERSION})
            return
        if path.startswith("/api/admin/app/"):   # the panel as a phone app: its manifest and icons (asked for without the sign-in)
            name = path.rsplit("/", 1)[1]
            f = PANEL / "app" / name
            if not re.match(r"^[a-z0-9-]+\.(png|svg|webmanifest)$", name) or not f.exists():
                self.send_error(404)
                return
            ctype = {"png": "image/png", "svg": "image/svg+xml", "webmanifest": "application/manifest+json"}[name.rsplit(".", 1)[1]]
            self._send(f.read_bytes(), ctype)
            return
        if not self._admin_ok():
            return
        q = parse_qs(urlsplit(self.path).query)
        if path == "/api/admin/state":
            self._json(200, admin_state())
        elif path == "/api/admin/geocode":
            try:
                self._json(200, {"places": ADMIN.geocode(q.get("q", [""])[0])})
            except Exception as e:
                self._json(502, {"ok": False, "error": str(e)})
        elif path == "/api/admin/wallpaper-preview" or path.startswith("/api/admin/wallpaper-thumb/"):
            name = path.rsplit("/", 1)[1]
            f = WALLPAPER_IMG if path.endswith("preview") else \
                (OWN_WALLS / name.replace(".jpg", ".thumb.jpg") if name in own_wallpapers() else None)
            if not f or not f.exists():
                self.send_error(404)
                return
            self._send(f.read_bytes(), "image/jpeg")
        elif path == "/api/admin/stats":
            try:
                n = int(parse_qs(urlsplit(self.path).query).get("days", ["7"])[0])
            except ValueError:
                n = 7
            self._json(200, stats_report(n))
        elif path == "/api/admin/silence.wav":
            # Silence exactly as long as what's playing, for the phone's lock screen: iPhones draw the progress bar from
            # the sound the page itself plays. Made on the fly (8 kHz, 16-bit, mono), with byte ranges for seeking.
            try:
                sec = max(10, min(6 * 3600, int(parse_qs(urlsplit(self.path).query).get("sec", ["600"])[0])))
            except ValueError:
                sec = 600
            data_len = sec * 16000
            header = (b"RIFF" + (36 + data_len).to_bytes(4, "little") + b"WAVEfmt " + (16).to_bytes(4, "little")
                      + (1).to_bytes(2, "little") + (1).to_bytes(2, "little") + (8000).to_bytes(4, "little")
                      + (16000).to_bytes(4, "little") + (2).to_bytes(2, "little") + (16).to_bytes(2, "little")
                      + b"data" + data_len.to_bytes(4, "little"))
            total = len(header) + data_len
            start, end = 0, total - 1
            m = re.match(r"bytes=(\d*)-(\d*)", self.headers.get("Range", "") or "")
            if m and (m.group(1) or m.group(2)):
                if m.group(1):
                    start = int(m.group(1))
                    end = min(int(m.group(2)), total - 1) if m.group(2) else total - 1
                else:
                    start = max(0, total - int(m.group(2)))
                if start >= total:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{total}")
                    self.end_headers()
                    return
            n = end - start + 1
            self.send_response(206 if m and (m.group(1) or m.group(2)) else 200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Accept-Ranges", "bytes")
            if m and (m.group(1) or m.group(2)):
                self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
            self.send_header("Content-Length", str(n))
            self.end_headers()
            try:
                pos = start
                if pos < len(header):
                    chunk = header[pos:min(len(header), end + 1)]
                    self.wfile.write(chunk)
                    pos += len(chunk)
                zeros = bytes(65536)
                while pos <= end:
                    k = min(len(zeros), end + 1 - pos)
                    self.wfile.write(zeros[:k])
                    pos += k
            except (BrokenPipeError, ConnectionResetError):
                pass
        elif path == "/api/admin/logs":
            src = parse_qs(urlsplit(self.path).query).get("src", ["helper"])[0]
            if src == "cc":
                lines, why = [], ""
                raw = _cc_ask("log", 6)
                if raw is None:
                    why = "The launcher's pointer service didn't answer (is the Chromecast on?)."
                else:
                    import base64
                    try:
                        lines = base64.b64decode(raw).decode("utf-8", "replace").splitlines()
                    except ValueError:
                        lines = []
                adb, serial, _ = _cc_serial()
                if serial:   # the app's crashes, if any, from Android's own log
                    try:
                        out = subprocess.run([adb, "-s", serial, "shell", "logcat -d -t 2000 -v time AndroidRuntime:E *:S"],
                                             capture_output=True, text=True, timeout=10).stdout
                        crash = [ln for ln in out.splitlines() if "cleanyourtv" in ln]
                        if crash:
                            lines += ["", "— crashes (Android's log) —"] + crash[-40:]
                    except subprocess.TimeoutExpired:
                        pass
                self._json(200, {"ok": not why, "error": why, "lines": [name_ips(x) for x in lines]})
            else:
                self._json(200, {"ok": True, "lines": [name_ips(x) for x in LOG_LINES[-600:]]})
        elif path == "/api/admin/np-art":
            data = cc_now_playing_art(parse_qs(urlsplit(self.path).query).get("t", [""])[0])
            if not data:
                self.send_error(404)
                return
            self._send(data, "image/jpeg")
        elif path == "/api/admin/now-playing":
            try:
                tv_key, tv_name = _tv_screen() if VIDAA else ("", "")
                self._json(200, dict(cc_now_playing(), sleep=sleep_state(),
                                     tv={"on": bool(tv_key), "screen": tv_name, "cc": bool(tv_key) and tv_key == (
                                         "src:" + str(CHROMECAST.get("tv_input")) if CHROMECAST.get("tv_input") else _stats_load().get("cc_input", ""))}))
            except subprocess.TimeoutExpired:
                self._json(200, {"ok": False, "error": "The Chromecast didn't answer in time."})
        elif path == "/api/admin/remote-status":
            self._json(200, remote_status())
        elif path == "/api/admin/cc-live":
            try:
                after = float(parse_qs(urlsplit(self.path).query).get("after", ["0"])[0])
            except ValueError:
                after = 0
            frame, why = cc_live_frame(after)
            if frame:
                body = frame
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg" if frame[:2] == b"\xff\xd8" else "image/png")
                self.send_header("X-Frame-At", str(_live["at"]))
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            else:
                self._json(409, {"ok": False, "error": why})
        elif path == "/api/admin/cc-screen":
            png, why = cc_screenshot()
            if png:
                self._send(png, "image/png")
            else:
                self._json(409, {"ok": False, "error": why})
        elif path.startswith("/api/admin/icon/"):
            kind, _, app_id = path[len("/api/admin/icon/"):].partition("/")
            got = None
            if SAFE_ID.match(app_id):
                if kind == "cc":
                    body = ADMIN.cc_icon(app_id)
                    got = (body, "image/png") if body else None
                elif kind == "tv" and app_id.startswith("web-"):
                    w = website(app_id[4:])
                    got = (web_icon_svg(w), "image/svg+xml") if w else None
                elif kind == "tv":
                    page = next((p for p in PAGES if p["appId"] == app_id), None)
                    if page:
                        f = WEB / page["icon"].lstrip("/")
                        got = (f.read_bytes(), "image/png") if f.exists() else None
                    else:
                        try:
                            got = icon_bytes(app_id)
                        except Exception:
                            got = None
            if not got:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", got[1])
            self.send_header("Content-Length", str(len(got[0])))
            self.end_headers()
            self.wfile.write(got[0])
        else:
            self.send_error(404)

    def _wallpaper_upload(self):
        if self.headers.get("X-CYT") != "1" or not self._admin_ok():
            if self.headers.get("X-CYT") != "1":
                self._json(403, {"ok": False})
            return
        length = int(self.headers.get("Content-Length", 0))
        if not 0 < length <= 40_000_000:
            self._json(413, {"ok": False, "error": "photos up to 40 MB"})
            return
        body = self.rfile.read(length)
        name = parse_qs(urlsplit(self.path).query).get("name", ["photo"])[0]
        try:
            self._json(200, {"ok": True, "name": wallpaper_upload(name, body)})
        except Exception as e:
            log(f"wallpaper upload {name!r} ({length} bytes, {self.headers.get('Content-Type')}): {e}")
            self._json(400, {"ok": False, "error": str(e)})

    def _admin_post(self, path: str, data: dict):
        # Only the panel's own page sends this header: another website can't make a browser send it here.
        if self.headers.get("X-CYT") != "1":
            self._json(403, {"ok": False})
            return
        if path == "/api/admin/login":
            token = ADMIN.password_set() and ADMIN.login(str(data.get("password", "")))
            if not token:
                self._json(401, {"ok": False, "error": "wrong password" if ADMIN.password_set() else "no password set"})
                return
            body = json.dumps({"ok": True}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Set-Cookie", f"{admin.COOKIE}={token}; Path=/; Max-Age={admin.SESSION_DAYS * 86400}; "
                                           "HttpOnly; SameSite=Strict")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if not self._admin_ok():
            return
        if path == "/api/admin/logout":
            ADMIN.logout(self.headers.get("Cookie"))
            self._json(200, {"ok": True})
        elif path == "/api/admin/save":
            try:
                cfg, notes = ADMIN.apply(data.get("changes") or {})
            except (ValueError, TypeError, KeyError) as e:
                self._json(400, {"ok": False, "error": str(e)})
                return
            ADMIN.save(cfg)
            if "takeover" in notes:
                set_takeover(notes["takeover"])
            if notes.get("tv_dock_changed"):
                APPS.set_order(None)   # the panel's dock wins over an order arranged on the TV
            if set(data.get("changes") or {}) <= LIVE_KEYS:
                apply_live(cfg)
                self._json(200, {"ok": True, "live": True})
            else:
                self._json(200, {"ok": True})
                restart_soon()
        elif path == "/api/admin/remote":
            try:
                self._json(200, remote_press(str(data.get("device", "")), str(data.get("key", "")), str(data.get("text", ""))))
            except ValueError as e:
                self._json(400, {"ok": False, "error": str(e)})
            except (ConnectionError, OSError, subprocess.TimeoutExpired) as e:
                self._json(502, {"ok": False, "error": "Couldn't reach it: " + str(e)})
        elif path == "/api/admin/cc-tap":
            try:
                self._json(200, cc_tap(float(data.get("x", -1)), float(data.get("y", -1))))
            except (TypeError, ValueError):
                self._json(400, {"ok": False, "error": "bad spot"})
        elif path == "/api/admin/push-sub":
            if not PUSH:
                self._json(400, {"ok": False, "error": "Notifications need the cryptography package on the helper."})
            else:
                try:
                    n = PUSH.add(data.get("sub") or {}, str(data.get("label", "")))
                    self._json(200, {"ok": True, "phones": n})
                except ValueError as e:
                    self._json(400, {"ok": False, "error": str(e)})
        elif path == "/api/admin/push-off":
            if PUSH:
                PUSH.remove(str(data.get("endpoint", "")))
            self._json(200, {"ok": True})
        elif path == "/api/admin/push-test":
            n = PUSH.send("Notifications work", "From your home helper.", tag="test") if PUSH else 0
            self._json(200, {"ok": n > 0, "sent": n, "error": "" if n else "No phone got it: switch notifications on there first."})
        elif path == "/api/admin/np-seek":
            try:
                sec = max(0, int(data.get("sec", 0)))
            except (TypeError, ValueError):
                sec = 0
            why = _cc_service(f"seek {sec * 1000}")
            _np_cache["at"] = 0
            self._json(200, {"ok": not why, "error": why and "The Chromecast's launcher didn't answer."})
        elif path == "/api/admin/sleep":
            try:
                self._json(200, {"ok": True, "sleep": sleep_set(str(data.get("set", "off")))})
            except ValueError as e:
                self._json(400, {"ok": False, "error": str(e)})
        elif path == "/api/admin/websites":
            try:
                ws = save_websites(data.get("websites"))
                st = admin_state()
                self._json(200, {"ok": True, "websites": ws, "tv_apps": st["tv_apps"], "cc": st["cc"]})
            except ValueError as e:
                self._json(400, {"ok": False, "error": str(e)})
        elif path == "/api/admin/scenes":
            try:
                self._json(200, {"ok": True, "scenes": save_scenes(data.get("scenes"))})
            except ValueError as e:
                self._json(400, {"ok": False, "error": str(e)})
        elif path == "/api/admin/scene-run":
            self._json(200, run_scene(str(data.get("id", ""))))
        elif path == "/api/admin/quick-new":
            quick_token(new=True)
            self._json(200, {"ok": True, "quick_base": quick_base()})
        elif path == "/api/admin/cc-connect":
            if not CC_IPS:
                self._json(400, {"ok": False, "error": "No Chromecast in config.json."})
            else:
                _, serial, why = _cc_serial(force=True)
                self._json(200, {"ok": bool(serial), "error": why})
        elif path == "/api/admin/pointer":
            try:
                self._json(200, pointer_send(data.get("ops") or []))
            except (ValueError, TypeError) as e:
                self._json(400, {"ok": False, "error": str(e)})
        elif path == "/api/admin/launch":
            try:
                self._json(200, remote_open(str(data.get("device", "")), str(data.get("app", "")), str(data.get("url", ""))))
            except ValueError as e:
                self._json(400, {"ok": False, "error": str(e)})
            except (ConnectionError, OSError, subprocess.TimeoutExpired) as e:
                self._json(502, {"ok": False, "error": "Couldn't reach it: " + str(e)})
        elif path == "/api/admin/wallpaper-next":
            try:
                self._json(200, {"ok": True, "now": wallpaper_next()})
            except Exception as e:
                self._json(502, {"ok": False, "error": str(e)})
        elif path == "/api/admin/addon-refresh":
            a = ADDONS.items.get(str(data.get("id", "")))
            if a:
                ADDONS.refresh(a, force=True)
            self._json(200, {"ok": bool(a), "addons": ADDONS.status()})
        elif path == "/api/admin/addons-reload":
            ADDONS.load()
            self._json(200, {"ok": True, "addons": ADDONS.status(), "broken": ADDONS.broken})
        elif path == "/api/admin/wallpaper-delete":
            self._json(200, {"ok": wallpaper_delete(str(data.get("name", "")))})
        elif path == "/api/admin/action":
            what = data.get("do")
            if what == "restore_tv":
                APPS.restore_all()
                self._json(200, {"ok": True})
            elif what == "open_home":
                threading.Thread(target=HOME_WATCH.open_launcher, args=("the admin panel",), daemon=True).start()
                self._json(200, {"ok": True})
            elif what == "restart":
                self._json(200, {"ok": True})
                restart_soon()
            else:
                self._json(400, {"ok": False, "error": "unknown action"})
        else:
            self.send_error(404)

    def _send(self, body: bytes, ctype: str, code: int = 200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _pipe(self, ctype: str, chunks):
        """Send a stream of unknown length; the TV closing the connection (a seek, Back) ends it."""
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        try:
            for b in chunks:
                self.wfile.write(b)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            pass

    def _extras(self, path: str, q: dict):
        try:
            codecs = [c for c in q.get("c", "avc1").split(",") if c in ("av01", "vp9", "avc1")] or ["avc1"]
            height = max(144, min(int(q.get("h", "0") or 0) or YT.max_height, 4320))
            if path == "/api/yt/home":
                self._json(200, {"rows": YT.home()})
            elif path == "/api/yt/search":
                self._json(200, {"items": YT.search(q.get("q", ""))})
            elif path == "/api/yt/info":
                self._json(200, YT.public(q.get("id", ""), codecs, height))
            elif path == "/api/yt/related":
                self._json(200, {"items": YT.related(q.get("id", ""))})
            elif path == "/yt/stream":
                cmd = YT.stream_command(q.get("id", ""), float(q.get("t", "0") or 0), codecs, height)
                proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                log(f"YouTube {q.get('id')} from {q.get('t', '0')} s ({','.join(codecs)} up to {height}p)")

                def chunks():
                    while True:
                        b = proc.stdout.read(65536)
                        if not b:
                            return
                        yield b
                try:
                    self._pipe("video/mp4", chunks())
                finally:
                    proc.kill()
            elif path == "/api/livetv":
                self._json(200, {"groups": LIVETV.get()})
            elif path == "/proxy":
                url = q.get("u", "")
                if urlsplit(url).scheme not in ("http", "https"):
                    self.send_error(400)
                    return
                code, ctype, body = extras.proxy_fetch(url, q.get("ref", ""), q.get("ua", ""))
                if isinstance(body, bytes):
                    self._send(body, ctype)
                else:
                    self._pipe(ctype, body)
        except Exception as e:
            log(f"{path} failed: {e}")
            try:
                self._json(502, {"ok": False, "error": str(e)[:300]})
            except OSError:
                pass

    @staticmethod
    def _with_order(back: str) -> str:
        """The hosted page keeps its own copy of the tile order; every trip through /go brings it the current one."""
        if not any(back.startswith(o.rstrip("/") + "/") for o in CONFIG.get("launcher_origins", [])):
            return back
        return back.split("#")[0] + "#order=" + (",".join(APPS.order()) or "default")

    def _go(self):
        """GET /go?app=ID | ?source=ID | ?browse=URL | ?key=KEY, plus &back=URL. The https launcher navigates here, since it may
        not fetch a LAN http address but may navigate to one. Shows a black page while the TV acts, then returns to
        back if nothing took over the screen."""
        query = {k: v[0] for k, v in parse_qs(urlsplit(self.path).query).items()}
        back = query.get("back", "")
        origins = CONFIG.get("launcher_origins", []) + [f"http://{self.headers.get('Host', '')}/"]
        if not any(back.startswith(o.rstrip("/") + "/") for o in origins):
            back = "/"
        error, text, wait = "", "", 7000
        try:
            if "order" in query:
                # From the hosted page, which may not call this helper but may navigate to it.
                APPS.set_order([i for i in query["order"].split(",") if i] if query["order"] != "default" else None)
                log("tile order " + ("reset" if query["order"] == "default" else "saved"))
                wait = 300
            if query.get("takeover") in ("on", "off"):
                set_takeover(query["takeover"] == "on")
                log("home takeover switched " + query["takeover"])
                wait = 300
            if "app" in query:
                app = APPS.get(query["app"])
                if not app:
                    error = "That app is not on the TV."
                else:
                    text = "Opening " + app.get("name", "the app").replace("▶", "").strip() + "…"
                    open_app(app)
                    log(f"launched {app.get('name')} via /go")
            elif "source" in query:
                source = next((s for s in TV.sources() if s.get("sourceid") == query["source"]), None)
                if not source:
                    error = "That input is not on the TV."
                else:
                    text = "Switching to " + source.get("displayname", source["sourceid"]).replace("▶", "").strip() + "…"
                    TV.change_source(source)
                    log(f"switched to {source['sourceid']} via /go")
            elif "browse" in query:
                text = "Opening the TV Browser…"
                browse(query["browse"])
                log("opened the TV Browser via /go")
            elif query.get("key") in ALLOWED_KEYS:
                # Menus open over the launcher, so go straight back to it (Home leaves the browser hidden, so it stays).
                TV.send_key(query["key"])
                log(f"sent {query['key']} via /go")
                text, wait = "", 300
            elif "takeover" not in query and "order" not in query:
                error = "Unknown request."
        except Exception as e:
            log(f"/go failed: {e}")
            TV.close()
            error = "The TV did not respond."
        body = f"""<!doctype html><html><head><meta charset="utf-8"><title>Opening</title>
<style>html,body{{margin:0;height:100%;background:#000;color:rgba(255,255,255,.7);font:300 40px sans-serif;
display:flex;align-items:center;justify-content:center}}</style></head><body>{html.escape(error or text)}
<script>
var back = {json.dumps(self._with_order(back))};
// If an app or input took over, this page is hidden; if not (or it failed), go back to the launcher.
setTimeout(function () {{ if (document.visibilityState === 'visible') location.replace(back); }}, {3000 if error else wait});
</script></body></html>""".encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if not self._allowed():
            return
        path = urlsplit(self.path).path
        if path == "/api/admin/wallpaper-upload":
            self._wallpaper_upload()
            return
        data = self._body()
        try:
            if path == "/api/launch" and str(data.get("appId", "")).startswith("web-"):   # a website: the TV's browser
                w = website(str(data["appId"])[4:])
                if not w:
                    self._json(404, {"ok": False, "error": "unknown website"})
                    return
                browse(w["url"])
                log(f"opened the website {w['name']}")
                self._json(200, {"ok": True})
                return
            if path == "/api/launch":
                app = APPS.get(str(data.get("appId", "")))
                if not app:
                    self._json(404, {"ok": False, "error": "unknown app"})
                    return
                t = time.time()
                open_app(app)
                log(f"launched {app.get('name')} in {int((time.time() - t) * 1000)} ms")
                self._json(200, {"ok": True})
                return
            if path.startswith("/api/admin/"):
                self._admin_post(path, data)
                return
            if path.startswith("/api/addons/") and path.endswith("/run"):
                self._json(200, addon_run(path.split("/")[3]))
                return
            if path == "/api/home-scene-run":   # only a scene allowed on Home: the TV pages are not signed in
                sid = str(data.get("id", ""))
                if not experience.scene_allowed(CONFIG, sid):
                    self._json(403, {"ok": False, "error": "That scene isn't allowed on Home (phone panel: Layout)."})
                    return
                self._json(200, run_scene(sid))
                return
            if path == "/api/tv-source":   # switch the TV's input (from a Home layout); only inputs the TV reported
                want = str(data.get("id", ""))
                source = next((x for x in TV.sources() if x.get("sourceid") == want), None) if VIDAA else None
                if not source:
                    self._json(404, {"ok": False, "error": "That input is not on the TV."})
                    return
                TV.change_source(source)
                log(f"switched to {want} from a Home layout")
                self._json(200, {"ok": True})
                return
            if path == "/api/media":
                key = str(data.get("key", ""))
                if key not in ("play", "prev", "next", "rew", "ff", "stop"):
                    self._json(400, {"ok": False, "error": "not a media key"})
                    return
                _np_cache["at"] = 0   # the next look is fresh
                self._json(200, remote_press("cc", key))
                return
            if path == "/api/home-edit":
                try:
                    self._json(200, home_edit_save(data))
                except (ValueError, TypeError, KeyError) as e:
                    self._json(400, {"ok": False, "error": str(e)})
                return
            if path == "/api/cc-apps":
                if self.client_address[0] not in CC_IPS or not isinstance(data.get("apps"), list):
                    self._json(400, {"ok": False})
                    return
                ADMIN.store_cc_apps(data["apps"], data.get("icon_packs") if isinstance(data.get("icon_packs"), list) else [])
                self._json(200, {"ok": True})
                return
            if path in ("/api/hub/pointer", "/api/hub/text"):
                # The Chromecast's own panel, for what only adb can do: the whole trackpad (taps, BrowseHere's arrows)
                # and typing. With the key this helper gave its launcher.
                import hmac
                if self.client_address[0] not in CC_IPS or not hmac.compare_digest(str(self.headers.get("X-Token", "")), pointer_token()):
                    self._json(403, {"ok": False})
                    return
                try:
                    self._json(200, pointer_send(data.get("ops") or []) if path == "/api/hub/pointer"
                               else remote_press("cc", key=str(data["key"])) if data.get("key")
                               else remote_press("cc", text=str(data.get("text", ""))))
                except Exception as e:
                    self._json(502, {"ok": False, "error": str(e)})
                return
            if path == "/api/hub/tap":
                # The Chromecast's own server (its panel's trackpad) asks for a tap at its pointer: only adb can tap
                # inside other apps on Google TV. With the key this helper gave its launcher.
                import hmac
                if self.client_address[0] not in CC_IPS or not hmac.compare_digest(str(self.headers.get("X-Token", "")), pointer_token()):
                    self._json(403, {"ok": False})
                    return
                try:
                    x, y = int(data.get("x", -1)), int(data.get("y", -1))
                    if not (0 <= x < 4000 and 0 <= y < 4000):
                        raise ValueError
                    _pointer_shell(f"input touchscreen tap {x} {y}" if not data.get("long") else f"input touchscreen swipe {x} {y} {x} {y} 700")
                    self._json(200, {"ok": True})
                except Exception as e:
                    self._json(502, {"ok": False, "error": str(e)})
                return
            if path == "/api/cc-open":
                # The Chromecast is about to open an app: switch the VPN first if that app has a country (or back).
                vpn_for_app(str(data.get("appId", "")))
                self._json(200, {"ok": True})
                return
            if path == "/api/browse":
                browse(str(data.get("url", "")))
                log("opened the TV Browser")
                self._json(200, {"ok": True})
                return
            if path == "/api/key":
                key = str(data.get("key", ""))
                if key not in ALLOWED_KEYS:
                    self._json(400, {"ok": False, "error": "key not allowed"})
                    return
                TV.send_key(key)
                self._json(200, {"ok": True})
                return
            if path == "/api/region":
                key = str(data.get("region", ""))
                if key not in [r["id"] for r in regions()] or not vpn_available():
                    self._json(400, {"ok": False, "error": "unknown region"})
                    return
                try:
                    _auto_vpn["prev"] = None   # a choice by hand: no app switches it back
                    self._json(200, {"ok": True, "region": region_set(key)})
                except Exception as e:
                    log(f"TV VPN: {e}")
                    self._json(502, {"ok": False, "error": str(e)})
                return
            if path == "/api/remove":
                if data.get("restore_all"):
                    APPS.restore_all()
                else:
                    APPS.set_removed(str(data.get("appId", "")), bool(data.get("removed", True)))
                self._json(200, {"ok": True, "removed_count": len(APPS.removed())})
                return
            if path == "/report":
                # Probe pages (web/probe*.html) send what they found on the TV.
                reports = ROOT / "reports"
                reports.mkdir(exist_ok=True)
                text = json.dumps(data, indent=2, ensure_ascii=False)
                (reports / time.strftime("report-%Y%m%d-%H%M%S.json")).write_text(text, encoding="utf-8")
                (reports / "latest.json").write_text(text, encoding="utf-8")
                log(f"report from {self.client_address[0]}: {data.get('page', 'probe')}")
                self._json(200, {"ok": True})
                return
            if path == "/api/order":
                APPS.set_order(data.get("order") or None)
                self._json(200, {"ok": True})
                return
            if path == "/api/livetv/report":
                LIVETV.report(str(data.get("url", "")), bool(data.get("ok")))
                self._json(200, {"ok": True})
                return
            if path in ("/api/yt/log", "/api/log"):
                log(("YouTube page: " if path == "/api/yt/log" else "") + str(data.get("msg", ""))[:300])
                self._json(200, {"ok": True})
                return
            if path == "/api/refresh":
                APPS.refresh()
                self._json(200, {"ok": True, "apps": len(APPS.apps)})
                return
        except Exception as e:
            log(f"{path} failed: {e}")
            TV.close()
            self._json(502, {"ok": False, "error": str(e)})
            return
        self.send_error(404)


def page_code(name: str) -> int:
    """The version of a page's code (when its file last changed): a screen that has an older one reloads itself."""
    try:
        return int(((PANEL if name.startswith("admin") else WEB) / name).stat().st_mtime)
    except OSError:
        return 0


def own_ip() -> str:
    """This computer's address on the TV's network."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # Towards the TV, or with none, the Chromecast; a UDP "connect" sends nothing, it only picks the way out.
        probe.connect((tv_host() or (sorted(CC_IPS) or ["192.168.1.1"])[0], 9))
        return probe.getsockname()[0]
    finally:
        probe.close()


def check_own_address():
    """The Home tile carries this computer's address and a tile cannot be changed once added. If the address
    changed (the router handed out a new one), add a Home tile with the new address; the old one can be deleted."""
    url = CONFIG.get("launcher_url")
    if not url:
        return
    ip = own_ip()
    state_path = DATA / "helper_state.json"
    state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {}
    if state.get("ip") == ip:
        return
    helper = f"http://{ip}:{CONFIG.get('port', 8765)}"
    app_id = "cythome" + ip.split(".")[-1]
    tile_url = url + "?helper=" + quote(helper, safe="")
    log(f"this computer's address changed ({state.get('ip')} -> {ip}); adding a Home tile for it")
    try:
        client.add_tile(client.Session.load(), app_id, "Home", tile_url, CONFIG.get("tile_icon", ""))
    except Exception as e:
        log(f"could not add the new Home tile: {e}")
        return
    log("NEW HOME TILE ADDED: delete the old Home tile on the TV")
    DATA.mkdir(exist_ok=True)
    state_path.write_text(json.dumps({"ip": ip, "tile": app_id}), encoding="utf-8")


# DIAL apps whose running state the TV reports, with the Origin each one requires.
DIAL_APPS = {"Netflix": None, "AmazonInstantVideo": None, "YouTube": "https://www.youtube.com"}


# TV app id -> DIAL name, so an app opened from the launcher is watched from the moment it opens.
APP_DIAL_NAMES = {"1": "Netflix", "2": "AmazonInstantVideo", "3": "YouTube"}


class HomeWatch(threading.Thread):
    """VIDAA has no setting for another home screen. It does say when it comes on: its remote port answers again, or,
    on a TV that keeps its network up in standby (Fast Start), its state turns from "fake_sleep_0" to something else.
    Over DIAL it says whether Netflix, YouTube or Prime Video is running; when one of them closes the TV is back on
    VIDAA's home. At those moments this opens the launcher, served by this helper so it shows the live app list.

    The TV answers DIAL one question at a time, Netflix in milliseconds but YouTube and Prime Video in one to two
    seconds. So while an app is open only that app (and Netflix) is asked about, which notices it closing quickly;
    all three are asked about only while nothing is open, to notice one starting."""

    def __init__(self):
        super().__init__(daemon=True, name="home-watch")
        self.up = None
        self.down_since = 0.0
        self.dial = (None, None)   # (TV host, DIAL apps URL)
        self.open_apps: set = set()
        self.starting: dict = {}    # app -> until when it counts as open while it starts
        self.sleeping = False       # in network standby ("fake_sleep"), as last asked
        self.slept = False          # this absence was a standby, not a dropped connection
        self.state_at = 0.0
        self.last_state = {}
        self.unanswered = 0

    def asleep(self) -> bool:
        """Asked every 3 seconds; between asks, and when the TV doesn't answer, the last answer stands."""
        if time.time() - self.state_at >= 3:
            self.state_at = time.time()
            try:
                state = TV.tv_state()
            except Exception:
                state = None
            if state is not None:
                self.sleeping = str(state.get("statetype", "")).startswith("fake_sleep")
                self.last_state = state   # what's on screen (an app, Live TV, an input): for the watch time
                self.unanswered = 0
            else:
                self.unanswered += 1
                if self.unanswered >= 3:   # a stale connection: start a fresh one
                    self.unanswered = 0
                    with TV.lock:
                        TV.close()
        return self.sleeping

    def dial_base(self, host: str) -> str:
        if self.dial[0] != host:
            base = f"http://{host}:3367/apps/"
            try:
                with urllib.request.urlopen(f"http://{host}:3366/dd.xml", timeout=3) as r:
                    base = r.headers.get("Application-URL") or base
            except OSError:
                pass
            self.dial = (host, base if base.endswith("/") else base + "/")
        return self.dial[1]

    def ask(self, host: str, apps) -> set:
        running = set()
        for app in apps:
            origin = DIAL_APPS[app]
            req = urllib.request.Request(self.dial_base(host) + app, headers={"Origin": origin} if origin else {})
            try:
                with urllib.request.urlopen(req, timeout=4) as r:
                    if b"<state>running</state>" in r.read():
                        running.add(app)
            except OSError:
                pass
        return running

    def opened(self, app_id: str):
        """The launcher just opened this app: watch it now rather than after the next full round."""
        if app_id in APP_DIAL_NAMES:
            # The TV reports it running only after a few seconds; until then it is starting, not closed.
            self.starting[APP_DIAL_NAMES[app_id]] = time.time() + 15
            self.open_apps.add(APP_DIAL_NAMES[app_id])

    @staticmethod
    def tv_up(host: str) -> bool:
        try:
            with socket.create_connection((host, client.MQTT_PORT), timeout=1.5):
                return True
        except OSError:
            return False

    @staticmethod
    def warm():
        """Keep the connection to the TV open, so opening the launcher does not wait ~3 s for a new one."""
        try:
            with TV.lock:
                TV._connect()
        except Exception:
            pass

    @staticmethod
    def open_launcher(why: str, always: bool = False):
        if not always and not takeover_on():   # (the remote's Home opens it even with the automatic Home off)
            return
        state_path = DATA / "helper_state.json"
        state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {}
        TV.launch({"appId": state.get("tile", "cythome"), "name": "Home",
                   "url": f"http://{own_ip()}:{CONFIG.get('port', 8765)}/"})
        log(f"opened the launcher ({why})")

    def run(self):
        last_warm = 0.0
        while True:
            try:
                self.step(time.time() - last_warm > 20)
                if time.time() - last_warm > 20:
                    last_warm = time.time()
            except Exception as e:
                log(f"home watch: {e}")
                TV.close()
            time.sleep(0.3)

    def step(self, warm_now: bool):
        host = client.Session.load().host
        reachable = self.tv_up(host)
        if not reachable or self.asleep():
            if self.up is not False:
                self.down_since = time.time()
                self.slept = reachable
                if reachable:
                    log("the TV is in standby")
            self.up, self.open_apps = False, set()
            return
        # Back from standby (which a Wi-Fi blip can't fake), or after a real absence: the TV was switched on.
        if self.up is False and (self.slept or time.time() - self.down_since > 20):
            self.up, self.slept = True, False
            log("the TV was switched on")
            threading.Thread(target=self.warm, daemon=True).start()
            time.sleep(CONFIG.get("power_on_delay_seconds", 5))
            self.open_apps = self.ask(host, ["Netflix"])
            if CONFIG.get("takeover_on_power_on", True) and not self.open_apps:
                self.open_launcher("the TV was switched on")
            return
        self.up = True
        if warm_now:
            threading.Thread(target=self.warm, daemon=True).start()
        if not self.open_apps:
            self.open_apps = self.ask(host, list(DIAL_APPS))
            return
        watched = set(self.open_apps) | {"Netflix"}
        now = self.ask(host, sorted(watched, key=lambda a: a != "Netflix"))
        for app, until in list(self.starting.items()):
            if app in now or time.time() > until:
                del self.starting[app]
            else:
                now.add(app)
        closed = self.open_apps - now
        if closed and not now:
            # A moment for a straight switch to Netflix to show; then the TV is on its home screen.
            time.sleep(0.8)
            now = self.ask(host, ["Netflix"] + sorted(closed))
            if not now:
                self.open_launcher(", ".join(sorted(closed)) + " closed")
        self.open_apps = now


HOME_WATCH = HomeWatch()


def keep_ytdlp_fresh():
    """YouTube changes often and yt-dlp follows within days: update it nightly, and restart the helper when it changed
    (the logon task starts it again within a minute)."""
    def version():
        try:
            from importlib.metadata import version as v
            return v("yt-dlp")
        except Exception:
            return ""
    while True:
        time.sleep(max(60, (4 - time.localtime().tm_hour) % 24 * 3600 - time.localtime().tm_min * 60) if version() else 60)
        before = version()
        subprocess.run([sys.executable, "-m", "pip", "install", "-q", "--disable-pip-version-check", "-U", "yt-dlp", "yt-dlp-ejs"],
                       capture_output=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        after = version()
        if after and after != before:
            log(f"yt-dlp updated {before or '(none)'} -> {after}; restarting")
            os._exit(1)
        time.sleep(3600)


def main():
    if VIDAA:
        try:
            APPS.refresh()
        except Exception as e:
            log(f"could not reach the TV yet ({e}); using the cached app list ({len(APPS.apps)} apps)")
        try:
            check_own_address()
        except Exception as e:
            log(f"address check failed: {e}")
        HOME_WATCH.start()
    else:
        log("no VIDAA TV (\"vidaa\": false): serving the Chromecast and the admin panel only")
    if STREMIO.get("server", True) and STREMIO_APP:
        StremioServer().start()
    threading.Thread(target=LIVETV.sweeper, daemon=True).start()
    threading.Thread(target=YT.home, daemon=True).start()          # warm the YouTube start screen
    threading.Thread(target=keep_ytdlp_fresh, daemon=True).start()
    threading.Thread(target=ADDONS.loop, daemon=True).start()
    if CC_IPS or VIDAA:
        threading.Thread(target=stats_loop, daemon=True).start()
    log("home takeover " + ("on" if takeover_on() else "off (VIDAA's own home)") +
        ": the launcher opens when the TV comes on or Netflix, YouTube or Prime Video closes")
    port = int(CONFIG.get("port", 8765))
    # On Windows, address reuse lets a second helper share the port with one still running; fail instead.
    ThreadingHTTPServer.allow_reuse_address = os.name != "nt"
    server = QuietServer(("0.0.0.0", port), partial(Handler, directory=str(WEB)))
    threading.Thread(target=https_serve, daemon=True).start()
    log(f"launcher on http://<this computer's ip>:{port}/  (serving {WEB})")
    wallpaper_meta()   # fetch today's photo and quote now, so they're ready before the TV first asks
    threading.Thread(target=quote_of_day, daemon=True).start()
    server.serve_forever()


if __name__ == "__main__":
    main()
