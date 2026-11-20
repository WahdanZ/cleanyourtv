package com.cleanyourtv.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * The Chromecast's Home in a layout other than Classic, drawn natively (NativeLayouts.kt has the layouts).
 *
 * The same design as the TV's web engine (shared/experiences/engine.js): one item shape for apps, inputs, Live TV,
 * scenes, the player and today's photo; act() opens one with honest feedback and remembers it for Resume; shared
 * overlays (All apps, Sources, the menu, the photo); focus kept per layout so it comes back where it was; loading,
 * empty, offline and reduced-motion states. The list of layouts and their options is the shared catalog
 * (shared/experiences/catalog.js, read by [Experience]); the server answers are the same as the TV's.
 *
 * MainActivity keeps the Classic Home underneath: Classic is the fallback ("Classic Home" in the menu, Classic in
 * the panel), and its admin QR codes and VPN switch are reused from here.
 */
class NativeHome(val a: MainActivity, private val root: FrameLayout) {
    companion object {
        const val W = 1920; const val H = 1080
        val NIGHT = Color.rgb(11, 21, 33); val SURFACE = Color.rgb(23, 41, 59); val FOCUS = Color.rgb(121, 214, 233)
        val WARM = Color.rgb(255, 201, 154); val TEXT = Color.rgb(243, 248, 251); val MUTED = Color.rgb(169, 188, 203); val SUB = Color.rgb(198, 212, 223)
        private val PALETTE = intArrayOf(0xFF2A6F97.toInt(), 0xFF7A3B69.toInt(), 0xFF2F7D6D.toInt(), 0xFF8A5A2B.toInt(), 0xFF3F4F9A.toInt(), 0xFF7D2F3B.toInt(), 0xFF466B2F.toInt(), 0xFF5B4A8A.toInt())
        fun colorFor(s: String): Int { var h = 0; for (ch in s) h = h * 31 + ch.code; return PALETTE[Math.abs(h % PALETTE.size)] }
        private val LISTEN = Regex("spotify|music|deezer|tidal|soundcloud|anghami|tunein|radio|podcast|audible|pandora|karaoke|yokee|shazam", RegexOption.IGNORE_CASE)
        private val PLAY = Regex("game|geforce|steam ?link|xbox|playstation|ps remote|stadia|luna|boosteroid|arcade|chess|retro|emulator", RegexOption.IGNORE_CASE)
        private val LIVE = Regex("live ?tv|live channels|youtube tv|pluto|sling|tivimate", RegexOption.IGNORE_CASE)
        private val WX = mapOf(0 to "Clear", 1 to "Mostly clear", 2 to "Partly cloudy", 3 to "Cloudy", 45 to "Fog", 48 to "Fog", 51 to "Drizzle", 53 to "Drizzle", 55 to "Drizzle",
            61 to "Rain", 63 to "Rain", 65 to "Heavy rain", 71 to "Snow", 73 to "Snow", 75 to "Heavy snow", 80 to "Showers", 81 to "Showers", 82 to "Heavy showers", 95 to "Thunderstorm")
    }

    // ------------------------------------------------------------------ items: one shape for every destination
    class Item(val type: String, val id: String, val name: String) {
        var sub = ""; var color = SURFACE; var badge = ""; var verb = "Open"; var glyph = ""; var emoji = ""; var why = ""
        var icon: Drawable? = null; var iconUrl = ""; var at = 0L; var live = false; var playing = false
    }
    class Src(val id: String, val name: String, val kind: String)

    class Data {
        val apps = ArrayList<Item>(); val favorites = ArrayList<String>(); val rows = ArrayList<Pair<String, List<String>>>()
        val sources = ArrayList<Src>(); val scenes = ArrayList<Item>(); var widgets = JSONArray(); var np: Item? = null
        var weather: JSONObject? = null; var wallUrl = ""; var wallTitle = ""; var regionNow = "off"; var regions = JSONArray()
        var clock24 = false; var online = true; var expId = "classic"; var opts = JSONObject(); var theme: JSONObject? = null
        fun app(id: String): Item? = apps.firstOrNull { it.id == id }
    }

    val ex: Experience by lazy { Experience(a.assets.open("experiences/catalog.js").use { it.readBytes() }.toString(Charsets.UTF_8)) }
    val prefs = a.getSharedPreferences("layouts", Context.MODE_PRIVATE)
    var data = Data(); private set
    var id = "classic"; private set
    private var suspended = false; private var pausedForAdmin = false
    private var lay: NativeLayouts.Layout? = null
    var state = JSONObject()
    private var shown = false
    private val icons = HashMap<String, Drawable?>()

