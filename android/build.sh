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
# Kotlin (the newer parts: the VPN, the Chromecast's own server): kotlinc from Android Studio, or `brew install kotlin`.
KOTLINC="${KOTLINC:-$(command -v kotlinc || true)}"
[ -n "$KOTLINC" ] || KOTLINC="/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc/bin/kotlinc"
[ -x "$KOTLINC" ] || { echo "No Kotlin compiler: install Android Studio, or: brew install kotlin"; exit 1; }
KSTDLIB="$(cd "$(dirname "$KOTLINC")/../lib" && pwd)/kotlin-stdlib.jar"
# The signing key: the same every build, so an update installs over the old app (a new key would mean uninstalling,
# which wipes the app's settings). CI passes the same key file and its password (KEYSTORE, KS_PASS).
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
VERSION_NAME="$(cat VERSION 2>/dev/null || echo 1.0.0)"   # android/VERSION: the app's version (the Release tag)
ADB="${ADB:-$([ -x /opt/homebrew/bin/adb ] && echo /opt/homebrew/bin/adb || command -v adb || true)}"
VERSION_CODE=$(date +%s | cut -c1-9)
# Where the app finds the helper: this computer's address on the home network, unless HELPER is given
# (HELPER=http://192.168.1.20:8765 ./build.sh when the helper runs on another computer).
# RELEASE=1 (the downloadable app, CI): no helper address; it runs on its own server until one is set.
if [ "${RELEASE:-}" = 1 ]; then HELPER="${HELPER:-}"
elif [ -z "${HELPER:-}" ]; then
  ip=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')
  HELPER="http://$ip:8765"
fi

# The WireGuard engine for the launcher's own VPN (Apache-2.0, from Maven Central), checked against its pinned checksum.
# ABIS: the processors to include (the Chromecast with Google TV: armeabi-v7a; newer boxes: arm64-v8a).
WG_VERSION=1.0.20260102
WG_SHA1=decdc1c35d64033aeff6565446483c26388307a5
ABIS="${ABIS:-armeabi-v7a arm64-v8a}"
WG=cache/wg
if [ ! -f "$WG/x/classes.jar" ]; then
  mkdir -p "$WG"
  curl -fsSL -o "$WG/tunnel.aar" "https://repo1.maven.org/maven2/com/wireguard/android/tunnel/$WG_VERSION/tunnel-$WG_VERSION.aar"
  [ "$(shasum "$WG/tunnel.aar" | cut -d' ' -f1)" = "$WG_SHA1" ] || { echo "the WireGuard library's checksum doesn't match"; exit 1; }
  rm -rf "$WG/x"; mkdir -p "$WG/x"; (cd "$WG/x" && unzip -q ../tunnel.aar)
fi

rm -rf build; mkdir -p build/classes build/assets/fonts build/dex build/gen
cp ../web/quotes.json build/assets/
cp ../web/fonts/outfit.ttf build/assets/fonts/
# The Chromecast's own server (Hub.kt): from shared/, the same phone panel and layout rules the helper uses, and the
# built-in add-ons' descriptions.
mkdir -p build/assets/web/app build/assets/addons
cp ../shared/panel/admin.html ../shared/panel/admin-sw.js build/assets/web/
cp ../shared/panel/app/* build/assets/web/app/
cp ../shared/layout.json build/assets/layout_spec.json
# The Home layouts: their catalog (shared/experiences/catalog.js; NativeLayouts.kt draws them here) and the previews
# the phone panel shows.
mkdir -p build/assets/experiences/previews
cp ../shared/experiences/catalog.js build/assets/experiences/
cp ../shared/experiences/previews/*.jpg build/assets/experiences/previews/ 2>/dev/null || true
for a in ../addons/*/addon.json; do cp "$a" "build/assets/addons/$(basename "$(dirname "$a")").json"; done

