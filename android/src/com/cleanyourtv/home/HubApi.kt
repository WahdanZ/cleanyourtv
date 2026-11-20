package com.cleanyourtv.home

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The answers of the Chromecast's own server (Hub.kt): Home's requests and the admin panel's, as the Mac helper gave
 * them (helper/cyt_helper.py and helper/admin.py), for a home with this Chromecast only (no VIDAA TV here).
 */
class HubApi(private val hub: Hub) {
    private val store = hub.store
    val content = Content(hub)
    private val spec: JSONObject by lazy { JSONObject(hub.ctx.assets.open("layout_spec.json").use { it.readBytes() }.toString(Charsets.UTF_8)) }
    private fun cfg() = store.config()
    private fun cc() = cfg().optJSONObject("chromecast") ?: JSONObject()
    private fun log(m: String) = CursorService.L(m)
    private fun svc(): CursorService? = CursorService.instance
    private fun ok(extra: JSONObject = JSONObject()) = Hub.Res.json(extra.put("ok", true))

    fun started() {
        content.loop()
        Thread({ statsLoop() }, "stats").start()
        Thread({ while (true) { try { content.wallpaperMeta() } catch (_: Exception) { }; Thread.sleep(15 * 60_000) } }, "wallpaper").start()
    }

    // =========================================================================================================
    // Home (this Chromecast's launcher, on 127.0.0.1)
    // =========================================================================================================
    fun homeGet(r: Hub.Req): Hub.Res {
        val p = r.path
        return when {
            p == "/api/cc" -> Hub.Res.json(chromecastSettings())
            p == "/api/rev" -> Hub.Res.json(JSONObject().put("started", hub.started).put("saved", hub.saved)
                .put("wall", content.wallpaperMeta()?.optString("image") ?: "").put("code", hub.started))
            p == "/api/home-edit" -> Hub.Res.json(JSONObject().put("layout", layout()).put("spec", spec).put("widgets", homeEditWidgets()))
            p == "/api/region" -> Hub.Res.json(JSONObject().put("region", if (Vpn.configured(hub.ctx)) Vpn.country else JSONObject.NULL).put("regions", regions()))
            p == "/api/now-playing" -> Hub.Res.json(nowPlaying())
            p.startsWith("/api/cc-icon/") -> icon(p.substringAfterLast('/'))
            p == "/api/admin-link" -> adminLink()
            p == "/api/weather" -> Hub.Res.json(content.weather())
            p == "/api/quote" -> Hub.Res.json(content.quote())
            p == "/api/quote-image" -> content.quoteImage?.let { Hub.Res(200, "image/jpeg", it) } ?: Hub.Res.err(404)
            p == "/api/wallpaper" -> Hub.Res.json(content.wallpaperMeta())
            p == "/api/wallpaper.jpg" -> if (content.wallImg.exists()) Hub.Res(200, "image/jpeg", content.wallImg.readBytes()) else Hub.Res.err(404)
            p.startsWith("/api/addons/") -> addonsGet(p)
            else -> Hub.Res.err(404)
        }
    }

    fun homePost(r: Hub.Req): Hub.Res {
        val d = r.json
        return when (r.path) {
            "/api/region" -> region(d.optString("region"))
            "/api/cc-open" -> { vpnForApp(d.optString("appId")); ok() }
            "/api/cc-apps" -> { storeApps(d.optJSONArray("apps") ?: JSONArray()); ok() }
            "/api/home-edit" -> homeEditSave(d)
            else -> if (r.path.startsWith("/api/addons/")) addonsGet(r.path) else Hub.Res.err(404)
        }
    }

    fun chromecastSettings(): JSONObject {
        val c = cfg(); val cc = cc()
        return JSONObject().put("dock", cc.optJSONArray("dock") ?: JSONArray()).put("rows", cc.optJSONArray("rows") ?: JSONArray())
            .put("rename", cc.optJSONObject("rename") ?: JSONObject()).put("hidden", cc.optJSONArray("hidden") ?: JSONArray())
            .put("app_vpn", cc.optJSONObject("app_vpn") ?: JSONObject()).put("greeting_name", c.optString("greeting_name"))
            .put("quotes", content.quoteOn()).put("weather", content.place() != null).put("wallpaper", content.wallOn())
            .put("region", Vpn.configured(hub.ctx)).put("clock_24h", c.optBoolean("clock_24h")).put("rev", cc.optLong("rev"))
            .put("layout", layout()).put("live_refresh", c.optBoolean("live_refresh", true))
            .put("cursor", JSONObject().put("port", CursorService.PORT).put("token", svc()?.token().orEmpty())).put("started", hub.started).put("saved", hub.saved)
    }

    fun layout(): JSONObject = cfg().optJSONObject("home_layout") ?: spec.getJSONObject("presets").getJSONObject("classic")

    private fun homeEditWidgets(): JSONArray {
        val conf = content.addonsConf()
        return JSONArray(content.manifests.filter { it.value.optString("type") == "widget" && !it.value.has("slot") }.map { (id, m) ->
            JSONObject().put("id", id).put("name", m.optString("name")).put("emoji", m.optString("emoji", "✨")).put("on", conf.optJSONObject(id)?.optBoolean("enabled") == true)
        })
    }

    private fun homeEditSave(d: JSONObject): Hub.Res {
        val addons = JSONObject(content.addonsConf().toString())
        val known = content.manifests.filter { it.value.optString("type") == "widget" && !it.value.has("slot") }.keys
        for ((key, on) in listOf("on" to true, "off" to false)) d.optJSONArray(key)?.let { a ->
            for (i in 0 until a.length()) { val id = a.optString(i); if (id in known) (addons.optJSONObject(id) ?: JSONObject().put("settings", JSONObject()).also { addons.put(id, it) }).put("enabled", on) }
        }
        return try { save(JSONObject().put("home_layout", d.opt("layout")).put("addons", addons)); ok() } catch (e: Exception) { Hub.Res.err(400, e.message ?: "") }
    }

    private fun adminLink(): Hub.Res {
        val url = "http://${hub.ownIp()}:${Hub.PORT}/admin"
        val links = JSONArray()
        for ((label, u) in listOf("Admin panel" to url, "Remote" to "$url#remote", "Player" to "$url#player"))
            links.put(JSONObject().put("label", label).put("url", u).put("qr", JSONArray(Qr.matrix(u))))
        return Hub.Res.json(JSONObject().put("url", url).put("qr", links.getJSONObject(0).get("qr")).put("links", links))
    }

    private fun addonsGet(p: String): Hub.Res {
        val parts = p.split("/")   // "", api, addons, id, ...
        if (p == "/api/addons/home") return Hub.Res.json(content.home().let { h -> h.put("tiles", JSONArray().also { t ->
            for (i in 0 until h.getJSONArray("tiles").length()) t.put(h.getJSONArray("tiles").get(i))
            webTiles().forEach { t.put(it) } }) })
        if (parts.size >= 5 && parts[4] == "screen") return content.screen(parts[3])?.let { Hub.Res.json(it) } ?: Hub.Res.err(404)
        if (parts.size >= 6 && parts[4] == "file" && parts[3] == "photos") {
            val f = File(content.walls, parts[5]); return if (parts[5] in content.ownWalls()) Hub.Res(200, "image/jpeg", f.readBytes()) else Hub.Res.err(404)
        }
        if (parts.size >= 5 && parts[4] == "run") return Hub.Res.json(addonRun(parts[3]))
        return Hub.Res.err(404)
    }

