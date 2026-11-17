#!/bin/bash
# TV VPN for macOS: this Mac is the gateway of your TV (and Chromecast), and can send their traffic, and only
# theirs, through a VPN in a country you pick. The Mac's own traffic is never touched. See docs/setup/vpn.md.
#
#   sudo tv-vpn.sh install <device ip>...   install (root-owned copy in /usr/local/libexec), the tunnel service, and
#                                           a sudo rule so your user can run "up" and "down" without a password
#   tv-vpn.sh key-from-conf [F]             NordVPN: save your NordLynx key from a WireGuard .conf from Nord Account
#   tv-vpn.sh key                           NordVPN: the same, from a Nord access token instead
#   sudo tv-vpn.sh add-conf <cc> F          any other WireGuard VPN (Mullvad, Proton, your own server...): use the
#                                           .conf F for country code <cc> (e.g. us); one file per country
#   sudo tv-vpn.sh up <cc>                  VPN on, in that country (two-letter code: us, gb, ae...)
#   sudo tv-vpn.sh down                     VPN off: the devices still go through this Mac, straight to the internet
#   tv-vpn.sh status                        what is running now ("on US us1234.nordvpn.com" or "off")
#   sudo tv-vpn.sh run                      the tunnel service itself (launchd runs it; not for typing)
#
# How: wireguard-go makes a utun interface whose only peer is the VPN server, and adds NO routes, so the Mac's own
# traffic is untouched. A pf rule in the com.apple/tv-vpn anchor (the stock /etc/pf.conf loads com.apple/*) sends
# packets from the devices to anywhere outside the home network into that interface, NATed to it. The devices must
# use this Mac as their gateway (a fixed address set on each device, gateway = this Mac), so forwarding stays on
# even with the VPN off.
#
# The tunnel lives in its own launchd service (com.tvvpn.tunnel), never in whatever process asked for it: "up" saves
# the country and restarts the service, "down" clears it. So a restart or a crash of the launcher helper can't leave
# the devices pointed at a tunnel that is gone; launchd also restarts the tunnel if it dies, and at every boot.
set -euo pipefail

CONF_DIR="/Library/Application Support/tv-vpn"   # root's: settings, the country that is on, WireGuard files
SETTINGS="$CONF_DIR/settings"
RUN_DIR="/var/run/tv-vpn"
ANCHOR="com.apple/tv-vpn"
PY=/usr/bin/python3
API="https://api.nordvpn.com/v1"
INSTALLED=/usr/local/libexec/tv-vpn.sh
SERVICE=com.tvvpn.tunnel
PLIST=/Library/LaunchDaemons/$SERVICE.plist
SUDO_RULE=/etc/sudoers.d/tv-vpn
NORD_DNS="103.86.96.100 103.86.99.100"

# Written by "install" (root-owned, so only root can change what the service does).
CLIENT_IPS=""; LAN_IF=""; LAN_NET=""; VPN_DNS=""; KEY_FILE=""
# shellcheck disable=SC1090
[ -r "$SETTINGS" ] && . "$SETTINGS"
[ -n "$KEY_FILE" ] || KEY_FILE="${HOME:-/var/root}/.config/tv-vpn/nordlynx.key"

tool() {   # tool <name>: wireguard-tools from Homebrew (Apple silicon or Intel)
  local p; for p in "/opt/homebrew/bin/$1" "/usr/local/bin/$1"; do [ -x "$p" ] && { echo "$p"; return; }; done
  command -v "$1" || { echo "tv-vpn: $1 is missing: brew install wireguard-tools wireguard-go" >&2; exit 1; }
}

say() { echo "tv-vpn: $*"; }
need_root() { [ "$(id -u)" = 0 ] || { say "run this with sudo"; exit 1; }; }
need_settings() { [ -n "$CLIENT_IPS" ] || { say "not installed yet: sudo $0 install <TV ip> [<Chromecast ip>]"; exit 1; }; }

