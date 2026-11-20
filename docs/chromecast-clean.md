# Clean your Chromecast

Turn off the Google parts you don't use: the ads and "recommended for you" rows, the Ambient screensaver, apps you
never open, and some of the tracking. Everything here can be undone, and a factory reset puts it all back.

- **How:** `pm disable-user` turns an app off for your user. It doesn't delete anything, survives restarts, and
  `pm enable` turns it back on. Never use `pm uninstall` on Google's own parts.
- **You need:** adb connected to the Chromecast ([chromecast-only.md](chromecast-only.md), steps 1 and 3).
- **Order:** do [Settings first](#1-settings-no-adb), then [one package at a time](#2-turn-off-packages-adb), and use
  the Chromecast for a minute after each.

## 1. Settings (no adb)

| Where | Set | What it does |
|---|---|---|
| *Settings > Privacy > Ads* | *Delete advertising ID*; *Opt out of Ads Personalization* | Ads are no longer matched to you |
| *Settings > Privacy > Usage & diagnostics* | Off | Stops sending usage reports to Google |
| *Settings > Privacy > Location* | Off, if no app of yours needs it | |
| *Settings > Apps* | Uninstall the apps you don't use | Frees space; they can come back from the Play Store |
| *Settings > System > Ambient mode* (or *Screensaver*) | Off, or a short timer | No photo slideshow with "suggestions" on top |

## 2. Turn off packages (adb)

Turn one off:

```bash
adb shell pm disable-user --user 0 <package>
```

Turn it back on:

```bash
adb shell pm enable <package>
```

See what is off now:

```bash
adb shell pm list packages -d
```

### Off already, with this launcher (tested)

These three are off on the Chromecast this project is tested on, a Chromecast with Google TV 4K on Android 14, and
everything else keeps working.

| Package | What it is | You lose |
|---|---|---|
| `com.google.android.apps.tv.launcherx` | Google TV's Home | Its Home, with its ads and rows. Clean Your TV is your Home instead |
| `com.google.android.tungsten.setupwraith` | Google TV's setup helper | Nothing day to day. It can bring Google TV's Home back after an update |
| `com.google.android.tvrecommendations` | The "recommended for you" service | Suggestions inside other apps' rows |

### Safe to turn off (tested)

All nine were turned off on the same Chromecast on 20 November 2026, while an app was playing. Nothing crashed, and
the app kept playing. Afterwards, Home was still this launcher, its panel and phone pointer worked, and the Cast
receiver and the Google TV phone remote were still running.

| Package | What it is | You lose |
|---|---|---|
| `com.google.android.apps.tv.dreamx` | Ambient mode (the photo screensaver) | That screensaver, and its page in Settings. See the note below the table |
| `com.google.android.backdrop` | Ambient mode's photos | The same |
| `com.google.android.videos` | Google TV's movie shop and library | Buying or renting films from Google, and your Google library |
| `com.google.android.play.games` | Google Play Games | Play Games sign-in in games |
| `com.google.android.feedback` | Sends feedback and crash reports to Google | Nothing you'd notice |
| `com.google.android.tv.remotecontrol.logging` | Remote usage logging | Nothing you'd notice |
| `com.google.android.apps.education.cast2class` | Casting in classrooms | Nothing at home |
| `com.google.android.syncadapters.calendar` | Google Calendar sync | Nothing on a TV |
| `com.google.android.marvin.talkback` | TalkBack, the screen reader | The screen reader. Keep it if anyone in your home uses it |

**The screensaver after `dreamx` is off:** Android still has Ambient mode set as the screensaver, but it can't start
it. So when the Chromecast is idle, the screen stays on until the sleep timer turns it off. (We haven't watched
this happen yet.) If you want a screensaver, choose Android's basic one, which shows slowly changing colours:

```bash
adb shell settings put secure screensaver_components com.android.dreams.basic/.Colors
```

Apps you installed yourself, or that came with the Chromecast (YouTube TV `com.google.android.youtube.tvunplugged`,
YouTube Music `com.google.android.youtube.tvmusic`…), can simply be uninstalled in *Settings > Apps*.

### Careful: you lose a feature

| Package | What it is | You lose |
|---|---|---|
| `com.google.android.katniss` | Google Assistant and voice search | The remote's microphone button, and search from Google TV |

### Leave on

Turning these off breaks things that are hard to diagnose. Some need a factory reset to fix.

| Package | Why |
|---|---|
| `com.google.android.apps.mediashell` | Casting from your phone (the "Chromecast" in Chromecast) |
| `com.google.android.tv.remote.service` | The Google TV remote in your phone, and pairing it |
| `com.google.android.gms`, `com.google.android.gsf`, `com.android.vending` | Google Play services and the Play Store: apps, sign-in, updates |
| `com.google.android.webview` | Many apps draw their pages with it |
| `com.google.android.tvlauncher` | The fallback Home, if this launcher ever fails |
| `com.google.android.tv.dfuservice`, `com.google.android.chromecast.chromecastservice` | The remote's updates; the Chromecast's own services |
| `com.google.tv.settings.chromecast`, `com.android.tv.settings` | Settings |
| `com.google.android.inputmethod.latin` | The on-screen keyboard |
| `com.netflix.tokenmanager` | Netflix sign-in |
| `com.android.vpndialogs` | The VPN built into the launcher |
| Anything with `adservices`, `ondevicepersonalization`, `federatedcompute` or `providers` in its name | System parts. Use *Privacy > Ads* (above) instead |

## 3. Block ads in apps with DNS (optional)

Ads inside apps come from ad servers that a DNS filter can block. Google TV has no menu for this, but adb can set it:

```bash
adb shell settings put global private_dns_mode hostname
adb shell settings put global private_dns_specifier dns.adguard-dns.com
```

`dns.adguard-dns.com` is AdGuard's free filter. You can use another filter, or your own AdGuard Home (its encrypted
DNS address). To undo it:

```bash
adb shell settings put global private_dns_mode opportunistic
```

Some apps (YouTube) serve ads from their own servers, and DNS can't block those.

## If something breaks

1. Turn the last package back on: `adb shell pm enable <package>`, then restart the Chromecast.
2. Still broken: run `adb shell pm list packages -d`, and turn each package in the list back on, except the three
   in [Off already](#off-already-with-this-launcher-tested).
3. Last resort: *Settings > System > About > Factory reset*. Everything, including this launcher, has to be set up
   again.

After a system update, check `pm list packages -d`: an update can turn Google TV's Home back on.
