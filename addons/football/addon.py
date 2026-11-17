from datetime import datetime


def _side(t):
    return {"name": t["team"].get("shortDisplayName", ""), "abbr": t["team"].get("abbreviation", ""),
            "logo": t["team"].get("logo", ""), "score": t.get("score", "")}


def fetch(settings, ctx):
    d = ctx.json("https://site.api.espn.com/apis/site/v2/sports/soccer/%s/scoreboard" % settings["league"])
    team = (settings.get("team") or "").strip().lower()
    matches = []
    for e in d.get("events", []):
        c = e["competitions"][0]
        home = _side(next(t for t in c["competitors"] if t["homeAway"] == "home"))
        away = _side(next(t for t in c["competitors"] if t["homeAway"] == "away"))
        if team and not any(team in s["name"].lower() for s in (home, away)):
            continue
        start = datetime.fromisoformat(e["date"].replace("Z", "+00:00"))
        matches.append({"home": home, "away": away, "state": e["status"]["type"]["state"],   # pre, in, post
                        "detail": e["status"]["type"].get("shortDetail", ""), "start": int(start.timestamp() * 1000)})
    # Live first, then the next ones by kick-off, then the finished ones (latest first).
    order = {"in": 0, "pre": 1, "post": 2}
    matches.sort(key=lambda m: (order.get(m["state"], 3), m["start"] if m["state"] != "post" else -m["start"]))
    league = (d.get("leagues") or [{}])[0]
    lines = []
    for m in matches[:3]:   # plain lines too, for screens that only show text
        h, a = m["home"], m["away"]
        if m["state"] == "pre":
            lines.append({"text": f"{h['name']} – {a['name']}",
                          "sub": datetime.fromtimestamp(m["start"] / 1000).strftime("%a %d %b · %H:%M")})
        else:
            lines.append({"text": f"{h['name']} {h['score']} – {a['score']} {a['name']}",
                          "sub": "LIVE · " + m["detail"] if m["state"] == "in" else "Full time", "live": m["state"] == "in"})
    return {"kind": "matches", "title": league.get("name", "Football"),
            "logo": ((league.get("logos") or [{}])[0]).get("href", ""), "matches": matches[:8],   # a tall card shows more
            "lines": lines or [{"text": "No matches this week", "sub": ""}]}
