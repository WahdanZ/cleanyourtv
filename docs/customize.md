# Make Home yours

Everything here is done in the admin panel (`http://<helper ip>:8765/admin`, see [setup/helper.md](setup/helper.md))
or with the TV remote. A save shows on the TV and the Chromecast within about 3 seconds; nothing to restart.

## The admin panel

| Tab | What's in it |
|---|---|
| **Home** | Opens first: a remote for the TV or the Chromecast, what's playing, and every app to open (or a web address) |
| **VPN** | The country now, a button per country, and which countries the TV's Region menu offers. Only with a VPN set up ([setup/vpn.md](setup/vpn.md)) |
| **TV** | A picture of the TV's Home. Tap anything on it to change it: the clock, a widget block, the dock, the wallpaper |
| **Chromecast** | The same for the Chromecast, plus its dock and rows, names, hidden apps, the VPN country per app, and **Real screen** (a photo of what the Chromecast shows now) |
| **Settings** | Your name for the greeting, 24-hour clock, the weather's city, the wallpaper, the TV's Home takeover; then status, open Home on the TV, restart the helper, sign out. Every save keeps a copy of the settings before it (the last 20, in `data/config-backups`) |

Each tab shows only when you have that device: no Chromecast in `config.json`, no Chromecast tab; `"vidaa": false`,
no TV tab; no VPN, no VPN tab. On a phone the tabs move to the bottom of the screen.

**Undo** steps back through your changes; nothing is kept until you press **Save**.

## The layout

Above the dock, Home is a grid of 12 columns by 6 rows (inside the edge some TVs cut off). The clock, the quote, the
weather, three widget blocks, the app's name and Now playing each sit somewhere on it, or are hidden. The top right
stays free for the buttons.

- **Start from a preset:** Classic, Widgets left, Big clock, Dashboard, Calm (just the clock, the weather and one
  card) or Photo frame (almost nothing over the photo).
- **Move:** drag a block on the picture. **Resize:** tap it and pick a size; each block has a few sizes its text
  always fits in. Blocks can't overlap.
- **Hide or show:** tap a block > Hide; hidden ones are listed under *Not on the screen*.
- **Dock and rows:** big, normal or small tiles (4, 5 or 6 across), and *Dock across the whole width*.

The TV and the Chromecast share one layout; each keeps its own apps.

## The clock

Tap the clock on the picture:

- **Style:** Classic, Bold, Stacked, Tiles, Analog or Words ("ten past nine").
- **A different one each day:** tick two or more styles; Home goes through them, one a day.
- **Weather on the clock:** the temperature and an icon on the date line (handy when the weather block is hidden).

## Widgets and add-ons

Widgets are cards on Home; three widget blocks can each show one card, or two stacked (the tall sizes). A block
shows the widgets you drop on it, in turn; one with none picked shows every widget no other block has.

- Drag a widget from **Add to the screen** onto a block, or tap a block and tick the widgets it shows.
- **✕** on a card takes it off Home; its settings (city, league, feed…) stay for next time.
- Rows (YouTube channels, Top movies) go after the dock; full-screen add-ons (News reader, Photo slideshow) and quick
  actions (Netflix USA: switch the VPN, then open Netflix) become tiles you place like apps.

Built in:

| Add-on | Kind | What it shows |
|---|---|---|
| Weather, Quote of the day | Home blocks | Now and today's high/low (Open-Meteo); a quote with the author's portrait (ZenQuotes) |
| Football scores | Widget | Today's and the next matches of a league, live scores (ESPN) |
| Prayer times | Widget | Today's times and a countdown to the next (AlAdhan) |
| News headlines | Widget | The latest headlines of an RSS feed |
| YouTube channels | Row | The newest videos of channels you pick; OK plays it in YouTube |
| Top movies | Row | This week's popular films; OK opens the trailer |
| News reader | Full screen | Headlines with their summaries |
| Photo slideshow | Full screen | Your own photos |
| Netflix USA, OSN+ UAE | Quick actions | Switch the VPN, then open the app (examples to copy) |

Write your own: a folder with an `addon.json` and a few lines of Python. See [addons/README.md](../addons/README.md).

## The wallpaper

Bing's photo of the day (pick the country), Wikipedia's or NASA's picture of the day, or your own photos (upload them
in the panel; one a day, or always the same). *Show another photo* changes today's. 4K when the TV can show it.

## Edit Home with the remote

On the TV or the Chromecast, open the **✎** pill at the top right:

1. Arrows choose a block; **OK** opens its menu: Move, its sizes, the widgets it shows, Hide.
2. **+ Add to Home** brings back a hidden block or a widget.
3. **Back** saves (the admin panel shows it at once).

On the Chromecast, hold **OK** on an app to move it or take it off Home.

## Remote, player and apps on your phone

In the admin panel (best as an app on your phone's Home screen, see [setup/helper.md](setup/helper.md)):

- **Remote** (the Home tab, or the round button at the bottom right on other tabs): arrows, OK, Back, Home, volume, for the TV or the Chromecast. Home goes
  to our launcher. **Apps**: open any app on either device, or type a web address (a YouTube link opens in YouTube).
- **Now playing** (the ♫ button): what's playing on the Chromecast, with play/pause, back and forward.
- **QR codes** on Home (the **Admin** pill, top right): point your phone at one to open the panel, the remote or the
  player, no typing.

The TV's remote can control what's playing on the Chromecast too: show **Now playing** on Home (tap Now playing in
the layout), then use the remote's media keys, or the ♫ pill.

## Settings that are only in config.json

A few things have no button: `allowed_clients`, the VIDAA app ids (`dock`, `native_apps`, `web_urls`), the browser's
search engine, Stremio and YouTube settings, `vpn_home_name`. See [setup/helper.md](setup/helper.md) and the
[README](../README.md#2-configure-and-start-the-helper). Restart the helper after changing them by hand.
