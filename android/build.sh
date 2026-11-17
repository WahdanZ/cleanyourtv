#!/bin/bash
# Builds the Chromecast launcher (build/home.apk) with the Android SDK's own tools: no Gradle, no Android Studio.
# The quotes (offline fallback) and the Outfit font are copied in from ../web.
#   ./build.sh            build
#   ./build.sh install    build, then install on the Chromecast over adb (it must be connected)
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-34/android.jar"
# A JDK 17 or newer: JAVA_HOME_17, else JAVA_HOME, else macOS's own pick of an installed one.
export JAVA_HOME="${JAVA_HOME_17:-${JAVA_HOME:-$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)}}"
export PATH="$JAVA_HOME/bin:$PATH"
KEYSTORE="$HOME/.android/debug.keystore"   # the same key every build, so an update installs over the old one
ADB="${ADB:-$([ -x /opt/homebrew/bin/adb ] && echo /opt/homebrew/bin/adb || command -v adb || true)}"
VERSION_CODE=$(date +%s | cut -c1-9)
# Where the app finds the helper: this computer's address on the home network, unless HELPER is given
# (HELPER=http://192.168.1.20:8765 ./build.sh when the helper runs on another computer).
if [ -z "${HELPER:-}" ]; then
  ip=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')
  HELPER="http://$ip:8765"
fi

rm -rf build; mkdir -p build/classes build/assets/fonts build/dex build/gen
cp ../web/quotes.json build/assets/
cp ../web/fonts/outfit.ttf build/assets/fonts/

mkdir -p build/gen/com/cleanyourtv/home
printf 'package com.cleanyourtv.home;\nfinal class BuildConfig { static final String HELPER = "%s"; }\n' "$HELPER" > build/gen/com/cleanyourtv/home/BuildConfig.java
echo "helper: $HELPER"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml -A build/assets -R build/res.zip --java build/gen --auto-add-overlay \
  --min-sdk-version 28 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "1.0"
javac -nowarn --release 11 -classpath "$JAR" -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 28 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q -X ../base.apk classes.dex)
"$BT/zipalign" -f -p 4 build/base.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --out build/home.apk build/aligned.apk
echo "built $(pwd)/build/home.apk ($(du -h build/home.apk | cut -f1))"

if [ "${1:-}" = install ]; then
  # The Chromecast's address comes from config.json ("chromecast": {"ip": ...}), or CHROMECAST=...
  cc="${CHROMECAST:-$(/usr/bin/python3 -c 'import json; c = json.load(open("../config.json")).get("chromecast", {}).get("ip", ""); print(c[0] if isinstance(c, list) else c)')}"
  dev=$("$ADB" devices | awk -v ip="$cc" 'index($1, ip ":") == 1 && $2 == "device" {print $1; exit}')
  [ -n "$dev" ] || { echo "The Chromecast is not connected: ./cc.sh connect"; exit 1; }
  "$ADB" -s "$dev" install -r build/home.apk
fi
