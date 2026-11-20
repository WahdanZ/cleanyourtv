package com.cleanyourtv.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * The Chromecast's own server: what the Mac helper did for the Chromecast, without the Mac. Port 8765, as the helper.
 *
 *  - Home (this app, on 127.0.0.1) asks it for its settings, the weather, the quote, the photo, the add-ons… the same
 *    requests it made to the helper (HubApi.kt answers them).
 *  - The phone panel (web/admin.html, the same page) at http://<this Chromecast>:8765/admin, from the home network
 *    only, after signing in with the panel's password; the phone shortcuts' links (/q/<secret>/<action>).
 *
 * It runs in CursorService (the always-on accessibility service), so it is there from boot, whether Home is open or not.
 */
class Hub(val ctx: Context) {
    val store = Store(ctx)
    val api = HubApi(this)
    val started = System.currentTimeMillis() / 1000
    @Volatile var saved = 0L   // the last save, for the screens' "update right away"
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        if (running) return
        running = true
        Thread({
            while (running) {
                try {
                    ServerSocket(PORT, 32, InetAddress.getByName("0.0.0.0")).use { s ->
                        server = s
                        CursorService.L("hub: listening on $PORT")
                        while (running) { val c = s.accept(); pool.execute { serve(c) } }
                    }
                } catch (e: Exception) {
                    if (running) { CursorService.L("hub: $e"); Thread.sleep(3000) }
                }
            }
        }, "hub").start()
        api.started()
    }

    fun stop() { running = false; try { server?.close() } catch (_: Exception) { } }

    // ---------- requests ----------
    class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>,
              val body: ByteArray, val ip: String) {
        val json: JSONObject by lazy { try { JSONObject(String(body, Charsets.UTF_8)) } catch (e: Exception) { JSONObject() } }
        fun cookie(name: String): String? = (headers["cookie"] ?: "").split(";").map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }?.substringAfter("=")
        val local get() = ip == "127.0.0.1" || ip == "::1"
    }

    class Res(val code: Int, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
        companion object {
            fun json(o: Any?, code: Int = 200, headers: Map<String, String> = emptyMap()) =
                Res(code, "application/json; charset=utf-8", (o?.toString() ?: "null").toByteArray(), headers)
            fun text(t: String, code: Int = 200) = Res(code, "text/plain; charset=utf-8", t.toByteArray())
            fun err(code: Int, msg: String = "") = json(JSONObject().put("ok", false).put("error", msg), code)
        }
    }

    private fun serve(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val req = read(input, s.inetAddress.hostAddress ?: "") ?: return
                val res = try { route(req) } catch (e: Exception) {
                    CursorService.L("hub: ${req.path}: $e")
                    Res.err(500, e.message ?: e.toString())
                }
                write(s.getOutputStream(), res)
            } catch (_: Exception) { }
        }
    }

    private fun read(input: InputStream, ip: String): Req? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {   // the head, up to the blank line
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) break
            if (head.size() > 64 * 1024) return null
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val first = lines[0].split(" ")
        if (first.size < 2) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) { val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
        val len = (headers["content-length"] ?: "0").toIntOrNull() ?: 0
        if (len > MAX_BODY) return null
        val body = ByteArray(len)
        var off = 0
        while (off < len) { val n = input.read(body, off, len - off); if (n < 0) break; off += n }
        val target = first[1]
        val path = URLDecoder.decode(target.substringBefore('?'), "UTF-8")
        val query = HashMap<String, String>()
        target.substringAfter('?', "").split("&").filter { it.isNotEmpty() }.forEach {
            query[URLDecoder.decode(it.substringBefore('='), "UTF-8")] = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        return Req(first[0].uppercase(), path, query, headers, body, ip.removePrefix("::ffff:"))
    }

    private fun write(out: OutputStream, r: Res) {
        val sb = StringBuilder("HTTP/1.1 ${r.code} ${reason(r.code)}\r\n")
        sb.append("Content-Type: ${r.type}\r\nContent-Length: ${r.body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n")
        r.headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(r.body)
        out.flush()
    }

    private fun reason(c: Int) = when (c) { 200 -> "OK"; 206 -> "Partial Content"; 302 -> "Found"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; else -> "Error" }

    // ---------- who may ask what ----------
    /** This Chromecast's address on the home network, and whether an address is in that network. */
    fun ownIp(): String {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback || ni.name.startsWith("tun") || ni.name.startsWith("dummy")) continue
                for (a in ni.inetAddresses) if (!a.isLoopbackAddress && a.hostAddress?.contains('.') == true) return a.hostAddress!!
            }
        } catch (_: Exception) { }
        return "127.0.0.1"
    }

    fun atHome(ip: String): Boolean {
        if (ip == "127.0.0.1") return true
        val own = ownIp()
        return own.substringBeforeLast('.') == ip.substringBeforeLast('.')
    }

    fun signedIn(r: Req) = r.local || store.validSession(r.cookie(COOKIE))

    private fun route(r: Req): Res {
        val p = r.path
        // Home on this Chromecast: everything under /api/ that isn't the panel's
        if (!r.local && !atHome(r.ip)) return Res.err(403)
        if (p == "/admin") return asset("web/admin.html", "text/html; charset=utf-8")
        if (p == "/admin-sw.js") return asset("web/admin-sw.js", "text/javascript; charset=utf-8", mapOf("Service-Worker-Allowed" to "/"))
        if (p.startsWith("/api/admin/app/")) {
            val name = p.substringAfterLast('/')
            if (!Regex("^[a-z0-9-]+\\.(png|svg|webmanifest)$").matches(name)) return Res.err(404)
            val type = mapOf("png" to "image/png", "svg" to "image/svg+xml", "webmanifest" to "application/manifest+json")[name.substringAfterLast('.')]!!
            return asset("web/app/$name", type)
        }
        if (p.startsWith("/q/")) return api.quick(r)
        if (p.startsWith("/api/hub/")) {   // the move from the Mac (tools/move-to-chromecast.py): the helper's key for this app
            val tok = CursorService.instance?.token().orEmpty()
            if (!r.local && (tok.isEmpty() || r.headers["x-token"] != tok)) return Res.err(403, "wrong key")
            return api.hubPost(r)
        }
        if (p == "/api/admin/hello") return Res.json(JSONObject().put("password_set", store.passwordSet()).put("signed_in", signedIn(r)).put("code", started))
        if (p == "/api/admin/login" && r.method == "POST") return api.login(r)
        if (p.startsWith("/api/admin/")) {
            if (!signedIn(r)) return Res.json(JSONObject().put("ok", false).put("error", "sign in").put("password_set", store.passwordSet()), 401)
            if (r.method == "POST" && r.headers["x-cyt"] != "1" && !r.local) return Res.err(403, "missing header")
            return if (r.method == "POST") api.adminPost(r) else api.adminGet(r)
        }
        if (p == "/api/region" && signedIn(r))   // the panel's VPN tab uses Home's own request, signed in
            return if (r.method == "POST") api.region(r.json.optString("region")) else api.homeGet(r)
        if (!r.local) return Res.err(403)   // the rest is Home's own (this Chromecast only)
        return if (r.method == "POST") api.homePost(r) else api.homeGet(r)
    }

    fun asset(name: String, type: String, headers: Map<String, String> = emptyMap()): Res = try {
        Res(200, type, ctx.assets.open(name).use { it.readBytes() }, headers)
    } catch (e: Exception) { Res.err(404) }

    companion object {
        const val PORT = 8765
        const val COOKIE = "cyt_admin"
        const val MAX_BODY = 25 * 1024 * 1024   // photo uploads
        @JvmStatic var instance: Hub? = null

        /** Started by CursorService (at boot, and after an update). */
        @JvmStatic fun start(c: Context) { if (instance == null) instance = Hub(c.applicationContext).also { it.start() } }

        fun arr(vararg xs: Any?) = JSONArray().apply { xs.forEach { put(it) } }
    }
}
