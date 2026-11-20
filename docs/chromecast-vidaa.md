# Chromecast + VIDAA TV: a helper on a computer

A small helper on an always-on computer at home (a Mac, a PC, a Linux box or a Raspberry Pi) runs the VIDAA TV's Home,
its remote and its VPN; the Chromecast has the launcher app. Everything the Chromecast can do on its own it does on its
own, and the helper adds what only it can: taps, typing and the live screen on the Chromecast (adb), and the TV.

## What you need

| | VIDAA TV | Chromecast | VPN (Mac) |
|---|---|---|---|
| A computer that stays on at home (Mac, Windows, Linux or a Raspberry Pi) with **Python 3.10+** | ✓ | ✓ | ✓ (a Mac) |
| [Sidee](https://github.com/Empi9245/Sidee), to pair with the TV once (PIN on screen) | ✓ | | |
| **adb** (Android platform-tools), with Wireless debugging on the Chromecast | | ✓ | |
| To build the launcher yourself (not needed with the [ready-made app](https://github.com/WahdanZ/cleanyourtv/releases/latest)): **JDK 17+**, a Kotlin compiler, and the Android SDK's `build-tools;35.0.0` and `platforms;android-34` (Android Studio has them all) | | ✓ | |
| [Homebrew](https://brew.sh) with `wireguard-tools` and `wireguard-go` | | | ✓ |
| A VPN with WireGuard (NordVPN, Mullvad, Proton VPN, Surfshark…) or your own WireGuard server | | | ✓ |
| A fixed address for each device, with the Mac as its gateway | | | ✓ |

Optional: **ffmpeg** and **Node.js** (YouTube without ads, Stremio's streaming server), [AdGuard Home](https://adguard.com/adguard-home/overview.html)
(blocks the TV's ads; can send the devices' DNS through the VPN), a static host such as Vercel (the VIDAA launcher
keeps working with the computer off).

A cloud server can't be the helper: the TV only takes commands from its own network. Check your TV is VIDAA, not
Android TV: if ports 5555/8008/8009 are closed, 36669 and 38400 are open, and `http://<tv ip>:7000/info` says
`Linux`, it is VIDAA. If it is Android, use [CleanYourTV](https://github.com/Gathin23/cleanyourtv) instead.

## Install

1. **The helper:** [setup/helper.md](setup/helper.md) (Python, the TV's pairing with Sidee, `config.json` with your
   TV and the Chromecast's address, start at login).
2. **The Chromecast:** pair it with the helper's computer and install the launcher, either the ready-made app from the
   [latest release](https://github.com/WahdanZ/cleanyourtv/releases/latest) or your own build
   ([setup/chromecast.md](setup/chromecast.md): `android/cc.sh pair`, `android/build.sh install`, `android/cc.sh home`).
3. **Optional, the VPN:** [setup/vpn.md](setup/vpn.md). The TV's VPN runs on the helper's computer; the Chromecast
   can use its own (built into the launcher).
4. **Optional, the Chromecast on its own** (so it keeps everything when the computer is off):
   `.venv/bin/python tools/move-to-chromecast.py --copy` to try its own panel first, then without `--copy` to switch.
   See [setup/chromecast.md](setup/chromecast.md#on-its-own-without-the-laptop).

## Which panel

- **The helper's:** `http://<computer>:8765/admin`: the TV and the Chromecast (while the Chromecast uses the helper).
- **The Chromecast's own:** `http://<Chromecast>:8765/admin`: the Chromecast, after it moved over. The TV stays in the
  helper's panel.

## Update

- **The helper:** `git pull`, then restart it (Settings > Restart in its panel). Its panel updates with it.
- **The launcher:** the newer release (`adb install -r`), or `android/build.sh install`. The helper and the launcher
  report the API version they speak (`shared/API.md`); keep both on the same release.
