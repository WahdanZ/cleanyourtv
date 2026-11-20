"""The same checks against either back end (shared/API.md): the helper, or the Chromecast's own server.

    python tests/api_contract.py http://127.0.0.1:8797 --password <pw>     # a helper (tests/run_helper_contract.sh)
    python tests/api_contract.py http://localhost:18765                    # the Chromecast's server, through
                                                                            #   adb forward tcp:18765 tcp:8765
Use a test copy, not your everyday setup: it saves a few settings (and puts them back). Standard library only.
Exit code 0 when every check passes.
"""
import http.cookiejar
import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://127.0.0.1:8797"
PASSWORD = sys.argv[sys.argv.index("--password") + 1] if "--password" in sys.argv else ""
jar = http.cookiejar.CookieJar()
web = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
results = []


def call(path, body=None):
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(),
                                 method="GET" if body is None else "POST",
                                 headers={"Content-Type": "application/json", "X-CYT": "1"})
    try:
        with web.open(req, timeout=30) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw[:1] in (b"{", b"[") else raw)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail and not ok else ""))


def has(d, keys):
    missing = [k for k in keys if not isinstance(d, dict) or k not in d]
    return not missing, "missing " + ", ".join(missing)


# ---------- sign in ----------
code, hello = call("/api/admin/hello")
ok, why = has(hello, ["password_set", "signed_in", "code", "api"])
check("hello: fields", code == 200 and ok, why)
check("hello: api version 1", isinstance(hello, dict) and hello.get("api") == 1, str(hello))
if isinstance(hello, dict) and not hello.get("signed_in"):
    code, _ = call("/api/admin/login", {"password": PASSWORD})
    check("login", code == 200, f"HTTP {code}")

# ---------- the panel's state ----------
code, st = call("/api/admin/state")
ok, why = has(st, ["settings", "tv_apps", "cc", "devices", "regions", "region", "layout_spec", "addons", "scenes",
                   "websites", "wallpaper", "preview", "quick_base", "https"])
check("state: fields", code == 200 and ok, why)
if code != 200:
    sys.exit(f"state: HTTP {code}; nothing more to check")
ok, why = has(st["settings"], ["greeting_name", "clock_24h", "live_refresh", "home_layout", "chromecast", "addons", "vpn_countries"])
check("state.settings: fields", ok, why)
ok, why = has(st["settings"]["chromecast"], ["dock", "rows", "rename", "hidden", "app_vpn"])
check("state.settings.chromecast: fields", ok, why)
ok, why = has(st["devices"], ["tv", "cc", "vpn"])
check("state.devices: fields", ok, why)
ok, why = has(st["layout_spec"], ["cols", "rows", "reserved", "blocks", "presets", "widget_blocks", "tile_sizes", "clock_styles"])
check("state.layout_spec: fields", ok, why)
check("state.cc.apps: a list", isinstance(st["cc"].get("apps"), list))
check("state.addons: each has id, type, enabled, values, settings",
      all(has(a, ["id", "name", "type", "enabled", "values", "settings"])[0] for a in st["addons"]))
ok, why = has(st["wallpaper"], ["now", "sources", "own", "active"])
check("state.wallpaper: fields", ok, why)
check("state.quick_base: a /q/ link", "/q/" in str(st["quick_base"]))

# ---------- saving: a valid change round-trips, an invalid one is refused ----------
old_name = st["settings"]["greeting_name"]
code, r = call("/api/admin/save", {"changes": {"greeting_name": "Contract test"}})
_, st2 = call("/api/admin/state")
check("save: a valid change is kept", code == 200 and st2["settings"]["greeting_name"] == "Contract test", f"HTTP {code} {r}")
call("/api/admin/save", {"changes": {"greeting_name": old_name}})
bad = json.loads(json.dumps(st["layout_spec"]["presets"]["classic"]))
bad["blocks"]["quote"] = dict(bad["blocks"]["clock"], on=True)   # on top of the clock
code, r = call("/api/admin/save", {"changes": {"home_layout": bad}})
check("save: overlapping blocks are refused (400)", code == 400 and isinstance(r, dict) and r.get("error"), f"HTTP {code} {r}")
code, r = call("/api/admin/save", {"changes": {"no_such_setting": 1}})
check("save: an unknown setting is refused (400)", code == 400, f"HTTP {code}")

# ---------- the player, remote status, sleep timer, logs, usage ----------
code, r = call("/api/admin/now-playing")
check("now-playing: ok and playing", code == 200 and "ok" in r and ("playing" in r or "error" in r), str(r)[:120])
code, r = call("/api/admin/remote-status")
ok, why = has(r, ["tv", "cc"])
check("remote-status: fields", code == 200 and ok, why)
code, r = call("/api/admin/sleep", {"set": "30"})
check("sleep: set", code == 200 and r.get("sleep", {}).get("on") is True and r["sleep"].get("v") == "30", str(r))
code, r = call("/api/admin/sleep", {"set": "off"})
check("sleep: off", code == 200 and r.get("sleep", {}).get("on") is False, str(r))
code, r = call("/api/admin/sleep", {"set": "nonsense"})
check("sleep: nonsense is refused (400)", code == 400, f"HTTP {code}")
code, r = call("/api/admin/logs")
check("logs: lines", code == 200 and isinstance(r.get("lines"), list))
code, r = call("/api/admin/stats?days=7")
ok, why = has(r, ["days", "total", "apps", "per_day", "titles", "tv", "all", "cc"])
check("stats: fields", code == 200 and ok, why)
check("stats: 7 days", isinstance(r, dict) and len(r.get("per_day", [])) == 7)

