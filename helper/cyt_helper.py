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
sys.path.insert(0, str((CONFIG_PATH.parent / CONFIG.get("sidee_dir", "../sidee")).resolve()))
from core import client, protocol  # noqa: E402  (Sidee)
sys.path.insert(0, str(Path(__file__).resolve().parent))
import extras  # noqa: E402
import admin  # noqa: E402
import qr  # noqa: E402
import addons  # noqa: E402
import layout  # noqa: E402

WEB = ROOT / "web"
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


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S ") + msg, flush=True)


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


def quote_of_day() -> dict | None:
    if not QUOTES_ON:
        return None
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
    try:
        q = json.loads(_fetch(QUOTE_URL))[0]
        data = {"text": html.unescape(q["q"]).strip(), "by": html.unescape(q["a"]).strip(), "source": "ZenQuotes.io"}
    except Exception as e:
        log(f"quote of the day: {e}")
        return None
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


def _wall_nasa(skip: int) -> tuple[bytes, str, str]:
    day = time.time() - skip * 86400
    for back in range(10):   # some days are a video or a tall picture
        d = time.strftime("%Y-%m-%d", time.localtime(day - back * 86400))
        apod = json.loads(_get(f"https://api.nasa.gov/planetary/apod?api_key={quote(NASA_KEY)}&date={d}"))
        if apod.get("media_type") != "image":
            continue
        body = _get(apod.get("hdurl") or apod["url"], timeout=90)
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


# Region for the TV: all its internet traffic goes out through NordVPN in a chosen country, or straight out ("off").
# The VPN is tv-vpn/tv-vpn.sh, installed root-owned in /usr/local/libexec; a sudoers rule lets this user run only its
# "up <country>" and "down" without a password. Without the script installed, the Region button is hidden.
VPN_SCRIPT = "/usr/local/libexec/tv-vpn.sh"
COUNTRY_NAMES = {"us": "USA", "gb": "UK", "ca": "Canada", "eg": "Egypt", "ae": "UAE", "fr": "France", "jp": "Japan",
                 "de": "Germany", "nl": "Netherlands", "it": "Italy", "es": "Spain", "au": "Australia", "se": "Sweden",
                 "ch": "Switzerland", "sa": "Saudi Arabia", "tr": "Turkey", "in": "India", "br": "Brazil"}
VPN_COUNTRIES = [str(c).lower() for c in CONFIG.get("vpn_countries", ["us", "gb"]) if len(str(c)) == 2 and str(c).isalpha()]
VPN_HOME_NAME = CONFIG.get("vpn_home_name", "Germany")


STARTED = int(time.time())


def vpn_available() -> bool:
    return os.access(VPN_SCRIPT, os.X_OK)


def regions() -> list:
    return [{"id": "off", "name": f"{VPN_HOME_NAME} (no VPN)"}] + \
           [{"id": c, "name": COUNTRY_NAMES.get(c, c.upper())} for c in VPN_COUNTRIES]


def region_now() -> str | None:
    """The country the VPN was started with, while its tunnel (NordLynx's 10.5.0.2) is up; both readable without root."""
    if not vpn_available():
        return None
    try:
        code = Path("/Library/Application Support/tv-vpn/country").read_text().strip().lower()
    except OSError:
        return "off"
    up = " 10.5.0.2 " in subprocess.run(["/sbin/ifconfig"], capture_output=True, text=True, timeout=10).stdout
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
CC_PATHS = {"/", "/index.html", "/quotes.json", "/api/rev", "/api/admin-link", "/api/home-edit", "/api/cc", "/api/cc-open", "/api/cc-apps", "/api/weather", "/api/quote",
            "/api/quote-image", "/api/wallpaper", "/api/wallpaper.jpg", "/api/region", "/vpn"}
APP_VPN.update({str(k): str(v).lower() for k, v in (CHROMECAST.get("app_vpn") or {}).items()})


def chromecast_settings() -> dict:
    return {"dock": CHROMECAST.get("dock", []), "rows": CHROMECAST.get("rows", []), "rename": CHROMECAST.get("rename", {}),
            "hidden": CHROMECAST.get("hidden", []), "app_vpn": CHROMECAST.get("app_vpn", {}),
            "greeting_name": CONFIG.get("greeting_name", ""), "quotes": QUOTES_ON, "weather": bool(WEATHER),
            "wallpaper": bool(WALLPAPER_ON), "region": vpn_available(), "clock_24h": CONFIG.get("clock_24h", False),
            "rev": CHROMECAST.get("rev", 0), "layout": CONFIG.get("home_layout") or layout.PRESETS["classic"],
            "live_refresh": CONFIG.get("live_refresh", True) is not False,
            "started": STARTED, "saved": SAVED["at"]}


# ---------- add-ons (helper/addons.py, addons/, data/addons/) ----------
ADDONS = addons.Addons([(ROOT / "addons", True), (DATA / "addons", False)], log, lambda: CONFIG,
                       lambda: WEATHER or (CONFIG.get("weather") or {}),
                       lambda addon_id, name: f"/api/addons/{addon_id}/file/{quote(name)}")


