# The helper

The helper (`helper/cyt_helper.py`) is a small Python program on a computer at home. It serves the TV launcher,
opens native apps on the VIDAA TV, feeds the Chromecast launcher its settings and widgets, runs the add-ons, serves
the admin panel and the phone remote, and switches the VPN. Plain Python 3.10+, no database, no cloud.

## Where to run it

On anything that stays on and sits on the same network as the TV: a Mac, a Windows PC, a Linux box or a Raspberry
Pi. A cloud server can't do it: the TV only takes commands from its own network.

| | macOS | Windows | Linux / Raspberry Pi |
|---|---|---|---|
| Helper, launchers, admin panel, add-ons | Yes | Yes | Yes |
| Chromecast remote, Now playing, screen picture (adb) | Yes | Yes | Yes |
| VPN switch from the TV and the panel | Yes ([vpn.md](vpn.md), A) | No (use the router) | Manual ([vpn.md](vpn.md), C) |
| Start-at-login service | `setup/macos/install.sh` | `setup/windows/install.ps1` | `setup/linux/install.sh` |

## 1. Get the code

```bash
git clone https://github.com/WahdanZ/cleanyourtv-vidaa.git
cd cleanyourtv-vidaa
cp config.example.json config.json
```

**With a VIDAA TV**, also put [Sidee](https://github.com/Empi9245/Sidee) next to it and pair once (README,
[step 1](../../README.md#1-pair-with-the-tv-once)). **Chromecast only:** skip Sidee and set `"vidaa": false` in
`config.json`.

## 2. Fill in config.json

Most of it is set later in the admin panel; by hand you need only what lets the helper find your devices.

| Key | What it is |
|---|---|
| `tv_name` | The TV's network name (VIDAA). The helper finds the TV again by it if its address changes |
| `vidaa` | `false` for a home without a VIDAA TV (Chromecast only). Default `true` |
| `chromecast` | `{"ip": "192.168.1.60"}`: the Chromecast's address. Leave the whole key out without one. Its `dock`, `rows`, `rename`, `hidden` and `app_vpn` are set in the admin panel |
| `port` | The helper's port, `8765` |
| `allowed_clients` | Addresses (besides the TV's) that may use the TV's pages, e.g. your computer's for testing |
| `weather` | `{"city", "lat", "lon"}`; easier from the admin panel (city search) |
| `vpn_countries`, `vpn_home_name`, `app_vpn` | The VPN's countries, the name for "no VPN", and apps that switch it ([vpn.md](vpn.md)) |
| `home_layout`, `addons` | Written by the admin panel: the Home layout, and which add-ons are on Home with their settings |
| `launcher_url`, `launcher_origins`, `tile_icon` | The hosted launcher page (VIDAA, README [step 3](../../README.md#3-host-the-page)) |

The VIDAA-only keys (`dock`, `native_apps`, `browser`, `stremio`, `youtube`…) are described in the
[README](../../README.md#2-configure-and-start-the-helper).

## 3. Run it as a service

Try it once by hand first (`python3 helper/cyt_helper.py`, stop with Ctrl-C), then:

- **macOS:** `bash setup/macos/install.sh`. Makes `.venv`, installs the Python packages and adds a login agent that
  keeps the Mac awake while it runs. Restart: `launchctl kickstart -k gui/$(id -u)/com.cleanyourtv.helper`.
  Log: `data/helper.log`. Allow Python on the local network when macOS asks.
- **Windows:** `powershell -ExecutionPolicy Bypass -File setup\windows\install.ps1` (README,
  [step 5](../../README.md#5-keep-the-helper-running)).
- **Linux:** `bash setup/linux/install.sh` (a systemd user service; written for Arch, change `pacman` to your package
  manager). Log: `journalctl --user -u cyt-helper -f`.

Give the computer a fixed address in your router: the TV's Home tile and the Chromecast app carry it.

## 4. The admin panel

Set its password once:

```bash
.venv/bin/python helper/set_admin_password.py      # Windows: python helper\set_admin_password.py
```

(on Windows and Linux, restart the helper afterwards). Then open `http://<helper ip>:8765/admin` on any computer or
phone at home. Everything in [customize.md](../customize.md) is done there.

**On your phone as an app:** open the panel in Safari (iPhone) and *Share > Add to Home Screen*; it then opens full
screen, with the remote and the player one tap away. On Android, Chrome's *Add to Home screen* works too, but opens
with an address bar (Android installs full-screen apps only from https pages); the ⤢ button at the top hides it.

The panel is only reachable from your home network, and only after signing in. The TV and the Chromecast reach only
the pages they need.

## Updating

```bash
git pull
```

then restart the helper (macOS: the `launchctl kickstart` line above). If `android/` changed, build and install the
Chromecast app again (`android/build.sh install`).

## Add-ons are code

Add-ons are Python that runs inside the helper with your user's rights. The built-in ones are in `addons/`; only add
your own (in `data/addons/`) when you have read them. Format: [addons/README.md](../../addons/README.md).
