package com.cleanyourtv.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * What Home shows from the internet, fetched by the Chromecast itself (the helper's weather, quote of the day,
 * wallpaper and add-ons, in Kotlin): the same sources, the same answers, kept in the app's own folder.
 */
class Content(private val hub: Hub) {
    private val store = hub.store
    private fun cfg() = store.config()
    private fun log(m: String) = CursorService.L(m)

    // ---------- the internet ----------
    fun fetch(url: String, timeout: Int = 20_000): ByteArray {
        val h = URL(url).openConnection() as HttpURLConnection
        h.connectTimeout = minOf(timeout, 10_000); h.readTimeout = timeout
        h.instanceFollowRedirects = true
        h.setRequestProperty("User-Agent", UA)
        try {
            if (h.responseCode >= 400) throw Exception("HTTP ${h.responseCode}")
            return h.inputStream.use { it.readBytes() }
        } finally { h.disconnect() }
    }
    fun json(url: String): Any = fetch(url).toString(Charsets.UTF_8).trim().let { if (it.startsWith("[")) JSONArray(it) else JSONObject(it) }
    private fun day(offset: Long = 0) = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(System.currentTimeMillis() + offset))
    private fun unesc(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    // ---------- add-on settings (config "addons": {"order": [...], "<id>": {"enabled", "settings"}}) ----------
    fun addonsConf(): JSONObject {
        val c = cfg()
        val a = JSONObject((c.optJSONObject("addons") ?: JSONObject()).toString())
        if (!a.has("quote")) a.put("quote", JSONObject().put("enabled", c.optBoolean("quotes", true)))
        if (!a.has("weather")) a.put("weather", JSONObject().put("enabled", c.has("weather")).put("settings", JSONObject().put("place", c.opt("weather") ?: JSONObject.NULL)))
        if (!a.has("order")) a.put("order", JSONArray())
        if (!a.has("widget_seconds")) a.put("widget_seconds", 10)
        a.keys().forEach { k -> (a.opt(k) as? JSONObject)?.let { if (!it.has("settings")) it.put("settings", JSONObject()) } }
        return a
    }

    val manifests: Map<String, JSONObject> by lazy {
        val m = LinkedHashMap<String, JSONObject>()
        for (f in hub.ctx.assets.list("addons").orEmpty().sorted()) {
            try { val j = JSONObject(hub.ctx.assets.open("addons/$f").use { it.readBytes() }.toString(Charsets.UTF_8)); m[j.optString("id", f.removeSuffix(".json"))] = j } catch (_: Exception) { }
        }
        m
    }

    fun settingsOf(id: String): JSONObject {
        val s = JSONObject()
        manifests[id]?.optJSONArray("settings")?.let { for (i in 0 until it.length()) { val o = it.getJSONObject(i); if (o.has("key")) s.put(o.getString("key"), o.opt("default") ?: JSONObject.NULL) } }
        addonsConf().optJSONObject(id)?.optJSONObject("settings")?.let { o -> o.keys().forEach { s.put(it, o.get(it)) } }
        return s
    }

    private fun enabledIds(): List<String> {
        val c = addonsConf()
        val order = (0 until c.optJSONArray("order").let { it?.length() ?: 0 }).map { c.getJSONArray("order").getString(it) }.filter { it in manifests }
        return (order + manifests.keys.filter { it !in order }.sorted()).filter { c.optJSONObject(it)?.optBoolean("enabled") == true }
    }

    // ---------- weather (Open-Meteo) ----------
    private var weatherAt = 0L
    private var weatherData: JSONObject? = null
    fun place(): JSONObject? {
        val w = addonsConf().optJSONObject("weather") ?: return null
        if (!w.optBoolean("enabled")) return null
        return w.optJSONObject("settings")?.optJSONObject("place")?.takeIf { it.has("lat") && it.has("lon") }
    }
    fun weather(): JSONObject? {
        val p = place() ?: return null
        if (System.currentTimeMillis() - weatherAt < 30 * 60_000) return weatherData
        weatherAt = System.currentTimeMillis()   // one try per half hour, even when it fails
        try {
            val d = json("https://api.open-meteo.com/v1/forecast?latitude=${p.getDouble("lat")}&longitude=${p.getDouble("lon")}&timezone=auto&forecast_days=1" +
                "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min") as JSONObject
            val cur = d.getJSONObject("current"); val daily = d.getJSONObject("daily")
            weatherData = JSONObject().put("city", p.optString("city")).put("temp", Math.round(cur.getDouble("temperature_2m")))
                .put("code", cur.getInt("weather_code")).put("day", cur.optInt("is_day", 1) == 1)
                .put("high", Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)))
                .put("low", Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)))
        } catch (e: Exception) { log("weather: $e") }
        return weatherData
    }

    // ---------- quote of the day ----------
    private var quoteDay = ""; private var quoteTried = 0L
    private var quoteData: JSONObject? = null
    @Volatile var quoteImage: ByteArray? = null
    fun quoteOn() = addonsConf().optJSONObject("quote")?.optBoolean("enabled", true) ?: true

    private fun builtinQuotes(): List<Pair<String, String>> = try {
        val a = JSONObject(hub.ctx.assets.open("quotes.json").use { it.readBytes() }.toString(Charsets.UTF_8)).getJSONArray("en")
        (0 until a.length()).map { a.getJSONArray(it).let { q -> q.getString(0) to q.optString(1) } }
    } catch (e: Exception) { emptyList() }

    fun parseQuotes(text: String): List<Pair<String, String>> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val m = Regex("^(.*\\S)\\s+[—–~-]\\s*([^—–~-]{1,80})$").find(line) ?: Regex("^(.*\\S)\\s*[—–]\\s*([^—–]{1,80})$").find(line)
        val t = (m?.groupValues?.get(1) ?: line).trim().trim('"', '“', '”').trim()
        t to (m?.groupValues?.get(2)?.trim() ?: "")
    }.filter { it.first.isNotEmpty() }

    private fun fromList(items: List<Pair<String, String>>, pick: String): JSONObject? {
        if (items.isEmpty()) return null
        if (pick == "each") return items[Random().nextInt(items.size)].let { JSONObject().put("text", it.first).put("by", it.second).put("each", true) }
        val d = System.currentTimeMillis() / 86_400_000
        val i = if (pick == "random") Random(d).nextInt(items.size) else (d % items.size).toInt()
        return JSONObject().put("text", items[i].first).put("by", items[i].second)
    }

    fun quote(): JSONObject? {
        if (!quoteOn()) return null
        val s = settingsOf("quote")
        when (s.optString("source", "zenquotes").ifEmpty { "zenquotes" }) {
            "mine" -> return fromList(parseQuotes(s.optString("mine")), s.optString("pick"))
            "builtin" -> return fromList(builtinQuotes(), s.optString("pick"))
        }
        val today = day()
        if (quoteDay == today) return quoteData
        if (System.currentTimeMillis() - quoteTried < 10 * 60_000) return fromList(builtinQuotes(), "order")
        quoteTried = System.currentTimeMillis()
        var q: JSONObject? = null
        for (attempt in 0..1) { try { q = (json("https://zenquotes.io/api/today") as JSONArray).getJSONObject(0); break } catch (_: Exception) { } }
        if (q == null) { log("quote of the day: ZenQuotes didn't answer; a built-in quote for now"); return fromList(builtinQuotes(), "order") }
        val data = JSONObject().put("text", unesc(q.optString("q"))).put("by", unesc(q.optString("a"))).put("source", "ZenQuotes.io")
        quoteDay = today; quoteData = data; quoteImage = null
        val img = q.optString("i")
        if (img.startsWith("https://zenquotes.io/")) Thread {
            try { quoteImage = fetch(img); data.put("image", "/api/quote-image?d=$today") } catch (e: Exception) { log("quote portrait: $e") }
        }.start()
        return data
    }

    // ---------- the wallpaper ----------
    val walls = File(store.dir, "wallpapers").apply { mkdirs() }
    val wallImg = File(store.dir, "wallpaper.jpg")
    private val wallMeta = File(store.dir, "wallpaper.json")
    private val wallState = File(store.dir, "wallpaper-state.json")
    @Volatile private var wallBusy = false
    private var wallTried = 0L

    fun wallOn() = cfg().optBoolean("wallpaper", true)
    fun wallSource() = cfg().optString("wallpaper_source", "bing")
    private fun wallWidth() = if (cfg().optString("wallpaper_size") == "UHD") 3840 else 1920
    private fun wallSkip(): Int = try { JSONObject(wallState.readText()).let { if (it.optString("day") == day()) it.optInt("skip") else 0 } } catch (e: Exception) { 0 }
    private fun wallKey(): String {
        val c = cfg(); val src = wallSource()
        return "$src|${if (src == "bing") c.optString("wallpaper_market", "en-US") else ""}|${if (src == "own") c.optString("wallpaper_pick") else ""}|${wallSkip()}|${c.optString("wallpaper_size", "1920x1080")}|v2"
    }
    fun ownWalls(): List<String> = walls.listFiles()?.map { it.name }?.filter { it.endsWith(".jpg") && !it.endsWith(".thumb.jpg") }?.sorted().orEmpty()

    fun wallpaperMeta(): JSONObject? {
        if (!wallOn()) return null
        val meta = try { if (wallImg.exists()) JSONObject(wallMeta.readText()) else null } catch (e: Exception) { null }
        val stale = meta == null || meta.optString("day") != day() || meta.optString("key") != wallKey()
        if (stale && !wallBusy && System.currentTimeMillis() - wallTried > 10 * 60_000) {
            wallBusy = true; wallTried = System.currentTimeMillis()
            Thread { refreshWallpaper(false) }.start()
        }
        return meta
    }

    @Synchronized fun refreshWallpaper(raise: Boolean) {
        val src = wallSource()
        try {
            val skip = wallSkip(); val key = wallKey()
            val (body, title, credit) = when (src) { "wikimedia" -> wallWikimedia(skip); "nasa" -> wallNasa(skip); "own" -> wallOwn(skip); else -> wallBing(skip) }
            if (body.size < 20_000) throw Exception("no usable image")
            val jpg = fitPhoto(body, wallWidth(), 82, frame = true)
            File(wallImg.path + ".tmp").also { it.writeBytes(jpg) }.renameTo(wallImg)
            val d = day()
            wallMeta.writeText(JSONObject().put("day", d).put("title", title).put("credit", credit).put("size", cfg().optString("wallpaper_size", "1920x1080"))
                .put("key", key).put("source", src).put("image", "/api/wallpaper.jpg?d=$d-" + Store.sha256(key).take(8)).toString())
            log("wallpaper ($src): ${title.ifEmpty { "your photo" }}")
        } catch (e: Exception) {
            log("wallpaper ($src): $e")
            if (raise) throw e
        } finally { wallBusy = false }
    }

    fun wallpaperNext(): JSONObject {
        wallState.writeText(JSONObject().put("day", day()).put("skip", wallSkip() + 1).toString())
        wallBusy = true
        refreshWallpaper(true)
        return JSONObject(wallMeta.readText())
    }

    private fun wallBing(skip: Int): Triple<ByteArray, String, String> {
        val img = (json("https://www.bing.com/HPImageArchive.aspx?format=js&n=1&idx=${minOf(skip, 7)}&mkt=" +
            URLEncoder.encode(cfg().optString("wallpaper_market", "en-US"), "UTF-8")) as JSONObject).getJSONArray("images").getJSONObject(0)
        val size = if (cfg().optString("wallpaper_size") == "UHD") "UHD" else "1920x1080"
        return Triple(fetch("https://www.bing.com" + img.getString("urlbase") + "_" + size + ".jpg", 60_000), img.optString("title"), img.optString("copyright"))
    }

    private fun wide(w: Int, h: Int) = w > 0 && h > 0 && w.toDouble() / h >= 1.3

    private fun wallWikimedia(skip: Int): Triple<ByteArray, String, String> {
        for (back in 0 until 12) {
            val d = Date(System.currentTimeMillis() - (skip + back) * 86_400_000L)
            val feed = json(SimpleDateFormat("'https://api.wikimedia.org/feed/v1/wikipedia/en/featured/'yyyy/MM/dd", Locale.ROOT).format(d)) as JSONObject
            val img = feed.optJSONObject("image") ?: continue
            val orig = img.optJSONObject("image") ?: continue
            val src = orig.optString("source").substringBefore('?')
            if (!wide(orig.optInt("width"), orig.optInt("height")) || !Regex("(?i)\\.(jpe?g|png)$").containsMatchIn(src)) continue
            val m = Regex("(https://upload\\.wikimedia\\.org/wikipedia/commons/)(\\w/\\w\\w/)([^/]+)$").find(src)
            var body: ByteArray? = null
            if (m != null && orig.optInt("width") > wallWidth())
                body = try { fetch("${m.groupValues[1]}thumb/${m.groupValues[2]}${m.groupValues[3]}/${wallWidth()}px-${m.groupValues[3]}", 60_000) } catch (e: Exception) { null }
            if (body == null && orig.optLong("width") * orig.optLong("height") <= 60_000_000) body = fetch(src, 90_000)
            if (body == null) continue
            val desc = unesc(img.optJSONObject("description")?.optString("text").orEmpty())
            val artist = unesc(img.optJSONObject("artist")?.optString("text").orEmpty())
            val title = desc.substringBefore(". ").take(120)
            return Triple(body, title, if (artist.isNotEmpty()) "$title — $artist, Wikimedia Commons" else "$title, Wikimedia Commons")
        }
        throw Exception("no wide picture in the last days")
    }

    private fun wallNasa(skip: Int): Triple<ByteArray, String, String> {
        val wideOnes = ArrayList<Triple<ByteArray, String, String>>()
        try {
            var feed = ""
            for (page in 1..2) { try { feed += fetch("https://science.nasa.gov/feed/?post_type=image-article" + (if (page > 1) "&paged=$page" else "")).toString(Charsets.UTF_8) } catch (e: Exception) { if (page == 1) throw e } }
            for (blob in Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(feed).map { it.groupValues[1] }) {
                val t = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(blob)?.groupValues?.get(1)?.replace(Regex("<!\\[CDATA\\[|]]>"), "")?.let { unesc(it) } ?: ""
                if (!t.startsWith("APOD")) continue
                val url = Regex("https://assets\\.science\\.nasa\\.gov/content/dam/science/cds/apod/[^\"\\s<>&]+?\\.(?:jpe?g|png)", RegexOption.IGNORE_CASE)
                    .findAll(blob).map { it.value }.firstOrNull { "_annotated" !in it } ?: continue
                val title = t.replace(Regex("^APOD:\\s*\\d{4} \\w+ \\d{1,2}\\s*[-–]\\s*"), "")
                val body = fetch(url, 90_000)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(body, 0, body.size, o)
                if (wide(o.outWidth, o.outHeight)) { wideOnes.add(Triple(body, title, "$title, NASA APOD")); if (wideOnes.size > skip) return wideOnes.last() }
            }
            if (wideOnes.isNotEmpty()) return wideOnes[skip % wideOnes.size]
        } catch (e: Exception) { log("wallpaper (nasa): APOD feed: $e") }
        throw Exception("no wide picture in the last days")
    }

    private fun wallOwn(skip: Int): Triple<ByteArray, String, String> {
        val files = ownWalls()
        if (files.isEmpty()) throw Exception("no photos uploaded yet")
        val pick = cfg().optString("wallpaper_pick")
        val name = if (pick in files) pick else files[((System.currentTimeMillis() / 86_400_000 + skip) % files.size).toInt()]
        return Triple(File(walls, name).readBytes(), "", "")
    }

    /** A photo at most `width` wide, as a JPEG; a tall one (frame) whole, in the middle of a 16:9 picture on a dark tint. */
    fun fitPhoto(body: ByteArray, width: Int, quality: Int, frame: Boolean = false): ByteArray {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(body, 0, body.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= width) sample *= 2
        val src = BitmapFactory.decodeByteArray(body, 0, body.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw Exception("not a photo")
        val out: Bitmap
        if (frame && src.width.toDouble() / src.height < 1.3) {
            val h = width * 9 / 16
            out = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val avg = Bitmap.createScaledBitmap(src, 1, 1, true).getPixel(0, 0)
            c.drawColor(Color.rgb(Color.red(avg) / 3, Color.green(avg) / 3, Color.blue(avg) / 3))
            val w = src.width * h / src.height
            c.drawBitmap(src, null, Rect((width - w) / 2, 0, (width - w) / 2 + w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            out = if (src.width > width) Bitmap.createScaledBitmap(src, width, src.height * width / src.width, true) else src
        }
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    fun wallpaperUpload(name: String, body: ByteArray): String {
        val stem = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + "-" +
            (name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]+"), "-").take(40).trim('-').ifEmpty { "photo" })
        File(walls, "$stem.jpg").writeBytes(fitPhoto(body, 3840, 85))
        File(walls, "$stem.thumb.jpg").writeBytes(fitPhoto(body, 480, 75))
        return "$stem.jpg"
    }

    // ---------- add-ons: the built-in ones, in Kotlin ----------
    private val results = HashMap<String, JSONObject>()
    private val errors = HashMap<String, String>()
    private val fetchedAt = HashMap<String, Long>()

    fun refresh(id: String, force: Boolean = false) {
        val m = manifests[id] ?: return
        if (m.optString("type") == "action" || m.optString("type") == "icons" || m.has("slot")) return
        val every = maxOf(1, m.optInt("refresh_minutes", 30)) * 60_000L
        val at = fetchedAt[id] ?: 0
        if (!force && results[id] != null && System.currentTimeMillis() - at < every) return
        if (!force && errors[id] != null && System.currentTimeMillis() - at < 5 * 60_000) return
        try {
            val s = settingsOf(id)
            val r = when (id) {
                "football" -> football(s); "news" -> news(s); "prayer" -> prayer(s); "youtube" -> youtube(s)
                "top-movies" -> topMovies(s); "photos" -> photos(s); "news-reader" -> newsReader(s)
                else -> throw Exception("only built-in add-ons run on the Chromecast")
            }
            synchronized(results) { results[id] = r; errors.remove(id) }
        } catch (e: Exception) {
            synchronized(results) { errors[id] = "${e.javaClass.simpleName}: ${e.message}" }
            log("add-on $id: $e")
        }
        fetchedAt[id] = System.currentTimeMillis()
    }

    /** Every minute: whatever enabled add-on is due. */
    fun loop() { Thread({ while (true) { try { enabledIds().forEach { refresh(it) } } catch (_: Exception) { }; Thread.sleep(60_000) } }, "addons").start() }

    fun home(): JSONObject {
        val widgets = JSONArray(); val rows = JSONArray(); val tiles = JSONArray()
        for (id in enabledIds()) {
            val m = manifests[id] ?: continue
            if (m.has("slot")) continue
            val r = synchronized(results) { results[id] }
            when (m.optString("type")) {
                "widget" -> if (r != null) widgets.put(JSONObject(r.toString()).put("id", id).put("emoji", m.optString("emoji", "✨")).put("color", m.optString("color", "#3a3a44")))
                "row" -> if (r != null && (r.optJSONArray("items")?.length() ?: 0) > 0) {
                    val items = r.getJSONArray("items"); val cut = JSONArray(); for (i in 0 until minOf(20, items.length())) cut.put(items.get(i))
                    rows.put(JSONObject().put("id", id).put("title", r.optString("title").ifEmpty { m.optString("name") }).put("items", cut))
                }
                "screen", "action" -> tiles.put(JSONObject().put("id", "addon:$id").put("name", m.optString("name")).put("emoji", m.optString("emoji", "✨")).put("color", m.optString("color", "#3a3a44")).put("kind", m.optString("type")))
            }
        }
        return JSONObject().put("widgets", widgets).put("rows", rows).put("tiles", tiles).put("widget_seconds", addonsConf().optInt("widget_seconds", 10))
            .put("icon_theme", iconTheme() ?: JSONObject.NULL)
    }

    /** The first enabled icon theme (helper/addons.py icon_theme()): its style; pictures only the helper serves. */
    private fun iconTheme(): JSONObject? {
        for (id in enabledIds()) {
            val m = manifests[id] ?: continue
            if (m.optString("type") != "icons") continue
            val st = m.optJSONObject("style") ?: JSONObject()
            val color = { v: String -> if (Regex("^#[0-9A-Fa-f]{6}$").matches(v)) v else "" }
            val bg = st.optString("background")
            val style = JSONObject().put("shape", st.optString("shape").takeIf { it in listOf("round", "squircle", "square", "keep") } ?: "squircle")
                .put("background", if (bg == "brand" || bg == "none") bg else color(bg).ifEmpty { "brand" })
                .put("monochrome", st.optBoolean("monochrome")).put("tint", color(st.optString("tint")))
                .put("padding", Math.max(0.0, Math.min(0.4, st.optDouble("padding", 0.18))))
            return JSONObject().put("id", id).put("name", m.optString("name", id)).put("style", style).put("icons", JSONObject())
        }
        return null
    }

    fun screen(id: String): JSONObject? {
        if (manifests[id]?.optString("type") != "screen") return null
        refresh(id, force = System.currentTimeMillis() - (fetchedAt[id] ?: 0) > 120_000)
        return JSONObject((results[id] ?: JSONObject()).toString()).put("id", id).put("error", errors[id] ?: "")
    }

    fun steps(id: String): JSONArray? = manifests[id]?.takeIf { it.optString("type") == "action" }?.optJSONArray("steps")

    fun status(): JSONArray {
        val c = addonsConf()
        val order = c.optJSONArray("order")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val out = manifests.map { (id, m) ->
            JSONObject().put("id", id).put("name", m.optString("name", id)).put("type", m.optString("type")).put("description", m.optString("description"))
                .put("emoji", m.optString("emoji", "✨")).put("color", m.optString("color", "#3a3a44")).put("settings", m.optJSONArray("settings") ?: JSONArray())
                .put("version", m.optString("version")).put("builtin", true).put("refresh_minutes", m.optInt("refresh_minutes", 30)).put("slot", m.optString("slot"))
                .put("enabled", c.optJSONObject(id)?.optBoolean("enabled") == true).put("values", settingsOf(id)).put("error", errors[id] ?: "")
                .put("updated", (fetchedAt[id] ?: 0) / 1000).put("has_data", results[id] != null)
        }.sortedWith(compareBy({ order.indexOf(it.getString("id")).let { i -> if (i < 0) order.size else i } }, { it.getString("name").lowercase() }))
        return JSONArray(out)
    }

    // rss/atom: title, link, summary, image, published, id (as the helper's ctx.rss)
    private fun rss(url: String, limit: Int): List<JSONObject> {
        val x = fetch(url).toString(Charsets.UTF_8)
        val items = Regex("<(item|entry)[\\s>](.*?)</(item|entry)>", RegexOption.DOT_MATCHES_ALL).findAll(x).take(limit)
        fun tag(b: String, t: String) = Regex("<$t(?:\\s[^>]*)?>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)
            ?.replace(Regex("<!\\[CDATA\\[|]]>"), "")?.trim().orEmpty()
        fun attr(b: String, t: String, a: String) = Regex("<$t\\s[^>]*$a=\"([^\"]+)\"").find(b)?.groupValues?.get(1).orEmpty()
        return items.map { mt ->
            val b = mt.groupValues[2]
            val link = tag(b, "link").ifEmpty { attr(b, "link", "href") }
            val image = listOf("media:thumbnail", "media:content", "enclosure").map { attr(b, it, "url") }.firstOrNull { it.isNotEmpty() } ?: ""
            val summary = tag(b, "description").ifEmpty { tag(b, "media:description") }.ifEmpty { tag(b, "summary") }
            JSONObject().put("title", unesc(tag(b, "title"))).put("link", link).put("summary", unesc(summary.replace(Regex("<[^>]+>"), " ")).take(400))
                .put("image", image).put("published", tag(b, "pubDate").ifEmpty { tag(b, "published") }).put("id", tag(b, "yt:videoId").ifEmpty { tag(b, "guid") })
        }.toList()
    }

    private fun side(t: JSONObject): JSONObject { val tm = t.getJSONObject("team")
        return JSONObject().put("name", tm.optString("shortDisplayName")).put("abbr", tm.optString("abbreviation")).put("logo", tm.optString("logo")).put("score", t.optString("score")) }

    private fun football(s: JSONObject): JSONObject {
        val d = json("https://site.api.espn.com/apis/site/v2/sports/soccer/${s.optString("league", "eng.1")}/scoreboard") as JSONObject
        val team = s.optString("team").trim().lowercase()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val ms = ArrayList<JSONObject>()
        val ev = d.optJSONArray("events") ?: JSONArray()
        for (i in 0 until ev.length()) {
            val e = ev.getJSONObject(i); val c = e.getJSONArray("competitions").getJSONObject(0); val cs = c.getJSONArray("competitors")
            val sides = (0 until cs.length()).map { cs.getJSONObject(it) }
            val home = side(sides.first { it.optString("homeAway") == "home" }); val away = side(sides.first { it.optString("homeAway") == "away" })
            if (team.isNotEmpty() && listOf(home, away).none { team in it.optString("name").lowercase() }) continue
            val start = try { iso.parse(e.getString("date"))!!.time } catch (x: Exception) { 0L }
            val st = e.getJSONObject("status").getJSONObject("type")
            ms.add(JSONObject().put("home", home).put("away", away).put("state", st.optString("state")).put("detail", st.optString("shortDetail")).put("start", start))
        }
        val order = mapOf("in" to 0, "pre" to 1, "post" to 2)
        ms.sortWith(compareBy({ order[it.optString("state")] ?: 3 }, { if (it.optString("state") != "post") it.optLong("start") else -it.optLong("start") }))
        val league = d.optJSONArray("leagues")?.optJSONObject(0) ?: JSONObject()
        val lines = JSONArray()
        val fmt = SimpleDateFormat("EEE dd MMM · HH:mm", Locale.ENGLISH)
        for (m in ms.take(3)) {
            val h = m.getJSONObject("home"); val a = m.getJSONObject("away")
            if (m.optString("state") == "pre") lines.put(JSONObject().put("text", "${h.optString("name")} – ${a.optString("name")}").put("sub", fmt.format(Date(m.optLong("start")))))
            else lines.put(JSONObject().put("text", "${h.optString("name")} ${h.optString("score")} – ${a.optString("score")} ${a.optString("name")}")
                .put("sub", if (m.optString("state") == "in") "LIVE · " + m.optString("detail") else "Full time").put("live", m.optString("state") == "in"))
        }
        if (lines.length() == 0) lines.put(JSONObject().put("text", "No matches this week").put("sub", ""))
        return JSONObject().put("kind", "matches").put("title", league.optString("name", "Football"))
            .put("logo", league.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty()).put("matches", JSONArray(ms.take(8))).put("lines", lines)
    }

    private fun news(s: JSONObject): JSONObject {
        val items = rss(s.optString("feed"), 6)
        val img = items.map { it.optString("image") }.firstOrNull { it.isNotEmpty() }.orEmpty().replace(Regex("/standard/\\d+/"), "/standard/480/")
        return JSONObject().put("kind", "headlines").put("title", s.optString("source").ifEmpty { "News" }).put("image", img)
            .put("items", JSONArray(items.take(6).map { JSONObject().put("title", it.optString("title")) }))
            .put("lines", JSONArray(items.take(3).map { JSONObject().put("text", it.optString("title")).put("sub", "") }))
    }

    private fun prayer(s: JSONObject): JSONObject {
        val names = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
        val place = s.optJSONObject("place") ?: place()
        if (place == null || !place.has("lat")) return JSONObject().put("title", "Prayer times").put("lines", JSONArray().put(JSONObject().put("text", "Choose a city in the admin panel").put("sub", "")))
        val out = HashMap<String, JSONObject>()
        for ((offset, key) in listOf(0L to "times", 86_400_000L to "tomorrow")) {
            val d = SimpleDateFormat("dd-MM-yyyy", Locale.ROOT).format(Date(System.currentTimeMillis() + offset))
            val t = (json("https://api.aladhan.com/v1/timings/$d?latitude=${place.get("lat")}&longitude=${place.get("lon")}&method=${s.optString("method").ifEmpty { "3" }}") as JSONObject)
                .getJSONObject("data").getJSONObject("timings")
            out[key] = JSONObject().apply { names.forEach { put(it, t.getString(it).take(5)) } }
        }
        val times = out["times"]!!
        val now = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date())
        val next = names.firstOrNull { times.getString(it) > now } ?: "Fajr"
        return JSONObject().put("kind", "prayer").put("title", "Prayer times · " + place.optString("city")).put("city", place.optString("city"))
            .put("date", day()).put("names", JSONArray(names)).put("times", times).put("tomorrow", out["tomorrow"])
            .put("lines", JSONArray().put(JSONObject().put("text", "Next: $next at ${times.getString(next)}").put("sub", ""))
                .put(JSONObject().put("text", names.joinToString("  ") { "$it ${times.getString(it)}" }).put("sub", "")))
    }

    private fun youtube(s: JSONObject): JSONObject {
        val videos = ArrayList<JSONObject>()
        for (ch in s.optString("channels").split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(10)) {
            try { videos.addAll(rss("https://www.youtube.com/feeds/videos.xml?channel_id=$ch", 6)) } catch (e: Exception) { log("add-on youtube: channel $ch: $e") }
        }
        videos.sortByDescending { it.optString("published") }
        val items = videos.filter { it.optString("id").isNotEmpty() }.take(15).map {
            JSONObject().put("title", it.optString("title")).put("subtitle", it.optString("published").take(10)).put("image", "https://i.ytimg.com/vi/${it.optString("id")}/hqdefault.jpg")
                .put("action", JSONObject().put("open", "com.google.android.youtube.tv").put("uri", "https://www.youtube.com/watch?v=" + it.optString("id")))
        }
        return JSONObject().put("title", s.optString("title").ifEmpty { "New on YouTube" }).put("items", JSONArray(items))
    }

    private fun topMovies(s: JSONObject): JSONObject {
        val d = json("https://itunes.apple.com/${s.optString("country", "us")}/rss/topmovies/limit=20/json") as JSONObject
        val ent = d.getJSONObject("feed").optJSONArray("entry") ?: JSONArray()
        val items = (0 until ent.length()).map { ent.getJSONObject(it) }.map { e ->
            val name = e.getJSONObject("im:name").getString("label")
            val imgs = e.getJSONArray("im:image")
            val image = imgs.getJSONObject(imgs.length() - 1).getString("label").substringBeforeLast('/') + "/600x900bb.jpg"
            JSONObject().put("title", name).put("subtitle", e.optJSONObject("category")?.optJSONObject("attributes")?.optString("label").orEmpty())
                .put("image", image).put("poster", true)
                .put("action", JSONObject().put("open", "com.google.android.youtube.tv").put("uri", "https://www.youtube.com/results?search_query=" + URLEncoder.encode("$name official trailer", "UTF-8")))
        }
        return JSONObject().put("title", "Top movies").put("items", JSONArray(items))
    }

    private fun photos(s: JSONObject): JSONObject = JSONObject().put("kind", "slideshow").put("title", "Photos").put("seconds", s.optInt("seconds", 8))
        .put("items", JSONArray(ownWalls().map { JSONObject().put("image", "/api/addons/photos/file/$it").put("caption", "") }))
        .put("empty", "No photos yet: upload some in the admin panel under Wallpaper → My photos.")

    private fun newsReader(s: JSONObject): JSONObject = JSONObject().put("kind", "list").put("title", "News")
        .put("items", JSONArray(rss(s.optString("feed"), 25).map { JSONObject().put("title", it.optString("title")).put("subtitle", it.optString("summary")).put("image", it.optString("image")) }))

    companion object {
        const val UA = "cleanyourtv-home-launcher/1.0 (personal TV home screen)"
        val SOURCES = JSONObject().put("bing", "Bing photo of the day").put("wikimedia", "Wikipedia picture of the day")
            .put("nasa", "NASA astronomy picture of the day").put("own", "My photos")
    }
}
