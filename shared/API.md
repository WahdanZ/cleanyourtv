# The panel and Home API (version 1)

Two back ends answer the same requests, so the same phone panel (`shared/panel/admin.html`) and the same Chromecast
Home work with either:

| Back end | Code | Where it runs |
|---|---|---|
| **The helper** | `helper/` (Python) | A computer at home (setups 2 and 3) |
| **The Chromecast's own server** | `android/.../Hub.kt`, `HubApi.kt`, `Content.kt` (Kotlin) | The Chromecast itself (setup 1) |

Each one ships its own copy of the panel (the helper serves `shared/panel/`; the app has it built in), so a panel always
talks to the back end it came with. Their answers must match this page; `tests/api_contract.py` checks both.

**Change one, change both.** A new field or request goes into this page, both back ends and the contract test in the
same change. A back end that can't do something (the Chromecast has no VIDAA TV; the helper has no built-in VPN)
answers in the same shape with the feature off (`"devices": {"tv": false}`, `"regions": []`) rather than an error.

`"api": 1` in `/api/admin/hello` and `/api/cc` is this version. Raise it only for a change an older panel or Home
can't read.

## Shared data

- `shared/layout.json`: the Home layout rules (grid, blocks, sizes, presets, tile sizes, clock styles), read by
  `helper/layout.py` and built into the app.
- `addons/<id>/addon.json`: what each built-in add-on is and its settings. The helper runs its `addon.py`; the
  Chromecast runs a Kotlin copy of the seven built-in ones (`Content.kt`).
- `shared/experiences/catalog.js`: the Home layouts (ids, names, key maps, options), a strict JSON block both
  back ends read (`helper/experience.py`, `Experience.kt`) and both screens draw from ([docs/experiences.md](../docs/experiences.md)).
- `web/quotes.json`: the built-in quotes (also loaded by the TV page when it's hosted on its own).
- `config.json`'s shape: both keep the settings in it (the helper in `config.json`, the Chromecast in its private
  copy). `tools/move-to-chromecast.py` copies it over.

## Home (the Chromecast's launcher; on the helper also the TV page)

| Request | Answer |
|---|---|
| `GET /api/cc` | `dock, rows, rename, hidden, app_vpn, layout, experience{id, opts, api, icon_pack}, greeting_name, quotes, weather, wallpaper, region, clock_24h, rev, live_refresh, cursor{port, token}, started, saved, api` |
| `GET /api/state` (helper: the TV page) | as before, plus `experience{id, opts, api}` for the TV |
| `GET /api/home-scenes` | `ok, scenes[{id, name, icon, summary}]`: only the scenes allowed on Home (`home_experience.scenes`), never their raw steps |
| `POST /api/home-scene-run {id}` | `ok, said` / 403 when that scene isn't allowed on Home (the screens aren't signed in) |
| `GET /api/tv-sources` · `POST /api/tv-source {id}` | the TV's inputs `sources[{id, name}]`; switch to one (the helper with a VIDAA TV; the Chromecast's server asks the helper) |
| `GET /experiences/<file>` | the layouts' files: `catalog.js` and `previews/<id>.jpg` on both; on the helper also the TV's engine (`engine.js`, `layouts.js`, `experiences.css`) |
| `GET /api/rev` | `started, saved, wall, code` (polled every 3 s: a change reloads settings or the page) |
| `GET /api/home-edit` / `POST` | `layout, spec, widgets[{id, name, emoji, on}]`; save `{layout, on[], off[]}` |
| `GET /api/addons/home` | `widgets[], rows[], tiles[], widget_seconds, icon_theme{id, name, style{shape, background, monochrome, tint, padding}, icons{app id or name: url}} or null` |
| `GET /api/addons/<id>/screen` · `…/run` · `…/file/<name>` | a full-screen add-on's page; a quick action's `steps[]`; an add-on's file |
| `GET /api/weather` · `/api/quote` · `/api/wallpaper` · `/api/wallpaper.jpg` | today's weather, quote, photo |
| `GET /api/region` / `POST {region}` | `region, regions[{id, name}]`; switch the VPN |
| `GET /api/now-playing` | `ok, playing{app, name, state, title, subtitle, position, duration, art, icon, screen_icon}` |
| `POST /api/cc-apps {apps, icon_packs[{pkg, name}]}` · `/api/cc-open {appId}` | the installed apps (names, icons) and Android icon packs (in `/api/admin/state` as `cc.icon_packs[{id, name}]`); an app is opening (per-app VPN) |
| `GET /api/admin-link` | `url, qr, links[{label, url, qr}]` (the Admin button's QR codes) |

## The panel (signed in; POSTs carry `X-CYT: 1`)

| Request | Answer |
|---|---|
| `GET /api/admin/hello` | `password_set, signed_in, code, api` |
| `POST /api/admin/login {password}` · `/logout` | sets / clears the `cyt_admin` cookie |
| `GET /api/admin/state` | `settings, tv_apps, cc{apps}, devices{tv, cc, vpn}, regions, region, layout_spec, addons, scenes, websites, wallpaper{now, sources, own, active}, preview, quick_base, https, …` |
| `POST /api/admin/save {changes}` | `ok`; 400 with `error` for anything invalid (checked like `helper/admin.py`). `home_experience: {tv, cc, opts{<layout>: {…}}, scenes[], icon_pack}` picks the layouts (`helper/experience.py`) and the Chromecast's Android icon pack (a package name, or ""); an icon theme is chosen by enabling its add-on in `addons` |
| `POST /api/admin/remote {device, key \| text}` · `/launch {device, app \| url}` · `/pointer {ops}` | the remote, opening apps, the trackpad |
| `GET /api/admin/now-playing` · `np-art` · `POST np-seek {sec}` | the player |
| `POST /api/admin/sleep {set}` | `sleep{on, end, left, v}` |
| `POST /api/admin/scenes {scenes}` · `scene-run {id}` · `websites {websites}` · `quick-new` | scenes, websites, shortcut links |
| `GET /api/admin/stats?days=n` | `days, total, apps, per_day, titles, tv{on, total, per_day, screens}, all{total, per_day, items}, cc` |
| `GET /api/admin/logs` · `remote-status` | `lines[]`; `tv{on}, cc{linked, awake, why, front}, sleep` |
| `POST /api/admin/wallpaper-next` · `wallpaper-upload` · `wallpaper-delete` · `addon-refresh` | the wallpaper and add-ons |
| `GET /q/<secret>/<action>` | a phone shortcut (plain-text answer) |

## Between the two (setup 2: the Chromecast's server and the helper on a computer)

The Chromecast's server asks the helper, with the key the helper gave its launcher (`X-Token`), for what only adb
can do: `POST /api/hub/pointer {ops}`, `/api/hub/tap {x, y, long}`, `/api/hub/text {text \| key}`,
`GET /api/hub/live?after=` (a screen frame). Without the helper, these fall back (a tap is OK) or say they need it.
`tools/move-to-chromecast.py` uses `POST /api/hub/file`, `/api/hub/vpn` and `/api/hub/mode` on the Chromecast.
