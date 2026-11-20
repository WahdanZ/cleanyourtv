# Add-ons

Add-ons put more on the home screens: **widgets** (cards on Home), **rows** (a row of pictures that open
something), **full-screen** pages (opened from a tile) and **quick actions** (a tile that does a few steps).
They are added to Home and set up in the admin panel (`/admin`, Chromecast tab). The Chromecast shows all
four kinds; the TV shows the widgets, the quote and the weather, in the same layout.

Built-in add-ons are here, in `addons/`. Your own go in `data/addons/` (not in git); one with the same id as a
built-in one replaces it. After adding a folder, press **Reload add-ons** in the admin panel.

## A folder per add-on

```
data/addons/my-addon/
  addon.json   what it is and its settings
  addon.py     fetch(settings, ctx): its data (not needed for quick actions)
```

### addon.json

```json
{
  "id": "my-addon",                 // a-z, 0-9 and -; the folder's name if left out
  "name": "My add-on",
  "type": "widget",                 // widget | row | screen | action
  "emoji": "⭐", "color": "#3a6ea5", // its tile and card
  "description": "One line for the admin panel.",
  "refresh_minutes": 30,            // how often fetch() runs
  "settings": [                     // shown as a form in the admin panel
    {"key": "feed", "label": "Feed address", "type": "text", "default": "https://…"},
    {"key": "count", "label": "How many", "type": "number", "default": 5},
    {"key": "live", "label": "Live only", "type": "bool", "default": false},
    {"key": "league", "label": "League", "type": "select", "default": "a", "options": [["a", "A"], ["b", "B"]]},
    {"key": "place", "label": "City", "type": "place"}   // a city search; gives {"city", "lat", "lon"}
  ]
}
```

A quick action has `"steps"` instead of an addon.py, done in order:
`{"vpn": "us"}` (switch the VPN; `"off"` for none), `{"open": "<Android package>"}` (optionally with `"uri"`),
`{"screen": "<id of a full-screen add-on>"}`, `{"toast": "a message"}`.

### addon.py

```python
def fetch(settings, ctx):
    items = ctx.rss(settings["feed"], 3)          # also: ctx.json(url), ctx.get(url) -> bytes, ctx.log(msg)
    return {"title": "News", "lines": [{"text": i["title"], "sub": ""} for i in items]}
```

It runs in the helper, on its computer, in the background; whatever it returns is what the home screens get.
`ctx.place` is the weather's city (`{"city", "lat", "lon"}`). An error is shown in the admin panel and the add-on
tries again 5 minutes later.

What fetch() returns, by type:

| type | returns |
|---|---|
| widget | `{"title", "lines": [{"text", "sub"}]}`: up to 3 lines |
| row | `{"title", "items": [{"title", "subtitle", "image", "poster", "action"}]}`; `poster: true` for a tall picture |
| screen | `{"kind": "slideshow", "items": [{"image", "caption"}], "seconds"}` or `{"kind": "list", "items": [{"title", "subtitle", "image", "action"}]}` |

An `action` (on a row item or a list item) is one step or a list of steps, as for quick actions, e.g.
`{"open": "com.google.android.youtube.tv", "uri": "https://www.youtube.com/watch?v=…"}`.

A full-screen add-on can serve its own files (photos, say): `def file(name, settings)` returns `(bytes, type)`,
and `ctx.file_url(name)` is their address.

Add-ons are Python running in the helper with your user's rights: only add ones you have read.

## Icon themes

An icon theme restyles every app's icon in the Home layouts ([docs/experiences.md](../docs/experiences.md)) on the
TV and the Chromecast. It is an add-on with `"type": "icons"`: one `addon.json`, no code, and optionally your own
pictures for some apps. Three come built in: `icons-glass`, `icons-mono`, `icons-light`. You choose one in the phone
panel under **Home layout → App icons**; one theme is on at a time.

On the Chromecast you can also use any **Android icon pack** made for phone launchers (Arcticons, Delta…); it needs
no add-on: [docs/experiences.md](../docs/experiences.md#the-layouts).

```
data/addons/my-icons/
  addon.json
  icons/            optional: your pictures (png, jpg, webp or svg; transparent PNG or SVG for a tint)
    netflix.png
    youtube.svg
```

```json
{
  "id": "my-icons",
  "name": "My icons",
  "type": "icons",
  "emoji": "🎨",
  "description": "One line for the panel.",
  "style": {
    "shape": "squircle",          // round | squircle | square | keep (the icon's own corners)
    "background": "brand",        // brand (each app's colour) | none | "#17293B"
    "monochrome": false,          // grey icons
    "tint": "#F3F8FB",            // with your own pictures: they become this colour (a silhouette)
    "padding": 0.2                // space around the icon, 0 to 0.4 of the tile
  },
  "icons": {                      // optional: an app id (Android package or the TV's app id) or the app's name,
    "com.netflix.ninja": "netflix.png",   // lower case, to a file in icons/
    "youtube": "youtube.svg"
  }
}
```

Apps your theme has no picture for keep their own artwork, redrawn in your style: the app's colour (read from its
icon or banner) on your plate, its logo inside. A bad value falls back to the
default (`squircle`, `brand`, `0.18`), so a theme never breaks Home. Press **Reload add-ons** in the panel after
adding the folder.

**Where pictures work:** your own pictures are served by the helper, so they show wherever the helper is (the TV,
and a Chromecast that uses it). A Chromecast running on its own uses a theme's style with the apps' own icons.
Themes apply to the 14 layouts, not to Classic.