# ---------- scenes and websites: valid lists are kept, bad ones refused; put back after ----------
old_scenes, old_sites = st["scenes"], st["websites"]
code, r = call("/api/admin/scenes", {"scenes": [{"name": "Contract test", "icon": "", "steps": ["wait-2"]}]})
check("scenes: a valid list is kept", code == 200 and r.get("scenes", [{}])[0].get("id") == "contracttest", str(r)[:120])
code, r = call("/api/admin/scenes", {"scenes": [{"name": "Bad", "steps": ["rm -rf"]}]})
check("scenes: an unknown step is refused (400)", code == 400, f"HTTP {code}")
code, r = call("/api/admin/scene-run", {"id": "no-such-scene"})
check("scene-run: unknown scene answers ok false", code == 200 and r.get("ok") is False, str(r))
call("/api/admin/scenes", {"scenes": old_scenes})
code, r = call("/api/admin/websites", {"websites": [{"name": "Example", "url": "example.com"}]})
check("websites: a valid list is kept, the address completed",
      code == 200 and r.get("websites", [{}])[0].get("url") == "https://example.com", str(r)[:120])
code, r = call("/api/admin/websites", {"websites": [{"name": "Bad", "url": "ftp://x"}]})
check("websites: a bad address is refused (400)", code == 400, f"HTTP {code}")
call("/api/admin/websites", {"websites": old_sites})

# ---------- Home's own requests (allowed from the Chromecast itself: localhost here) ----------
code, r = call("/api/cc")
if code == 403:
    print("SKIP Home's requests (this address may not ask them)")
else:
    ok, why = has(r, ["dock", "rows", "rename", "hidden", "app_vpn", "layout", "greeting_name", "quotes", "weather",
                      "wallpaper", "region", "clock_24h", "rev", "live_refresh", "cursor", "started", "saved", "api"])
    check("home /api/cc: fields", code == 200 and ok, why)
    code, r = call("/api/rev")
    ok, why = has(r, ["started", "saved", "wall", "code"])
    check("home /api/rev: fields", code == 200 and ok, why)
    code, r = call("/api/home-edit")
    ok, why = has(r, ["layout", "spec", "widgets"])
    check("home /api/home-edit: fields", code == 200 and ok, why)
    code, r = call("/api/addons/home")
    ok, why = has(r, ["widgets", "rows", "tiles", "widget_seconds"])
    check("home /api/addons/home: fields", code == 200 and ok, why)
    code, r = call("/api/region")
    ok, why = has(r, ["region", "regions"])
    check("home /api/region: fields", code == 200 and ok, why)
    code, r = call("/api/admin-link")
    check("home /api/admin-link: QR codes", code == 200 and isinstance(r.get("links"), list) and len(r["links"]) == 3
          and all(len(l["qr"]) >= 21 for l in r["links"]), str(r)[:120])

# ---------- Home layouts (shared/experiences): a choice round-trips, a bad one is refused, scenes stay scoped ----------
code, st3 = call("/api/admin/state")
old_exp = st3["settings"].get("home_experience") if isinstance(st3, dict) else None
check("layouts: the choice is in the panel's settings", isinstance(old_exp, dict) and {"tv", "cc", "opts", "scenes"} <= set(old_exp), str(old_exp)[:120])
code, r = call("/api/admin/save", {"changes": {"home_experience": {"tv": "channel", "cc": "aurora", "scenes": [],
                                                                   "opts": {"aurora": {"strip": "6", "junk": 1}, "nope": {"x": 1}}}}})
code2, cc = call("/api/cc")
got = cc.get("experience") if isinstance(cc, dict) else None
check("layouts: a choice reaches the Chromecast's settings, unknown options dropped", code == 200 and isinstance(got, dict) and got.get("id") == "aurora"
      and got.get("opts", {}).get("aurora") == {"strip": "6"} and "nope" not in got.get("opts", {}), f"HTTP {code} {got}")
code, r = call("/api/admin/save", {"changes": {"home_experience": {"cc": "no-such-layout"}}})
check("layouts: an unknown layout is refused (400)", code == 400, f"HTTP {code}")
code, r = call("/api/home-scenes")
check("layouts: /api/home-scenes lists only allowed scenes", code == 200 and isinstance(r, dict) and r.get("scenes") == [], str(r)[:120])
code, r = call("/api/home-scene-run", {"id": "notallowed"})
check("layouts: a scene not allowed on Home is refused (403)", code == 403 and isinstance(r, dict) and r.get("ok") is False, f"HTTP {code} {r}")
code, r = call("/experiences/catalog.js")
check("layouts: the catalog is served", code == 200 and isinstance(r, bytes) and b"/*JSON*/" in r, f"HTTP {code}")
if isinstance(old_exp, dict):
    call("/api/admin/save", {"changes": {"home_experience": old_exp}})

failed = [n for n, ok, _ in results if not ok]
print(f"\n{len(results) - len(failed)} of {len(results)} passed" + (": failed " + ", ".join(failed) if failed else ""))
sys.exit(1 if failed else 0)
