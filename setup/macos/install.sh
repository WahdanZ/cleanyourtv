#!/bin/bash
# Install the helper as an always-on service on a Mac: it starts at login, restarts if it stops, and keeps the Mac
# from sleeping while it runs (caffeinate). Run from the repo:  bash setup/macos/install.sh
# Remove it again:  launchctl bootout gui/$(id -u)/com.cleanyourtv.helper; rm ~/Library/LaunchAgents/com.cleanyourtv.helper.plist
set -euo pipefail
DIR="$(cd "$(dirname "$0")/../.." && pwd)"
LABEL=com.cleanyourtv.helper
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$DIR/data/helper.log"

[ -f "$DIR/config.json" ] || { echo "Copy config.example.json to config.json and fill it in first (see README.md)."; exit 1; }
sidee=$(/usr/bin/python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("sidee_dir", "../sidee"))' "$DIR/config.json")
vidaa=$(/usr/bin/python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("vidaa", True) is not False)' "$DIR/config.json")
if [ "$vidaa" = True ] && [ ! -d "$DIR/$sidee/core" ] && [ ! -d "$sidee/core" ]; then
  echo "Sidee is missing ($sidee): see README.md, step 1. (Chromecast only? Put \"vidaa\": false in config.json.)"; exit 1
fi

echo "1/3  Python environment ($DIR/.venv)"
PY=$(command -v python3)
"$PY" -c 'import sys; sys.exit(sys.version_info < (3, 10))' || { echo "Python 3.10 or newer is needed: brew install python"; exit 1; }
[ -x "$DIR/.venv/bin/python" ] || "$PY" -m venv "$DIR/.venv"
"$DIR/.venv/bin/pip" install -q --disable-pip-version-check "paho-mqtt==2.1.0" yt-dlp yt-dlp-ejs truststore requests
command -v ffmpeg >/dev/null || echo "     (optional) brew install ffmpeg node   for YouTube without ads and Stremio's server"

echo "2/3  Service ($PLIST)"
mkdir -p "$DIR/data" "$(dirname "$PLIST")"
path="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
if command -v node >/dev/null; then path="$(dirname "$(command -v node)"):$path"; fi
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array>
    <string>/usr/bin/caffeinate</string><string>-i</string><string>-s</string>
    <string>$DIR/.venv/bin/python</string><string>-u</string><string>helper/cyt_helper.py</string>
  </array>
  <key>EnvironmentVariables</key><dict><key>PATH</key><string>$path</string></dict>
  <key>WorkingDirectory</key><string>$DIR</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>StandardOutPath</key><string>$LOG</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict></plist>
EOF
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
sleep 1
launchctl bootstrap "gui/$(id -u)" "$PLIST"

echo "3/3  Checking"
for _ in $(seq 1 30); do curl -s -m 2 -o /dev/null "http://127.0.0.1:$(/usr/bin/python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("port", 8765))' "$DIR/config.json")/api/rev" && break; sleep 2; done
ip=$(ipconfig getifaddr en0 || ipconfig getifaddr en1 || echo "<this Mac's ip>")
echo
echo "Done. Admin panel: http://$ip:8765/admin   (set its password: $DIR/.venv/bin/python helper/set_admin_password.py)"
echo "Log: tail -f $LOG    Restart: launchctl kickstart -k gui/\$(id -u)/$LABEL"
echo "macOS asks once whether Python may find devices on your network (Local Network): allow it."
echo "Keep the Mac on power with the lid open (or use an external display): a sleeping Mac can't serve the TV."