def addon_run(addon_id: str) -> dict:
    """A quick action: the VPN step here (it waits for the tunnel), the rest for the Chromecast to do."""
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
            elif kind == "place":
                val = None if not val else {"city": str(val.get("city", ""))[:60], "lat": round(float(val["lat"]), 4),
                                            "lon": round(float(val["lon"]), 4)}
            else:
                val = str(val)[:500]
            clean[key] = val
        out[addon_id] = {"enabled": bool(conf.get("enabled")), "settings": clean}
    return out


# ---------- admin panel (web/admin.html, helper/admin.py) ----------
ADMIN = admin.Admin(CONFIG_PATH, DATA, log)
ADMIN.validate_addons = validate_addons
ADMIN.load_sessions()
SAFE_ID = re.compile(r"^[A-Za-z0-9_.-]{1,120}$")


# Saves that only change what the home screens draw are applied here at once, without a restart; the screens
# check /api/rev every few seconds and see "saved" change. Everything else (VPN, TV apps, wallpaper…) restarts.
LIVE_KEYS = {"home_layout", "chromecast", "greeting_name", "clock_24h", "live_refresh", "addons", "widget_seconds"}
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
    for a in tv:
        a["icon"] = f"/api/admin/icon/tv/{a['id']}"
    cc = ADMIN.cc_apps()
    for a in cc["apps"]:
        a["icon"] = f"/api/admin/icon/cc/{a['id']}"
    for t in ADDONS.home()["tiles"]:   # full-screen and quick-action add-ons are tiles to arrange, like apps
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
            "tv_on": HOME_WATCH.up, "started": STARTED, "addons": ADDONS.status(), "addons_broken": ADDONS.broken,
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


def _cc_serial() -> tuple[str | None, str | None, str]:
    """(adb, the Chromecast's debugging serial, why not): the link the screenshot and the remote use."""
    adb = _adb()
    if not adb:
        return None, None, "adb isn't on the Mac."
    out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=10).stdout
    ips = [CC_IPS] if isinstance(CC_IPS, str) else list(CC_IPS or [])
    serial = next((ln.split()[0] for ln in out.splitlines()[1:]
                   if ln.split()[-1:] == ["device"] and any(ln.startswith(ip + ":") for ip in ips)), None)
    if not serial:
        return adb, None, "The Chromecast isn't connected: on the Mac, double-click Connect Chromecast."
    return adb, serial, ""


# ---------- the remote (admin panel): the TV's keys over its remote channel, the Chromecast's over its debugging link ----------
TV_KEYS = {"up": "KEY_UP", "down": "KEY_DOWN", "left": "KEY_LEFT", "right": "KEY_RIGHT", "ok": "KEY_OK", "back": "KEY_RETURNS",
           "home": "KEY_HOME", "menu": "KEY_MENU", "volup": "KEY_VOLUMEUP", "voldown": "KEY_VOLUMEDOWN", "mute": "KEY_MUTE",
           "play": "KEY_PLAY", "pause": "KEY_PAUSE", "power": "KEY_POWER", "input": "KEY_INPUT", "exit": "KEY_EXIT",
           "rew": "KEY_BACKS", "ff": "KEY_FORWARDS", "stop": "KEY_STOP"}
CC_KEYS = {"up": "DPAD_UP", "down": "DPAD_DOWN", "left": "DPAD_LEFT", "right": "DPAD_RIGHT", "ok": "DPAD_CENTER",
           "back": "BACK", "home": "HOME", "menu": "MENU", "volup": "VOLUME_UP", "voldown": "VOLUME_DOWN", "mute": "VOLUME_MUTE",
           "play": "MEDIA_PLAY_PAUSE", "pause": "MEDIA_PLAY_PAUSE", "power": "SLEEP", "wake": "WAKEUP", "settings": "SETTINGS",
           "prev": "MEDIA_PREVIOUS", "next": "MEDIA_NEXT", "rew": "MEDIA_REWIND", "ff": "MEDIA_FAST_FORWARD", "stop": "MEDIA_STOP"}


def cc_now_playing() -> dict:
    """What the Chromecast is playing, from its media sessions: the app, title, artist or channel, playing or paused,
    and how far in. The session the media buttons go to wins; else the first one playing."""
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
            parts = d.rsplit(", ", 2) if d else []
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


def remote_status() -> dict:
    out = {"tv": {"on": HOME_WATCH.up}, "cc": {"linked": False, "awake": None, "why": ""}}
    try:
        adb, serial, why = _cc_serial()
        out["cc"]["why"] = why
        if serial:
            power = subprocess.run([adb, "-s", serial, "shell", "dumpsys power | grep -m1 mWakefulness"],
                                   capture_output=True, text=True, timeout=10).stdout
            out["cc"].update(linked=True, awake="Awake" in power)
    except subprocess.TimeoutExpired:
        out["cc"]["why"] = "The Chromecast didn't answer in time."
    return out


