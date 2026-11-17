from urllib.parse import quote


def fetch(settings, ctx):
    d = ctx.json("https://itunes.apple.com/%s/rss/topmovies/limit=20/json" % settings.get("country", "us"))
    items = []
    for e in d["feed"].get("entry", []):
        name = e["im:name"]["label"]
        image = e["im:image"][-1]["label"]
        image = image.rsplit("/", 1)[0] + "/600x900bb.jpg"   # a bigger poster than the feed's thumbnail
        items.append({"title": name, "subtitle": e.get("category", {}).get("attributes", {}).get("label", ""),
                      "image": image, "poster": True,
                      "action": {"open": "com.google.android.youtube.tv",
                                 "uri": "https://www.youtube.com/results?search_query=" + quote(name + " official trailer")}})
    return {"title": "Top movies", "items": items}
