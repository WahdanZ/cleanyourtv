package com.cleanyourtv.home

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Home layouts ("experiences"), as helper/experience.py has them: which one each screen shows, its options,
 * and the scenes allowed on Home. The list is shared/experiences/catalog.js (its JSON part), built into the app as
 * assets/experiences/catalog.js, the same file the screens and the phone panel use.
 */
class Experience(catalogJs: String) {
    private val catalog: JSONObject = JSONObject(catalogJs.substring(catalogJs.indexOf("/*JSON*/") + 8, catalogJs.indexOf("/*END*/")))
    private val byId: Map<String, JSONObject> = catalog.getJSONArray("experiences").let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { e -> e.getString("id") to e } } }
    val ids: Set<String> get() = byId.keys
    fun info(id: String): JSONObject? = byId[id]
    val common: JSONArray get() = catalog.getJSONArray("common")

    /** A layout's options with the catalog's defaults filled in (what engine.js does for the TV). */
    fun options(id: String, given: JSONObject?): JSONObject {
        val out = JSONObject()
        val e = byId[id] ?: return out
        val all = (e.optJSONArray("options") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } +
            common.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        for (o in all) { val k = o.getString("key"); out.put(k, given?.opt(k) ?: o.opt("def") ?: JSONObject.NULL) }
        if (e.has("variantOf") || id == "scenes") out.put("variant", id)
        return out
    }
    private val idRe = Regex("^[A-Za-z0-9_.:-]{1,120}$")

    private fun option(o: JSONObject, v: Any?): Any? = when (o.getString("type")) {
        "choice" -> o.getJSONArray("choices").let { c -> (0 until c.length()).map { c.getJSONArray(it).getString(0) } }.firstOrNull { it == v?.toString() }
        "text" -> v?.toString()?.replace(Regex("\\s+"), " ")?.trim()?.take(o.optInt("max", 40))?.ifEmpty { null }
        "app" -> v?.toString()?.takeIf { it.isEmpty() || idRe.matches(it) } ?: ""
        "groups" -> (v as? JSONObject)?.let { g -> JSONObject().apply {
            for (k in listOf("watch", "play", "listen")) put(k, JSONArray((g.optJSONArray(k) ?: JSONArray()).let { a -> (0 until minOf(a.length(), 60)).map { a.optString(it) }.filter { idRe.matches(it) } }))
        } }
        else -> null
    }

    /** helper/experience.py validate(): unknown layouts are an error, unknown options are dropped. */
    fun validate(v: JSONObject): JSONObject {
        val out = JSONObject().put("tv", "classic").put("cc", "classic").put("opts", JSONObject()).put("scenes", JSONArray())
        for (dev in listOf("tv", "cc")) if (v.has(dev) && !v.isNull(dev)) {
            val id = v.getString(dev)
            require(id in byId) { "unknown Home layout $id" }
            out.put(dev, id)
        }
        val opts = v.optJSONObject("opts") ?: JSONObject()
        for (eid in opts.keys()) {
            val e = byId[eid] ?: continue
            val given = opts.optJSONObject(eid) ?: continue
            val kept = JSONObject()
            val all = (e.optJSONArray("options") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } +
                catalog.getJSONArray("common").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            for (o in all) if (given.has(o.getString("key")) && o.getString("type") != "scenes") option(o, given.opt(o.getString("key")))?.let { kept.put(o.getString("key"), it) }
            if (kept.length() > 0) out.getJSONObject("opts").put(eid, kept)
        }
        val sc = v.optJSONArray("scenes") ?: JSONArray()
        out.put("scenes", JSONArray((0 until minOf(sc.length(), 20)).map { sc.optString(it) }.filter { Regex("^[a-z0-9]{1,24}$").matches(it) }))
        return out
    }

    fun current(cfg: JSONObject): JSONObject {
        val h = cfg.optJSONObject("home_experience") ?: return validate(JSONObject())
        return try { validate(h) } catch (e: Exception) {
            val c = JSONObject(h.toString())
            for (dev in listOf("tv", "cc")) if (c.optString(dev) !in byId) c.put(dev, "classic")
            validate(c)
        }
    }

    fun forScreen(cfg: JSONObject, dev: String): JSONObject = current(cfg).let {
        JSONObject().put("id", it.getString(dev)).put("opts", it.getJSONObject("opts")).put("api", catalog.getInt("version"))
    }

    fun sceneAllowed(cfg: JSONObject, id: String): Boolean = current(cfg).getJSONArray("scenes").let { a -> (0 until a.length()).any { a.getString(it) == id } }

    /** The scenes allowed on Home, for the screens: id, name, icon and the steps in words (never the raw steps). */
    fun homeScenes(cfg: JSONObject, scenes: JSONArray, names: Map<String, String>): JSONArray {
        val allowed = current(cfg).getJSONArray("scenes").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val out = JSONArray()
        for (i in 0 until scenes.length()) {
            val s = scenes.getJSONObject(i)
            if (s.optString("id") !in allowed) continue
            val steps = s.optJSONArray("steps") ?: JSONArray()
            out.put(JSONObject().put("id", s.optString("id")).put("name", s.optString("name")).put("icon", s.optString("icon"))
                .put("summary", summary((0 until steps.length()).map { steps.getString(it) }, names)))
        }
        return out
    }

    private val words = mapOf("cc-wake" to "Wake the Chromecast", "cc-sleep" to "Chromecast to sleep", "tv-on" to "TV on", "tv-off" to "TV off",
        "vpn-off" to "VPN off", "sleep-off" to "Sleep timer off", "sleep-end" to "Sleep at the end")

    fun summary(steps: List<String>, names: Map<String, String>): String = steps.filter { !it.startsWith("wait-") }.map { st ->
        when {
            st in words -> words.getValue(st)
            st.startsWith("vpn-") -> "VPN: " + st.removePrefix("vpn-").uppercase()
            st.startsWith("sleep-") -> "Sleep in ${st.removePrefix("sleep-")} min"
            "-open-" in st -> names[st.substringAfter("-open-")]?.let { "Open $it" } ?: ((if (st.startsWith("cc")) "Chromecast: " else "TV: ") + "open an app")
            st.startsWith("scene-") -> "Another scene"
            else -> st.replace('-', ' ')
        }
    }.take(4).joinToString(" → ")
}
