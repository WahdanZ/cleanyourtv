# VIDAA TV only: a helper on a computer

A VIDAA TV can't run apps of its own, so a small helper on an always-on computer at home (a Mac, a PC, a Linux box or
a Raspberry Pi) serves its Home and talks to it over its remote-control channel. Nothing is installed on the TV except
one launcher tile; deleting the tile undoes it.

## Install

1. **Pair the TV** with [Sidee](https://github.com/Empi9245/Sidee) (its PIN pairing) and
   **start the helper**: the README's [quick start](../README.md#quick-start) and [setup/helper.md](setup/helper.md).
   Leave `"chromecast"` out of `config.json`.
2. **The admin panel:** set its password (`helper/set_admin_password.py`), then open `http://<computer>:8765/admin`
   on your phone ([customize.md](customize.md)).
3. **Optional:** the VPN for the TV ([setup/vpn.md](setup/vpn.md)) and ad blocking with AdGuard Home.

## Update

`git pull`, then restart the helper (Settings > Restart in its panel, or `launchctl kickstart` on a Mac). Nothing to
update on the TV.
