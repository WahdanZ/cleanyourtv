package com.cleanyourtv.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The Chromecast's own settings and files (Hub.kt), in the same shapes the Mac helper keeps them, so moving them over
 * is a copy: config.json (the helper's config: home_layout, chromecast, addons, wallpaper…), admin.json (the panel
 * password's salted hash), scenes.json, websites.json, quick-token, watch-stats.json, wallpapers/ (your photos).
 * Everything lives in the app's private folder; a save keeps the five copies before it in backups/.
 */
class Store(context: Context) {
    val dir = File(context.filesDir, "hub").apply { mkdirs() }
    private val lock = Any()

    fun file(name: String) = File(dir, name)

    // ---------- config.json ----------
    @Volatile private var cache: JSONObject? = null

    fun config(): JSONObject = synchronized(lock) {
        cache ?: (readJson("config.json") as? JSONObject ?: JSONObject()).also { cache = it }
    }

    /** A copy to change; save() keeps it. */
    fun configCopy(): JSONObject = JSONObject(config().toString())

    fun save(cfg: JSONObject) = synchronized(lock) {
        val f = file("config.json")
        if (f.exists()) {
            val b = File(dir, "backups").apply { mkdirs() }
            f.copyTo(File(b, "config-" + SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(Date()) + ".json"), true)
            b.listFiles()?.sortedBy { it.name }?.dropLast(5)?.forEach { it.delete() }
        }
        writeText("config.json", cfg.toString(2))
        cache = JSONObject(cfg.toString())
    }

    // ---------- files ----------
    fun readJson(name: String): Any? = try {
        val t = file(name).readText().trim()
        if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
    } catch (e: Exception) { null }

    fun writeText(name: String, text: String) {
        val f = file(name)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    fun list(name: String): JSONArray = readJson(name) as? JSONArray ?: JSONArray()

    // ---------- the panel's password and sessions ----------
    fun passwordSet() = file("admin.json").exists()

    fun checkPassword(pw: String): Boolean {
        val s = readJson("admin.json") as? JSONObject ?: return false
        val digest = pbkdf2(pw, hex(s.optString("salt")), s.optInt("rounds", 240_000))
        return MessageDigest.isEqual(digest.toByteArray(), s.optString("hash").toByteArray())
    }

    fun setPassword(pw: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        writeText("admin.json", JSONObject().put("salt", salt.toHex()).put("rounds", 240_000).put("hash", pbkdf2(pw, salt, 240_000)).toString())
    }

    private fun pbkdf2(pw: String, salt: ByteArray, rounds: Int): String {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return f.generateSecret(PBEKeySpec(pw.toCharArray(), salt, rounds, 256)).encoded.toHex()
    }

    /** Sessions: token hash -> expiry (seconds), like the helper's admin-sessions.json. */
    private val sessions: MutableMap<String, Double> by lazy {
        val m = HashMap<String, Double>()
        (readJson("admin-sessions.json") as? JSONObject)?.let { o -> o.keys().forEach { m[it] = o.optDouble(it) } }
        m
    }

    fun newSession(): String {
        val t = ByteArray(32).also { SecureRandom().nextBytes(it) }.toHex()
        synchronized(lock) { sessions[sha256(t)] = now() + 30 * 86400; saveSessions() }
        return t
    }

    fun validSession(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val exp = synchronized(lock) { sessions[sha256(token)] } ?: return false
        return exp > now()
    }

    fun endSession(token: String?) {
        if (token.isNullOrEmpty()) return
        synchronized(lock) { sessions.remove(sha256(token)); saveSessions() }
    }

    private fun saveSessions() {
        val n = now()
        val o = JSONObject()
        sessions.entries.filter { it.value > n }.forEach { o.put(it.key, it.value) }
        writeText("admin-sessions.json", o.toString())
    }

    /** The secret in the phone shortcuts' links (/q/<secret>/<action>). */
    fun quickToken(fresh: Boolean = false): String = synchronized(lock) {
        val f = file("quick-token")
        if (!fresh && f.exists() && f.readText().trim().length >= 16) return f.readText().trim()
        val t = ByteArray(18).also { SecureRandom().nextBytes(it) }.let { android.util.Base64.encodeToString(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING) }
        writeText("quick-token", t)
        t
    }

    companion object {
        fun now() = System.currentTimeMillis() / 1000.0
        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).toHex()
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