def cc_screenshot() -> tuple[bytes | None, str]:
    """A picture of the Chromecast's screen right now, over wireless debugging. Never wakes it."""
    adb = _adb()
    if not adb:
        return None, "adb isn't on the Mac."
    try:
        out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=10).stdout
        ips = [CC_IPS] if isinstance(CC_IPS, str) else list(CC_IPS or [])
        serial = next((ln.split()[0] for ln in out.splitlines()[1:]
                       if ln.split()[-1:] == ["device"] and any(ln.startswith(ip + ":") for ip in ips)), None)
        if not serial:
            return None, "The Chromecast isn't connected for debugging: run Connect Chromecast on the Mac."
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
        if ip in CONFIG.get("allowed_clients", []) or ip == client.Session.load().host:
            return True
        path = urlsplit(self.path).path
        if (ip == "127.0.0.1" or ip.rsplit(".", 1)[0] == own_ip().rsplit(".", 1)[0]) and \
                (path == "/admin" or path.startswith("/api/admin/")):
            return True
        if ip in CC_IPS and (path in CC_PATHS or path.startswith("/fonts/") or path.startswith("/api/addons/")):
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
                             "started": STARTED, "saved": SAVED["at"]})
            return
        if path == "/api/cc":
            self._json(200, chromecast_settings())
            return
        if path == "/api/home-edit":
            self._json(200, home_edit_state())
            return
        if path.startswith("/api/addons/"):
            self._addons_get(path)
            return
        if path == "/api/admin-link":
            # The TV launcher's QR button: the admin panel's address, and its QR code for a phone camera.
            url = f"http://{own_ip()}:{CONFIG.get('port', 8765)}/admin"
            self._json(200, {"url": url, "qr": qr.matrix(url)})
            return
        if path == "/api/rev":
            # Polled by both home screens every 3 s: a save in the admin panel changes "saved" (or restarts the
            # helper: "started"), and a new photo changes "wall". Cheap: nothing is fetched or computed here.
            try:
                wall = json.loads(WALLPAPER_META.read_text(encoding="utf-8")).get("image", "") if WALLPAPER_ON else ""
            except (OSError, ValueError):
                wall = ""
            self._json(200, {"started": STARTED, "wall": wall, "saved": SAVED["at"]})
            return
        if path == "/admin":
            self._send((WEB / "admin.html").read_bytes(), "text/html; charset=utf-8")
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
            self._json(200, ADDONS.home())
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
            return True
        self._json(401, {"ok": False, "error": "sign in", "password_set": ADMIN.password_set()})
        return False

    def _admin_get(self, path: str):
        if path == "/api/admin/hello":   # whether a password exists and this browser is signed in
            self._json(200, {"password_set": ADMIN.password_set(), "signed_in": ADMIN.signed_in(self.headers.get("Cookie"))})
            return
        if path.startswith("/api/admin/app/"):   # the panel as a phone app: its manifest and icons (asked for without the sign-in)
            name = path.rsplit("/", 1)[1]
            f = WEB / "app" / name
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
        elif path == "/api/admin/now-playing":
            try:
                self._json(200, cc_now_playing())
            except subprocess.TimeoutExpired:
                self._json(200, {"ok": False, "error": "The Chromecast didn't answer in time."})
        elif path == "/api/admin/remote-status":
            self._json(200, remote_status())
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
                ADMIN.store_cc_apps(data["apps"])
                self._json(200, {"ok": True})
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


def own_ip() -> str:
    """This computer's address on the TV's network."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect((client.Session.load().host, 9))
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
    try:
        APPS.refresh()
    except Exception as e:
        log(f"could not reach the TV yet ({e}); using the cached app list ({len(APPS.apps)} apps)")
    try:
        check_own_address()
    except Exception as e:
        log(f"address check failed: {e}")
    HOME_WATCH.start()
    if STREMIO.get("server", True) and STREMIO_APP:
        StremioServer().start()
    threading.Thread(target=LIVETV.sweeper, daemon=True).start()
    threading.Thread(target=YT.home, daemon=True).start()          # warm the YouTube start screen
    threading.Thread(target=keep_ytdlp_fresh, daemon=True).start()
    threading.Thread(target=ADDONS.loop, daemon=True).start()
    log("home takeover " + ("on" if takeover_on() else "off (VIDAA's own home)") +
        ": the launcher opens when the TV comes on or Netflix, YouTube or Prime Video closes")
    port = int(CONFIG.get("port", 8765))
    # On Windows, address reuse lets a second helper share the port with one still running; fail instead.
    ThreadingHTTPServer.allow_reuse_address = os.name != "nt"
    server = ThreadingHTTPServer(("0.0.0.0", port), partial(Handler, directory=str(WEB)))
    log(f"launcher on http://<this computer's ip>:{port}/  (serving {WEB})")
    wallpaper_meta()   # fetch today's photo and quote now, so they're ready before the TV first asks
    threading.Thread(target=quote_of_day, daemon=True).start()
    server.serve_forever()


if __name__ == "__main__":
    main()
