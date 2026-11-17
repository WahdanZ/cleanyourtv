#!/usr/bin/env python3
"""Tell AdGuard Home to answer some devices' lookups through other DNS servers (your VPN's), uncached.

With the VPN on, a streaming app asks DNS where to stream from; if that answer comes from your own country while
the video goes through the VPN's, some services notice. tv-vpn.sh sends the VPN's DNS servers through the tunnel,
and this makes AdGuard ask those servers for the TV and the Chromecast only. Everything else keeps AdGuard's usual
upstream, and ad filtering stays as it is. Safe to run again. Asks for your AdGuard login; it is only sent to AdGuard.

  python3 adguard-client-dns.py --dns 103.86.96.100 103.86.99.100 TV=192.168.1.50 Chromecast=192.168.1.60
  (--adguard http://127.0.0.1:80 if AdGuard Home's web page is somewhere else; 103.86.* are NordVPN's DNS servers)
"""
import argparse
import base64
import getpass
import ipaddress
import json
import sys
import urllib.error
import urllib.request

ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
ap.add_argument("--adguard", default="http://127.0.0.1:80", help="AdGuard Home's web address")
ap.add_argument("--dns", nargs="+", required=True, help="the DNS servers these devices should use")
ap.add_argument("devices", nargs="+", metavar="NAME=IP", help="each device, e.g. TV=192.168.1.50")
args = ap.parse_args()

devices = []
for d in args.devices:
    name, _, ip = d.partition("=")
    try:
        ipaddress.ip_address(ip)
    except ValueError:
        sys.exit(f"{d}: give each device as NAME=IP")
    devices.append((name, ip))

user = input("AdGuard username: ").strip()
auth = "Basic " + base64.b64encode(f"{user}:{getpass.getpass('AdGuard password (not shown): ')}".encode()).decode()


def call(path, body=None):
    req = urllib.request.Request(args.adguard.rstrip("/") + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": auth, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        raw = r.read()
    return json.loads(raw) if raw.strip().startswith((b"{", b"[")) else None


try:
    clients = (call("/control/clients") or {}).get("clients") or []
except urllib.error.HTTPError as e:
    sys.exit("AdGuard said no (wrong username or password?)" if e.code in (401, 403) else f"AdGuard error {e.code}")
except OSError as e:
    sys.exit(f"Cannot reach AdGuard at {args.adguard}: {e}")

for name, ip in devices:
    have = next((c for c in clients if ip in (c.get("ids") or [])), None)
    if have:
        data = dict(have)
        data.update(upstreams=args.dns, upstreams_cache_enabled=False, upstreams_cache_size=0)
        call("/control/clients/update", {"name": have["name"], "data": data})
        print(f"updated {have['name']} ({ip})")
    else:
        call("/control/clients/add", {
            "name": name, "ids": [ip], "tags": [], "use_global_settings": True, "use_global_blocked_services": True,
            "filtering_enabled": True, "parental_enabled": False, "safebrowsing_enabled": False,
            "safe_search": {"enabled": False}, "ignore_querylog": False, "ignore_statistics": False,
            "upstreams": args.dns, "upstreams_cache_enabled": False, "upstreams_cache_size": 0})
        print(f"added {name} ({ip})")

ips = {ip for _, ip in devices}
check = [c for c in (call("/control/clients") or {}).get("clients") or [] if set(c.get("ids") or []) & ips]
ok = len(check) == len(devices) and all(c.get("upstreams") == args.dns for c in check)
print("OK: AdGuard uses those DNS servers for " + ", ".join(n for n, _ in devices) + "." if ok else "NOT confirmed: see the lines above.")
sys.exit(0 if ok else 1)
