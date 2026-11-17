import time

NAMES = ["Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"]


def fetch(settings, ctx):
    place = settings.get("place") or ctx.place
    if not place or "lat" not in place:
        return {"title": "Prayer times", "lines": [{"text": "Choose a city in the admin panel", "sub": ""}]}
    out = {}
    for day, key in ((time.time(), "times"), (time.time() + 86400, "tomorrow")):   # tomorrow: for after Isha
        url = "https://api.aladhan.com/v1/timings/%s?latitude=%s&longitude=%s&method=%s" % (
            time.strftime("%d-%m-%Y", time.localtime(day)), place["lat"], place["lon"], settings.get("method") or "3")
        t = ctx.json(url)["data"]["timings"]
        out[key] = {n: t[n][:5] for n in NAMES}
    times = out["times"]
    now = time.strftime("%H:%M")
    nxt = next((n for n in NAMES if times[n] > now), "Fajr")
    # The home screen works the next prayer and its countdown out itself, from these times, minute by minute.
    return {"kind": "prayer", "title": "Prayer times · " + place.get("city", ""), "city": place.get("city", ""),
            "date": time.strftime("%Y-%m-%d"), "names": NAMES, "times": times, "tomorrow": out["tomorrow"],
            "lines": [{"text": f"Next: {nxt} at {times[nxt]}", "sub": ""},
                      {"text": "  ".join(f"{n} {times[n]}" for n in NAMES), "sub": ""}]}