    fun addonRun(id: String): JSONObject {
        if (id.startsWith("web-")) {   // a website: in the Chromecast's browser
            val w = website(id.removePrefix("web-")) ?: return JSONObject().put("ok", false).put("error", "unknown website")
            val pkg = webBrowser()
            return JSONObject().put("ok", true).put("steps", JSONArray().put(if (pkg.isNotEmpty()) JSONObject().put("open", pkg).put("uri", w.optString("url")) else JSONObject().put("url", w.optString("url"))))
        }
        val steps = content.steps(id) ?: return JSONObject().put("ok", false).put("error", "unknown add-on")
        val client = JSONArray()
        for (i in 0 until steps.length()) {
            val st = steps.getJSONObject(i)
            if (st.has("vpn")) { Vpn.set(hub.ctx, st.optString("vpn")); autoPrev = null } else client.put(st)
        }
        return JSONObject().put("ok", true).put("steps", client)
    }

    // ---------- the Chromecast's apps, as Home reports them (names and icons) ----------
    private fun appsFile() = store.file("cc-apps.json")
    fun ccApps(): JSONObject = (store.readJson("cc-apps.json") as? JSONObject) ?: JSONObject().put("seen", 0).put("apps", JSONArray())

    private fun storeApps(apps: JSONArray) {
        val dir = File(store.dir, "cc-icons").apply { mkdirs() }
        val out = JSONArray()
        for (i in 0 until minOf(apps.length(), 300)) {
            val a = apps.optJSONObject(i) ?: continue
            val pkg = a.optString("pkg")
            if (!Regex("^[A-Za-z0-9_.]{1,120}$").matches(pkg)) continue
            out.put(JSONObject().put("id", pkg).put("name", a.optString("name", pkg).take(60)).put("banner", a.optBoolean("banner")))
            a.optString("icon").takeIf { it.isNotEmpty() && it.length < 400_000 }?.let { try { File(dir, "$pkg.png").writeBytes(Base64.decode(it, Base64.DEFAULT)) } catch (_: Exception) { } }
        }
        store.writeText("cc-apps.json", JSONObject().put("seen", System.currentTimeMillis() / 1000).put("apps", out).toString())
    }

    private fun icon(pkg: String): Hub.Res {
        if (!Regex("^[A-Za-z0-9_.]{1,120}$").matches(pkg)) return Hub.Res.err(404)
        val f = File(store.dir, "cc-icons/$pkg.png")
        return if (f.exists()) Hub.Res(200, "image/png", f.readBytes()) else Hub.Res.err(404)
    }

    // =========================================================================================================
    // The VPN: the launcher's own (Vpn.kt); the countries offered are the panel's "vpn_countries"
    // =========================================================================================================
    @Volatile private var autoPrev: String? = null   // the country before an app switched it, while that switch stands

    fun regions(): JSONArray {
        if (!Vpn.configured(hub.ctx)) return JSONArray()
        val names = HashMap<String, String>()
        try { val all = Vpn.countries(hub.ctx); for (i in 0 until all.length()) all.getJSONObject(i).let { names[it.optString("code")] = it.optString("name") } } catch (_: Exception) { }
        val out = JSONArray().put(JSONObject().put("id", "off").put("name", cfg().optString("vpn_home_name", "Home") + " (no VPN)"))
        cfg().optJSONArray("vpn_countries")?.let { a -> for (i in 0 until a.length()) a.optString(i).lowercase().let { out.put(JSONObject().put("id", it).put("name", names[it] ?: it.uppercase())) } }
        return out
    }

    fun region(code: String): Hub.Res {
        autoPrev = null   // a choice by hand: no app switches it back
        val why = Vpn.set(hub.ctx, code)
        return if (why.isEmpty()) ok(JSONObject().put("region", Vpn.country)) else Hub.Res.err(502, why)
    }

    /** An app with a country (Settings > the app > VPN) switches to it when opened; any other app switches back. */
    fun vpnForApp(app: String) {
        if (!Vpn.configured(hub.ctx)) return
        val want = cc().optJSONObject("app_vpn")?.optString(app).orEmpty().lowercase()
        if (want.isNotEmpty()) {
            if (Vpn.country != want) { if (autoPrev == null) autoPrev = Vpn.country; Vpn.set(hub.ctx, want) }
        } else autoPrev?.let { prev -> autoPrev = null; if (Vpn.country != prev) Vpn.set(hub.ctx, prev) }
    }

    // =========================================================================================================
    // Moving over from the Mac (tools/move-to-chromecast.py), with the helper's key for this app
    // =========================================================================================================
    fun hubPost(r: Hub.Req): Hub.Res {
        val d = r.json
        return when (r.path) {
            "/api/hub/file" -> {   // one of the helper's files: {"name": "scenes.json" | "wallpapers/x.jpg" | …, "data": base64}
                val name = d.optString("name")
                require(Regex("^(config\\.json|admin\\.json|scenes\\.json|websites\\.json|quick-token|watch-stats\\.json|cc-apps\\.json|" +
                    "wallpapers/[A-Za-z0-9_.-]{1,120}\\.jpg|cc-icons/[A-Za-z0-9_.]{1,120}\\.png)$").matches(name)) { "not a file to move: $name" }
                val bytes = Base64.decode(d.optString("data"), Base64.DEFAULT)
                if (name == "config.json") store.save(JSONObject(String(bytes, Charsets.UTF_8)))
                else File(store.dir, name).apply { parentFile?.mkdirs(); writeBytes(bytes) }
                ok()
            }
            "/api/hub/vpn" -> {   // the NordLynx key and which apps go through the VPN (nothing: all but this launcher)
                val e = Vpn.prefs(hub.ctx).edit()
                if (d.has("key")) e.putString("key", d.optString("key"))
                if (d.has("apps")) e.putString("apps", d.optString("apps"))
                e.apply(); log("vpn: settings moved over"); ok()
            }
            "/api/hub/mode" -> {   // Home uses this server from now on (or the Mac again)
                val on = d.optBoolean("on")
                hub.ctx.getSharedPreferences("home", android.content.Context.MODE_PRIVATE).edit().putBoolean("hub", on).remove("reported").apply()
                hub.saved = System.currentTimeMillis() / 1000
                log("hub: Home uses " + if (on) "this Chromecast's own server" else "the Mac's helper")
                ok()
            }
            else -> Hub.Res.err(404)
        }
    }