    // the stage: 1920 x 1080, scaled to the screen like Classic's
    val stage = FrameLayout(a)
    val bg = ImageView(a)
    private val shade = View(a)
    val content = FrameLayout(a)
    private val brand = TextView(a); private val layName = TextView(a); private val clock = TextView(a); private val status = TextView(a)
    private val hints = TextView(a)
    val overlay = FrameLayout(a)
    private val toastV = TextView(a)
    var still = false; private set
    private var ov: ((String) -> Boolean)? = null   // the open overlay's keys

    init {
        stage.visibility = View.GONE
        stage.setBackgroundColor(NIGHT)
        bg.scaleType = ImageView.ScaleType.CENTER_CROP
        stage.addView(bg, FrameLayout.LayoutParams(W, H))
        stage.addView(shade, FrameLayout.LayoutParams(W, H))
        stage.addView(content, FrameLayout.LayoutParams(W, H))
        brand.text = "CLEAN  YOUR TV"; style(brand, 30f, TEXT, 700)
        place(stage, brand, 96, 54); style(layName, 18f, MUTED, 500); layName.letterSpacing = .12f; place(stage, layName, 98, 96)
        style(clock, 44f, TEXT, 600); place(stage, clock, W - 96 - 260, 50, 260); clock.gravity = Gravity.END
        style(status, 24f, TEXT, 500); status.background = round(Color.rgb(107, 42, 42), 24f); status.setPadding(18, 8, 18, 8); place(stage, status, W - 96 - 900, 60, 600)
        status.gravity = Gravity.CENTER; status.visibility = View.GONE
        style(hints, 24f, MUTED, 500); hints.letterSpacing = .04f; hints.isSingleLine = true; hints.ellipsize = TextUtils.TruncateAt.END; place(stage, hints, 96, H - 80, W - 192)
        overlay.setBackgroundColor(Color.argb(219, 6, 12, 20)); overlay.visibility = View.GONE
        stage.addView(overlay, FrameLayout.LayoutParams(W, H))
        style(toastV, 28f, NIGHT, 600); toastV.background = round(TEXT, 18f); toastV.setPadding(36, 20, 36, 20); toastV.alpha = 0f
        stage.addView(toastV, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 110 })
        root.addView(stage, FrameLayout.LayoutParams(W, H))
        val dm = a.resources.displayMetrics
        val k = Math.min(dm.widthPixels / W.toFloat(), dm.heightPixels / H.toFloat())
        stage.pivotX = 0f; stage.pivotY = 0f; stage.scaleX = k; stage.scaleY = k
    }

    // ------------------------------------------------------------------ view kit (sizes in the stage's pixels)
    fun style(t: TextView, size: Float, color: Int = TEXT, weight: Int = 400) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size); t.setTextColor(color); t.typeface = a.font(weight); t.includeFontPadding = false
    }
    fun text(s: String, size: Float, color: Int = TEXT, weight: Int = 400, lines: Int = 1): TextView = TextView(a).also {
        style(it, size, color, weight); it.text = s
        if (lines == 1) { it.isSingleLine = true; it.ellipsize = TextUtils.TruncateAt.END } else { it.maxLines = lines; it.ellipsize = TextUtils.TruncateAt.END }
    }
    fun place(parent: FrameLayout, v: View, x: Int, y: Int, w: Int = ViewGroup.LayoutParams.WRAP_CONTENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT): View {
        parent.addView(v, FrameLayout.LayoutParams(w, h).apply { leftMargin = x; topMargin = y }); return v
    }
    fun round(color: Int, r: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply { setColor(color); cornerRadius = r; if (stroke > 0) setStroke(stroke, strokeColor) }
    fun radial(c1: Int, c2: Int, cx: Float = .4f, cy: Float = .3f) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c1, c2)).apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = 1500f; setGradientCenter(cx, cy)
    }
    /** The focus ring: one, thick, light, with a dark halo, on [v] (or off); tiles also grow a little. */
    fun ring(v: View?, on: Boolean, r: Float = 22f, grow: Boolean = false, color: Int = FOCUS) {
        if (v == null) return
        v.foreground = if (on) android.graphics.drawable.LayerDrawable(arrayOf(round(Color.TRANSPARENT, r + 4, 9, Color.argb(217, 11, 21, 33)), round(Color.TRANSPARENT, r, 5, color))) else null
        if (grow) anim(v).scaleX(if (on) 1.06f else 1f).scaleY(if (on) 1.06f else 1f).start()
    }
    fun anim(v: View) = v.animate().setDuration(if (still) 0 else 160)
    fun dur() = if (still) 0L else 160L

    /** A tile: the icon (adapted to the tile's shape, or as the icon theme draws it), else the initial on its
     *  colour; the name always under it. shape: "wide" (a banner may fill it), "round", "square", "tall". */
    fun tile(it: Item, w: Int, h: Int, nameSize: Float = 26f, showName: Boolean = true, shape: String = if (w > h * 1.25) "wide" else "tall"): LinearLayout {
        val box = LinearLayout(a); box.orientation = LinearLayout.VERTICAL; box.gravity = Gravity.CENTER_HORIZONTAL
        val art = FrameLayout(a); art.background = if (shape == "round") GradientDrawable().apply { this.shape = GradientDrawable.OVAL; setColor(it.color) } else round(it.color, 20f)
        art.clipToOutline = true
        box.addView(art, LinearLayout.LayoutParams(w, h))
        val th = if (it.type == "app") data.theme?.optJSONObject("style") else null
        val own = if (it.type == "app") themeSrc(it) else ""
        val designed = th != null || shape != "wide"   // drawn for its shape (IconFit); a wide tile may show a banner as it is
        val d = it.icon ?: if (it.type == "app") appIcon(it.id, !designed) else null
        val iv = ImageView(a)
        val pack = if (it.type == "app") IconPacks.icon(a, it.id) else null
        when {
            pack != null -> {   // the Android icon pack's icon: its own shape, as the pack drew it, on a faint plate
                art.background = if (shape == "round") GradientDrawable().apply { this.shape = GradientDrawable.OVAL; setColor(Color.argb(30, 255, 255, 255)) }
                                 else round(Color.argb(30, 255, 255, 255), 20f)
                val side = (Math.min(w, h) * if (shape == "round") .7f else .8f).toInt()   // a square icon's corners stay in a circle
                iv.scaleType = ImageView.ScaleType.FIT_CENTER; iv.setImageDrawable(pack.constantState?.newDrawable()?.mutate() ?: pack)
                art.addView(iv, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
            }
            own.isNotEmpty() && th != null -> {   // the theme's own picture: on its plate, tinted or grey if asked
                val bg = th.optString("background", "brand")
                art.background = round(if (bg == "brand") it.color else if (bg == "none") Color.TRANSPARENT else MainActivity.parseColor(bg, it.color), if (shape == "round") w / 2f else 20f)
                val side = (Math.min(w, h) * (1 - 2 * th.optDouble("padding", .18))).toInt()
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                if (th.optBoolean("monochrome")) iv.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
                th.optString("tint").takeIf { t -> t.isNotEmpty() }?.let { t -> iv.setColorFilter(MainActivity.parseColor(t, Color.WHITE), android.graphics.PorterDuff.Mode.SRC_IN) }
                art.addView(iv, FrameLayout.LayoutParams(side, side, Gravity.CENTER)); a.loadImage(own, iv, side)
            }
            d != null && designed -> {
                iv.scaleType = ImageView.ScaleType.FIT_XY
                iv.setImageBitmap(IconFit.bitmap(it.id, d, w, h, shape, th, it.color))
                art.addView(iv, FrameLayout.LayoutParams(w, h))
            }
            d != null -> {   // a wide tile, no theme: the banner edge to edge, or the icon designed for it
                if (d.intrinsicWidth > d.intrinsicHeight * 1.3) { iv.setImageDrawable(d); iv.scaleType = ImageView.ScaleType.CENTER_CROP }
                else { iv.setImageBitmap(IconFit.bitmap(it.id, d, w, h, "wide", null, it.color)); iv.scaleType = ImageView.ScaleType.FIT_XY }
                art.addView(iv, FrameLayout.LayoutParams(w, h))
            }
            it.iconUrl.isNotEmpty() -> { iv.scaleType = ImageView.ScaleType.CENTER_CROP; art.addView(iv, FrameLayout.LayoutParams(w, h)); a.loadImage(it.iconUrl, iv, w) }
            else -> { val m = text(mono(it), h * .42f, Color.WHITE, 700); m.gravity = Gravity.CENTER; art.addView(m, FrameLayout.LayoutParams(w, h)) }
        }
        if (showName) {
            val n = text(it.name, nameSize, Color.rgb(230, 238, 244), 500); n.gravity = Gravity.CENTER
            box.addView(n, LinearLayout.LayoutParams(w + 20, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 })
        }
        box.tag = art
        return box
    }
    private fun themeSrc(it: Item): String {
        val icons = data.theme?.optJSONObject("icons") ?: return ""
        return icons.optString(it.id.lowercase()).ifEmpty { icons.optString(it.name.lowercase()) }
    }
    fun ringTile(t: View?, on: Boolean) { if (t == null) return; ring(t.tag as? View ?: t, on, 20f); anim(t).scaleX(if (on) 1.06f else 1f).scaleY(if (on) 1.06f else 1f).start() }
    fun mono(it: Item): String = when {
        it.glyph.isNotEmpty() -> it.glyph
        it.emoji.isNotEmpty() -> it.emoji
        it.type == "source" -> if (it.badge == "LIVE") "TV" else Regex("\\d+").find(it.id)?.value ?: it.name.take(1)
        else -> it.name.filter { c -> c.isLetterOrDigit() }.take(1).uppercase().ifEmpty { "•" }
    }
    /** For a wide tile, the app's banner; for any other shape, its adaptive icon (it fills any shape) when it has one,
     *  else its banner (more of its logo than an old square icon), else its icon. */
    private fun appIcon(pkg: String, wide: Boolean): Drawable? = icons.getOrPut(pkg + wide) {
        val pm = a.packageManager
        try {
            val icon = pm.getApplicationIcon(pkg)
            if (wide) pm.getApplicationBanner(pkg) ?: icon
            else if (icon is android.graphics.drawable.AdaptiveIconDrawable) icon else pm.getApplicationBanner(pkg) ?: icon
        } catch (e: Exception) { null }
    }

    // ------------------------------------------------------------------ item helpers (engine.js E.items)
    private fun appItem(x: MainActivity.App): Item = Item("app", x.id, x.name).also {
        it.color = if (!x.color.isNullOrEmpty()) MainActivity.parseColor(x.color, colorFor(x.id)) else colorFor(x.id)
        it.sub = when (x.kind) { "addon" -> "Add-on"; "item" -> "Item"; else -> "App" }; it.badge = "APP"; it.emoji = x.emoji ?: ""
        it.live = LIVE.containsMatchIn(x.name)
    }
    private fun srcItem(s: Src): Item = Item("source", s.id, s.name).also {
        val live = s.kind == "live"
        it.color = if (live) Color.rgb(31, 111, 139) else Color.rgb(58, 79, 115)
        it.badge = if (live) "LIVE" else if (s.id.startsWith("HDMI", true)) "HDMI" else "INPUT"
        it.sub = if (live) "Broadcast" else if (s.id.startsWith("HDMI", true)) "HDMI input" else "Input"; it.verb = "Switch"
    }
    fun all(): List<Item> = data.apps.toList()
    fun favorites(): List<Item> = data.favorites.mapNotNull { data.app(it) }.ifEmpty { data.apps.take(6) }
    fun sources(): List<Item> = data.sources.filter { it.kind != "live" && it.kind != "other" }.map { srcItem(it) }
    fun live(): Item? = data.sources.firstOrNull { it.kind == "live" }?.let { srcItem(it) }
        ?: data.apps.firstOrNull { it.live }?.let { x -> Item("app", x.id, x.name).also { it.icon = x.icon; it.color = x.color; it.badge = "LIVE"; it.sub = "Live TV" } }
    fun allApps() = Item("all", "all", "All apps").also { it.sub = "Everything installed"; it.color = Color.rgb(38, 57, 77); it.glyph = "⋯"; it.verb = "Show" }
    fun none(name: String, why: String) = Item("none", "none-$name", name).also { it.sub = why; it.why = why; it.verb = "" }
    fun photo(): Item? = if (data.wallUrl.isEmpty()) null else Item("photo", "photo", "Today's photo").also { it.sub = data.wallTitle.ifEmpty { "Picture of the day" }; it.iconUrl = data.wallUrl; it.badge = "PHOTO"; it.verb = "Look at" }
    fun recent(): List<Item> {
        val arr = try { JSONArray(prefs.getString("recent", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Item>(); val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val key = r.optString("type") + r.optString("id"); if (!seen.add(key)) continue
            val it = if (r.optString("type") == "app") data.app(r.optString("id")) else data.sources.firstOrNull { s -> s.id == r.optString("id") }?.let { srcItem(it) }
            if (it != null) { it.at = r.optLong("at"); out.add(it) }
        }
        return out
    }
    private fun remember(it: Item) {
        if (it.type != "app" && it.type != "source") return
        val arr = try { JSONArray(prefs.getString("recent", "[]")) } catch (e: Exception) { JSONArray() }
        val out = JSONArray().put(JSONObject().put("type", it.type).put("id", it.id).put("at", System.currentTimeMillis()))
        for (i in 0 until arr.length()) { val r = arr.optJSONObject(i) ?: continue; if (!(r.optString("type") == it.type && r.optString("id") == it.id) && out.length() < 12) out.put(r) }
        prefs.edit().putString("recent", out.toString()).apply()
    }
    class Groups(val watch: List<Item>, val play: List<Item>, val listen: List<Item>, val sources: List<Item>)
    fun groups(): Groups {
        val g = data.opts.optJSONObject("groups")
        fun pick(k: String): List<Item>? = g?.optJSONArray(k)?.takeIf { it.length() > 0 }?.let { arr -> (0 until arr.length()).mapNotNull { data.app(arr.optString(it)) } }
        val listen = pick("listen") ?: data.apps.filter { LISTEN.containsMatchIn(it.name) }
        val play = pick("play") ?: data.apps.filter { PLAY.containsMatchIn(it.name) }
        val taken = HashSet<String>(); (listen + play).forEach { taken.add(it.id) }
        val watch = pick("watch") ?: (favorites() + data.apps).filter { taken.add(it.id) }
        val l = live()
        return Groups(watch, play, listen, (if (l != null) listOf(l) else emptyList()) + sources())
    }
    class Moment(val at: Long, val name: String, val source: String, val kind: String, val live: Boolean)
    fun upcoming(): List<Moment> {
        val out = ArrayList<Moment>(); val now = System.currentTimeMillis()
        for (i in 0 until data.widgets.length()) {
            val w = data.widgets.optJSONObject(i) ?: continue
            w.optJSONArray("matches")?.let { ms -> for (j in 0 until ms.length()) {
                val m = ms.optJSONObject(j) ?: continue
                val at = m.optLong("when", m.optLong("at", m.optLong("kickoff", 0)))
                if (at > now - 2 * 3600_000 && at < now + 36 * 3600_000) {
                    val home = m.optJSONObject("home")?.optString("name") ?: m.optString("home"); val away = m.optJSONObject("away")?.optString("name") ?: m.optString("away")
                    out.add(Moment(at, if (home.isNotEmpty() && away.isNotEmpty()) "$home – $away" else m.optString("title", w.optString("name", "Match")), w.optString("name", "Football"), "match", m.optBoolean("live")))
                }
            } }
            if (w.has("next") && w.optLong("when") > 0) out.add(Moment(w.optLong("when"), w.optString("next") + (if (w.has("at")) " · " + w.optString("at") else ""), w.optString("name", "Prayer times"), "prayer", false))
        }
        return out.sortedBy { it.at }
    }
    fun hhmm(ms: Long): String = Calendar.getInstance().let { c -> c.timeInMillis = ms; String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) }
    fun until(ms: Long): String {
        val min = Math.round((ms - System.currentTimeMillis()) / 60000.0).toInt()
        return when { min <= 0 -> "now"; min < 60 -> "in $min min"; min < 1440 -> "in ${min / 60} h" + (if (min % 60 > 0) " ${min % 60} min" else ""); else -> "in ${min / 1440} days" }
    }
    fun clockText(): Pair<String, String> {
        val c = Calendar.getInstance(); val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
        return if (data.clock24) Pair(String.format(Locale.ROOT, "%02d:%02d", h, m), "") else Pair("${if (h % 12 == 0) 12 else h % 12}:" + String.format(Locale.ROOT, "%02d", m), if (h < 12) "AM" else "PM")
    }
    fun dateText(): String = java.text.SimpleDateFormat("EEEE d MMMM", Locale.ENGLISH).format(java.util.Date())
    fun weatherText(): String = data.weather?.takeIf { it.has("temp") }?.let { w -> "${Math.round(w.optDouble("temp"))}°" + (WX[w.optInt("code", -1)]?.let { " · $it" } ?: "") } ?: ""

    // ------------------------------------------------------------------ showing it
    fun visible() = shown
    fun apply(expId: String) {
        val wanted = if (expId in ex.ids) expId else "classic"
        if (wanted == "classic" || suspended || pausedForAdmin) { hide(); id = wanted; return }
        refreshData()
        if (id != wanted || lay == null) {
            id = wanted
            lay = NativeLayouts.make(id, this)
            state = try { JSONObject(prefs.getString("state-$id", "{}")) } catch (e: Exception) { JSONObject() }
            closeOverlay()
        }
        shown = true
        stage.visibility = View.VISIBLE
        draw()
        fetchExtras()
    }
    private fun hide() { shown = false; stage.visibility = View.GONE; closeOverlay() }

    /** What MainActivity has: the apps, dock and rows of the Classic Home, its settings, the media session. */
    private fun refreshData() {
        val d = Data()
        val cfg = a.cfg
        d.clock24 = cfg.optBoolean("clock_24h"); d.online = a.helperUp()
        d.expId = id
        for (x in a.apps) d.apps.add(appItem(x))
        for (i in 0 until Math.min(a.dockSize, a.apps.size)) d.favorites.add(a.apps[i].id)
        for (s in a.sections) d.rows.add(Pair(s.title ?: "", a.apps.drop(s.start).take(s.count).map { it.id }))
        d.widgets = a.addonHome.optJSONArray("widgets") ?: JSONArray()
        d.theme = a.addonHome.optJSONObject("icon_theme")
        d.regionNow = a.regionNow ?: "off"; d.regions = a.regionList
        // kept from the last fetch
        d.sources.addAll(data.sources); d.scenes.addAll(data.scenes); d.weather = data.weather; d.wallUrl = data.wallUrl; d.wallTitle = data.wallTitle
        d.np = npItem()
        data = d
    }
    fun npItem(): Item? {
        val c = a.npCtl ?: return null
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val app = try { a.packageManager.getApplicationLabel(a.packageManager.getApplicationInfo(c.packageName, 0)).toString() } catch (e: Exception) { "" }
        return Item("np", "np", title).also {
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
            it.sub = (if (artist.isNotEmpty()) "$artist · " else "") + app.ifEmpty { "Playing" }
            it.emoji = artist; it.why = app; it.playing = playing; it.badge = if (playing) "PLAYING" else "PAUSED"; it.verb = if (playing) "Pause" else "Play"
            it.icon = (md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))?.let { b -> android.graphics.drawable.BitmapDrawable(a.resources, b) }
            it.color = Color.rgb(47, 72, 88)
        }
    }
    /** Scenes allowed on Home, the TV's inputs (through the helper), the weather, today's photo: from the server. */
    private fun fetchExtras() {
        a.io.execute {
            val scenes = try { JSONObject(a.get("/api/home-scenes", 8000)).optJSONArray("scenes") } catch (e: Exception) { null }
            val src = try { JSONObject(a.get("/api/tv-sources", 8000)).optJSONArray("sources") } catch (e: Exception) { null }
            val wx = if (a.cfg.optBoolean("weather")) try { JSONObject(a.get("/api/weather", 8000)) } catch (e: Exception) { null } else null
            val wall = if (a.cfg.optBoolean("wallpaper")) try { JSONObject(a.get("/api/wallpaper", 8000)) } catch (e: Exception) { null } else null
            a.ui.post {
                val d = data
                if (scenes != null) { d.scenes.clear(); for (i in 0 until scenes.length()) scenes.optJSONObject(i)?.let { s ->
                    d.scenes.add(Item("scene", s.optString("id"), s.optString("name")).also { it.emoji = s.optString("icon").ifEmpty { "✦" }; it.sub = s.optString("summary").ifEmpty { "Scene" }
                        it.color = colorFor("scene" + s.optString("id")); it.badge = "SCENE"; it.verb = "Start" }) } }
                if (src != null) { d.sources.clear(); for (i in 0 until src.length()) src.optJSONObject(i)?.let { s -> d.sources.add(Src(s.optString("id"), s.optString("name"), kind(s.optString("id")))) } }
                d.weather = wx
                d.wallUrl = wall?.optString("image").orEmpty(); d.wallTitle = wall?.optString("title").orEmpty()
                if (shown) refresh()
            }
        }
    }
    private fun kind(id: String) = when {
        Regex("^(TV|ATV|DTV)$", RegexOption.IGNORE_CASE).matches(id) -> "live"
        id.startsWith("HDMI", true) -> "hdmi"
        Regex("^(DP|AV|AVS|YPBPR|VGA|COMP)", RegexOption.IGNORE_CASE).containsMatchIn(id) -> "input"
        else -> "other"
    }

    private fun frame() {
        val info = ex.info(id)
        data.opts = ex.options(id, a.cfg.optJSONObject("experience")?.optJSONObject("opts")?.optJSONObject(id))
        still = data.opts.optString("motion") == "reduced" ||
            Settings.Global.getFloat(a.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f   // the system's "remove animations"
        layName.text = (info?.optString("name") ?: "").uppercase()
        val l = lay ?: return
        hints.text = (l.hints + "Hold OK · Menu").joinToString("   •   ").uppercase()
        status.visibility = if (data.online) View.GONE else View.VISIBLE
        status.text = "Server offline: some things may not work"
        stage.background = l.background(this)
        bg.setImageDrawable(null); shade.background = null
        if (l.photo && data.wallUrl.isNotEmpty()) {
            a.loadImage(data.wallUrl, bg, 1920)
            shade.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(224, 11, 21, 33), Color.argb(115, 11, 21, 33), Color.argb(64, 11, 21, 33)))
        }
        clock.visibility = if (l.calm) View.GONE else View.VISIBLE
        val (t, ap) = clockText(); clock.text = if (ap.isEmpty()) t else "$t $ap"
    }
    private var drawn = false
    private fun draw() { drawn = false; frame(); content.removeAllViews(); safely { lay?.render(); drawn = true } }
    /** New data, same layout: drawn again with focus exactly where it was. */
    fun refresh() { if (!shown) return; refreshData(); frame(); content.removeAllViews(); drawn = false; safely { lay?.refresh(); drawn = true } }
    fun tick() { val (t, ap) = clockText(); clock.text = if (ap.isEmpty()) t else "$t $ap"; if (drawn) safely { lay?.tick() } }
    /** A layout that fails never takes Home down: Classic instead, until Home starts again, and the log says why. */
    private fun safely(f: () -> Unit) {
        try { f() } catch (e: Exception) {
            CursorService.L("home layout $id failed: $e")
            suspended = true; hide(); a.toast("This Home layout hit a problem: Classic for now")
        }
    }
    fun save() { prefs.edit().putString("state-$id", state.toString()).apply() }

    private var toastHide: Runnable? = null
    fun toast(m: String) {
        toastV.text = m; toastV.animate().alpha(1f).setDuration(dur()).start()
        toastHide?.let { a.ui.removeCallbacks(it) }
        toastHide = Runnable { toastV.animate().alpha(0f).setDuration(dur()).start() }.also { a.ui.postDelayed(it, 3500) }
    }

    // ------------------------------------------------------------------ act: opening something
    fun act(it: Item?, v: View? = null) {
        if (it == null) return
        v?.let { x -> x.animate().scaleX(.97f).scaleY(.97f).setDuration(80).withEndAction { x.animate().scaleX(1f).scaleY(1f).setDuration(80).start() }.start() }
        save()
        when (it.type) {
            "none" -> toast(it.why.ifEmpty { "Not available here" })
            "all" -> openGrid("All apps", all())
            "sources" -> openSources()
            "photo" -> openPhoto()
            "np" -> { media("play") }
            "scene" -> {
                toast(it.name + "…")
                a.io.execute {
                    val r = try { JSONObject(a.post("/api/home-scene-run", JSONObject().put("id", it.id).toString(), 15_000)) } catch (e: Exception) { null }
                    a.ui.post { toast(if (r?.optBoolean("ok") == true) r.optString("said", it.name + ": on its way") else "Did not start: " + (r?.optString("error") ?: "no answer from Home's server")) }
                }
            }
            "source" -> {
                if (data.sources.isEmpty()) { toast("Switching inputs needs the helper, which isn't reachable"); return }
                remember(it); toast("Switching to ${it.name}…")
                a.io.execute {
                    val r = try { JSONObject(a.post("/api/tv-source", JSONObject().put("id", it.id).toString(), 15_000)) } catch (e: Exception) { null }
                    if (r?.optBoolean("ok") != true) a.ui.post { toast("Could not switch: " + (r?.optString("error") ?: "no answer")) }
                }
            }
            else -> {
                val app = a.apps.firstOrNull { x -> x.id == it.id }
                if (app == null) { toast("${it.name} isn't on this Chromecast"); return }
                remember(it)
                if (app.kind == "app") toast("Opening ${it.name}…")
                a.open(app)
            }
        }
    }
    fun media(key: String) {
        val tc = a.npCtl?.transportControls ?: run { toast("Nothing is playing"); return }
        when (key) { "next" -> tc.skipToNext(); "prev" -> tc.skipToPrevious(); else -> if (a.npPlaying()) tc.pause() else tc.play() }
        a.ui.postDelayed({ a.npPick(); refresh() }, 600)
    }

    // ------------------------------------------------------------------ overlays (one at a time; Back closes)
    fun closeOverlay() { ov = null; overlay.removeAllViews(); overlay.visibility = View.GONE; content.visibility = View.VISIBLE }
    private fun cover() { overlay.removeAllViews(); overlay.visibility = View.VISIBLE; content.visibility = View.INVISIBLE }
    fun openGrid(title: String, items: List<Item>, cols: Int = 6, empty: String = "Nothing here yet.", onOk: ((Item) -> Unit)? = null) {
        cover()
        place(overlay, text(title, 52f, TEXT, 700), 96, 150)
        if (items.isEmpty()) place(overlay, text(empty, 30f, SUB, 400, 3), 96, 250, W - 192)
        val grid = FrameLayout(a); place(overlay, grid, 96, 250, W - 192, H - 360); grid.clipChildren = true
        val inner = FrameLayout(a); grid.addView(inner, FrameLayout.LayoutParams(W - 192, 4000))
        val cells = items.mapIndexed { n, x -> tile(x, 248, 140).also { t -> place(inner, t, (n % cols) * 280, (n / cols) * 236) } }
        var i = 0
        fun paint() { cells.forEachIndexed { n, t -> ringTile(t, n == i) }; inner.translationY = -Math.max(0, i / cols - 2) * 236f }
        paint()
        ov = { k ->
            if (k == "back") { closeOverlay(); true } else if (items.isEmpty()) true else {
                when (k) {
                    "left" -> i = Math.max(0, i - 1); "right" -> i = Math.min(items.size - 1, i + 1)
                    "up" -> if (i - cols >= 0) i -= cols; "down" -> i = Math.min(items.size - 1, i + cols)
                    "ok" -> { val x = items[i]; closeOverlay(); if (onOk != null) onOk(x) else act(x) }
                }
                if (k != "ok") paint(); true
            }
        }
    }
    fun openSources() {
        val l = live()
        openGrid("Sources", (if (l != null) listOf(l) else emptyList()) + sources(), 4,
            if (data.sources.isNotEmpty()) "The TV reported no inputs." else "Inputs need the helper (a VIDAA TV), which isn't reachable from here.")
    }
    private fun openPhoto() {
        cover()
        val iv = ImageView(a); iv.scaleType = ImageView.ScaleType.CENTER_CROP; overlay.addView(iv, FrameLayout.LayoutParams(W, H)); a.loadImage(data.wallUrl, iv, 1920)
        place(overlay, text(data.wallTitle.ifEmpty { "Today's photo" }, 30f, TEXT, 500), 96, H - 120, W - 192)
        ov = { _ -> closeOverlay(); true }
    }
    fun openMenu() {
        cover()
        val rows = ArrayList<Pair<String, () -> Unit>>()
        rows.add("All apps" to { openGrid("All apps", all()) })
        rows.add("Sources" to { openSources() })
        rows.add("Phone panel (change the layout there)" to { pausedForAdmin = true; hide(); a.openAdmin() })
        rows.add("Classic Home, until Home restarts" to { suspended = true; hide(); a.toast("Classic Home, until Home starts again") })
        rows.add("Settings" to { a.startSafely(Intent(Settings.ACTION_SETTINGS)) })
        val box = LinearLayout(a); box.orientation = LinearLayout.VERTICAL; box.background = round(SURFACE, 28f); box.setPadding(36, 36, 36, 36)
        place(overlay, box, 640, 240, 640)
        box.addView(text(ex.info(id)?.optString("name") ?: "Home", 44f, TEXT, 700), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 })
        val els = rows.map { r -> text(r.first, 30f, TEXT, 500).also { t -> t.background = round(Color.argb(10, 255, 255, 255), 14f); t.setPadding(26, 20, 26, 20)
            box.addView(t, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }) } }
        var i = 0
        fun paint() { els.forEachIndexed { n, t -> ring(t, n == i, 14f) } }
        paint()
        ov = { k ->
            when (k) {
                "back", "menu" -> closeOverlay(); "up" -> { i = Math.max(0, i - 1); paint() }; "down" -> { i = Math.min(rows.size - 1, i + 1); paint() }
                "ok" -> { val r = rows[i]; closeOverlay(); r.second() }
            }
            true
        }
    }
    fun regionMenu() {
        val list = ArrayList<Item>()
        list.add(Item("none", "off", "Local (no VPN)").also { it.glyph = "⌂"; it.color = Color.rgb(36, 67, 90) })
        for (i in 0 until data.regions.length()) data.regions.optJSONObject(i)?.let { r -> if (r.optString("id") != "off")
            list.add(Item("none", r.optString("id"), r.optString("name")).also { it.glyph = r.optString("id").take(2).uppercase(); it.color = Color.rgb(36, 67, 90); it.sub = if (r.optString("id") == data.regionNow) "Now" else "VPN" }) }
        openGrid("VPN country", list, 5) { r -> a.setRegion(r.id); a.ui.postDelayed({ refresh() }, 2500) }
    }
    fun adminClosed() { if (pausedForAdmin) { pausedForAdmin = false; apply(id) } }

    // ------------------------------------------------------------------ keys
    private var okDown = false
    private val longOk = Runnable { if (okDown) { okDown = false; key("menu") } }
    fun key(k: String) {
        val l = lay ?: return
        ov?.let { it(k); return }
        when {
            k == "menu" -> openMenu()
            k.length == 1 && k[0].isDigit() -> safely { l.digit(k.toInt()) }
            else -> { safely { l.key(k) }; save() }
        }
    }
    fun dispatch(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN; val up = e.action == KeyEvent.ACTION_UP
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) key("up"); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) key("down"); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) key("left"); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) key("right"); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (down && e.repeatCount == 0) { okDown = true; a.ui.postDelayed(longOk, 600) }
                else if (up && okDown) { okDown = false; a.ui.removeCallbacks(longOk); key("ok") }
                return true
            }
            KeyEvent.KEYCODE_MENU -> { if (up) key("menu"); return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> { if (up) key("back"); return true }
        }
        if (e.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { if (down) key((e.keyCode - KeyEvent.KEYCODE_0).toString()); return true }
        return false
    }

    // MainActivity's life: rebuild with its new app list; a clock tick and the player while Home shows
    fun appsChanged() { if (shown) refresh() }
    private val ticker = object : Runnable { override fun run() { if (shown) { tick(); val was = data.np?.name + data.np?.playing; val np = npItem(); if (was != np?.name + np?.playing) refresh() }; a.ui.postDelayed(this, 5000) } }
    fun resume() { a.ui.removeCallbacks(ticker); a.ui.post(ticker) }
    fun pause() { a.ui.removeCallbacks(ticker) }
}
