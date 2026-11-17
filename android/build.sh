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
export JAVA_HOME="${JAVA_HOME_17:-$HOME/Library/Java/JavaVirtualMachines/jbrsdk_jcef-17.0.14/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
KEYSTORE="$HOME/.android/debug.keystore"   # the same key every build, so an update installs over the old one
ADB=/opt/homebrew/bin/adb
VERSION_CODE=$(date +%s | cut -c1-9)
# Where the app finds the helper: this Mac's address on the home network, unless HELPER is given.
HELPER="${HELPER:-http://$(ipconfig getifaddr en0 || ipconfig getifaddr en1):8765}"

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
  cc="${CHROMECAST:-$(/usr/bin/python3 -c 'import json; print(json.load(open("../config.json")).get("chromecast", {}).get("ip", ""))')}"
  dev=$("$ADB" devices | awk -v ip="$cc" 'index($1, ip ":") == 1 && $2 == "device" {print $1; exit}')
  [ -n "$dev" ] || { echo "The Chromecast is not connected: double-click 'Connect Chromecast'."; exit 1; }
  "$ADB" -s "$dev" install -r build/home.apk
fi
