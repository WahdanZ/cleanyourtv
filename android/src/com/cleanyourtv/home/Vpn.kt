package com.cleanyourtv.home

import android.content.Context
import android.content.SharedPreferences
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * The launcher's own VPN: WireGuard (the official engine, built in), one country at a time, for the apps you choose.
 *
 * A country comes from NordVPN (NordLynx is WireGuard: your NordLynx key, and the server NordVPN recommends for that
 * country, as vpn/macos/tv-vpn.sh does), or from a WireGuard file of your own for that country (any other provider,
 * your own server). Only the listed apps go through it; "all apps" leaves the launcher itself out, so the phone panel
 * and the home network stay reachable. The country stays on after a restart.
 *
 * Settings (SharedPreferences "vpn"): key (NordLynx private key), apps (package names, comma separated; empty: all),
 * conf.<cc> (a wg-quick file for that country), country (what's on), countries (NordVPN's list, kept a week).
 */
object Vpn {
    private const val API = "https://api.nordvpn.com/v1"
    private const val NORD_DNS = "103.86.96.100, 103.86.99.100"
    private const val NORD_ADDRESS = "10.5.0.2/32"   // NordLynx gives every client this address inside the tunnel

    @JvmField var backend: GoBackend? = null
    @JvmField @Volatile var state: Tunnel.State = Tunnel.State.DOWN
    @JvmField @Volatile var country = "off"
    @JvmField @Volatile var server = ""
    @JvmField @Volatile var error = ""

    @JvmField val TUNNEL: Tunnel = object : Tunnel {
        override fun getName() = "home"
        override fun onStateChange(newState: Tunnel.State) { state = newState; CursorService.L("vpn: $newState") }
    }

    @JvmStatic fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences("vpn", Context.MODE_PRIVATE)

    @Synchronized private fun backend(c: Context): GoBackend = backend ?: GoBackend(c.applicationContext).also { backend = it }

    /** Whether Android still needs the one-time OK on the TV ("Home wants to set up a VPN"). */
    @JvmStatic fun needsConsent(c: Context) = VpnService.prepare(c) != null

    @JvmStatic fun configured(c: Context): Boolean {
        val p = prefs(c)
        return p.getString("key", "").orEmpty().isNotEmpty() || p.all.keys.any { it.startsWith("conf.") }
    }

    /** Turns the VPN to that country ("off" for none). Blocking (network): call it off the UI thread. "" or why not. */
    @JvmStatic @Synchronized fun set(c: Context, code: String?): String {
        val cc = (code ?: "off").trim().lowercase(Locale.ROOT).ifEmpty { "off" }
        return try {
            if (cc == "off") {
                backend(c).setState(TUNNEL, Tunnel.State.DOWN, null)
                country = "off"; server = ""; error = ""
                prefs(c).edit().putString("country", "off").apply()
                CursorService.L("vpn: off")
                return ""
            }
            if (needsConsent(c)) {
                error = "The VPN needs your OK once: open Home on the TV and choose the country there."
                return error
            }
            backend(c).setState(TUNNEL, Tunnel.State.UP, config(c, cc))
            country = cc; error = ""
            prefs(c).edit().putString("country", cc).apply()
            CursorService.L("vpn: $cc via $server")
            ""
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            CursorService.L("vpn: $cc failed: $error")
            error
        }
    }

    /** After a restart: the country that was on, again. */
    @JvmStatic fun restore(c: Context) {
        val cc = prefs(c).getString("country", "off") ?: "off"
        if (cc != "off" && configured(c) && !needsConsent(c)) Thread({ set(c, cc) }, "vpn-restore").start()
    }

    private fun config(c: Context, cc: String): Config {
        val p = prefs(c)
        val own = p.getString("conf.$cc", "").orEmpty()
        val apps = p.getString("apps", "").orEmpty()
        val text = if (own.isNotEmpty()) {   // a WireGuard file of your own for this country: as it is, plus the app list
            server = "your WireGuard file"
            withApps(own, apps, c.packageName)
        } else {
            val key = p.getString("key", "").orEmpty()
            if (key.isEmpty()) throw Exception("No NordVPN key and no WireGuard file for ${cc.uppercase(Locale.ROOT)}.")
            val (host, station, pub) = nordServer(c, cc)
            server = host
            "[Interface]\nPrivateKey = $key\nAddress = $NORD_ADDRESS\nDNS = $NORD_DNS\n" + appsLine(apps, c.packageName) +
                "[Peer]\nPublicKey = $pub\nAllowedIPs = 0.0.0.0/0\nEndpoint = $station:51820\nPersistentKeepalive = 25\n"
        }
        return Config.parse(BufferedReader(StringReader(text)))
    }