    // =========================================================================================================
    // The admin panel
    // =========================================================================================================
    fun login(r: Hub.Req): Hub.Res {
        if (!store.passwordSet()) {   // the first visit sets it (the helper set it from the command line)
            val pw = r.json.optString("password")
            if (pw.length < 6) return Hub.Res.err(401, "no password set: choose one of at least 6 characters")
            store.setPassword(pw); log("admin: the panel's password was set")
        } else if (!synchronized(this) { val okPw = store.checkPassword(r.json.optString("password")); if (!okPw) Thread.sleep(1000); okPw }) {
            log("admin: a wrong password"); return Hub.Res.err(401, "wrong password")
        }
        val t = store.newSession()
        return Hub.Res.json(JSONObject().put("ok", true), headers = mapOf("Set-Cookie" to "${Hub.COOKIE}=$t; Path=/; Max-Age=${30 * 86400}; HttpOnly; SameSite=Strict"))
    }

    fun adminGet(r: Hub.Req): Hub.Res {
        val p = r.path
        return when {
            p == "/api/admin/state" -> Hub.Res.json(state())
            p == "/api/admin/now-playing" -> Hub.Res.json(nowPlaying().put("sleep", sleepState()))
            p == "/api/admin/np-art" -> svc()?.art()?.takeIf { it != "-" }?.let { Hub.Res(200, "image/jpeg", Base64.decode(it, Base64.DEFAULT)) } ?: Hub.Res.err(404)
            p == "/api/admin/remote-status" -> Hub.Res.json(JSONObject().put("tv", JSONObject().put("on", JSONObject.NULL))
                .put("cc", JSONObject().put("linked", svc() != null).put("awake", true).put("why", if (svc() == null) "The launcher's service isn't running." else "")
                    .put("front", CursorService.front)).put("sleep", sleepState()))
            p == "/api/admin/stats" -> Hub.Res.json(statsReport(r.query["days"]?.toIntOrNull() ?: 7))
            p == "/api/admin/logs" -> Hub.Res.json(JSONObject().put("ok", true).put("lines", JSONArray(CursorService.logLines())))
            p.startsWith("/api/admin/icon/cc/") -> icon(p.substringAfterLast('/'))
            p.startsWith("/api/admin/wallpaper-thumb/") -> p.substringAfterLast('/').let { n -> File(content.walls, n.removeSuffix(".jpg") + ".thumb.jpg").takeIf { it.exists() && n in content.ownWalls() }
                ?.let { Hub.Res(200, "image/jpeg", it.readBytes()) } ?: Hub.Res.err(404) }
            p == "/api/admin/geocode" -> geocode(r.query["q"].orEmpty())
            p == "/api/admin/cc-screen" || p == "/api/admin/cc-live" -> helper("/api/hub/live?after=" + (r.query["after"] ?: "0"), null, 8000)?.let { (code, data) ->
                if (code == 200) Hub.Res(200, "image/jpeg", data, mapOf("X-Frame-At" to lastFrameAt)) else Hub.Res(code, "application/json; charset=utf-8", data) }
                ?: Hub.Res.err(409, "The live screen needs the laptop's helper (adb), and it isn't on.")
            else -> Hub.Res.err(404)
        }
    }

    fun adminPost(r: Hub.Req): Hub.Res {
        val d = r.json
        return when (r.path) {
            "/api/admin/logout" -> { store.endSession(r.cookie(Hub.COOKIE)); ok() }
            "/api/admin/save" -> try { save(d.optJSONObject("changes") ?: d); ok(JSONObject().put("restarting", false)) } catch (e: Exception) { Hub.Res.err(400, e.message ?: "") }
            "/api/admin/remote" -> remote(d)
            "/api/admin/launch" -> launch(d)
            "/api/admin/pointer" -> pointer(d.optJSONArray("ops") ?: JSONArray())
            "/api/admin/np-seek" -> { svc()?.cmd("seek ${maxOf(0, d.optInt("sec")) * 1000L}"); ok() }
            "/api/admin/sleep" -> try { ok(JSONObject().put("sleep", sleepSet(d.optString("set", "off")))) } catch (e: Exception) { Hub.Res.err(400, e.message ?: "") }
            "/api/admin/scenes" -> try { ok(JSONObject().put("scenes", saveList("scenes.json", d.optJSONArray("scenes"), ::cleanScene, 20))) } catch (e: Exception) { Hub.Res.err(400, e.message ?: "") }
            "/api/admin/scene-run" -> Hub.Res.json(runScene(d.optString("id")))
            "/api/admin/websites" -> try { saveList("websites.json", d.optJSONArray("websites"), ::cleanWebsite, 30)
                ok(JSONObject().put("websites", store.list("websites.json")).put("tv_apps", JSONArray()).put("cc", ccState())) } catch (e: Exception) { Hub.Res.err(400, e.message ?: "") }
            "/api/admin/quick-new" -> { store.quickToken(true); ok(JSONObject().put("quick_base", quickBase())) }
            "/api/admin/region" -> region(d.optString("region"))
            "/api/admin/action" -> ok()
            "/api/admin/addon-refresh" -> { content.refresh(d.optString("id"), true); ok(JSONObject().put("addons", content.status())) }
            "/api/admin/addons-reload" -> ok(JSONObject().put("addons", content.status()).put("broken", JSONObject()))
            "/api/admin/wallpaper-next" -> try { ok(JSONObject().put("now", content.wallpaperNext())) } catch (e: Exception) { Hub.Res.err(502, e.message ?: "") }
            "/api/admin/wallpaper-upload" -> try {
                val b = Base64.decode(d.optString("data").substringAfter(","), Base64.DEFAULT)
                ok(JSONObject().put("name", content.wallpaperUpload(d.optString("name", "photo.jpg"), b)).put("own", JSONArray(content.ownWalls())))
            } catch (e: Exception) { Hub.Res.err(400, e.message ?: "") }
            "/api/admin/wallpaper-delete" -> { val n = d.optString("name"); if (n in content.ownWalls()) { File(content.walls, n).delete(); File(content.walls, n.removeSuffix(".jpg") + ".thumb.jpg").delete() }
                ok(JSONObject().put("own", JSONArray(content.ownWalls()))) }
            "/api/admin/cc-connect" -> ok()
            "/api/admin/push-sub", "/api/admin/push-test", "/api/admin/push-off" -> Hub.Res.err(400, "Phone notifications need the laptop's helper for now.")
            else -> Hub.Res.err(404)
        }
    }

