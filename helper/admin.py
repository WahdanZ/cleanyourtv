"""The admin panel's back end: sign-in, and reading and saving the settings both launchers use.

The page is shared/panel/admin.html, opened from a phone or computer at home (http://<this computer>:8765/admin). Every
/api/admin/ call needs the session cookie from signing in with the admin password, which is set with
helper/set_admin_password.py (only a salted hash is kept, in data/admin.json). Saving checks every value, keeps a
copy of the old config.json in data/config-backups/ and then restarts the helper, so everything picks it up.
"""
import hashlib
import hmac
import json
import re
import secrets
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

SESSION_DAYS = 30
COOKIE = "cyt_admin"
CODE = re.compile(r"^[a-z]{2}$")


def hash_password(password: str, salt: bytes | None = None) -> dict:
    salt = salt or secrets.token_bytes(16)
    rounds = 240_000
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, rounds)
    return {"salt": salt.hex(), "rounds": rounds, "hash": digest.hex()}


class Admin:
    def __init__(self, config_path: Path, data: Path, log):
        self.config_path, self.data, self.log = config_path, data, log
        self.pass_file = data / "admin.json"
        self.sessions: dict[str, float] = {}
        self.failures: list[float] = []
        self.lock = threading.Lock()
        self._hashed: dict[str, float] = {}   # sessions from before the last restart, by hash

    # ---------- sign-in ----------
    def password_set(self) -> bool:
        return self.pass_file.exists()

    def check_password(self, password: str) -> bool:
        try:
            stored = json.loads(self.pass_file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return False
        digest = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(stored["salt"]), int(stored["rounds"]))
        return hmac.compare_digest(digest.hex(), stored["hash"])

    def login(self, password: str) -> str | None:
        """A new session token, or None. After 8 wrong tries in 10 minutes, every try waits until that eases."""
        now = time.time()
        with self.lock:
            self.failures = [t for t in self.failures if now - t < 600]
            if len(self.failures) >= 8:
                return None
        if not self.check_password(str(password)):
            with self.lock:
                self.failures.append(now)
            time.sleep(1)
            self.log("admin: a wrong password")
            return None
        token = secrets.token_urlsafe(32)
        with self.lock:
            self.sessions[token] = now + SESSION_DAYS * 86400
        self.save_sessions()
        return token

    def valid(self, cookie_header: str | None) -> bool:
        token = self.token(cookie_header)
        with self.lock:
            exp = self.sessions.get(token or "")
            return bool(exp and exp > time.time())

    @staticmethod
    def token(cookie_header: str | None) -> str | None:
        for part in (cookie_header or "").split(";"):
            k, _, v = part.strip().partition("=")
            if k == COOKIE:
                return v
        return None

    def logout(self, cookie_header: str | None):
        token = self.token(cookie_header) or ""
        with self.lock:
            self.sessions.pop(token, None)
            self._hashed.pop(hashlib.sha256(token.encode()).hexdigest(), None)
        self.save_sessions()

    # Sessions survive the helper's restart after a save (only their hashes are kept on disk).
    def _sessions_file(self) -> Path:
        return self.data / "admin-sessions.json"

    def save_sessions(self):
        now = time.time()
        with self.lock:
            keep = {h: e for h, e in self._hashed.items() if e > now}
            keep.update({hashlib.sha256(t.encode()).hexdigest(): e for t, e in self.sessions.items() if e > now})
        f = self._sessions_file()
        f.write_text(json.dumps(keep), encoding="utf-8")
        f.chmod(0o600)

    def load_sessions(self):
        try:
            hashed = json.loads(self._sessions_file().read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return
        self._hashed = hashed

    def valid_hashed(self, cookie_header: str | None) -> bool:
        """A session from before the restart: its hash is on disk; it becomes a live session again."""
        token = self.token(cookie_header)
        hashed = self._hashed
        if not token:
            return False
        exp = hashed.get(hashlib.sha256(token.encode()).hexdigest())
        if exp and exp > time.time():
            with self.lock:
                self.sessions[token] = exp
            return True
        return False

    def signed_in(self, cookie_header: str | None) -> bool:
        return self.valid(cookie_header) or self.valid_hashed(cookie_header)

    # ---------- settings ----------
    def read_config(self) -> dict:
        return json.loads(self.config_path.read_text(encoding="utf-8"))

    def editable(self, cfg: dict, takeover: bool) -> dict:
        cc = cfg.get("chromecast") or {}
        return {
            "greeting_name": cfg.get("greeting_name", ""), "clock_24h": bool(cfg.get("clock_24h", False)),
            "live_refresh": cfg.get("live_refresh", True) is not False,
            "quotes": cfg.get("quotes", True) is not False, "wallpaper": cfg.get("wallpaper", True) is not False,
            "wallpaper_size": cfg.get("wallpaper_size", "1920x1080"), "weather": cfg.get("weather") or None,
            "wallpaper_source": cfg.get("wallpaper_source", "bing"), "wallpaper_market": cfg.get("wallpaper_market", "en-US"),
            "wallpaper_pick": cfg.get("wallpaper_pick") or "", "nasa_key_set": bool(cfg.get("nasa_api_key")),
            "vpn_countries": cfg.get("vpn_countries", []), "home_takeover": takeover,
            "addons": self.addons_conf(cfg),
            "home_layout": cfg.get("home_layout") or None,
            "home_experience": __import__("experience").current(cfg),
            "takeover_on_power_on": cfg.get("takeover_on_power_on", True) is not False,
            "power_on_delay_seconds": cfg.get("power_on_delay_seconds", 5),
            "tv": {"dock": cfg.get("dock", []), "rows": cfg.get("rows", []), "rename": cfg.get("rename", {}),
                   "hidden": cfg.get("hidden", []), "app_vpn": cfg.get("app_vpn", {})},
            "chromecast": {"dock": cc.get("dock", []), "rows": cc.get("rows", []), "rename": cc.get("rename", {}),
                           "hidden": cc.get("hidden", []), "app_vpn": cc.get("app_vpn", {})},
        }

    @staticmethod
    def addons_conf(cfg: dict) -> dict:
        """The add-ons' part of the config, with the quote and weather of an older config read in."""
        c = json.loads(json.dumps(cfg.get("addons") or {}))
        c.setdefault("quote", {"enabled": cfg.get("quotes", True) is not False})
        c.setdefault("weather", {"enabled": bool(cfg.get("weather")), "settings": {"place": cfg.get("weather") or None}})
        c.setdefault("order", [])
        c.setdefault("widget_seconds", 10)
        for k, v in c.items():
            if isinstance(v, dict):
                v.setdefault("settings", {})
        return c

    @staticmethod
    def _ids(v, limit=300) -> list:
        if not isinstance(v, list):
            raise ValueError("a list was expected")
        out = []
        for x in v[:limit]:
            s = str(x).strip()
            if not s or len(s) > 120:
                raise ValueError(f"bad app id {s!r}")
            if s not in out:
                out.append(s)
        return out

    def _layout(self, v: dict, dock_max: int = 4) -> dict:
        if not isinstance(v, dict):
            raise ValueError("bad layout")
        rows = []
        for r in (v.get("rows") or [])[:20]:
            title = str(r.get("title", "")).strip()[:40]
            rows.append({"title": title, "apps": self._ids(r.get("apps") or [])})
        rename = {}
        for k, name in (v.get("rename") or {}).items():
            name = str(name).strip()[:40]
            if name:
                rename[str(k)[:120]] = name
        app_vpn = {}
        for k, c in (v.get("app_vpn") or {}).items():
            c = str(c).lower()
            if not CODE.match(c):
                raise ValueError(f"bad country {c!r}")
            app_vpn[str(k)[:120]] = c
        return {"dock": self._ids(v.get("dock") or [], dock_max), "rows": rows, "rename": rename,
                "hidden": self._ids(v.get("hidden") or []), "app_vpn": app_vpn}

    def apply(self, changes: dict) -> tuple[dict, dict]:
        """Checks the changes and returns (new config, notes). Raises ValueError for anything wrong."""
        cfg = self.read_config()
        notes = {}
        for key, v in changes.items():
            if key == "greeting_name":
                cfg[key] = str(v).strip()[:40]
            elif key in ("clock_24h", "quotes", "wallpaper", "takeover_on_power_on", "live_refresh"):
                cfg[key] = bool(v)
            elif key == "wallpaper_size":
                if v not in ("1920x1080", "UHD"):
                    raise ValueError("wallpaper size must be 1920x1080 or UHD")
                cfg[key] = v
            elif key == "wallpaper_source":
                if v not in ("bing", "wikimedia", "nasa", "own"):
                    raise ValueError("unknown wallpaper source")
                cfg[key] = v
            elif key == "wallpaper_market":
                if not re.match(r"^[a-z]{2}-[A-Z]{2}$", str(v)):
                    raise ValueError("bad Bing region")
                cfg[key] = v
            elif key == "wallpaper_pick":
                v = str(v or "")
                if v and not re.match(r"^[A-Za-z0-9_.-]{1,80}\.jpg$", v):
                    raise ValueError("bad photo name")
                cfg[key] = v
            elif key == "nasa_api_key":
                v = str(v or "").strip()
                if v and not re.match(r"^[A-Za-z0-9]{20,60}$", v):
                    raise ValueError("that doesn't look like a NASA API key")
                if v:
                    cfg[key] = v
                else:
                    cfg.pop(key, None)
            elif key == "nasa_key_set":
                pass
            elif key == "home_layout":
                import layout
                cfg["home_layout"] = layout.validate(v)
            elif key == "home_experience":
                import experience
                cfg["home_experience"] = experience.validate(v)
            elif key == "addons":
                cfg["addons"] = self.validate_addons(v)   # set by the helper: it knows each add-on's settings
                cfg.pop("quotes", None)
                cfg.pop("weather", None)   # now the quote and weather add-ons' settings
            elif key == "weather":
                if v is None:
                    cfg.pop("weather", None)
                else:
                    cfg[key] = {"city": str(v.get("city", "")).strip()[:60], "lat": round(float(v["lat"]), 4),
                                "lon": round(float(v["lon"]), 4)}
            elif key == "vpn_countries":
                codes = [str(c).lower() for c in v]
                if not all(CODE.match(c) for c in codes) or len(codes) > 30:
                    raise ValueError("countries are 2-letter codes")
                cfg[key] = list(dict.fromkeys(codes))
            elif key == "home_takeover":
                notes["takeover"] = bool(v)
            elif key == "power_on_delay_seconds":
                n = int(v)
                if not 0 <= n <= 30:
                    raise ValueError("the delay is 0 to 30 seconds")
                cfg[key] = n
            elif key == "tv":
                lay = self._layout(v)
                if lay["dock"] != [str(x) for x in cfg.get("dock", [])]:
                    notes["tv_dock_changed"] = True
                cfg.update(dock=lay["dock"], rows=lay["rows"], rename=lay["rename"], hidden=lay["hidden"],
                           app_vpn=lay["app_vpn"])
            elif key == "chromecast":
                import layout   # Normal and Small dock tiles fit 5 and 6 apps
                lay = self._layout(v, layout.dock_max(changes.get("home_layout") or cfg.get("home_layout")))
                cc = cfg.setdefault("chromecast", {})
                cc.update(lay)
                cc["rev"] = int(time.time())   # the Chromecast drops its own Move/Remove changes for these
            else:
                raise ValueError(f"{key} can't be changed here")
        return cfg, notes

    def save(self, cfg: dict):
        backups = self.data / "config-backups"
        backups.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y-%m-%d_%H%M%S")
        old = self.config_path.read_text(encoding="utf-8")
        (backups / f"config-{stamp}.json").write_text(old, encoding="utf-8")
        (backups / f"config-{stamp}.json").chmod(0o600)
        for extra in sorted(backups.glob("config-*.json"))[:-20]:   # keep the last 20
            extra.unlink()
        tmp = self.config_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(cfg, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        tmp.chmod(0o600)
        tmp.replace(self.config_path)
        self.log(f"admin: settings saved (backup {stamp})")

    # ---------- the Chromecast's apps, as its launcher reports them ----------
    def cc_apps_file(self) -> Path:
        return self.data / "cc-apps.json"

    def store_cc_apps(self, apps: list, packs: list | None = None):
        icons = self.data / "cc-icons"
        icons.mkdir(parents=True, exist_ok=True)
        out = []
        for a in apps[:300]:
            pkg = str(a.get("pkg", ""))
            if not re.match(r"^[A-Za-z0-9_.]{1,120}$", pkg):
                continue
            out.append({"id": pkg, "name": str(a.get("name", pkg))[:60], "banner": bool(a.get("banner"))})
            png = a.get("icon")
            if isinstance(png, str) and len(png) < 400_000:
                try:
                    import base64
                    (icons / f"{pkg}.png").write_bytes(base64.b64decode(png))
                except ValueError:
                    pass
        # the Android icon packs installed there (Home layout → Icon pack in the panel)
        icon_packs = [{"id": str(x.get("pkg")), "name": str(x.get("name") or x.get("pkg"))[:60]} for x in (packs or [])[:50]
                      if isinstance(x, dict) and re.match(r"^[A-Za-z0-9_.]{1,120}$", str(x.get("pkg", "")))]
        self.cc_apps_file().write_text(json.dumps({"seen": int(time.time()), "apps": out, "icon_packs": icon_packs}, ensure_ascii=False),
                                       encoding="utf-8")

    def cc_apps(self) -> dict:
        try:
            return json.loads(self.cc_apps_file().read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {"seen": 0, "apps": []}

    def cc_icon(self, pkg: str) -> bytes | None:
        if not re.match(r"^[A-Za-z0-9_.]{1,120}$", pkg):
            return None
        f = self.data / "cc-icons" / f"{pkg}.png"
        return f.read_bytes() if f.exists() else None

    # ---------- weather place search (Open-Meteo) ----------
    @staticmethod
    def geocode(q: str) -> list:
        url = "https://geocoding-api.open-meteo.com/v1/search?count=6&language=en&name=" + urllib.parse.quote(q[:60])
        with urllib.request.urlopen(url, timeout=10) as r:
            data = json.loads(r.read())
        return [{"city": p.get("name", ""), "country": p.get("country", ""), "admin": p.get("admin1", ""),
                 "lat": p.get("latitude"), "lon": p.get("longitude")} for p in data.get("results") or []]