mkdir -p build/gen/com/cleanyourtv/home
printf 'package com.cleanyourtv.home;\nfinal class BuildConfig { static final String HELPER = "%s"; }\n' "$HELPER" > build/gen/com/cleanyourtv/home/BuildConfig.java
echo "helper: $HELPER"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml -A build/assets -R build/res.zip --java build/gen --auto-add-overlay \
  --min-sdk-version 28 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
# Kotlin first (it reads the Java sources for their names), then Java with the Kotlin classes on its path.
mkdir -p build/kt
"$KOTLINC" -nowarn -jvm-target 11 -no-reflect -classpath "$JAR:$WG/x/classes.jar" -d build/kt \
  $(find src -name '*.kt') $(find src stubs build/gen -name '*.java') 2>&1 | grep -v "^warning" || true
[ -n "$(find build/kt -name '*.class' | head -1)" ] || { echo "the Kotlin part didn't compile"; exit 1; }
javac -nowarn --release 11 -classpath "$JAR:$WG/x/classes.jar:build/kt:$KSTDLIB" -d build/classes $(find src stubs build/gen -name '*.java')
# (d8 is a little older than this Kotlin and warns about Kotlin's own metadata, which nothing reads at run time:
# its messages go to build/d8.log, shown only if it fails.)
"$BT/d8" --release --min-api 28 --lib "$JAR" --output build/dex $(find build/classes build/kt -name '*.class') "$WG/x/classes.jar" "$KSTDLIB" \
  > build/d8.log 2>&1 || { cat build/d8.log; exit 1; }
(cd build/dex && zip -q -X ../base.apk classes*.dex)
mkdir -p build/native
for abi in $ABIS; do mkdir -p "build/native/lib/$abi"; cp "$WG/x/jni/$abi/libwg-go.so" "build/native/lib/$abi/"; done
(cd build/native && zip -q -X -r ../base.apk lib)
"$BT/zipalign" -f -p 4 build/base.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --out build/home.apk build/aligned.apk
echo "built $(pwd)/build/home.apk ($(du -h build/home.apk | cut -f1))"

if [ "${1:-}" = install ]; then
  # The Chromecast's address comes from config.json ("chromecast": {"ip": ...}), or CHROMECAST=...
  cc="${CHROMECAST:-$(/usr/bin/python3 -c 'import json; c = json.load(open("../config.json")).get("chromecast", {}).get("ip", ""); print(c[0] if isinstance(c, list) else c)')}"
  dev=$("$ADB" devices | awk -v ip="$cc" 'index($1, ip ":") == 1 && $2 == "device" {print $1; exit}')
  [ -n "$dev" ] || { echo "The Chromecast is not connected: ./cc.sh connect"; exit 1; }
  "$ADB" -s "$dev" install -r build/home.apk
  # An update stops the pointer service (the trackpad, and the remote without adb): out of the list and back in
  # restarts it. Only when it was on; and let the launcher keep Wireless debugging on (AdbKeeper).
  svc=com.cleanyourtv.home/com.cleanyourtv.home.CursorService
  cur=$("$ADB" -s "$dev" shell settings get secure enabled_accessibility_services | tr -d '\r')
  case ":$cur:" in *":$svc:"*)
    rest=$(printf '%s' "$cur" | tr ':' '\n' | { grep -vx "$svc" || true; } | paste -sd: -)
    "$ADB" -s "$dev" shell settings put secure enabled_accessibility_services "${rest:-null}"
    "$ADB" -s "$dev" shell settings put secure enabled_accessibility_services "${rest:+$rest:}$svc"
    echo "Pointer service restarted." ;;
  esac
  "$ADB" -s "$dev" shell pm grant com.cleanyourtv.home android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
  # what's playing, with its length and picture, for the phone's Now playing (MediaWatch)
  "$ADB" -s "$dev" shell cmd notification allow_listener com.cleanyourtv.home/com.cleanyourtv.home.MediaWatch 2>/dev/null || true
fi
