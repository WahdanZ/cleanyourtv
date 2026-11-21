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
| **Usage** | How long the TV and the Chromecast were used, per day and per app (see below) |
| **Settings** (the ⚙ button at the top) | Your name for the greeting, 24-hour clock, the weather's city, the wallpaper, the TV's Home takeover; then status, open Home on the TV, restart the helper, sign out; the secure address and notifications, scenes, phone shortcuts. Every save keeps a copy of the settings before it (the last 20, in `data/config-backups`) |

Each tab shows only when you have that device: no Chromecast in `config.json`, no Chromecast tab; `"vidaa": false`,
no TV tab; no VPN, no VPN tab. On a phone the tabs move to the bottom of the screen.

**Undo** steps back through your changes; nothing is kept until you press **Save**.

## The layout

Above the dock, Home is a grid of 12 columns by 6 rows (inside the edge some TVs cut off). The clock, the quote, the
weather, three widget blocks, the app's name and Now playing each sit somewhere on it, or are hidden. The top right
stays free for the buttons.

- **Start from a preset:** Classic, Widgets left, Big clock, Dashboard, Calm (just the clock, the weather and one
  card) or Photo frame (almost nothing over the photo).
- **Move:** drag a block on the picture. **Resize:** drag its corner (it snaps to the grid), use its Width and Height
  buttons, or pick one of its quick sizes. Any size works; what is bigger than its block (a big clock in a small
  one) shrinks to fit. Blocks can't overlap.
- **Hide or show:** tap a block > Hide; hidden ones are listed under *Not on the screen*.
- **Dock and rows:** big, normal or small tiles (4, 5 or 6 across), and *Dock across the whole width*.

The TV and the Chromecast share one layout; each keeps its own apps.

## The clock

Tap the clock on the picture:

- **Style:** Classic, Bold, Stacked, Tiles, Analog or Words ("ten past nine").
- **A different one each day:** tick two or more styles; Home goes through them, one a day.
- **Weather on the clock:** the temperature and an icon on the date line (handy when the weather block is hidden).

## Widgets and add-ons

Widgets are cards on Home, in three widget blocks, which resize like any other block. The card fills its
block and shows what fits: one line when it is one row tall, no pictures when it is narrow, more games or headlines
and a bigger picture when it is tall. A block of four rows or more stacks cards (one per two rows). A block shows the
widgets you drop on it, in turn; one with none picked shows every widget no other block has.

- Drag a widget from **Add to the screen** onto a block, or tap a block and tick the widgets it shows.
- **✕** on a card takes it off Home; its settings (city, league, feed…) stay for next time.
- Rows (YouTube channels, Top movies) go after the dock; full-screen add-ons (News reader, Photo slideshow) and quick
  actions (Netflix USA: switch the VPN, then open Netflix) become tiles you place like apps.

Built in:

| Add-on | Kind | What it shows |
|---|---|---|
| Weather | Its own block | Now and today's high/low (Open-Meteo), for the city you pick |
| Quote of the day | Its own block | From ZenQuotes (with the author's portrait), **your own list** (one per line, `quote — author`, any language), or the built-in 30; one a day in order or at random, or a new one each time Home opens |
| Football scores | Widget | Today's and the next matches of a league, live scores (ESPN) |
| Prayer times | Widget | Today's times and a countdown to the next (AlAdhan) |
| News headlines | Widget | The latest headlines of an RSS feed |
| YouTube channels | Row | The newest videos of channels you pick; OK plays it in YouTube |
| Top movies | Row | This week's popular films; OK opens the trailer |
| News reader | Full screen | Headlines with their summaries |
| Photo slideshow | Full screen | Your own photos |
| Netflix USA, OSN+ UAE | Quick actions | Switch the VPN, then open the app (examples to copy) |

The quote and the weather are added, removed and set up like the others (in **Add to the screen** and **All add-ons**,
or by tapping their block); each has a block of its own.

Write your own: a folder with an `addon.json` and a few lines of Python. See [addons/README.md](../addons/README.md).

## The wallpaper

Bing's photo of the day (pick the country), Wikipedia's or NASA's picture of the day, or your own photos (upload them
in the panel; one a day, or always the same). *Show another photo* changes today's. 4K when the TV can show it.

**Look** (the TV, and Classic Home on the Chromecast): *Standard* draws the cards, the buttons and the dock dark and calm; *Glass* draws
them as frosted glass over the photo, with a bright rim. Pick it in the panel (tap the photo, then *Look*) or with the
remote (Edit Home → *+ Add to Home* → *Look*). The photo is frosted once when it arrives, so Glass costs no more to
draw than Standard.

## Edit Home with the remote

On the TV or the Chromecast, open the **✎** pill at the top right:

1. Arrows choose a block; **OK** opens its menu: Move, Resize (◀ ▶ width, ▲ ▼ height), its quick sizes, the
   widgets it shows, Hide.
2. **+ Add to Home** brings back a hidden block or a widget.
3. **Back** saves (the admin panel shows it at once).

On the Chromecast, hold **OK** on an app to move it or take it off Home.

## Remote, player and apps on your phone

