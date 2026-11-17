import re


def fetch(settings, ctx):
    items = ctx.rss(settings["feed"], 3)
    return {"kind": "headlines", "title": settings.get("source") or "News",
            "image": re.sub(r"/standard/\d+/", "/standard/480/", next((i["image"] for i in items if i["image"]), "")),
            "items": [{"title": i["title"]} for i in items[:3]],
            "lines": [{"text": i["title"], "sub": ""} for i in items[:3]]}
