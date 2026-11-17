from pathlib import Path

PHOTOS = Path(__file__).resolve().parent.parent.parent / "data" / "wallpapers"


def _names():
    return sorted(p.name for p in PHOTOS.glob("*.jpg") if not p.name.endswith(".thumb.jpg")) if PHOTOS.exists() else []


def fetch(settings, ctx):
    return {"kind": "slideshow", "title": "Photos", "seconds": int(settings.get("seconds") or 8),
            "items": [{"image": ctx.file_url(n), "caption": ""} for n in _names()],
            "empty": "No photos yet: upload some in the admin panel under Wallpaper → My photos."}


def file(name, settings):
    if name in _names():
        return (PHOTOS / name).read_bytes(), "image/jpeg"
    return None