    // ---------- the panel's state ----------
    private fun editable(): JSONObject {
        val c = cfg(); val cc = cc()
        return JSONObject().put("greeting_name", c.optString("greeting_name")).put("clock_24h", c.optBoolean("clock_24h"))
            .put("live_refresh", c.optBoolean("live_refresh", true)).put("quotes", c.optBoolean("quotes", true)).put("wallpaper", c.optBoolean("wallpaper", true))
            .put("wallpaper_size", c.optString("wallpaper_size", "1920x1080")).put("weather", c.opt("weather") ?: JSONObject.NULL)
            .put("wallpaper_source", c.optString("wallpaper_source", "bing")).put("wallpaper_market", c.optString("wallpaper_market", "en-US"))
            .put("wallpaper_pick", c.optString("wallpaper_pick")).put("nasa_key_set", c.has("nasa_api_key"))
            .put("vpn_countries", c.optJSONArray("vpn_countries") ?: JSONArray()).put("home_takeover", false).put("addons", content.addonsConf())
            .put("home_layout", c.opt("home_layout") ?: JSONObject.NULL).put("takeover_on_power_on", false).put("power_on_delay_seconds", 5)
            .put("tv", JSONObject().put("dock", JSONArray()).put("rows", JSONArray()).put("rename", JSONObject()).put("hidden", JSONArray()).put("app_vpn", JSONObject()))
            .put("chromecast", JSONObject().put("dock", cc.optJSONArray("dock") ?: JSONArray()).put("rows", cc.optJSONArray("rows") ?: JSONArray())
                .put("rename", cc.optJSONObject("rename") ?: JSONObject()).put("hidden", cc.optJSONArray("hidden") ?: JSONArray()).put("app_vpn", cc.optJSONObject("app_vpn") ?: JSONObject()))
    }

    private fun ccState(): JSONObject {
        val cc = JSONObject(ccApps().toString())
        val apps = cc.optJSONArray("apps") ?: JSONArray().also { cc.put("apps", it) }
        for (i in 0 until apps.length()) apps.getJSONObject(i).let { it.put("icon", "/api/admin/icon/cc/${it.optString("id")}") }
        for (i in 0 until content.home().getJSONArray("tiles").length()) content.home().getJSONArray("tiles").getJSONObject(i).let {
            apps.put(JSONObject().put("id", it.optString("id")).put("name", it.optString("name")).put("emoji", it.optString("emoji")).put("color", it.optString("color")).put("banner", true)) }
        webTiles().forEach { apps.put(JSONObject().put("id", it.optString("id")).put("name", it.optString("name")).put("emoji", it.optString("emoji")).put("color", it.optString("color")).put("banner", true)) }
        return cc
    }

    fun state(): JSONObject {
        val vpnOn = Vpn.configured(hub.ctx)
        val countryNames = JSONObject()
        try { val all = Vpn.countries(hub.ctx); for (i in 0 until all.length()) all.getJSONObject(i).let { countryNames.put(it.optString("code"), it.optString("name")) } } catch (_: Exception) { }
        val wall = JSONObject().put("now", content.wallpaperMeta() ?: JSONObject.NULL).put("sources", Content.SOURCES).put("own", JSONArray(content.ownWalls()))
            .put("active", JSONObject().put("source", content.wallSource()).put("market", cfg().optString("wallpaper_market", "en-US")).put("pick", cfg().optString("wallpaper_pick")))
        return JSONObject().put("settings", editable()).put("tv_apps", JSONArray()).put("tv_removed", JSONArray()).put("cc", ccState())
            .put("cc_ip", hub.ownIp()).put("regions", regions()).put("region", if (vpnOn) Vpn.country else JSONObject.NULL)
            .put("vpn_server", if (Vpn.country != "off") Vpn.server else "").put("country_names", countryNames).put("tv_on", JSONObject.NULL)
            .put("started", hub.started).put("devices", JSONObject().put("tv", false).put("cc", true).put("vpn", vpnOn))
            .put("quick_base", quickBase()).put("scenes", store.list("scenes.json")).put("https", JSONObject().put("on", false))
            .put("websites", store.list("websites.json")).put("addons", content.status()).put("addons_broken", JSONObject())
            .put("layout_spec", spec).put("preview", JSONObject().put("widgets", content.home().getJSONArray("widgets")).put("quote", content.quote() ?: JSONObject.NULL).put("weather", content.weather() ?: JSONObject.NULL))
            .put("wallpaper", wall).put("hub", true)
    }

    // ---------- saving (helper/admin.py apply(), for this Chromecast's settings) ----------
    @Synchronized fun save(changes: JSONObject) {
        val c = store.configCopy()
        for (key in changes.keys()) {
            val v = changes.get(key)
            when (key) {
                "greeting_name" -> c.put(key, v.toString().trim().take(40))
                "clock_24h", "quotes", "wallpaper", "live_refresh", "takeover_on_power_on" -> c.put(key, v == true)
                "wallpaper_size" -> { require(v == "1920x1080" || v == "UHD") { "wallpaper size must be 1920x1080 or UHD" }; c.put(key, v) }
                "wallpaper_source" -> { require(v in listOf("bing", "wikimedia", "nasa", "own")) { "unknown wallpaper source" }; c.put(key, v) }
                "wallpaper_market" -> { require(Regex("^[a-z]{2}-[A-Z]{2}$").matches(v.toString())) { "bad Bing region" }; c.put(key, v) }
                "wallpaper_pick" -> { val s = if (v == JSONObject.NULL) "" else v.toString(); require(s.isEmpty() || Regex("^[A-Za-z0-9_.-]{1,80}\\.jpg$").matches(s)) { "bad photo name" }; c.put(key, s) }
                "nasa_api_key" -> { val s = v.toString().trim(); if (s.isEmpty()) c.remove(key) else { require(Regex("^[A-Za-z0-9]{20,60}$").matches(s)) { "that doesn't look like a NASA API key" }; c.put(key, s) } }
                "nasa_key_set", "home_takeover", "power_on_delay_seconds", "tv" -> { }
                "home_layout" -> c.put(key, validateLayout(v as JSONObject))
                "addons" -> { c.put(key, validateAddons(v as JSONObject)); c.remove("quotes"); c.remove("weather") }
                "weather" -> if (v == JSONObject.NULL) c.remove("weather") else (v as JSONObject).let { c.put("weather", JSONObject().put("city", it.optString("city").take(60)).put("lat", it.getDouble("lat")).put("lon", it.getDouble("lon"))) }
                "vpn_countries" -> { val a = v as JSONArray; val codes = LinkedHashSet<String>(); for (i in 0 until a.length()) codes.add(a.getString(i).lowercase())
                    require(codes.size <= 30 && codes.all { Regex("^[a-z]{2}$").matches(it) }) { "countries are 2-letter codes" }; c.put(key, JSONArray(codes.toList())) }
                "chromecast" -> {
                    val lay = appLayout(v as JSONObject, dockMax((changes.optJSONObject("home_layout") ?: c.optJSONObject("home_layout"))))
                    val cc = c.optJSONObject("chromecast") ?: JSONObject().also { c.put("chromecast", it) }
                    lay.keys().forEach { cc.put(it, lay.get(it)) }
                    cc.put("rev", System.currentTimeMillis() / 1000)
                }
                else -> throw IllegalArgumentException("$key can't be changed here")
            }
        }
        store.save(c)
        hub.saved = System.currentTimeMillis() / 1000
        log("admin: settings saved")
    }

    private fun ids(a: JSONArray?, limit: Int = 300): JSONArray {
        val out = LinkedHashSet<String>()
        if (a != null) for (i in 0 until minOf(a.length(), limit)) { val s = a.get(i).toString().trim(); require(s.isNotEmpty() && s.length <= 120) { "bad app id $s" }; out.add(s) }
        return JSONArray(out.toList())
    }