json() {   # json <python expression over d> : reads JSON on stdin
  "$PY" -c "import json,sys; d=json.load(sys.stdin); print($1)"
}

valid_ip() { printf '%s' "$1" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$'; }

# ---------- NordVPN key ----------
save_key() {   # save_key <key> : as the user, into ~/.config/tv-vpn (only the user and root can read it)
  mkdir -p "$(dirname "$KEY_FILE")"; chmod 700 "$(dirname "$KEY_FILE")"
  umask 077; printf '%s\n' "$1" > "$KEY_FILE"; chmod 600 "$KEY_FILE"
}

cmd_key() {
  [ "$(id -u)" != 0 ] || { say "run 'key' as yourself, without sudo"; exit 1; }
  printf "Nord access token (Nord Account > NordVPN > Manual setup > Generate new token), not shown: "
  read -rs token; echo
  [ -n "$token" ] || { say "no token given"; exit 1; }
  key=$(curl -fsS -u "token:$token" "$API/users/services/credentials" | json 'd["nordlynx_private_key"]') || {
    say "Nord did not accept that token"; exit 1; }
  unset token
  [ ${#key} -ge 40 ] || { say "Nord sent no usable key"; exit 1; }
  save_key "$key"; unset key
  say "key saved in $KEY_FILE. You can delete the token in your Nord account now."
}

cmd_key_from_conf() {
  # Nord Account > NordVPN > Manual setup > (any server) > WireGuard downloads a .conf with your NordLynx key in it.
  # The key is the same for every server, so only "PrivateKey" is kept; the rest of the file isn't needed.
  [ "$(id -u)" != 0 ] || { say "run 'key-from-conf' as yourself, without sudo"; exit 1; }
  local f="${1:-}"
  if [ -z "$f" ]; then   # no path given: the newest Nord WireGuard file in Downloads
    f=$(ls -t "$HOME"/Downloads/*nordvpn*.conf 2>/dev/null | head -1 || true)
    [ -n "$f" ] && say "using $f"
  fi
  [ -f "$f" ] || { say "no Nord .conf found: download it (Manual setup > WireGuard), or give its path"; exit 1; }
  local key; key=$(conf_value "$f" PrivateKey)
  [ -n "$key" ] || { say "that file has no WireGuard PrivateKey line"; exit 1; }
  save_key "$key"; unset key
  rm -f "$f"
  say "key saved in $KEY_FILE; the downloaded file is deleted"
}

# ---------- any WireGuard VPN: one .conf per country ----------
conf_value() {   # conf_value <file> <Key> : the first value of "Key = value" in a WireGuard .conf
  sed -n "s/^[[:space:]]*$2[[:space:]]*=[[:space:]]*\\([^[:space:]#]*\\).*/\\1/p" "$1" | head -1
}

cmd_add_conf() {
  need_root
  local code; code=$(printf '%s' "${1:-}" | tr '[:lower:]' '[:upper:]')
  printf '%s' "$code" | grep -Eq '^[A-Z]{2}$' || { say "usage: sudo $0 add-conf <two-letter country> <file.conf>"; exit 1; }
  local f="${2:-}"
  [ -f "$f" ] || { say "no such file: $f"; exit 1; }
  local k; for k in PrivateKey Address PublicKey Endpoint; do
    [ -n "$(conf_value "$f" "$k")" ] || { say "that file has no $k line: is it a WireGuard .conf?"; exit 1; }
  done
  mkdir -p "$CONF_DIR/wg"; chmod 700 "$CONF_DIR/wg"
  install -o root -g wheel -m 600 "$f" "$CONF_DIR/wg/$code.conf"
  say "saved for $code. Turn it on with: sudo $INSTALLED up $(printf '%s' "$code" | tr '[:upper:]' '[:lower:]')"
  say "you can delete $f now (it holds a private key)"
}

# ---------- the tunnel ----------
forwarding_on() {
  sysctl -qw net.inet.ip.forwarding=1 >/dev/null
  sysctl -qw net.inet.ip.redirect=0 >/dev/null   # no "use the router directly" hints to the devices: they'd skip this Mac
}

pf_clear() {
  pfctl -a "$ANCHOR" -F all >/dev/null 2>&1 || true
  if [ -f "$RUN_DIR/pf_token" ]; then pfctl -X "$(cat "$RUN_DIR/pf_token")" >/dev/null 2>&1 || true; fi
}

dns_routes() {   # dns_routes add <utun> | delete : the VPN's DNS servers are reached through the tunnel
  local d
  for d in $VPN_DNS; do
    route -q -n delete -host "$d" >/dev/null 2>&1 || true
    [ "$1" = add ] && route -q -n add -host "$d" -interface "$2" >/dev/null 2>&1 || true
  done
}

tunnel_iface() {   # the utun the service made, if it is still there (readable without root)
  local i; i=$(cat "$RUN_DIR/iface" 2>/dev/null || true)
  [ -n "$i" ] && ifconfig -l | tr ' ' '\n' | grep -qx "$i" && echo "$i" || true
}

cmd_status() {
  local code=""; [ -s "$CONF_DIR/country" ] && code=$(cat "$CONF_DIR/country")
  if [ -n "$code" ] && [ -n "$(tunnel_iface)" ]; then
    echo "on $code $(cat "$RUN_DIR/server" 2>/dev/null || true)"
  else
    echo "off"
  fi
}

wait_status() {   # wait_status <code|off> <seconds>
  local want="$1" i
  for i in $(seq 1 $(( $2 * 2 ))); do
    case "$(cmd_status)" in
      "on $want"*) return 0 ;;
      off) [ "$want" = off ] && return 0 ;;
    esac
    sleep 0.5
  done
  return 1
}

cmd_up() {
  need_root; need_settings
  local code; code=$(printf '%s' "${1:-}" | tr '[:lower:]' '[:upper:]')
  # Two letters only: the code goes into a Python expression in 'run', and this runs as root.
  printf '%s' "$code" | grep -Eq '^[A-Z]{2}$' || { say "which country? a two-letter code, e.g. sudo tv-vpn.sh up us"; exit 1; }
  if [ ! -f "$CONF_DIR/wg/$code.conf" ] && [ ! -s "$KEY_FILE" ]; then
    say "no VPN for $code: add a WireGuard file (sudo $0 add-conf $code F) or a NordVPN key ($0 key-from-conf)"; exit 1
  fi
  [ -f "$PLIST" ] || { say "the tunnel service isn't installed: run 'sudo tv-vpn.sh install'"; exit 1; }
  echo "$code" > "$CONF_DIR/country"
  launchctl kickstart -k "system/$SERVICE"
  if wait_status "$code" 25; then
    say "VPN on: the devices go out through $(cut -d' ' -f2 "$RUN_DIR/server" 2>/dev/null) ($code)"
  else
    rm -f "$CONF_DIR/country"; launchctl kickstart -k "system/$SERVICE" || true
    say "the VPN did not come up for $code: left off. $(tail -1 /var/log/tv-vpn.log 2>/dev/null | sed 's/^tv-vpn: //')"
    exit 1
  fi
}

cmd_down() {
  need_root
  rm -f "$CONF_DIR/country"
  launchctl kickstart -k "system/$SERVICE" 2>/dev/null || true   # with no country, the service just tidies up and stops
  wait_status off 10 || true
  pf_clear; forwarding_on
  say "VPN off: the devices go straight to the internet (through this Mac)"
}

nord_server() {   # nord_server <CC> : "host ip publickey" of a recommended NordLynx server in that country
  local cid
  cid=$(curl -fsS --max-time 15 "$API/servers/countries" | json "next((c['id'] for c in d if c['code']=='$1'), '')") || {
    say "can't reach Nord (no internet yet?)" >&2; return 1; }
  [ -n "$cid" ] || { say "Nord has no servers in $1" >&2; rm -f "$CONF_DIR/country"; return 2; }
  curl -fsS --max-time 15 -g "$API/servers/recommendations?filters[servers_technologies][identifier]=wireguard_udp&filters[country_id]=$cid&limit=1" |
    json "' '.join([d[0]['hostname'], d[0]['station'], next(m['value'] for t in d[0]['technologies'] if t['identifier']=='wireguard_udp' for m in t['metadata'] if m['name']=='public_key')])" || {
    say "Nord sent no WireGuard server for $1" >&2; return 1; }
}

cmd_run() {
  # The service: set the tunnel up for the saved country and stay in the foreground with it. Leaves pf clean on exit.
  need_root
  forwarding_on
  pf_clear
  local code=""; [ -s "$CONF_DIR/country" ] && code=$(cat "$CONF_DIR/country")
  if [ -z "$code" ]; then rm -rf "$RUN_DIR"; exit 0; fi   # VPN off: nothing to run (exit 0, launchd leaves it)
  printf '%s' "$code" | grep -Eq '^[A-Z]{2}$' || { say "bad saved country '$code'"; exit 0; }
  [ -n "$CLIENT_IPS" ] || { say "no devices set: run install again"; exit 0; }
  local WG WGGO; WG=$(tool wg); WGGO=$(tool wireguard-go)

  # The peer: your own WireGuard file for this country, or a NordVPN server picked now.
  local conf="$CONF_DIR/wg/$code.conf" host endpoint pub key_file addr psk="" dns="$VPN_DNS"
  if [ -f "$conf" ]; then
    endpoint=$(conf_value "$conf" Endpoint); host=${endpoint%:*}; pub=$(conf_value "$conf" PublicKey)
    addr=$(conf_value "$conf" Address | tr ',' '\n' | grep -E '^[0-9.]+(/[0-9]+)?$' | head -1); addr=${addr%/*}
    psk=$(conf_value "$conf" PresharedKey)
    [ -n "$dns" ] || dns=$(conf_value "$conf" DNS | tr ',' ' ')
    key_file="$RUN_DIR/key"
  else
    [ -s "$KEY_FILE" ] || { say "no WireGuard file for $code and no NordVPN key"; exit 0; }
    local server ip rc=0
    server=$(nord_server "$code") || rc=$?
    [ "$rc" = 2 ] && exit 0
    [ "$rc" = 0 ] || exit 1
    read -r host ip pub <<<"$server"
    endpoint="$ip:51820"; addr="10.5.0.2"   # NordLynx gives every client this address inside the tunnel
    key_file="$KEY_FILE"
    [ -n "$dns" ] || dns="$NORD_DNS"
  fi
  [ -n "$addr" ] && [ -n "$pub" ] && [ -n "$endpoint" ] || { say "the WireGuard file for $code is incomplete"; exit 0; }
  VPN_DNS="$dns"

  rm -rf "$RUN_DIR"; mkdir -p "$RUN_DIR"; chmod 755 "$RUN_DIR"
  if [ -f "$conf" ]; then
    (umask 077; conf_value "$conf" PrivateKey > "$RUN_DIR/key"; [ -z "$psk" ] || printf '%s\n' "$psk" > "$RUN_DIR/psk")
  fi
  WG_TUN_NAME_FILE="$RUN_DIR/iface" "$WGGO" -f utun >/dev/null 2>&1 &
  local wgpid=$!
  local IF="" i
  for i in $(seq 1 20); do [ -s "$RUN_DIR/iface" ] && IF=$(cat "$RUN_DIR/iface") && break; sleep 0.25; done
  cleanup() { pf_clear; dns_routes delete; kill "$wgpid" 2>/dev/null || true; rm -f "$RUN_DIR/key" "$RUN_DIR/psk"; }
  trap 'cleanup; exit 0' TERM INT
  [ -n "$IF" ] || { cleanup; say "wireguard-go did not start"; exit 1; }
  if [ -n "$psk" ]; then
    "$WG" set "$IF" private-key "$key_file" peer "$pub" preshared-key "$RUN_DIR/psk" endpoint "$endpoint" allowed-ips 0.0.0.0/0 persistent-keepalive 25
  else
    "$WG" set "$IF" private-key "$key_file" peer "$pub" endpoint "$endpoint" allowed-ips 0.0.0.0/0 persistent-keepalive 25
  fi
  rm -f "$RUN_DIR/key" "$RUN_DIR/psk"   # wg has read them
  ifconfig "$IF" inet "$addr" "$addr" netmask 255.255.255.255 mtu 1420 up

  # Only the listed devices, and only to outside the home network (DNS, the launcher and the remote stay on the LAN).
  local who="{ $CLIENT_IPS }"
  printf '%s\n' \
    "nat on $IF inet from $who to any -> ($IF)" \
    "pass in quick on $LAN_IF route-to ($IF $addr) inet from $who to ! $LAN_NET keep state" |
    pfctl -a "$ANCHOR" -f - 2>/dev/null
  pfctl -E 2>&1 | sed -n 's/.*Token : \([0-9]*\).*/\1/p' > "$RUN_DIR/pf_token"
  dns_routes add "$IF"

  local hs=0
  for i in $(seq 1 20); do
    hs=$("$WG" show "$IF" latest-handshakes | awk '{print $2}')
    [ "${hs:-0}" != 0 ] && break
    sleep 0.5
  done
  if [ "${hs:-0}" = 0 ]; then cleanup; say "the VPN server $host did not answer"; exit 1; fi
  echo "$code $host" > "$RUN_DIR/server"; chmod 644 "$RUN_DIR/server" "$RUN_DIR/iface"
  say "tunnel up: $host ($code) on $IF"
  wait "$wgpid" || true      # stays here while the tunnel runs
  cleanup
  say "tunnel stopped"
  exit 1                     # not a clean stop: launchd starts it again
}

# ---------- install ----------
detect_lan() {   # sets LAN_IF and LAN_NET from the default route, unless given
  [ -n "$LAN_IF" ] || LAN_IF=$(route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}')
  [ -n "$LAN_IF" ] || { say "no network found: connect this Mac to the home network first"; exit 1; }
  if [ -z "$LAN_NET" ]; then
    local ip mask
    ip=$(ipconfig getifaddr "$LAN_IF" || true)
    mask=$(ifconfig "$LAN_IF" | awk '/inet /{print $4; exit}')
    [ -n "$ip" ] && [ -n "$mask" ] || { say "can't read $LAN_IF's address"; exit 1; }
    LAN_NET=$("$PY" -c "import ipaddress,sys; print(ipaddress.ip_network(sys.argv[1] + '/' + str(bin(int(sys.argv[2], 16)).count('1')), strict=False))" "$ip" "$mask")
  fi
}

cmd_install() {
  need_root
  local owner="${SUDO_USER:-}"
  [ -n "$owner" ] && [ "$owner" != root ] || { say "run it with sudo from your own account (not as root)"; exit 1; }
  local ips="${*:-$CLIENT_IPS}" ip
  [ -n "$ips" ] || { say "which devices? sudo $0 install <TV ip> [<Chromecast ip>]"; exit 1; }
  for ip in $ips; do valid_ip "$ip" || { say "not an IPv4 address: $ip"; exit 1; }; done
  CLIENT_IPS="$ips"
  detect_lan
  local home; home=$(dscl . -read "/Users/$owner" NFSHomeDirectory | awk '{print $2}')
  tool wg >/dev/null; tool wireguard-go >/dev/null

  mkdir -p "$(dirname "$INSTALLED")" "$CONF_DIR"
  install -o root -g wheel -m 755 "$0" "$INSTALLED"
  umask 022
  cat > "$SETTINGS" <<EOF
# tv-vpn settings, written by "tv-vpn.sh install"; change them by running install again.
CLIENT_IPS="$CLIENT_IPS"
LAN_IF="$LAN_IF"
LAN_NET="$LAN_NET"
# The DNS servers to reach through the tunnel (empty: NordVPN's, or the DNS line of your WireGuard file).
VPN_DNS="$VPN_DNS"
KEY_FILE="$home/.config/tv-vpn/nordlynx.key"
EOF
  chown root:wheel "$SETTINGS"; chmod 644 "$SETTINGS"

  cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$SERVICE</string>
  <key>ProgramArguments</key><array><string>$INSTALLED</string><string>run</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>ThrottleInterval</key><integer>20</integer>
  <key>StandardOutPath</key><string>/var/log/tv-vpn.log</string>
  <key>StandardErrorPath</key><string>/var/log/tv-vpn.log</string>
</dict></plist>
EOF
  launchctl bootout "system/$SERVICE" 2>/dev/null || true
  # bootout returns before the old service is gone; bootstrapping too early fails with "5: Input/output error".
  local i
  for i in $(seq 1 20); do launchctl print "system/$SERVICE" >/dev/null 2>&1 || break; sleep 0.5; done
  for i in 1 2 3 4 5; do launchctl bootstrap system "$PLIST" 2>/dev/null && break; sleep 2; done
  launchctl print "system/$SERVICE" >/dev/null 2>&1 || { say "the tunnel service did not start"; exit 1; }
  forwarding_on

  # The launcher helper switches countries with "sudo -n": allow exactly "up <cc>" and "down" for this user.
  local tmp; tmp=$(mktemp)
  printf '# tv-vpn: %s may switch the VPN country (and turn it off) without a password. Nothing else.\n%s ALL=(root) NOPASSWD: %s up *, %s down\n' \
    "$owner" "$owner" "$INSTALLED" "$INSTALLED" > "$tmp"
  if grep -Eq '^[#@]includedir[[:space:]]+/(private/)?etc/sudoers.d' /etc/sudoers && visudo -cf "$tmp" >/dev/null; then
    install -o root -g wheel -m 440 "$tmp" "$SUDO_RULE"
  else
    say "could not add the sudo rule: the VPN works from the Terminal, but not from the TV or the admin panel"
  fi
  rm -f "$tmp"

  say "installed for $CLIENT_IPS on $LAN_IF ($LAN_NET); forwarding on; the VPN comes back after every restart"
  say "next: a NordVPN key ($INSTALLED key-from-conf) or a WireGuard file (sudo $INSTALLED add-conf us F), then sudo $INSTALLED up us"
}

cmd_uninstall() {
  need_root
  rm -f "$CONF_DIR/country"
  launchctl bootout "system/$SERVICE" 2>/dev/null || true
  pf_clear
  rm -f "$PLIST" "$SUDO_RULE" "$INSTALLED"
  rm -rf "$RUN_DIR"
  sysctl -qw net.inet.ip.forwarding=0 >/dev/null
  say "removed. Your WireGuard files and settings stay in $CONF_DIR (delete it to remove them too)."
  say "Set the devices' gateway back to your router."
}

case "${1:-}" in
  key) cmd_key ;;
  key-from-conf) cmd_key_from_conf "${2:-}" ;;
  add-conf) cmd_add_conf "${2:-}" "${3:-}" ;;
  up) cmd_up "${2:-}" ;;
  down) cmd_down ;;
  status) cmd_status ;;
  run) cmd_run ;;
  install) shift; cmd_install "$@" ;;
  uninstall) cmd_uninstall ;;
  *) sed -n '2,14p' "$0"; exit 1 ;;
esac
