# Chromecast with Google TV

A native home screen for the Chromecast with Google TV (and other Android TV / Google TV devices): the same calm
look as the VIDAA launcher, with no ads, rows of your apps only, widgets (football, prayer times, news…), the clock
styles, the weather, today's photo, a Now playing card and a Region (VPN) button. It is a small Android app
(`android/`, plain Java, no Gradle) that gets everything from the helper and keeps a copy for when the helper is off.

It works alone (no VIDAA TV: set `"vidaa": false` in `config.json`) or next to a VIDAA TV, with one helper for both.

## What you need

- The helper running on a computer at home ([helper.md](helper.md)).
- **adb** (Android's platform-tools) on that computer, to install the app and for the remote, Now playing and the
  "Take a picture" button: `brew install android-platform-tools` on a Mac, `sudo apt install adb` on Debian/Ubuntu,
  or [platform-tools](https://developer.android.com/tools/releases/platform-tools) on Windows.
- To build the app: a JDK 17 or newer and the Android SDK's `build-tools;35.0.0` and `platforms;android-34`. With
  Android Studio you have them; without it:

  ```bash
  brew install --cask android-commandlinetools temurin@17      # macOS; on Linux, the "command line tools only" zip
  sdkmanager "build-tools;35.0.0" "platforms;android-34"
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
  ```

## 1. Let the computer talk to the Chromecast

On the Chromecast: *Settings > System > About*, press OK on **Android TV OS build** seven times (Developer options
appear), then *Settings > System > Developer options > Wireless debugging*: on.

Put its address in `config.json`:

```json
"chromecast": { "ip": "192.168.1.60" }
```

Give it a fixed address so this stays right (in the router, or on the Chromecast: *Network & Internet >* your
Wi-Fi *> IP settings*). Then pair once and connect:

```bash
android/cc.sh pair        # type the address and the 6-digit code from "Pair device with pairing code"
android/cc.sh connect     # again after a restart of either side, or a Wi-Fi change
```

On a Mac run these from Terminal: macOS asks whether Terminal may find devices on your network; allow it.

## 2. Build and install the launcher

```bash
android/build.sh install
```

It builds `android/build/home.apk` with this computer's address baked in as the helper's. If the helper runs on
another computer: `HELPER=http://192.168.1.20:8765 android/build.sh install`. The first build needs a signing key;
if you never used Android Studio, make one:

```bash
keytool -genkeypair -keystore ~/.android/debug.keystore -storepass android -keypass android -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
```

Keep that file: an update must be signed with the same key to install over the old app.

## 3. Make it the Home screen

```bash
android/cc.sh home
```

The Home button now opens the launcher. Google TV updates sometimes take Home back; run it again, or follow what it
prints to switch Google TV's home off. To undo: `android/cc.sh restore` (or, without a computer, *Settings > Apps >
See all apps > Show system apps > Google TV Home > Enable*).

## 4. Set it up

Everything is in the admin panel's **Chromecast** tab (see [customize.md](../customize.md)): the dock and the rows,
names, hidden apps, the layout of the clock, widgets and the rest, add-ons, the wallpaper. Changes show on the
Chromecast within a few seconds. On the Chromecast itself: hold OK on an app to move or remove it, or open the ✎ pill
(top right) to rearrange Home with the remote.

The phone remote's **Trackpad** uses a small pointer service in the launcher (an accessibility service, listed as
"Home trackpad" under *Settings > System > Accessibility*); the helper switches it on over adb the first time you use
the trackpad, and it listens on port 8791 for the helper only (with a key the helper gives it).

The app asks the helper for its settings, apps and widgets; only the paths it needs are open to the Chromecast's
address (it can't reach the admin panel or control the TV).

## Without the helper running

The launcher keeps working: it shows the last settings, apps and widgets it got, and opens apps by itself. The
weather, widgets and the photo update again when the helper is back.

## Troubleshooting

- **`build.sh install` says the Chromecast is not connected:** `android/cc.sh connect`. Wireless debugging turns
  itself off after a Wi-Fi change or a restart of the Chromecast; turn it on again.
- **Pairing works but connect fails on a Mac:** run the commands from Terminal, not from an editor's built-in
  terminal (macOS's Local Network permission).
- **The Home button still opens Google TV:** `android/cc.sh home` again; a Google TV update may have reset it.
- **The launcher shows "can't reach the helper":** the address baked in at build time is wrong (the helper computer
  got a new one). Give it a fixed address, then build and install again.
