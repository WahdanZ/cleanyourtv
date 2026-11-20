def fetch(settings, ctx):
    videos = []
    for ch in [c.strip() for c in settings["channels"].split(",") if c.strip()][:10]:
        try:
            for v in ctx.rss("https://www.youtube.com/feeds/videos.xml?channel_id=" + ch, 6):
                videos.append(v)
        except Exception as e:
            ctx.log(f"channel {ch}: {e}")
    videos.sort(key=lambda v: v["published"], reverse=True)
    items = [{"title": v["title"], "subtitle": v["published"][:10],
              "image": "https://i.ytimg.com/vi/%s/hqdefault.jpg" % v["id"],
              "action": {"open": "com.google.android.youtube.tv", "uri": "https://www.youtube.com/watch?v=" + v["id"]}}
             for v in videos if v["id"]]
    return {"title": settings.get("title") or "New on YouTube", "items": items[:15]}
