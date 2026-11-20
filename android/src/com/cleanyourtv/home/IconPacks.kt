package com.cleanyourtv.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Android icon packs: the apps made for Nova, Lawnchair, ADW and other launchers (Arcticons, Delta, Lines…).
 * Each one says what it is with an intent filter and lists its icons in appfilter.xml, the format they all share:
 *   <item component="ComponentInfo{com.app/com.app.Main}" drawable="app"/>
 *   <iconback img1=".."/> <iconmask img1=".."/> <iconupon img1=".."/> <scale factor="0.8"/>   (for apps it has no icon for)
 * The chosen pack (home_experience.icon_pack) gives an app its icon; an app it doesn't cover gets the pack's back,
 * mask and overlay around its own icon, or, with none of those, keeps its own icon. Read once, off the UI thread.
 */
object IconPacks {
    /** The intents icon packs answer to (each launcher made its own; most packs list several). */
    private val ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.teslacoilsw.launcher.THEME",
        "com.gau.go.launcherex.theme", "com.fede.launcher.THEME_ICONPACK", "com.anddoes.launcher.THEME",
        "com.dlto.atom.launcher.THEME", "net.oneplus.launcher.icons.ACTION_PICK_ICON")

    /** TV apps are often other packages than the phone apps the packs draw: try the phone one too. */
    private val PHONE = mapOf(
        "com.netflix.ninja" to "com.netflix.mediaclient",
        "com.google.android.youtube.tv" to "com.google.android.youtube",
        "com.google.android.youtube.tvmusic" to "com.google.android.apps.youtube.music",
        "com.google.android.youtube.tvkids" to "com.google.android.apps.youtube.kids",
        "com.amazon.amazonvideo.livingroom" to "com.amazon.avod.thirdpartyclient",
        "com.spotify.tv.android" to "com.spotify.music",
        "com.android.tv.settings" to "com.android.settings")

    class Pack(val pkg: String, val res: Resources) {
        val byComponent = HashMap<String, String>()
        val byPackage = HashMap<String, String>()
        val backs = ArrayList<String>(); var mask = ""; var upon = ""; var scale = 1f
        val icons = HashMap<String, Drawable?>()
        fun drawable(name: String): Drawable? {
            if (name.isEmpty()) return null
            for (type in listOf("drawable", "mipmap")) {
                val id = res.getIdentifier(name, type, pkg)
                if (id != 0) return try { res.getDrawable(id, null) } catch (e: Exception) { null }
            }
            return null
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var pack: Pack? = null
    @Volatile private var wanted = ""

    /** The installed icon packs: package and name, by name. */
    @JvmStatic fun installed(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager; val seen = LinkedHashMap<String, String>()
        for (act in ACTIONS) for (ri in try { pm.queryIntentActivities(Intent(act), PackageManager.GET_META_DATA) } catch (e: Exception) { emptyList() }) {
            val p = ri.activityInfo.packageName
            if (p !in seen) seen[p] = try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p }
        }
        // the Apex-style ones: MAIN with the theme as a category
        for (ri in try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME"), 0) } catch (e: Exception) { emptyList() }) {
            val p = ri.activityInfo.packageName
            if (p !in seen) seen[p] = ri.loadLabel(pm).toString()
        }
        return seen.entries.map { Pair(it.key, it.value) }.sortedBy { it.second.lowercase() }
    }

    /** Use [pkg] ("" for none). [changed] runs on the UI thread once the icons to draw are different. */
    @JvmStatic fun use(ctx: Context, pkg: String, changed: Runnable) {
        val p = if (Regex("^[A-Za-z0-9_.]{1,120}$").matches(pkg)) pkg else ""
        if (p == wanted) return
        wanted = p
        if (p.isEmpty()) { if (pack != null) { pack = null; changed.run() }; return }
        val app = ctx.applicationContext
        Thread {
            val got = try { load(app, p) } catch (e: Throwable) { null }   // not installed (any more), or not a real pack
            ui.post { if (wanted == p) { pack = got; changed.run() } }
        }.start()
    }

    @JvmStatic fun active(): String = pack?.pkg ?: ""

    /** The pack's icon for this app: its own, or made with the pack's back/mask/overlay; null to keep the app's own. */
    @JvmStatic fun icon(ctx: Context, app: String): Drawable? {
        val pk = pack ?: return null
        synchronized(pk) {
            if (pk.icons.containsKey(app)) return pk.icons[app]
            val d = try { find(ctx, pk, app) ?: made(ctx, pk, app) } catch (e: Exception) { null }
            pk.icons[app] = d
            return d
        }
    }

    private fun find(ctx: Context, pk: Pack, app: String): Drawable? {
        val pm = ctx.packageManager
        val comps = listOfNotNull(
            pm.getLeanbackLaunchIntentForPackage(app)?.component,
            pm.getLaunchIntentForPackage(app)?.component).map { "${it.packageName}/${it.className}" }
        for (c in comps) pk.byComponent[c]?.let { n -> pk.drawable(n)?.let { return it } }
        pk.byPackage[app]?.let { n -> pk.drawable(n)?.let { return it } }
        PHONE[app]?.let { ph -> pk.byPackage[ph]?.let { n -> pk.drawable(n)?.let { return it } } }
        return null
    }

    /** The app's own icon on the pack's back, cut by its mask, under its overlay (as ADW, Nova and Lawnchair do). */
    private fun made(ctx: Context, pk: Pack, app: String): Drawable? {
        if (pk.backs.isEmpty() && pk.mask.isEmpty() && pk.upon.isEmpty()) return null
        val own = ctx.packageManager.getApplicationIcon(app).let { it.constantState?.newDrawable(ctx.resources)?.mutate() ?: it }
        val s = 192
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(out)
        if (pk.backs.isNotEmpty()) pk.drawable(pk.backs[Math.abs(app.hashCode()) % pk.backs.size])?.let { it.setBounds(0, 0, s, s); it.draw(c) }
        val layer = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val lc = Canvas(layer)
        val w = (s * pk.scale).toInt(); val o = (s - w) / 2
        own.setBounds(o, o, o + w, o + w); own.draw(lc)
        pk.drawable(pk.mask)?.let { m ->
            val mb = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); m.setBounds(0, 0, s, s); m.draw(Canvas(mb))
            lc.drawBitmap(mb, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        c.drawBitmap(layer, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        pk.drawable(pk.upon)?.let { it.setBounds(0, 0, s, s); it.draw(c) }
        return BitmapDrawable(ctx.resources, out)
    }

    private fun load(ctx: Context, pkg: String): Pack {
        val res = ctx.packageManager.getResourcesForApplication(pkg)
        val pk = Pack(pkg, res)
        val id = res.getIdentifier("appfilter", "xml", pkg)
        val xp: XmlPullParser = if (id != 0) res.getXml(id) else
            XmlPullParserFactory.newInstance().newPullParser().apply { setInput(ctx.createPackageContext(pkg, 0).assets.open("appfilter.xml"), "utf-8") }
        var ev = xp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) when (xp.name) {
                "item" -> {
                    val comp = xp.getAttributeValue(null, "component") ?: ""; val dr = xp.getAttributeValue(null, "drawable") ?: ""
                    val m = Regex("ComponentInfo\\{\\s*([^/\\s]+)\\s*/\\s*([^}\\s]+)\\s*\\}").find(comp)
                    if (m != null && dr.isNotEmpty()) {
                        val p = m.groupValues[1]; val c = m.groupValues[2].let { if (it.startsWith(".")) p + it else it }
                        pk.byComponent.putIfAbsent("$p/$c", dr)
                        pk.byPackage.putIfAbsent(p, dr)
                    }
                }
                "iconback" -> for (i in 0 until xp.attributeCount) xp.getAttributeValue(i).takeIf { it.isNotEmpty() }?.let { pk.backs.add(it) }
                "iconmask" -> pk.mask = xp.getAttributeValue(null, "img1") ?: ""
                "iconupon" -> pk.upon = xp.getAttributeValue(null, "img1") ?: ""
                "scale" -> pk.scale = (xp.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f).coerceIn(.2f, 1f)
            }
            ev = xp.next()
        }
        return pk
    }
}