    private fun appLayout(v: JSONObject, dockMax: Int): JSONObject {
        val rows = JSONArray()
        v.optJSONArray("rows")?.let { for (i in 0 until minOf(it.length(), 20)) it.getJSONObject(i).let { r -> rows.put(JSONObject().put("title", r.optString("title").trim().take(40)).put("apps", ids(r.optJSONArray("apps")))) } }
        val rename = JSONObject()
        v.optJSONObject("rename")?.let { o -> o.keys().forEach { k -> o.optString(k).trim().take(40).takeIf { it.isNotEmpty() }?.let { rename.put(k.take(120), it) } } }
        val appVpn = JSONObject()
        v.optJSONObject("app_vpn")?.let { o -> o.keys().forEach { k -> val c = o.optString(k).lowercase(); require(Regex("^[a-z]{2}$").matches(c)) { "bad country $c" }; appVpn.put(k.take(120), c) } }
        return JSONObject().put("dock", ids(v.optJSONArray("dock"), dockMax)).put("rows", rows).put("rename", rename).put("hidden", ids(v.optJSONArray("hidden"))).put("app_vpn", appVpn)
    }

    private fun dockMax(lay: JSONObject?): Int {
        val size = lay?.optString("dock").orEmpty()
        val ts = spec.getJSONObject("tile_sizes")
        return (ts.optJSONArray(size) ?: ts.getJSONArray("big")).getInt(1)
    }

    /** helper/layout.py validate(): known blocks, allowed sizes, inside the grid, no overlaps. */
    private fun validateLayout(v: JSONObject): JSONObject {
        val blocks = spec.getJSONObject("blocks"); val cols = spec.getInt("cols"); val rowsN = spec.getInt("rows"); val res = spec.getJSONObject("reserved")
        val styles = spec.getJSONArray("clock_styles").let { a -> (0 until a.length()).map { a.getJSONArray(it).getString(0) } }
        val widgetBlocks = spec.getJSONArray("widget_blocks").let { a -> (0 until a.length()).map { a.getString(it) } }
        val vb = v.optJSONObject("blocks") ?: throw IllegalArgumentException("bad layout")
        val out = JSONObject()
        for (bid in vb.keys()) {
            val meta = blocks.optJSONObject(bid) ?: throw IllegalArgumentException("unknown block $bid")
            val b = vb.getJSONObject(bid)
            val x = b.getInt("x"); val y = b.getInt("y"); val w = b.getInt("w"); val h = b.getInt("h"); val on = b.optBoolean("on", true)
            val name = meta.optString("name")
            meta.optJSONObject("resize")?.getJSONArray("min")?.let { require(w >= it.getInt(0) && h >= it.getInt(1)) { "$name: at least ${it.getInt(0)} columns by ${it.getInt(1)} row" } }
            require(x >= 0 && x + w <= cols && y >= 0 && y + h <= rowsN) { "$name is outside the grid" }
            val o = JSONObject().put("x", x).put("y", y).put("w", w).put("h", h).put("on", on)
            if (bid == "clock") {
                o.put("style", b.optString("style", "classic").takeIf { it in styles } ?: "classic")
                val rot = b.optJSONArray("rotate"); o.put("rotate", JSONArray(styles.filter { s -> rot != null && (0 until rot.length()).any { rot.optString(it) == s } }))
                o.put("weather", b.optBoolean("weather"))
            }
            if (bid in widgetBlocks) o.put("addons", JSONArray(b.optJSONArray("addons")?.let { a -> (0 until minOf(a.length(), 20)).map { a.optString(it) }.filter { Regex("^[a-z0-9][a-z0-9-]{0,40}$").matches(it) } } ?: emptyList<String>()))
            out.put(bid, o)
        }
        fun ov(a: JSONObject, b: JSONObject) = a.getInt("x") < b.getInt("x") + b.getInt("w") && b.getInt("x") < a.getInt("x") + a.getInt("w") &&
            a.getInt("y") < b.getInt("y") + b.getInt("h") && b.getInt("y") < a.getInt("y") + a.getInt("h")
        val shown = out.keys().asSequence().filter { out.getJSONObject(it).optBoolean("on") }.toList()
        for (k in shown) require(!ov(out.getJSONObject(k), res)) { "${blocks.getJSONObject(k).optString("name")} is over the buttons' corner" }
        for (i in shown.indices) for (j in i + 1 until shown.size) require(!ov(out.getJSONObject(shown[i]), out.getJSONObject(shown[j]))) {
            "${blocks.getJSONObject(shown[i]).optString("name")} and ${blocks.getJSONObject(shown[j]).optString("name")} overlap" }
        val classic = spec.getJSONObject("presets").getJSONObject("classic").getJSONObject("blocks")
        for (bid in blocks.keys()) if (!out.has(bid)) out.put(bid, JSONObject(classic.getJSONObject(bid).toString()).put("on", false))
        val res2 = JSONObject().put("blocks", out).put("preset", v.optString("preset").take(30)).put("dock_full", v.optBoolean("dock_full"))
        for (k in listOf("dock", "rows")) { val s = v.optString(k).ifEmpty { "big" }; require(spec.getJSONObject("tile_sizes").has(s)) { "bad $k size" }; res2.put(k, s) }
        return res2
    }