In the admin panel (best as an app on your phone's Home screen, see [setup/helper.md](setup/helper.md)):

- **Remote** (the Home tab, or the round button at the bottom right on other tabs): arrows, OK, Back, Home, volume, for the TV or the Chromecast. On the Chromecast the button at the end of the top row swaps the arrows for the trackpad (and back), in the same spot. Home goes
  to our launcher. **Apps**: open any app on either device, or type a web address (a YouTube link opens in YouTube).
- **Trackpad** (Chromecast: above the remote's buttons): a pointer over any app, handy in a browser or on a
  website. Drag to move it, tap to click, hold for a long press, two fingers (or a mouse wheel) to scroll. The first
  time, the helper switches on the launcher's pointer service over adb (Android lets an app tap inside other apps only
  as an accessibility service); it reads nothing on the screen. Apps that move their own pointer with the arrow keys
  and ignore clicks, like BrowseHere, switch the trackpad to **arrow mode** by themselves: a tap sends OK; touch and slide a little in a
  direction and its pointer keeps moving that way until you lift your finger, like holding the remote's arrow
  (`chromecast.trackpad_arrow_apps` in `config.json` lists these apps).
- **Live screen** (Chromecast, above the trackpad): the Chromecast's screen about once a second; tap on it to click
  there. Video apps (YouTube, Netflix…) show black: they block screen pictures; the remote and the pointer still work.
- **Without Wireless debugging** the Chromecast's remote keeps working through the launcher: arrows, OK, Back, Home,
  volume (to the TV over HDMI-CEC), play/pause and opening apps. Typing, Wake, the live screen and trackpad clicks
  need Wireless debugging.
- **Usage** (its own tab; today, 7 or 30 days):
  - **All:** the TV and the Chromecast together, one bar per day (TV blue, Chromecast orange) and one list; the
    Chromecast's input is counted once, by what played on it ("Chromecast: menus and other" is the rest).
  - **TV:** how long it was on, per day and per screen: each app, Live TV, each input (the Chromecast's input is
    found by itself and named so, or set it: `"tv_input": "HDMI2"` under `chromecast` in `config.json`), the home screen.
  - **Chromecast:** how long something played, per day and per app, and the titles watched most.
  Counted every 30 seconds; kept on the helper's computer only (`data/watch-stats.json`, 90 days).
- **Scenes** (Settings, then buttons on the Home tab): several steps with one tap, e.g. *Movie night*: wake the
  Chromecast, VPN to the US, open Netflix. Steps: any remote button, open an app, a VPN country, the sleep timer, a
  wait. Each scene gets a phone shortcut link too.
- **Sleep timer** (in Now playing): 30 min, 1 h, 1½ h, or a minute and a half after the video stops. The TV turns
  off, then the Chromecast goes to sleep. Also as a phone shortcut and a scene step.
- **Now playing** (the ♫ button): what's playing on the Chromecast, with the video's picture, how far in and how
  long it is (tap the bar to jump), play/pause, back and forward.
  **Controls in my phone's notifications** puts the same in the phone's notifications and lock screen, with the
  picture and the progress bar (drag it to jump). When nothing plays on the Chromecast but the TV is on, it shows what
  the TV shows (with a made-up one-hour bar: the TV doesn't say how long things are) and its buttons go to the TV (the page plays
  a silent sound to get them, so music playing on the phone pauses; tap the button again to remove them). Best on
  Android in Chrome; on an iPhone, in Safari with the page left open.
- **Phone shortcuts** (Settings): a secret link per button (TV on/off, Chromecast wake/sleep, play/pause, volume,
  open an app, VPN country) for the phone's Lock Screen, Control Center or widgets, without opening the panel. On an
  iPhone: a Shortcut with *Get Contents of URL*; on Android: the HTTP Shortcuts app. The panel shows the steps, a
  **Try** button and **New links** (the old ones stop working). They work at home only. *TV: turn on* uses Wake-on-LAN
  (turn on *Wake on LAN / Wi-Fi* in the TV's network settings).
  The phone's own volume buttons can't be used: a web page never gets them (with Now playing in the notifications,
  they change the phone's volume). Use the panel's volume buttons or the volume shortcuts.
- **Messages** show at the bottom of the screen: grey for news, red for errors (with **Reconnect** when the
  Chromecast's link dropped).
- **QR codes** on Home (the **Admin** pill, top right): point your phone at one to open the panel, the remote or the
  player, no typing.

**Now playing on the Chromecast's Home** (switch the *Now playing* block on in the Chromecast tab): like a music
app's mini player, for Spotify, YouTube, YouTube Music or any player: the cover, title and artist, a progress bar,
and ⏮ ⏯ ⏭ and *Open* (back to the app). Press ▲ from the dock to reach the buttons, ◀ ▶ to move, OK to press, ▼ or
Back to return. It follows the player live (the launcher reads the media sessions itself) and shows only while
something is playing or paused; at one row tall it's a slim line with ⏯ and *Open*.

The TV's remote can control what's playing on the Chromecast too: show **Now playing** on Home (tap Now playing in
the layout), then use the remote's media keys, or the ♫ pill.

## Websites as apps

*Settings > Websites as apps*: a name and an address, on the TV and/or the Chromecast. Each becomes a tile you place
like an app in the TV and Chromecast tabs (it starts under More); it opens in the TV's browser, or the Chromecast's
(BrowseHere when it's installed; `"web_browser"` under `chromecast` in `config.json` picks another). While a browser
is in front on the Chromecast, the phone's remote switches to the trackpad by itself, and back afterwards.

## Logs

*Settings > Logs* shows what happened lately, newest at the bottom: **Helper** (its log), **Chromecast** (the
launcher's pointer service: connections, every move and where the pointer went, keys, and the app's crashes from
Android's own log) and **This phone** (what the panel sent and the errors it showed). They're kept in memory only.
When something goes wrong, open the right one right away and **Copy** it. Addresses are shown with a name: the TV,
the Chromecast, this computer, a phone that opened the panel ("iPhone"), or the router's name for it.

## Settings that are only in config.json

A few things have no button: `allowed_clients`, the VIDAA app ids (`dock`, `native_apps`, `web_urls`), the browser's
search engine, Stremio and YouTube settings, `vpn_home_name`. See [setup/helper.md](setup/helper.md) and the
[the VIDAA guide](vidaa.md#2-configure-and-start-the-helper). Restart the helper after changing them by hand.
