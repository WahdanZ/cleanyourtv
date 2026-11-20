def fetch(settings, ctx):
    items = ctx.rss(settings["feed"], 25)
    return {"kind": "list", "title": "News",
            "items": [{"title": i["title"], "subtitle": i["summary"], "image": i["image"]} for i in items]}