    /** The app list as an [Interface] line: the chosen apps only, or all but the launcher. */
    private fun appsLine(list: String, self: String) =
        if (list.isBlank()) "ExcludedApplications = $self\n" else "IncludedApplications = ${list.trim()}\n"

    private fun withApps(conf: String, list: String, self: String): String {
        val out = StringBuilder()
        var done = false
        for (line in conf.split("\n")) {
            val t = line.trim().lowercase(Locale.ROOT)
            if (t.startsWith("includedapplications") || t.startsWith("excludedapplications")) continue
            out.append(line).append('\n')
            if (!done && t == "[interface]") { out.append(appsLine(list, self)); done = true }
        }
        return out.toString()
    }

    /** NordVPN's recommended WireGuard server for a country code: hostname, station address, public key. */
    private fun nordServer(c: Context, cc: String): Triple<String, String, String> {
        val id = countryId(c, cc)
        val q = "$API/servers/recommendations?filters${enc("[servers_technologies][identifier]")}=wireguard_udp" +
            "&filters${enc("[country_id]")}=$id&limit=5"
        val list = JSONArray(get(q))
        for (i in 0 until list.length()) {
            val s = list.getJSONObject(i)
            val techs = s.optJSONArray("technologies") ?: continue
            for (t in 0 until techs.length()) {
                val tech = techs.getJSONObject(t)
                if (tech.optString("identifier") != "wireguard_udp") continue
                val meta = tech.optJSONArray("metadata") ?: continue
                for (m in 0 until meta.length()) {
                    val md = meta.getJSONObject(m)
                    if (md.optString("name") == "public_key") return Triple(s.optString("hostname"), s.optString("station"), md.optString("value"))
                }
            }
        }
        throw Exception("NordVPN has no WireGuard server for ${cc.uppercase(Locale.ROOT)} right now.")
    }

    /** NordVPN's id for a country code. */
    private fun countryId(c: Context, cc: String): Int {
        val all = countries(c)
        for (i in 0 until all.length()) {
            val o = all.getJSONObject(i)
            if (cc.equals(o.optString("code"), ignoreCase = true)) return o.getInt("id")
        }
        throw Exception("NordVPN has no country ${cc.uppercase(Locale.ROOT)}.")
    }

    /** NordVPN's countries (id, code, name), kept for a week. */
    @JvmStatic fun countries(c: Context): JSONArray {
        val p = prefs(c)
        val cached = p.getString("countries", "").orEmpty()
        if (System.currentTimeMillis() - p.getLong("countries_at", 0) < 7L * 86_400_000 && cached.isNotEmpty()) return JSONArray(cached)
        val raw = JSONArray(get("$API/servers/countries"))
        val slim = JSONArray()
        for (i in 0 until raw.length()) {
            val o = raw.getJSONObject(i)
            slim.put(JSONObject().put("id", o.optInt("id")).put("code", o.optString("code").lowercase(Locale.ROOT)).put("name", o.optString("name")))
        }
        p.edit().putString("countries", slim.toString()).putLong("countries_at", System.currentTimeMillis()).apply()
        return slim
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val h = URL(url).openConnection() as HttpURLConnection
        h.connectTimeout = 8000; h.readTimeout = 15000
        h.setRequestProperty("User-Agent", "Home")
        try { return h.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { h.disconnect() }
    }

    /** For the panel: what's on and how, and whether traffic comes back through the tunnel. */
    @JvmStatic fun status(c: Context): JSONObject {
        val o = JSONObject()
            .put("country", country).put("up", state == Tunnel.State.UP).put("server", server).put("error", error)
            .put("configured", configured(c)).put("consent", !needsConsent(c)).put("apps", prefs(c).getString("apps", ""))
        try {
            val b = backend
            if (b != null && state == Tunnel.State.UP) b.getStatistics(TUNNEL).let { o.put("rx", it.totalRx()).put("tx", it.totalTx()) }
        } catch (e: Exception) { o.put("stats_error", e.toString()) }
        return o
    }
}
