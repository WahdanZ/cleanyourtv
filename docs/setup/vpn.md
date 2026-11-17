# VPN for the TV and the Chromecast (optional)

Watch another country's catalogue on the TV and the Chromecast, and only on them: the rest of your home, and the
computer that does the work, stay on your normal connection. You switch the country from the TV (the **Region**
button), from the Chromecast, from the admin panel's **VPN** tab, or from your phone (`http://<helper ip>:8765/vpn`).
An app can also switch it by itself: give it a country in the admin panel (OSN+ → UAE, say) and the VPN goes there
when the app opens, and back when you next open an app without one.

Skip this page if you don't want a VPN: without it, the VPN tab and the Region button are simply not shown.

## Pick a way

| | Country switch from the TV, phone and panel | Runs on | Effort |
|---|---|---|---|
| **[A. The Mac script](#a-on-a-mac)** (`vpn/macos/tv-vpn.sh`) | Yes | The Mac that runs the helper | 15 minutes |
| **[B. Your router](#b-on-your-router)** | No: switch in the router's own page | A router with a VPN client and per-device rules | Depends on the router |
| **[C. A Linux box as the gateway](#c-on-a-linux-computer)** | No: `wg-quick up/down` | Linux (a Raspberry Pi is enough) | 30 minutes, not tested here |
| **D. A VPN app on the device** | No | Chromecast only (NordVPN, Surfshark… have Google TV apps) | 5 minutes |

Windows can't easily act as a gateway for other devices: with a Windows helper, use B, C or D.

All of them work with NordVPN, and A, B and C with any VPN that gives you WireGuard files (Mullvad, Proton VPN,
Surfshark, IVPN, AirVPN…) or with **your own WireGuard server** (a cheap cloud server in the country you want, set up
with e.g. [wireguard-install](https://github.com/angristan/wireguard-install), or a friend's home router).

## How the traffic flows (A and C)

```
TV / Chromecast ──(gateway = the helper computer)──► helper computer ──► VPN tunnel ──► the internet, in the VPN country
                                                                └──────────────► your router ──► the internet (VPN off)
```

The devices use the helper computer as their **gateway** instead of the router. That computer passes their traffic
to the internet, through the tunnel when the VPN is on and straight out when it is off. Traffic to your home
network (the helper, AdGuard, the phone remote) never goes into the tunnel.

So the computer must stay on: if it is off, the TV and the Chromecast have no internet. To undo, set their gateway
back to your router (below).

## A. On a Mac

Needs macOS on the helper computer, an admin password, and [Homebrew](https://brew.sh).

1. Install WireGuard's tools:

   ```bash
   brew install wireguard-tools wireguard-go
   ```

2. Give the TV and the Chromecast fixed addresses with the **Mac as gateway** (see [Device settings](#device-settings)).
   Write the addresses down; this guide uses `192.168.1.50` (TV) and `192.168.1.60` (Chromecast).

3. Install the script, naming the devices that should follow the VPN:

   ```bash
   sudo vpn/macos/tv-vpn.sh install 192.168.1.50 192.168.1.60
   ```

   This copies it to `/usr/local/libexec/tv-vpn.sh` (owned by root), finds your home network, adds a background
   service that keeps the tunnel up (and brings it back after a restart), turns on forwarding, and adds a sudo rule
   so your user may run exactly `tv-vpn.sh up <country>` and `tv-vpn.sh down` without a password: that is how the
   TV and the admin panel switch it. Run it again to change the devices.

4. Add your VPN, **either**

   - **NordVPN**: in Nord Account, *NordVPN > Manual setup > WireGuard*, download a configuration for any server,
     then run (as yourself, no sudo; it takes the newest Nord file in Downloads, saves only the key, and deletes the
     file):

     ```bash
     /usr/local/libexec/tv-vpn.sh key-from-conf
     ```

     Every Nord country then works: the script picks a recommended server each time.

   - **or any WireGuard file**, one per country you want. From your provider's site (or your own server), download a
     WireGuard `.conf` for a server in that country and add it under its two-letter code:

     ```bash
     sudo /usr/local/libexec/tv-vpn.sh add-conf us ~/Downloads/us-server.conf
     sudo /usr/local/libexec/tv-vpn.sh add-conf gb ~/Downloads/uk-server.conf
     ```

     A file for a country wins over NordVPN for that country, so you can mix both.

5. Try it, then list the countries in the TV's Region menu:

   ```bash
   sudo /usr/local/libexec/tv-vpn.sh up us
   /usr/local/libexec/tv-vpn.sh status        # on US us1234.nordvpn.com
   ```

   In the admin panel's **VPN** tab, add the countries you use (saved as `vpn_countries` in `config.json`), and set
   `vpn_home_name` in `config.json` to what "no VPN" should be called (`"Germany"` shows *Germany (no VPN)*).

Turn it off with `sudo /usr/local/libexec/tv-vpn.sh down`; remove it all with
`sudo /usr/local/libexec/tv-vpn.sh uninstall`. The log is `/var/log/tv-vpn.log`.

How it works: `wireguard-go` makes a tunnel interface with no routes, so the Mac's own traffic never uses it. A
`pf` rule (anchor `com.apple/tv-vpn`) sends only the listed devices' traffic, and only to outside your home network,
into the tunnel. The tunnel runs in its own launchd service (`com.tvvpn.tunnel`), never inside the helper, so a
helper restart can't leave the devices without internet.

## B. On your router

Many routers can run a VPN client and send only chosen devices through it: OpenWrt (with the `pbr` package),
GL.iNet (*VPN > VPN Dashboard > Policies*), Asus with Merlin firmware (*VPN Director*), pfSense / OPNsense, and
several mesh systems. Add your provider's WireGuard or OpenVPN file there and choose the TV and the Chromecast as the
devices that use it. Nothing changes on the devices, and nothing to install on the helper computer.

You switch the country in the router's page. Leave `vpn_countries` empty in `config.json`; the TV's Region button
stays hidden.

## C. On a Linux computer

Not tested in this repo; the same idea as A with Linux's own tools. On the Linux computer (say `192.168.1.20`, home
network `192.168.1.0/24`):

1. `sudo apt install wireguard` (or your distribution's package), and turn on forwarding:

   ```bash
   echo 'net.ipv4.ip_forward=1
   net.ipv4.conf.all.send_redirects=0' | sudo tee /etc/sysctl.d/90-tv-gateway.conf && sudo sysctl --system
   ```

2. Save your provider's WireGuard file as `/etc/wireguard/tvvpn.conf` and add these lines under `[Interface]`
   (they keep the computer's own traffic out of the tunnel, and send only the two devices in):

   ```ini
   Table = off
   PostUp = ip rule add to 192.168.1.0/24 lookup main pref 100; ip rule add from 192.168.1.50 lookup 51820 pref 101; ip rule add from 192.168.1.60 lookup 51820 pref 101; ip route add default dev %i table 51820; iptables -t nat -A POSTROUTING -o %i -j MASQUERADE
   PostDown = ip rule del pref 100; ip rule del pref 101; ip rule del pref 101; iptables -t nat -D POSTROUTING -o %i -j MASQUERADE
   ```

   Remove any `DNS =` line (it would change the computer's own DNS).

3. `sudo systemctl enable --now wg-quick@tvvpn` (on), `sudo systemctl stop wg-quick@tvvpn` (off). One file per
   country, switched by hand.

4. Set the devices' gateway to the Linux computer ([Device settings](#device-settings)).

## Device settings

The devices need a fixed address and the helper computer as their gateway. Most routers (the FRITZ!Box among them)
can't hand out a different gateway to one device, so set it on the device:

- **VIDAA TV:** *Settings > Network > Network configuration*, then the IP settings: Manual (names vary a little by model). IP address: a free address
  outside your router's automatic range (or reserve it in the router). Gateway: the helper computer's address. DNS:
  your router, or AdGuard (below).
- **Chromecast with Google TV:** *Settings > Network & Internet >* your Wi-Fi *> IP settings*: Static, with the same
  fields.

Put the Chromecast's address in `config.json` (`chromecast.ip`). The helper finds the TV at its new address by itself.

**IPv6:** a device with IPv6 can reach some services around the gateway, from your real country. If an app still
sees your home country with the VPN on, turn IPv6 off in your router (FRITZ!Box: *Internet > Account Information >
IPv6*), or on the device.

## DNS: matching the VPN country (optional)

Streaming apps ask DNS where to stream from. If the answer comes from your own country while the video goes through
the VPN, some services notice. If you run [AdGuard Home](https://adguard.com/adguard-home/overview.html) (also good
for blocking the TV's ads, see [OPEN-PROBLEMS](../OPEN-PROBLEMS.md)), let the devices use AdGuard as their DNS and
have AdGuard ask the VPN's DNS servers for those two devices only:

```bash
python3 vpn/adguard-client-dns.py --dns 103.86.96.100 103.86.99.100 TV=192.168.1.50 Chromecast=192.168.1.60
```

(`103.86.96.100` and `103.86.99.100` are NordVPN's; use your provider's.) With the Mac script, those DNS servers are
reached through the tunnel, so the answers come from the VPN country; set `VPN_DNS` in
`/Library/Application Support/tv-vpn/settings` if they aren't Nord's or in your WireGuard file.

## Checking it

With the VPN on, open a "what is my IP" page in the TV Browser (the launcher's Search) or on the Chromecast: it
should show the VPN country. The admin panel's VPN tab shows the server in use.