    /** cyt_helper.validate_addons(): each add-on's settings checked against its own list. */
    private fun validateAddons(v: JSONObject): JSONObject {
        val out = JSONObject()
        out.put("order", JSONArray(v.optJSONArray("order")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it in content.manifests } } ?: emptyList<String>()))
        if (v.has("widget_seconds")) { val n = v.getInt("widget_seconds"); require(n in 4..120) { "widgets change every 4 to 120 seconds" }; out.put("widget_seconds", n) }
        for (id in v.keys()) {
            if (id == "order" || id == "widget_seconds") continue
            val m = content.manifests[id] ?: continue
            val conf = v.optJSONObject(id) ?: continue
            val given = conf.optJSONObject("settings") ?: JSONObject()
            val clean = JSONObject()
            m.optJSONArray("settings")?.let { specs -> for (i in 0 until specs.length()) {
                val sp = specs.getJSONObject(i); val key = sp.optString("key"); if (!given.has(key)) continue
                val value = given.get(key)
                clean.put(key, when (sp.optString("type", "text")) {
                    "number" -> value.toString().let { if ("." in it) it.toDouble() else it.toDouble().toLong() }
                    "bool" -> value == true
                    "select" -> { val opts = sp.optJSONArray("options"); val ok = opts != null && (0 until opts.length()).any { opts.getJSONArray(it).get(0).toString() == value.toString() }
                        require(ok) { "$id: $key must be one of its choices" }; value.toString() }
                    "lines" -> { val ls = value.toString().replace("\r", "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                        require(ls.size <= 500) { "$id: at most 500 lines" }; ls.joinToString("\n") { it.take(400) } }
                    "place" -> if (value == JSONObject.NULL || value == "") JSONObject.NULL else (value as JSONObject).let { JSONObject().put("city", it.optString("city").take(60)).put("lat", it.getDouble("lat")).put("lon", it.getDouble("lon")) }
                    else -> value.toString().take(500)
                })
            } }
            out.put(id, JSONObject().put("enabled", conf.optBoolean("enabled")).put("settings", clean))
        }
        return out
    }

    private fun geocode(q: String): Hub.Res = try {
        val d = content.json("https://geocoding-api.open-meteo.com/v1/search?count=6&language=en&name=" + java.net.URLEncoder.encode(q.take(60), "UTF-8")) as JSONObject
        val res = d.optJSONArray("results") ?: JSONArray()
        Hub.Res.json(JSONObject().put("places", JSONArray((0 until res.length()).map { res.getJSONObject(it) }.map { p ->
            JSONObject().put("city", p.optString("name")).put("country", p.optString("country")).put("admin", p.optString("admin1")).put("lat", p.optDouble("latitude")).put("lon", p.optDouble("longitude")) })))
    } catch (e: Exception) { Hub.Res.err(502, e.message ?: "") }

    // =========================================================================================================
    // Remote, apps, pointer, Now playing (CursorService does them on this Chromecast)
    // =========================================================================================================
    private val ccKeys = setOf("up", "down", "left", "right", "ok", "back", "home", "menu", "volup", "voldown", "mute", "play", "pause", "power", "wake", "prev", "next", "rew", "ff", "stop", "settings")

    private fun remote(d: JSONObject): Hub.Res {
        if (d.optString("device") == "tv") return Hub.Res.err(400, "The TV is controlled by the laptop's helper.")
        if (d.has("text")) return helper("/api/hub/text", JSONObject().put("text", d.optString("text")))?.let { (code, data) -> Hub.Res(code, "application/json; charset=utf-8", data) }
            ?: Hub.Res.json(JSONObject().put("ok", false).put("error", "Typing on the Chromecast needs the laptop's helper (adb), and it isn't on."))
        val k = d.optString("key")
        if (k !in ccKeys) return Hub.Res.err(400, "no such key")
        val s = svc() ?: return Hub.Res.json(JSONObject().put("ok", false).put("error", "The launcher's service isn't running."))
        // Arrows and OK in another app: Android takes them only through adb, so the laptop's helper sends them when it's
        // on; Home in front takes them itself; Back, Home, volume and media work from here either way.
        if (k in setOf("up", "down", "left", "right", "ok") && !CursorService.homeInFront())
            helper("/api/hub/text", JSONObject().put("key", k))?.let { (code, _) -> if (code == 200) return ok() }
        s.cmd(if (k == "settings") "o com.android.tv.settings" else "k $k")
        return ok()
    }

    private fun launch(d: JSONObject): Hub.Res {
        val s = svc() ?: return Hub.Res.json(JSONObject().put("ok", false).put("error", "The launcher's service isn't running."))
        var app = d.optString("app"); var url = d.optString("url")
        val web = when { app.startsWith("addon:web-") -> app.removePrefix("addon:web-"); app.startsWith("web-") -> app.removePrefix("web-"); else -> "" }
        if (web.isNotEmpty()) { url = website(web)?.optString("url") ?: return Hub.Res.err(400, "no such website"); app = "" }
        if (url.isNotEmpty()) {
            if (!Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(url)) url = "https://$url"
            val pkg = webBrowser()
            s.cmd(if (web.isNotEmpty() && pkg.isNotEmpty()) "u2 $pkg $url" else "u $url")
            return ok()
        }
        if (app.startsWith("addon:")) return Hub.Res.err(400, "Open it from the Chromecast's Home.")
        if (!Regex("^[A-Za-z0-9_.]+$").matches(app)) return Hub.Res.err(400, "the Chromecast has no such app")
        vpnForApp(app)
        s.cmd("o $app")
        return ok()
    }

    // ---------- the laptop's helper, for what only adb can do (when it's on): the trackpad's taps and arrows, typing, the live screen ----------
    @Volatile private var helperDownUntil = 0L

    /** A request to the helper with this app's key; null when it isn't there (then not asked again for 30 s). */
    private fun helper(path: String, body: JSONObject?, timeout: Int = 6000): Pair<Int, ByteArray>? {
        if (System.currentTimeMillis() < helperDownUntil) return null
        return try {
            val h = java.net.URL(BuildConfig.HELPER + path).openConnection() as java.net.HttpURLConnection
            h.connectTimeout = 1500; h.readTimeout = timeout
            h.setRequestProperty("X-Token", svc()?.token().orEmpty())
            if (body != null) { h.requestMethod = "POST"; h.doOutput = true; h.setRequestProperty("Content-Type", "application/json")
                h.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = h.responseCode
            val data = (if (code < 400) h.inputStream else h.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            val at = h.getHeaderField("X-Frame-At")
            h.disconnect()
            if (at != null) lastFrameAt = at
            Pair(code, data)
        } catch (e: Exception) { helperDownUntil = System.currentTimeMillis() + 30_000; null }
    }
    @Volatile private var lastFrameAt = "0"

    private fun pointer(ops: JSONArray): Hub.Res {
        helper("/api/hub/pointer", JSONObject().put("ops", ops))?.let { (code, data) -> if (code == 200) return Hub.Res(200, "application/json; charset=utf-8", data) }
        val s = svc() ?: return Hub.Res.json(JSONObject().put("ok", false).put("error", "The launcher's service isn't running."))
        for (i in 0 until minOf(ops.length(), 200)) {
            val op = ops.optJSONArray(i) ?: continue
            when (op.optString(0)) {
                "m" -> s.cmd("m ${op.optDouble(1)} ${op.optDouble(2)}")
                // Google TV takes taps only through adb: the laptop's helper taps at the pointer when it's on; else OK
                "c", "l" -> { val x = s.x; val y = s.y; val long = op.optString(0) == "l"
                    Thread { if (!helperTap(x, y, long)) s.cmd("k ok") }.start() }
                "s" -> { val dy = op.optDouble(2); if (Math.abs(dy) > 40) s.cmd(if (dy < 0) "k down" else "k up") }
                "h" -> s.cmd("h")
            }
        }
        return ok(JSONObject().put("mode", "pointer"))
    }

    /** A tap at (x, y) by the laptop's helper over adb (it checks the key it gave this app). False when it's not there. */
    private fun helperTap(x: Float, y: Float, long: Boolean): Boolean = try {
        val h = java.net.URL(BuildConfig.HELPER + "/api/hub/tap").openConnection() as java.net.HttpURLConnection
        h.connectTimeout = 1500; h.readTimeout = 4000; h.requestMethod = "POST"; h.doOutput = true
        h.setRequestProperty("Content-Type", "application/json"); h.setRequestProperty("X-Token", svc()?.token().orEmpty())
        h.outputStream.use { it.write(JSONObject().put("x", Math.round(x)).put("y", Math.round(y)).put("long", long).toString().toByteArray()) }
        val okTap = h.responseCode == 200; h.disconnect(); okTap
    } catch (e: Exception) { false }

    fun nowPlaying(): JSONObject {
        val raw = svc()?.nowPlaying() ?: return JSONObject().put("ok", false).put("error", "The launcher's service isn't running.")
        val j = JSONObject(raw)
        if (j.has("error")) return JSONObject().put("ok", false).put("error", "The launcher may not read what's playing yet.")
        if (!j.optBoolean("playing")) return JSONObject().put("ok", true).put("playing", JSONObject.NULL)
        val app = j.optString("app")
        val name = ccApps().optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == app }?.optString("name") } ?: app.substringAfterLast('.')
        return JSONObject().put("ok", true).put("via", "launcher").put("playing", JSONObject().put("app", app).put("state", j.optString("state"))
            .put("title", j.optString("title")).put("subtitle", j.optString("subtitle")).put("position", j.optLong("position") / 1000)
            .put("duration", j.optLong("duration") / 1000).put("art", j.optBoolean("art")).put("name", name)
            .put("icon", "/api/admin/icon/cc/$app").put("screen_icon", "/api/cc-icon/$app"))
    }

    // =========================================================================================================
    // Sleep timer, scenes, websites, phone shortcuts
    // =========================================================================================================
    @Volatile private var sleepAt = 0L
    @Volatile private var sleepEnd = false
    @Volatile private var sleepV = ""
    private var sleepSeen = false; private var sleepSince = 0L
    private var sleepThread: Thread? = null

    fun sleepState(): JSONObject = JSONObject().put("on", sleepAt > 0).put("end", sleepEnd && sleepAt > 0)
        .put("left", if (sleepAt > 0) maxOf(0, (sleepAt - System.currentTimeMillis()) / 1000) else 0).put("v", if (sleepAt > 0) sleepV else "off")

    fun sleepSet(v: String): JSONObject {
        val s = v.trim().lowercase()
        when {
            s == "off" || s == "0" || s.isEmpty() -> { sleepAt = 0; sleepEnd = false; log("sleep timer: off"); return sleepState() }
            s == "end" -> { sleepAt = System.currentTimeMillis() + 4 * 3600_000; sleepEnd = true; sleepSeen = false; sleepSince = 0; sleepV = "end" }
            else -> { val m = s.toIntOrNull() ?: throw IllegalArgumentException("minutes, end or off"); require(m in 1..600) { "1 to 600 minutes" }
                sleepAt = System.currentTimeMillis() + m * 60_000L; sleepEnd = false; sleepV = m.toString() }
        }
        log("sleep timer: " + if (sleepEnd) "when the video ends" else "in $s min")
        if (sleepThread?.isAlive != true) sleepThread = Thread({
            while (sleepAt > 0) {
                Thread.sleep(10_000)
                if (sleepAt == 0L) break
                val now = System.currentTimeMillis()
                if (sleepEnd) {
                    val p = nowPlaying().optJSONObject("playing")
                    if (p != null && p.optString("state") == "playing") { sleepSeen = true; sleepSince = 0 }
                    else if (sleepSeen) { if (sleepSince == 0L) sleepSince = now; if (now - sleepSince >= 90_000) sleepAt = now }
                }
                if (sleepAt in 1..now) { sleepAt = 0; sleepEnd = false; log("sleep timer: turning off"); svc()?.cmd("k power") }
            }
        }, "sleep").also { it.start() }
        return sleepState()
    }

    private fun cleanScene(o: JSONObject, ids: MutableSet<String>): JSONObject {
        val name = o.optString("name").trim().take(40)
        var id = o.optString("id").ifEmpty { name }.lowercase().replace(Regex("[^a-z0-9]+"), "").take(24).ifEmpty { "scene" }
        val base = id; var n = 2
        while (!ids.add(id)) id = "$base${n++}"
        val stepRe = Regex("^((tv|cc)-[a-z]+|(tv|cc)-open-[A-Za-z0-9_.:-]+|vpn-[a-z]+|wait-\\d{1,2}|sleep-(\\d{1,3}|end|off)|scene-[a-z0-9]+)$")
        val steps = o.optJSONArray("steps")?.let { a -> (0 until minOf(a.length(), 12)).map { a.optString(it) } }.orEmpty()
        require(name.isNotEmpty()) { "A scene needs a name" }
        require(steps.isNotEmpty()) { "$name: needs a step" }
        steps.firstOrNull { !stepRe.matches(it) }?.let { throw IllegalArgumentException("$name: unknown step $it") }
        return JSONObject().put("id", id).put("name", name).put("icon", o.optString("icon").take(4)).put("steps", JSONArray(steps))
    }

    private fun cleanWebsite(o: JSONObject, ids: MutableSet<String>): JSONObject {
        val name = o.optString("name").trim().take(40)
        var url = o.optString("url").trim()
        if (url.isNotEmpty() && !Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(url)) url = "https://$url"
        require(name.isNotEmpty() && Regex("^https?://[^/\\s]+", RegexOption.IGNORE_CASE).containsMatchIn(url)) { "${name.ifEmpty { "A website" }}: needs a name and a web address" }
        var id = o.optString("id").ifEmpty { name }.lowercase().replace(Regex("[^a-z0-9]+"), "").take(24).ifEmpty { "site" }
        val base = id; var n = 2
        while (!ids.add(id)) id = "$base${n++}"
        val colors = listOf("#2a78d6", "#1baf7a", "#6250d6", "#eb6834", "#e87ba4", "#008300", "#eda100")
        return JSONObject().put("id", id).put("name", name).put("url", url).put("tv", false).put("cc", o.optBoolean("cc", true))
            .put("color", o.optString("color").ifEmpty { colors[ids.size % colors.size] }.take(9))
    }

    private fun saveList(file: String, v: JSONArray?, clean: (JSONObject, MutableSet<String>) -> JSONObject, max: Int): JSONArray {
        require(v != null && v.length() <= max) { "at most $max" }
        val ids = HashSet<String>()
        val out = JSONArray((0 until v.length()).map { clean(v.getJSONObject(it), ids) })
        store.writeText(file, out.toString(1))
        return out
    }

    private fun website(id: String) = store.list("websites.json").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == id } }
    private fun webTiles() = store.list("websites.json").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("cc", true) }
        .map { JSONObject().put("id", "addon:web-" + it.optString("id")).put("name", it.optString("name")).put("emoji", "🌐").put("color", it.optString("color")).put("kind", "action") } }

    private fun webBrowser(): String {
        cc().optString("web_browser").takeIf { it.isNotEmpty() }?.let { return it }
        val have = ccApps().optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("id") }.toSet() }.orEmpty()
        return listOf("com.tcl.browser", "com.cloudmosa.puffinTV", "com.android.chrome").firstOrNull { it in have } ?: ""
    }

    fun runScene(id: String): JSONObject {
        val sc = store.list("scenes.json").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == id } }
            ?: return JSONObject().put("ok", false).put("error", "No such scene.")
        Thread {
            val steps = sc.getJSONArray("steps")
            for (i in 0 until steps.length()) {
                val st = steps.getString(i)
                try {
                    if (st.startsWith("wait-")) { Thread.sleep(st.removePrefix("wait-").toLong() * 1000); continue }
                    quickAction(st)
                } catch (e: Exception) { log("scene ${sc.optString("name")}: $st: $e") }
                Thread.sleep(if (st == "cc-wake") 5000 else 1200)
            }
            log("scene ${sc.optString("name")}: done")
        }.start()
        return JSONObject().put("ok", true).put("said", "${sc.optString("name")}: on its way.")
    }

    private fun quickBase() = "http://${hub.ownIp()}:${Hub.PORT}/q/${store.quickToken()}/"

    fun quickAction(action: String): JSONObject {
        val dev = action.substringBefore('-'); val rest = action.substringAfter('-', "")
        return when {
            dev == "scene" -> runScene(rest)
            dev == "sleep" -> sleepSet(rest).let { s -> JSONObject().put("ok", true).put("said", if (!s.optBoolean("on")) "Sleep timer off." else if (s.optBoolean("end")) "Turning off when the video ends." else "Turning off in ${s.optLong("left") / 60} min.") }
            dev == "vpn" -> { val why = Vpn.set(hub.ctx, rest); if (why.isEmpty()) JSONObject().put("ok", true).put("said", "VPN: $rest") else JSONObject().put("ok", false).put("error", why) }
            dev == "cc" && rest.startsWith("open-") -> { launch(JSONObject().put("app", rest.removePrefix("open-"))); JSONObject().put("ok", true).put("said", "Opening on the Chromecast.") }
            dev == "cc" && rest in ccKeys -> { svc()?.cmd("k $rest"); JSONObject().put("ok", true).put("said", "$rest on the Chromecast.") }
            dev == "tv" -> JSONObject().put("ok", false).put("error", "The TV is controlled by the laptop's helper.")
            else -> JSONObject().put("ok", false).put("error", "No such button.")
        }
    }

    fun quick(r: Hub.Req): Hub.Res {
        val parts = r.path.split("/", limit = 4)   // "", q, token, action
        if (parts.size < 4 || !java.security.MessageDigest.isEqual(parts[2].toByteArray(), store.quickToken().toByteArray())) return Hub.Res.err(403)
        val res = try { quickAction(parts[3].trim('/')) } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message) }
        log("shortcut ${parts[3]}: ${res.optString("said", res.optString("error"))}")
        return Hub.Res.text(if (res.optBoolean("ok")) res.optString("said", "Done.") else "Didn't work: " + res.optString("error"), if (res.optBoolean("ok")) 200 else 502)
    }

    // =========================================================================================================
    // Usage: what played on this Chromecast, per day (data/watch-stats.json's shape; the TV's part is the helper's)
    // =========================================================================================================
    private val statsLock = Any()

    private fun statsLoop() {
        var last = System.currentTimeMillis()
        while (true) {
            Thread.sleep(30_000)
            val now = System.currentTimeMillis(); val gap = minOf(now - last, 60_000) / 1000.0; last = now
            try {
                val p = nowPlaying().optJSONObject("playing") ?: continue
                if (p.optString("state") != "playing") continue
                synchronized(statsLock) {
                    val all = (store.readJson("watch-stats.json") as? JSONObject) ?: JSONObject().put("days", JSONObject())
                    val days = all.getJSONObject("days")
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                    val d = days.optJSONObject(today) ?: JSONObject().put("apps", JSONObject()).put("titles", JSONObject()).also { days.put(today, it) }
                    val app = p.optString("app")
                    d.getJSONObject("apps").put(app, Math.round(d.getJSONObject("apps").optDouble(app, 0.0) + gap))
                    val title = p.optString("title").trim()
                    if (title.isNotEmpty()) {
                        val k = "$app|$title".take(300)
                        val t = d.getJSONObject("titles").optJSONObject(k) ?: JSONObject().put("app", app).put("title", title.take(200)).put("sub", p.optString("subtitle").take(120)).put("secs", 0).also { d.getJSONObject("titles").put(k, it) }
                        t.put("secs", Math.round(t.optDouble("secs") + gap)).put("last", now / 1000)
                    }
                    days.keys().asSequence().toList().sorted().dropLast(90).forEach { days.remove(it) }
                    store.writeText("watch-stats.json", all.toString())
                }
            } catch (e: Exception) { log("watch stats: $e") }
        }
    }

    private fun statsReport(n: Int): JSONObject {
        val days = maxOf(1, minOf(90, n))
        val all = synchronized(statsLock) { (store.readJson("watch-stats.json") as? JSONObject)?.optJSONObject("days") ?: JSONObject() }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val want = (days - 1 downTo 0).map { fmt.format(Date(System.currentTimeMillis() - it * 86_400_000L)) }
        val names = ccApps().optJSONArray("apps")?.let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { o -> o.optString("id") to o.optString("name") } } }.orEmpty()
        val rename = cc().optJSONObject("rename")
        val apps = HashMap<String, Long>(); val titles = HashMap<String, JSONObject>(); val perDay = JSONArray()
        for (d in want) {
            val o = all.optJSONObject(d)
            val a = o?.optJSONObject("apps") ?: JSONObject()
            var sum = 0L
            a.keys().forEach { k -> val v = a.optLong(k); sum += v; apps[k] = (apps[k] ?: 0) + v }
            perDay.put(JSONObject().put("day", d).put("secs", sum))
            o?.optJSONObject("titles")?.let { t -> t.keys().forEach { k -> val x = t.getJSONObject(k); val y = titles.getOrPut(k) { JSONObject(x.toString()).put("secs", 0) }; y.put("secs", y.optLong("secs") + x.optLong("secs")) } }
        }
        fun nm(id: String) = rename?.optString(id)?.takeIf { it.isNotEmpty() } ?: names[id] ?: id.substringAfterLast('.')
        val appList = apps.entries.filter { it.value >= 60 }.sortedByDescending { it.value }.map { JSONObject().put("app", it.key).put("name", nm(it.key)).put("secs", it.value).put("icon", "/api/admin/icon/cc/${it.key}") }
        val top = titles.values.sortedByDescending { it.optLong("secs") }.take(15).filter { it.optLong("secs") >= 60 }.map { it.put("name", nm(it.optString("app"))) }
        val total = apps.values.sum()
        val allDays = JSONArray((0 until perDay.length()).map { perDay.getJSONObject(it).let { d -> JSONObject().put("day", d.getString("day")).put("tv", 0).put("cc", d.getLong("secs")) } })
        return JSONObject().put("days", days).put("total", total).put("apps", JSONArray(appList)).put("per_day", perDay).put("titles", JSONArray(top))
            .put("tv", JSONObject().put("on", false).put("total", 0).put("per_day", JSONArray()).put("screens", JSONArray()))
            .put("all", JSONObject().put("total", total).put("per_day", allDays).put("items", JSONArray(appList.map { JSONObject(it.toString()).put("dev", "cc") })))
            .put("cc", true)
    }
}
