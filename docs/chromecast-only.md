# Chromecast only: no computer day to day

The launcher app runs everything on the Chromecast itself: its Home, the phone panel, the weather, quote, photo and
add-ons, scenes, the sleep timer, phone shortcuts and Usage. You need a computer (or an adb app on your phone) **once**,
to install it and make it the Home screen; Google TV allows that only through adb.

## What you need

- A Chromecast with Google TV (or another Google TV / Android TV 13+ device).
- Once: adb, from a computer (`brew install android-platform-tools`, `sudo apt install adb`, or Google's
  [platform-tools](https://developer.android.com/tools/releases/platform-tools)) or an adb app on an Android phone.

## Install

1. **On the Chromecast:** *Settings > System > About*, press OK on *Android TV OS build* seven times; then
   *Settings > System > Developer options > Wireless debugging*: on. Note the address and port it shows.
2. **Download** `cleanyourtv-chromecast-<version>.apk` from the
   [latest release](https://github.com/WahdanZ/cleanyourtv/releases/latest).
3. **Install it** (with the address from step 1; the first time, *Pair device with pairing code* and `adb pair` first):

   ```bash
   adb connect 192.168.1.60:37000
   adb install cleanyourtv-chromecast-<version>.apk
   ```

4. **Make it the Home screen:**

   ```bash
   adb shell cmd package set-home-activity com.cleanyourtv.home/.MainActivity
   ```

   If Google TV's Home comes back after an update, also:
   `adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx` (undo: `pm enable` the same).

5. **Optional, still with adb:** the Now playing card's cover and controls, and the phone remote's pointer:

   ```bash
   adb shell cmd notification allow_listener com.cleanyourtv.home/com.cleanyourtv.home.MediaWatch
   adb shell settings put secure enabled_accessibility_services com.cleanyourtv.home/com.cleanyourtv.home.CursorService
   adb shell settings put secure accessibility_enabled 1
   ```

6. **On your phone:** open the Admin button's QR code on the Chromecast's Home (or `http://<Chromecast>:8765/admin`)
   and choose the panel's password. Everything else is set up there ([customize.md](customize.md)).

Wireless debugging can go off again afterwards.

## What works without a computer

| Works | Doesn't (needs a computer with adb, or isn't built yet) |
|---|---|
| Home: layout, apps, weather, quote, photo, the built-in add-ons, Now playing | Taps and typing inside other apps from the phone (Google TV takes them only through adb); the live screen |
| The phone panel: settings, layout, scenes, websites, sleep timer, shortcuts, Usage, Logs | Your own add-ons written in Python (they run in the helper) |
| The phone remote: Home, Back, volume, play/pause; arrows and OK on this Home | Arrows and OK inside other apps |
| The VPN, once its key is set | Setting the VPN key from the panel (for now it comes over with `tools/move-to-chromecast.py`) |

**Tested:** a fresh install with no computer on Google TV's Android 14 emulator (Home, the panel and the API checks
in `tests/`). Not yet: a fresh install on a real Chromecast with no computer at all.

## Update and undo

- **Update:** install a newer release over it (`adb install -r`); its settings stay. Releases are signed with the
  same key every time; a build of your own with another key must be uninstalled first, which clears its settings.
- **Undo:** `adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity` (or enable
  Google TV's Home again), then uninstall the app.
