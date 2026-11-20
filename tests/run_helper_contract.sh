#!/bin/bash
# The contract checks (tests/api_contract.py) against a throw-away helper: a copy in a temporary folder, with a test
# config (no TV, a "Chromecast" at 127.0.0.1) and a test password, on port 8797. Your own helper and data/ are untouched.
#   tests/run_helper_contract.sh            (PYTHON=... to pick the Python; CI uses its own)
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PYTHON:-python3}"
tmp=$(mktemp -d)
trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; rm -rf "$tmp"' EXIT
cp -R helper shared web addons "$tmp/"
mkdir -p "$tmp/data"
cat > "$tmp/config.json" <<JSON
{"vidaa": false, "port": 8797, "chromecast": {"ip": ["127.0.0.1"]}, "greeting_name": "",
 "addons": {"quote": {"enabled": true, "settings": {"source": "builtin"}}}}
JSON
pw="contract-$RANDOM-$RANDOM"
"$PY" -c "import sys, json; sys.path.insert(0, '$tmp/helper'); import admin; open('$tmp/data/admin.json', 'w').write(json.dumps(admin.hash_password(sys.argv[1])))" "$pw"
"$PY" "$tmp/helper/cyt_helper.py" "$tmp/config.json" > "$tmp/helper.log" 2>&1 &
pid=$!
for _ in $(seq 1 60); do curl -s -m1 http://127.0.0.1:8797/api/rev >/dev/null && break; sleep 0.5; done
"$PY" tests/api_contract.py http://127.0.0.1:8797 --password "$pw" || { echo "--- helper log"; tail -30 "$tmp/helper.log"; exit 1; }
