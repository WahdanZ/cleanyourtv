#!/bin/bash
# The Chromecast (Google TV) over adb: pair, connect, make the launcher its Home, or undo that. macOS and Linux.
#   ./cc.sh pair       once: pair this computer (Chromecast: Developer options > Wireless debugging >
#                      Pair device with pairing code; keep that box open while this runs)
#   ./cc.sh connect    connect again (after a restart of either side, or a Wi-Fi change)
#   ./cc.sh home       make the launcher (build.sh install) the Home screen
#   ./cc.sh restore    give Google TV its own Home screen back
# The Chromecast's address comes from config.json ("chromecast": {"ip": ...}), or CHROMECAST=192.168.1.60 ./cc.sh ...
# On macOS, run this from Terminal: macOS lets Terminal reach devices at home (Local Network), so the adb service it
# starts can too.
set -euo pipefail
cd "$(dirname "$0")"
ADB="${ADB:-$([ -x /opt/homebrew/bin/adb ] && echo /opt/homebrew/bin/adb || command -v adb || true)}"
[ -x "$ADB" ] || { echo "adb is missing: install Android platform-tools (macOS: brew install android-platform-tools)"; exit 1; }
CC="${CHROMECAST:-$(python3 -c 'import json; c = json.load(open("../config.json")).get("chromecast", {}).get("ip", ""); print(c[0] if isinstance(c, list) else c)' 2>/dev/null || true)}"
[ -n "$CC" ] || { echo "Which Chromecast? Put its address in config.json (chromecast.ip) or run: CHROMECAST=<ip> $0 $*"; exit 1; }
HOME_PKG=com.cleanyourtv.home

device() { "$ADB" devices | awk -v ip="$CC" 'index($1, ip ":") == 1 && $2 == "device" {print $1; exit}'; }
need_device() {
  dev=$(device)
  [ -n "$dev" ] || { echo "The Chromecast ($CC) is not connected: $0 connect (or $0 pair the first time)."; exit 1; }
}

case "${1:-}" in
  pair)
    "$ADB" start-server >/dev/null 2>&1
    echo "On the Chromecast: Wireless debugging > Pair device with pairing code. It shows an ADDRESS and a 6-digit CODE."
    printf "ADDRESS (like %s:41234): " "$CC"; read -r addr
    printf "CODE: "; read -r code
    "$ADB" pair "$(printf '%s' "$addr" | tr -d ' ')" "$(printf '%s' "$code" | tr -d ' ')"
    unset code
    echo "Paired. Now the address on the main Wireless debugging screen (IP address & Port):"
    printf "> "; read -r conn
    "$ADB" connect "$(printf '%s' "$conn" | tr -d ' ')"
    [ -n "$(device)" ] && echo "OK: connected." || { echo "NOT connected."; exit 1; }
    ;;
  connect)
    "$ADB" start-server >/dev/null 2>&1
    [ -n "$(device)" ] && { echo "Already connected."; exit 0; }
    echo "Looking for the Chromecast (up to 30 seconds)..."
    conn=""
    for _ in $(seq 1 15); do
      conn=$("$ADB" mdns services 2>/dev/null | awk '/_adb-tls-connect/{print $NF}' | grep -E "^$CC:[0-9]+$" | head -1 || true)
      [ -n "$conn" ] && break
      sleep 2
    done
    if [ -z "$conn" ]; then
      printf "Not found by itself. The address on its Wireless debugging screen (IP address & Port): "; read -r conn
    fi
    "$ADB" connect "$(printf '%s' "$conn" | tr -d ' ')"
    [ -n "$(device)" ] && echo "OK: connected." || { echo "NOT connected: is Wireless debugging on?"; exit 1; }
    ;;
  home)
    need_device
    "$ADB" -s "$dev" shell pm path "$HOME_PKG" >/dev/null || { echo "The launcher isn't installed: ./build.sh install"; exit 1; }
    "$ADB" -s "$dev" shell cmd package set-home-activity "$HOME_PKG/.MainActivity"
    "$ADB" -s "$dev" shell input keyevent KEYCODE_HOME
    echo "OK: Home opens the launcher. If Google TV's Home still comes back, also run:"
    echo "  $ADB -s $dev shell pm disable-user --user 0 com.google.android.apps.tv.launcherx"
    echo "  (undo: $0 restore)"
    ;;
  restore)
    need_device
    for p in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith; do
      "$ADB" -s "$dev" shell pm enable "$p" >/dev/null 2>&1 || true
    done
    gtv=$("$ADB" -s "$dev" shell cmd package query-activities --brief -a android.intent.action.MAIN \
      -c android.intent.category.HOME 2>/dev/null | tr -d '\r' | grep -m1 '^ *com.google.android.apps.tv.launcherx/' | tr -d ' ' || true)
    [ -z "$gtv" ] || "$ADB" -s "$dev" shell cmd package set-home-activity "$gtv" >/dev/null
    "$ADB" -s "$dev" shell input keyevent KEYCODE_HOME
    echo "Done: Google TV's Home is back. Without a computer: Settings > Apps > See all apps > Show system apps >"
    echo "Google TV Home > Enable."
    ;;
  *) sed -n '2,9p' "$0"; exit 1 ;;
esac
