package com.cleanyourtv.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cleanyourtv.home.NativeHome.Companion.FOCUS
import com.cleanyourtv.home.NativeHome.Companion.MUTED
import com.cleanyourtv.home.NativeHome.Companion.SUB
import com.cleanyourtv.home.NativeHome.Companion.SURFACE
import com.cleanyourtv.home.NativeHome.Companion.TEXT
import com.cleanyourtv.home.NativeHome.Companion.WARM
import com.cleanyourtv.home.NativeHome.Item

/**
 * The Chromecast's Home layouts, natively: the same ones as shared/experiences/layouts.js draws on the TV, with the
 * same remote maps (the "D-pad & remote focus map" of each concept in CleanYourTV_10_TV_UI_Layout_Concepts.pdf,
 * pages 6-24, and the follow-ups on p.25). Each arranges NativeHome's items its own way; focus is kept in
 * NativeHome.state (per layout) and restored by item id.
 */
object NativeLayouts {
    fun make(id: String, h: NativeHome): Layout = when (id) {
        "liquid" -> Liquid(h); "channel", "signal" -> Channel(h, id == "signal"); "typography" -> Typography(h); "worlds" -> Worlds(h)
        "fold" -> Fold(h); "four" -> Four(h); "river" -> River(h); "gallery" -> Gallery(h); "aurora", "ambient" -> Aurora(h, id == "ambient")
        "deck" -> Deck(h); "scenes" -> Scenes(h); else -> Theater(h)
    }

    abstract class Layout(val h: NativeHome) {
        open val photo = false
        open val calm = false
        abstract val hints: List<String>
        open fun background(h: NativeHome): Drawable = h.radial(Color.rgb(18, 48, 74), NativeHome.NIGHT)
        abstract fun render()
        open fun refresh() = render()
        abstract fun key(k: String): Boolean
        open fun digit(n: Int): Boolean = false
        open fun tick() {}
        val st get() = h.state
        val d get() = h.data
        val opts get() = h.data.opts
        val c: FrameLayout get() = h.content
        fun int(k: String, def: Int = 0) = st.optInt(k, def)
        fun set(k: String, v: Any?) { st.put(k, v) }
        fun clamp(v: Int, lo: Int, hi: Int) = Math.max(lo, Math.min(hi, v))
        fun idx(list: List<Item>, id: String?, def: Int): Int { val i = list.indexOfFirst { it.id == id }; return if (i >= 0) i else def }
        fun header(kicker: String, title: String, sub: String, y: Int = 168) {
            var at = y
            if (kicker.isNotEmpty()) { h.place(c, h.text(kicker.uppercase(), 26f, FOCUS, 500).also { it.letterSpacing = .12f }, 96, at); at += 44 }
            if (title.isNotEmpty()) { h.place(c, h.text(title, 72f, TEXT, 700), 96, at); at += 84 }
            if (sub.isNotEmpty()) h.place(c, h.text(sub, 30f, SUB, 400), 96, at)
        }
        fun chip(s: String) = h.text(s, 26f, TEXT, 500).also { it.background = h.round(Color.argb(219, 23, 41, 59), 32f); it.setPadding(26, 14, 26, 14) }
        fun button(s: String, light: Boolean = true) = h.text(s, 28f, if (light) NativeHome.NIGHT else TEXT, 700).also {
            it.letterSpacing = .08f; it.background = h.round(if (light) Color.rgb(232, 247, 251) else Color.argb(230, 23, 41, 59), 16f); it.setPadding(40, 18, 40, 18)
        }
        fun row(items: List<Item>, x: Int, y: Int, w: Int, hh: Int, gap: Int, nameSize: Float = 24f): List<View> =
            items.mapIndexed { n, it -> h.tile(it, w, hh, nameSize).also { t -> h.place(c, t, x + n * (w + gap), y) } }
        fun allGrid() = h.openGrid("All apps", h.all())
    }

    // ============================================================================================ 01 Liquid OS
    // LEFT/RIGHT previous/next favourite, UP sources, DOWN all apps, OK open. Fixed order; the one in focus grows.
    class Liquid(h: NativeHome) : Layout(h) {
        override val hints = listOf("◀ ▶ Flow between favourites", "OK Open", "▲ Sources", "▼ All apps")
        override fun background(h: NativeHome) = h.radial(Color.rgb(35, 58, 122), NativeHome.NIGHT, .5f, .4f)
        private var items = listOf<Item>(); private val els = ArrayList<FrameLayout>()
        override fun render() {
            header("", "Everything flows", "The one in focus grows. The rest make room.")
            val out = ArrayList(h.favorites()); h.live()?.let { out.add(0, it) }; h.sources().firstOrNull()?.let { out.add(Math.min(2, out.size), it) }
            items = out; els.clear()
            for (it in items) {
                val b = FrameLayout(h.a); b.pivotX = 0f; b.pivotY = 0f
                val m = h.tile(it, 400, 400, 40f, false, "round")
                b.addView(m, FrameLayout.LayoutParams(400, 400))
                val name = h.text(it.name, 40f, TEXT, 600); name.gravity = Gravity.CENTER
                b.addView(name, FrameLayout.LayoutParams(400, -2).apply { topMargin = 420 })
                h.place(c, b, 0, 380, 400, 480); els.add(b)
            }
            set("i", clamp(idx(items, st.optString("id"), int("i")), 0, Math.max(0, items.size - 1)))
            paint()
        }
        private fun paint() {
            val n = items.size; if (n == 0) return
            val count = opts.optString("count", "5").toIntOrNull() ?: 5; val i = int("i")
            val start = clamp(i - count / 2, 0, Math.max(0, n - count)); val shown = Math.min(count, n)
            val big = 400; val small = Math.min(250, (1640 - big) / Math.max(1, shown - 1) - 24)
            val widths = (0 until shown).map { if (start + it == i) big else small }
            val total = widths.sum() + 24 * (shown - 1); var x = (1920 - total) / 2f
            els.forEachIndexed { k, b ->
                val vis = k in start until start + shown
                b.visibility = if (vis) View.VISIBLE else View.INVISIBLE
                if (!vis) return@forEachIndexed
                val w = widths[k - start]; val s = w / big.toFloat()
                h.anim(b).translationX(x).translationY((big - w) / 2f).scaleX(s).scaleY(s).start()
                x += w + 24
                val art = (b.getChildAt(0) as View).tag as View
                art.foreground = if (k == i) GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(10, FOCUS) } else null
            }
            set("id", items[i].id)
        }
        override fun key(k: String): Boolean {
            val n = items.size
            when (k) {
                "left", "right" -> { if (n > 0) { set("i", clamp(int("i") + if (k == "left") -1 else 1, 0, n - 1)); paint() } }
                "up" -> h.openSources(); "down" -> allGrid()
                "ok" -> h.act(items.getOrNull(int("i")), els.getOrNull(int("i")))
                "back" -> { set("i", 0); paint() }
                else -> return false
            }
            return true
        }
    }

    // =============================================================================== 02 Channel Zero (Signal merged in)
    // A dial: your apps numbered like channels, then the TV's own inputs (only when the TV can really switch to them,
    // i.e. the helper reports them). UP/DOWN previous/next channel (it wraps), number keys jump, LEFT next group,
    // RIGHT the stage's buttons, OK open or switch, BACK to the top. Over today's photo, in the Glass or plain look,
    // with what's playing and the next match beside it.
    class Channel(h: NativeHome, @Suppress("unused") private val signal: Boolean) : Layout(h) {
        override val hints = listOf("▲ ▼ Change channel", "OK Open", "▶ All apps", "◀ Next group")   // (number keys jump too, where the remote has them)
        override val photo get() = opts.optString("photo") != "off"
        private class Row(val item: Item, val group: Int, val view: LinearLayout, val texts: List<TextView>, val badge: TextView?)
        private val flat = ArrayList<Row>()
        private lateinit var inner: LinearLayout; private lateinit var stage: FrameLayout; private lateinit var art: FrameLayout
        private lateinit var name: TextView; private lateinit var what: TextView; private lateinit var num: TextView
        private lateinit var go: TextView; private lateinit var more: TextView; private lateinit var jump: TextView
        private var zone = "list"; private var typed = ""
        private val glass get() = h.a.glassLook && photo
        private fun panel(v: View, r: Float) { v.background = if (glass) Glass.Panel(v, r, 110) else h.round(Color.argb(196, 23, 41, 59), r, 2, Color.argb(46, 121, 214, 233)) }
        private fun lastUsed(): Map<String, Long> {
            val out = HashMap<String, Long>()
            try { val arr = org.json.JSONArray(h.prefs.getString("recent", "[]")); for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { r ->
                val k = r.optString("type") + ":" + r.optString("id"); if (!out.containsKey(k)) out[k] = r.optLong("at") } } catch (e: Exception) { }
            return out
        }
        private fun ago(at: Long): String {
            if (at <= 0) return ""
            val days = ((System.currentTimeMillis() - at) / 86_400_000L).toInt()
            return when { days <= 0 -> "Opened today"; days == 1 -> "Opened yesterday"; days < 7 -> "Opened $days days ago"; else -> "" }
        }
        override fun render() {
            val apps = if (opts.optString("apps") == "all") (h.favorites() + h.all()).distinctBy { it.id } else h.favorites()
            val tv = listOfNotNull(h.live()) + h.sources()   // empty without a TV the helper can switch
            val groups = ArrayList<Pair<String, List<Item>>>()
            if (opts.optString("order") == "live" && tv.isNotEmpty()) groups.add("The TV" to tv)
            groups.add("Your apps" to apps)
            if (opts.optString("order") != "live" && tv.isNotEmpty()) groups.add("The TV" to tv)
            flat.clear()
            // the dial
            val dial = FrameLayout(h.a); panel(dial, 30f); dial.clipToOutline = true; h.place(c, dial, 80, 150, 720, 800)
            val rail = FrameLayout(h.a); rail.clipChildren = true; dial.addView(rail, FrameLayout.LayoutParams(720, 800))
            inner = LinearLayout(h.a); inner.orientation = LinearLayout.VERTICAL; inner.setPadding(22, 12, 22, 22)
            rail.addView(inner, FrameLayout.LayoutParams(720, -2))
            var n = 0
            groups.forEachIndexed { g, (title, list) ->
                inner.addView(h.text(title.uppercase(), 22f, Color.argb(235, 255, 255, 255), 700).also { it.letterSpacing = .14f; it.setPadding(10, 18, 0, 10) })
                for (it in list) {
                    n++
                    val row = LinearLayout(h.a); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL; row.setPadding(20, 0, 20, 0)
                    val numT = h.text(String.format("%02d", n), 30f, FOCUS, 600); row.addView(numT, LinearLayout.LayoutParams(70, -2))
                    if (it.type == "app") row.addView(h.tile(it, 96, 54, 18f, false, "wide"), LinearLayout.LayoutParams(96, 54).apply { rightMargin = 20 })
                    val t = LinearLayout(h.a); t.orientation = LinearLayout.VERTICAL
                    val subText = it.sub.takeUnless { s -> s.equals(it.badge, true) || s.equals("app", true) }.orEmpty()   // no "APP" twice
                    val nm = h.text(it.name, 32f, TEXT, 600); val sb = h.text(subText.uppercase(), 20f, SUB, 500).also { s -> s.letterSpacing = .08f }
                    t.addView(nm); if (subText.isNotEmpty()) t.addView(sb)
                    row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                    val badge = it.badge.takeIf { b -> b.isNotEmpty() }?.let { b -> h.text(if (it.playing) "♪" else b, 18f, FOCUS, 700).also { v ->
                        v.background = h.round(Color.argb(41, 121, 214, 233), 10f); v.setPadding(12, 6, 12, 6); row.addView(v) } }
                    inner.addView(row, LinearLayout.LayoutParams(-1, 84).apply { bottomMargin = 8 })
                    flat.add(Row(it, g, row, listOf(numT, nm, sb), badge))
                }
            }
            if (flat.isEmpty()) inner.addView(h.text("No apps yet: they appear here once the Chromecast reports them.", 28f, SUB, 400, 3).also { it.setPadding(10, 20, 10, 0) })
            // the widgets: what's playing and the next match, each only when there is one
            val cards = ArrayList<View>()
            d.np?.let { cards.add(nowCard(it)) }
            h.upcoming().filter { it.kind == "match" }.take(2).takeIf { it.isNotEmpty() }?.let { cards.add(matchCard(it)) }
            val stageH = if (cards.isEmpty()) 800 else 520
            // the stage
            stage = FrameLayout(h.a); panel(stage, 30f); h.place(c, stage, 860, 150, 980, stageH)
            num = h.text("", 150f, Color.argb(230, 159, 227, 242), 700); num.gravity = Gravity.END; h.place(stage, num, 560, 40, 370)
            art = FrameLayout(h.a); h.place(stage, art, 60, 56, 420, 190)
            name = h.text("", 80f, TEXT, 700); h.place(stage, name, 60, 262, 860)
            what = h.text("", 22f, SUB, 600); what.letterSpacing = .08f; h.place(stage, what, 64, 356, 860)
            go = button("OK  OPEN"); more = button("▶  ALL APPS", false)
            val btns = LinearLayout(h.a); btns.addView(go); btns.addView(more, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 18 }); h.place(stage, btns, 60, stageH - 124)
            if (cards.size == 1) h.place(c, cards[0], 860, 700, 980, 250)
            if (cards.size == 2) { h.place(c, cards[0], 860, 700, 520, 250); h.place(c, cards[1], 1404, 700, 436, 250) }
            jump = h.text("", 40f, NativeHome.NIGHT, 700); jump.background = h.round(TEXT, 16f); jump.setPadding(30, 18, 30, 18); jump.alpha = 0f; h.place(c, jump, 1500, 70)
            set("i", clamp(idx(flat.map { it.item }, st.optString("id"), int("i")), 0, Math.max(0, flat.size - 1)))
            paint()
        }
        private fun nowCard(np: Item): View {
            val card = FrameLayout(h.a); panel(card, 26f)
            val col = LinearLayout(h.a); col.orientation = LinearLayout.VERTICAL; col.setPadding(30, 26, 30, 26)
            col.addView(h.text(("♪ " + (if (np.playing) "Now playing" else "Paused") + if (np.why.isNotEmpty()) " · " + np.why else "").uppercase(), 18f, SUB, 700).also { it.letterSpacing = .12f })
            val r = LinearLayout(h.a); r.gravity = Gravity.CENTER_VERTICAL
            val iv = android.widget.ImageView(h.a); iv.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; iv.background = h.round(Color.rgb(47, 72, 88), 18f); iv.clipToOutline = true
            np.icon?.let { iv.setImageDrawable(it) }; r.addView(iv, LinearLayout.LayoutParams(130, 130))
            val t = LinearLayout(h.a); t.orientation = LinearLayout.VERTICAL; t.setPadding(22, 0, 0, 0)
            listOf(h.text(np.name, 30f, TEXT, 600), if (np.emoji.isNotEmpty()) h.text(np.emoji, 22f, SUB) else null).filterNotNull().forEach { v ->
                v.textDirection = View.TEXT_DIRECTION_LTR; v.textAlignment = View.TEXT_ALIGNMENT_VIEW_START; v.gravity = Gravity.LEFT; v.layoutDirection = View.LAYOUT_DIRECTION_LTR; t.addView(v, LinearLayout.LayoutParams(-1, -2)) }   // an Arabic title starts at the left, by its picture
            r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            col.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16 })
            card.addView(col, FrameLayout.LayoutParams(-1, -1))
            return card
        }
        private fun matchCard(ms: List<NativeHome.Moment>): View {
            val card = FrameLayout(h.a); panel(card, 26f)
            val col = LinearLayout(h.a); col.orientation = LinearLayout.VERTICAL; col.setPadding(30, 26, 30, 26)
            col.addView(h.text(("⚽ " + ms[0].source + " · " + if (ms[0].live) "Live" else "Next").uppercase(), 18f, SUB, 700).also { it.letterSpacing = .12f })
            for (m in ms) {
                val r = LinearLayout(h.a); r.orientation = LinearLayout.VERTICAL; r.setPadding(0, 18, 0, 0)
                r.addView(h.text(m.name, 26f, TEXT, 600))
                r.addView(h.text(if (m.live) "LIVE NOW" else h.hhmm(m.at) + "  ·  " + h.until(m.at), 20f, if (m.live) Color.rgb(255, 107, 107) else MUTED, 600))
                col.addView(r)
            }
            card.addView(col, FrameLayout.LayoutParams(-1, -1))
            return card
        }
        override fun refresh() { val z = zone; render(); zone = z; paint() }
        private fun paint() {
            val i = int("i"); val row = flat.getOrNull(i); val it = row?.item
            flat.forEachIndexed { k, r ->
                val on = k == i
                r.view.background = if (on) h.round(Color.argb(if (zone == "list") 236 else 120, 255, 255, 255), 18f) else null
                r.texts.forEachIndexed { j, t -> t.setTextColor(if (on && zone == "list") (if (j == 0) Color.rgb(11, 79, 95) else if (j == 2) Color.argb(150, 0, 0, 0) else Color.rgb(17, 17, 17)) else (if (j == 0) FOCUS else if (j == 2) SUB else TEXT)) }
                r.view.alpha = if (zone == "stage" && !on) .5f else 1f
            }
            row?.let { r -> r.view.post { inner.translationY = -Math.max(0, r.view.top - 300).toFloat() } }
            art.removeAllViews()
            if (it != null) {
                num.text = String.format("%02d", i + 1)
                if (it.type == "source") {
                    val m = Regex("^([A-Za-z]+)(\\d+)$").find(it.id)
                    art.addView(h.text(if (m != null) m.groupValues[1].uppercase() + " " + m.groupValues[2].toInt() else if (it.live || it.id.equals("TV", true)) "LIVE TV" else it.name.uppercase().take(10), 96f, Color.rgb(159, 227, 242), 700), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
                } else art.addView(h.tile(it, 300, 168, 26f, false, "wide"), FrameLayout.LayoutParams(300, 168))
                name.text = it.name
                val used = ago(lastUsed()[it.type + ":" + it.id] ?: 0L)
                what.text = listOf(it.sub.takeUnless { s -> s.equals("app", true) }.orEmpty(), if (it.playing) "Playing now" else used, if (it.type == "source") "OK switches the TV to it" else "OK opens it").filter { s -> s.isNotEmpty() }.joinToString("  ·  ").uppercase()
                go.text = "OK  " + if (it.type == "source") "SWITCH" else "OPEN"
                set("id", it.id)
            }
            h.ring(go, zone == "stage" && int("btn") == 0, 16f); h.ring(more, zone == "stage" && int("btn") == 1, 16f)
        }
        override fun digit(n: Int): Boolean {
            typed = (typed + n).takeLast(2); val want = typed.toInt()
            jump.text = "Channel $typed"; jump.alpha = 1f
            h.a.ui.removeCallbacks(clear); h.a.ui.postDelayed(clear, 1400)
            if (want in 1..flat.size) { set("i", want - 1); zone = "list"; paint(); h.save() }
            return true
        }
        private val clear = Runnable { typed = ""; jump.alpha = 0f }
        override fun key(k: String): Boolean {
            val n = flat.size
            if (n == 0) { if (k == "down" || k == "right") { allGrid(); return true }; return k != "back" }
            if (zone == "stage") {
                when (k) {
                    "back" -> zone = "list"
                    "left" -> if (int("btn") == 1) set("btn", 0) else zone = "list"
                    "right" -> set("btn", 1)
                    "ok" -> { if (int("btn") == 1) { allGrid(); return true }; h.act(flat[int("i")].item, go); return true }
                    else -> return true
                }
                paint(); return true
            }
            when (k) {
                "up", "down" -> set("i", (int("i") + (if (k == "down") 1 else -1) + n) % n)
                "right" -> { zone = "stage"; set("btn", 0) }
                "left" -> { val g = flat[int("i")].group; for (j in 1..n) if (flat[(int("i") + j) % n].group != g) { set("i", (int("i") + j) % n); break } }
                "ok" -> { h.act(flat[int("i")].item, flat[int("i")].view); return true }
                "back" -> set("i", 0)
                else -> return false
            }
            paint(); return true
        }
    }

    // ================================================================================= 03 Typography First
    // UP/DOWN previous/next word, RIGHT (or OK) enter, LEFT back to the words, OK open, BACK back.
    class Typography(h: NativeHome) : Layout(h) {
        override val hints = listOf("▲ ▼ Mode", "▶ / OK Enter", "◀ Words", "OK Open")
        override fun background(h: NativeHome) = h.radial(Color.rgb(43, 34, 25), Color.rgb(18, 16, 20), .2f, .4f)
        private class Mode(val id: String, val verb: String, val ing: String, val items: List<Item>)
        private val modes = ArrayList<Mode>(); private val mels = ArrayList<TextView>(); private var iels = listOf<TextView>()
        private lateinit var word: TextView; private lateinit var rowBox: LinearLayout; private lateinit var menu: LinearLayout
        private var zone = "menu"
        private val accent get() = if (opts.optString("accent") == "cool") FOCUS else WARM
        override fun render() {
            val g = h.groups(); modes.clear(); mels.clear()
            for ((id, v, ing, list) in listOf(listOf("watch", "Watch", "Watching", g.watch), listOf("play", "Play", "Playing", g.play), listOf("listen", "Listen", "Listening", g.listen), listOf("sources", "Sources", "Switching", g.sources)))
                @Suppress("UNCHECKED_CAST") (list as List<Item>).let { if (id == "watch" || it.isNotEmpty()) modes.add(Mode(id as String, v as String, ing as String, it)) }
            if (d.scenes.isNotEmpty()) modes.add(Mode("scenes", "Scenes", "Setting", d.scenes))
            val q = opts.optString("question", "Watching tonight?").trim().ifEmpty { "Watching tonight?" }
            val rest = if (q.indexOf(' ') > 0) q.substring(q.indexOf(' ') + 1) else q
            h.place(c, h.text("WHAT ARE WE", 26f, FOCUS, 500).also { it.letterSpacing = .12f }, 96, 210)
            word = h.text("", 200f, TEXT, 800); h.place(c, word, 90, 250, 1250)
            h.place(c, h.text(rest.uppercase(), 150f, accent, 800).also { it.paintFlags = it.paintFlags or Paint.UNDERLINE_TEXT_FLAG }, 96, 460, 1200)
            rowBox = LinearLayout(h.a); h.place(c, rowBox, 96, 690, 1240)
            menu = LinearLayout(h.a); menu.orientation = LinearLayout.VERTICAL; menu.background = h.round(Color.argb(209, 32, 28, 26), 26f); menu.setPadding(36, 30, 36, 30)
            h.place(c, menu, 1354, 300, 470)
            modes.forEachIndexed { n, m ->
                val r = h.text(String.format("0%d   %s", n + 1, m.verb.uppercase()), 40f, Color.rgb(185, 171, 153), 600); r.setPadding(10, 16, 10, 16)
                menu.addView(r, LinearLayout.LayoutParams(-1, -2)); mels.add(r)
            }
            set("m", clamp(modes.indexOfFirst { it.id == st.optString("mid") }.takeIf { it >= 0 } ?: int("m"), 0, modes.size - 1))
            zone = if (st.optString("zone") == "items") "items" else "menu"
            paint()
        }
        private fun paint() {
            val m = modes[int("m")]
            word.text = m.ing.uppercase()
            mels.forEachIndexed { k, t -> val on = k == int("m"); t.setTextColor(if (on) TEXT else Color.rgb(185, 171, 153))
                t.background = if (on && zone == "menu") h.round(Color.TRANSPARENT, 0f).also { _ -> } else null
                t.foreground = if (on) GradientDrawable().apply { setColor(Color.TRANSPARENT) } else null
                h.ring(t, on && zone == "menu", 10f, color = accent) }
            menu.alpha = if (zone == "items") .55f else 1f
            rowBox.removeAllViews()
            iels = m.items.take(6).map { it -> h.text(it.name, 40f, TEXT, 600).also { t -> t.setPadding(22, 10, 22, 10); rowBox.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 20 }) } }
            if (m.items.isEmpty()) rowBox.addView(h.text("Nothing here yet: choose apps for ${m.verb} in the phone panel.", 30f, Color.rgb(201, 187, 169)))
            set("j", clamp(int("j"), 0, Math.max(0, iels.size - 1)))
            iels.forEachIndexed { k, t -> val on = zone == "items" && k == int("j"); t.background = if (on) h.round(TEXT, 12f) else null; t.setTextColor(if (on) Color.rgb(18, 16, 20) else TEXT) }
            rowBox.alpha = if (zone == "items") 1f else .6f
            set("mid", m.id); set("zone", zone)
        }
        override fun key(k: String): Boolean {
            val m = modes[int("m")]
            if (zone == "menu") {
                when (k) {
                    "up", "down" -> { set("m", clamp(int("m") + if (k == "down") 1 else -1, 0, modes.size - 1)); set("j", 0) }
                    "right", "ok" -> if (m.items.isNotEmpty()) { zone = "items"; set("j", 0) }
                    "back" -> set("m", 0)
                }
                paint(); return true
            }
            when {
                k == "back" || (k == "left" && int("j") == 0) -> zone = "menu"
                k == "left" || k == "right" -> set("j", clamp(int("j") + if (k == "right") 1 else -1, 0, iels.size - 1))
                k == "up" || k == "down" -> { zone = "menu"; set("m", clamp(int("m") + if (k == "down") 1 else -1, 0, modes.size - 1)); set("j", 0) }
                k == "ok" -> { h.act(m.items.getOrNull(int("j")), iels.getOrNull(int("j"))); return true }
            }
            paint(); return true
        }
    }

    // ================================================================================== 04 Floating Worlds
    // LEFT/RIGHT world, UP TV controls, DOWN favourites, OK enter the world, BACK back to the worlds.
    class Worlds(h: NativeHome) : Layout(h) {
        override val hints = listOf("◀ ▶ Between worlds", "OK Enter", "▼ Favourites", "▲ TV controls")
        override fun background(h: NativeHome) = h.radial(Color.rgb(30, 47, 102), NativeHome.NIGHT, .5f, .3f)
        private val worlds = ArrayList<Triple<String, String, List<Item>>>(); private val wels = ArrayList<View>(); private var cels = listOf<View>(); private var fels = listOf<View>()
        private var favs = listOf<Item>(); private val controls = ArrayList<Pair<String, () -> Unit>>(); private var zone = "worlds"
        private lateinit var panel: FrameLayout; private var pels = listOf<View>(); private var pitems = listOf<Item>()
        override fun render() {
            val g = h.groups(); worlds.clear(); wels.clear(); controls.clear()
            worlds.add(Triple("Watch", "▶", g.watch)); worlds.add(Triple("Play", "◆", g.play)); worlds.add(Triple("Listen", "♪", g.listen)); worlds.add(Triple("Sources", "⇄", g.sources))
            d.np?.let { np -> controls.add(((if (np.playing) "❚❚  " else "▶  ") + np.name) to { h.act(np) }) }
            controls.add("Sources" to { h.openSources() }); controls.add("All apps" to { allGrid() }); controls.add("Menu" to { h.openMenu() })
            val bar = LinearLayout(h.a); h.place(c, bar, 96, 150)
            cels = controls.map { x -> chip(x.first).also { bar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18 }) } }
            h.place(c, h.text("CHOOSE YOUR WORLD", 56f, TEXT, 700).also { it.letterSpacing = .04f }, 96, 240)
            val colors = intArrayOf(Color.rgb(192, 86, 122), Color.rgb(58, 167, 160), Color.rgb(141, 106, 208), Color.rgb(216, 147, 90))
            worlds.forEachIndexed { n, (name, glyph, items) ->
                val b = LinearLayout(h.a); b.orientation = LinearLayout.VERTICAL; b.gravity = Gravity.CENTER_HORIZONTAL; b.setPadding(26, 26, 26, 26)
                val art = h.text(glyph, 96f, TEXT, 400); art.gravity = Gravity.CENTER; art.background = h.round(Color.argb(204, 23, 41, 59), 24f, 3, Color.argb(90, 255, 255, 255))
                b.addView(art, LinearLayout.LayoutParams(268, 190))
                b.addView(View(h.a).also { it.background = h.round(colors[n], 24f) }, LinearLayout.LayoutParams(320, 46))
                b.addView(h.text(name.uppercase(), 34f, TEXT, 700).also { it.letterSpacing = .06f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 22 })
                b.addView(h.text(if (items.isEmpty()) "empty" else "${items.size} place" + if (items.size == 1) "" else "s", 24f, MUTED))
                h.place(c, b, 140 + n * 410, 360, 372); wels.add(b)
            }
            h.place(c, h.text("EXPLORE A WORLD WITH OK. YOUR FAVOURITES ARE ALWAYS ONE KEY AWAY.", 26f, SUB, 500), 140, 770)
            favs = h.favorites().take(8); fels = row(favs, 140, 820, 180, 100, 30, 24f)
            panel = FrameLayout(h.a); panel.background = h.round(Color.argb(247, 15, 26, 44), 30f); panel.visibility = View.GONE; h.place(c, panel, 96, 300, 1728, 680)
            zone = if (st.optString("zone") == "favs" || st.optString("zone") == "controls") st.optString("zone") else "worlds"
            set("w", clamp(int("w"), 0, 3)); paint()
        }
        override fun refresh() { val inside = zone == "inside"; render(); if (inside) enter() }
        private fun enter() {
            val (name, _, items) = worlds[int("w")]; zone = "inside"; panel.removeAllViews()
            h.place(panel, h.text(name, 52f, TEXT, 700), 40, 36)
            if (items.isEmpty()) h.place(panel, h.text("Nothing in $name yet. Choose its apps in the phone panel (Layout, Floating Worlds).", 30f, SUB, 400, 2), 40, 130, 1600)
            pitems = items; pels = items.mapIndexed { n, it -> h.tile(it, 248, 140).also { t -> h.place(panel, t, 40 + (n % 6) * 276, 130 + (n / 6) * 230) } }
            set("p", clamp(int("p"), 0, Math.max(0, pels.size - 1))); paint()
        }
        private fun paint() {
            wels.forEachIndexed { k, v -> h.ring(v, zone == "worlds" && k == int("w"), 30f, color = Color.rgb(201, 182, 255)); v.translationY = if (k == int("w") && zone == "worlds") -10f else 0f
                v.background = if (zone != "worlds" && k == int("w")) h.round(Color.TRANSPARENT, 30f, 3, Color.argb(115, 121, 214, 233)) else null }
            cels.forEachIndexed { k, v -> h.ring(v, zone == "controls" && k == int("c"), 32f) }
            fels.forEachIndexed { k, v -> h.ringTile(v, zone == "favs" && k == int("f")) }
            panel.visibility = if (zone == "inside") View.VISIBLE else View.GONE
            if (zone == "inside") pels.forEachIndexed { k, v -> h.ringTile(v, k == int("p")) }
            set("zone", zone)
        }
        override fun key(k: String): Boolean {
            when (zone) {
                "inside" -> { val n = pels.size
                    when (k) { "back" -> zone = "worlds"; "left" -> set("p", Math.max(0, int("p") - 1)); "right" -> set("p", Math.min(n - 1, int("p") + 1))
                        "up" -> if (int("p") >= 6) set("p", int("p") - 6) else zone = "worlds"; "down" -> set("p", Math.min(n - 1, int("p") + 6))
                        "ok" -> { h.act(pitems.getOrNull(int("p")), pels.getOrNull(int("p"))); return true } } }
                "controls" -> when (k) { "left", "right" -> set("c", clamp(int("c") + if (k == "right") 1 else -1, 0, controls.size - 1))
                    "down", "back" -> zone = "worlds"; "ok" -> { controls[int("c")].second(); return true } }
                "favs" -> when (k) { "left", "right" -> set("f", clamp(int("f") + if (k == "right") 1 else -1, 0, favs.size - 1))
                    "up", "back" -> zone = "worlds"; "ok" -> { h.act(favs.getOrNull(int("f")), fels.getOrNull(int("f"))); return true } }
                else -> when (k) { "left", "right" -> set("w", clamp(int("w") + if (k == "right") 1 else -1, 0, 3)); "up" -> zone = "controls"
                    "down" -> if (favs.isNotEmpty()) zone = "favs"; "ok" -> { set("p", 0); enter(); return true }; "back" -> set("w", 0) }
            }
            paint(); return true
        }
    }

    // ========================================================================================= 05 The Fold
    // LEFT/RIGHT panel, UP shortcuts, DOWN all apps, OK open the list, BACK fold it again. The panels are drawn as
    // parallelograms (2D only); the focused one widens, the others stay where they are.
    class Fold(h: NativeHome) : Layout(h) {
        override val hints = listOf("◀ ▶ Fold panels", "OK Open the list", "▲ Shortcuts", "▼ All apps", "BACK Fold")
        private class Panel(val name: String, val items: List<Item>, val empty: String, val color: Int) { var els = listOf<TextView>(); lateinit var view: FrameLayout; lateinit var shape: Slant; var emptyView: View? = null }
        private val panels = ArrayList<Panel>(); private var sels = listOf<View>(); private var zone = "panels"
        override fun render() {
            val g = h.groups(); val live = h.live(); panels.clear()
            panels.add(Panel("Live TV", listOfNotNull(live), "No Live TV source here.", Color.rgb(45, 74, 99)))
            panels.add(Panel("Watch", g.watch.take(5), "No apps yet.", Color.rgb(47, 122, 133)))
            panels.add(Panel("Play", g.play.take(5), "Choose apps for Play in the phone panel.", Color.rgb(106, 90, 140)))
            panels.add(Panel("Inputs", h.sources().take(5), if (d.sources.isNotEmpty()) "The TV reported no inputs." else "Inputs need the helper.", Color.rgb(44, 76, 107)))
            val bar = LinearLayout(h.a); h.place(c, bar, 96, 150)
            val shorts = listOf<Pair<String, () -> Unit>>("Sources" to { h.openSources() }, "All apps" to { allGrid() }, "Menu" to { h.openMenu() })
            sels = shorts.map { x -> chip(x.first).also { bar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18 }) } }
            bar.tag = shorts
            header("", "The Fold", "An interface that unfolds around focus.", 240)
            panels.forEach { p ->
                val f = FrameLayout(h.a); p.shape = Slant(h.a, p.color); f.addView(p.shape, FrameLayout.LayoutParams(-1, -1))
                val col = LinearLayout(h.a); col.orientation = LinearLayout.VERTICAL; col.setPadding(40, 50, 30, 30)
                col.addView(h.text(p.name.uppercase(), 40f, TEXT, 700).also { it.paintFlags = it.paintFlags or Paint.UNDERLINE_TEXT_FLAG })
                p.els = p.items.map { it -> h.text(it.name, 34f, TEXT, 500).also { t -> t.setPadding(14, 8, 14, 8); col.addView(t) } }
                if (p.items.isEmpty()) p.emptyView = h.text(p.empty, 26f, Color.rgb(216, 227, 235), 400, 3).also { col.addView(it) }
                col.tag = "list"; f.addView(col, FrameLayout.LayoutParams(-1, -1)); p.view = f
                c.addView(f, FrameLayout.LayoutParams(310, 430).apply { topMargin = 440 })
            }
            h.place(c, h.text("FOCUS REVEALS OPTIONS WITHOUT SHIFTING OTHER TARGETS.", 24f, MUTED, 500), 140, 920)
            zone = if (st.optString("zone") == "list") "list" else "panels"
            set("p", clamp(st.optInt("p", 1), 0, 3)); paint()
        }
        private fun paint() {
            var x = 140
            panels.forEachIndexed { n, p ->
                val open = n == int("p"); val w = if (open) 600 else 300
                (p.view.layoutParams as FrameLayout.LayoutParams).let { lp -> lp.leftMargin = x; lp.width = w; lp.topMargin = if (open) 420 else 460; lp.height = if (open) 450 else 370; p.view.layoutParams = lp }
                x += w + 26
                p.shape.focus = open && zone == "panels"; p.shape.invalidate()
                p.emptyView?.visibility = if (open) View.VISIBLE else View.GONE
                p.els.forEachIndexed { k, t -> t.visibility = if (open) View.VISIBLE else View.GONE; val on = zone == "list" && open && k == int("j")
                    t.background = if (on) h.round(TEXT, 10f) else null; t.setTextColor(if (on) NativeHome.NIGHT else TEXT) }
            }
            sels.forEachIndexed { k, v -> h.ring(v, zone == "short" && k == int("s"), 32f) }
            set("zone", zone)
        }
        override fun key(k: String): Boolean {
            val p = panels[int("p")]
            when (zone) {
                "short" -> { @Suppress("UNCHECKED_CAST") val shorts = (sels.first().parent as View).tag as List<Pair<String, () -> Unit>>
                    when (k) { "left", "right" -> set("s", clamp(int("s") + if (k == "right") 1 else -1, 0, sels.size - 1)); "down", "back" -> zone = "panels"
                        "ok" -> { shorts[int("s")].second(); return true } } }
                "list" -> when (k) {
                    "up" -> if (int("j") > 0) set("j", int("j") - 1) else zone = "panels"
                    "down" -> set("j", Math.min(p.items.size - 1, int("j") + 1)); "back" -> zone = "panels"
                    "left", "right" -> { zone = "panels"; set("p", clamp(int("p") + if (k == "right") 1 else -1, 0, 3)); set("j", 0) }
                    "ok" -> { h.act(p.items.getOrNull(int("j")), p.els.getOrNull(int("j"))); return true } }
                else -> when (k) {
                    "left", "right" -> { set("p", clamp(int("p") + if (k == "right") 1 else -1, 0, 3)); set("j", 0) }
                    "ok" -> if (p.items.isNotEmpty()) { zone = "list"; set("j", 0) } else h.toast(p.empty)
                    "up" -> zone = "short"; "down" -> { allGrid(); return true }; "back" -> set("p", 1) }
            }
            paint(); return true
        }
    }
    /** A panel of The Fold: a parallelogram (the top edge slants), with the focus ring drawn along it. */
    class Slant(ctx: Context, private val color: Int) : View(ctx) {
        var focus = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG); private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = FOCUS }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 14f; color = Color.argb(217, 11, 21, 33) }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val hh = height.toFloat(); val s = w * .07f
            val p = Path().apply { moveTo(8f, s + 8); lineTo(w - 8, 8f); lineTo(w - 8, hh - s - 8); lineTo(8f, hh - 8); close() }
            fill.color = color; cv.drawPath(p, fill)
            if (focus) { cv.drawPath(p, halo); cv.drawPath(p, line) }
        }
    }

    // ================================================================================= 06 Four Directions
    // At the hub: UP discover, RIGHT sources, DOWN all apps, LEFT resume, OK the one used last; inside a destination
    // the arrows move normally and BACK returns to the hub. Number keys 1-4: the favourites in the corner.
    class Four(h: NativeHome) : Layout(h) {
        override val hints = listOf("▲ Discover", "▼ All apps", "◀ Resume", "▶ Sources", "OK Last used", "1-4 Favourites")
        private val boxes = HashMap<String, View>(); private lateinit var ok: LinearLayout; private lateinit var okSub: TextView; private lateinit var sub: FrameLayout
        private var favs = listOf<Item>(); private var zone = "hub"; private var dest = ""; private var items = listOf<Item>(); private var sels = listOf<View>()
        private fun center(): Item? = (if (opts.optString("center") != "first") h.recent().firstOrNull() else null) ?: h.favorites().firstOrNull()
        override fun render() {
            header("", "Four directions", "One key. One destination.")
            val dirs = listOf(Triple("up", "DISCOVER", 750 to 300), Triple("down", "ALL APPS", 750 to 730), Triple("left", "RESUME", 260 to 515), Triple("right", "SOURCES", 1240 to 515))
            for ((k, name, pos) in dirs) {
                val b = LinearLayout(h.a); b.orientation = LinearLayout.VERTICAL; b.gravity = Gravity.CENTER; b.setPadding(28, 28, 28, 28)
                b.background = h.round(Color.argb(204, 23, 41, 59), 20f, 2, Color.argb(38, 121, 214, 233))
                b.addView(h.text(name, 40f, TEXT, 700).also { it.gravity = Gravity.CENTER }); b.addView(h.text("${k.uppercase()} ARROW", 22f, MUTED, 500).also { it.letterSpacing = .1f; it.gravity = Gravity.CENTER })
                h.place(c, b, pos.first, pos.second, 420); boxes[k] = b
            }
            ok = LinearLayout(h.a); ok.orientation = LinearLayout.VERTICAL; ok.gravity = Gravity.CENTER
            ok.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(17, 39, 58)); setStroke(4, Color.argb(128, 121, 214, 233)) }
            ok.addView(h.text("OK", 80f, TEXT, 700).also { it.gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2)); okSub = h.text("", 24f, SUB, 500); okSub.gravity = Gravity.CENTER; ok.addView(okSub, LinearLayout.LayoutParams(220, -2))
            h.place(c, ok, 830, 450, 260, 260)
            favs = h.favorites().take(4)
            favs.forEachIndexed { n, it -> val t = h.tile(it, 150, 84, 22f); h.place(c, t, 1170 + n * 166, 168)
                (t.tag as FrameLayout).addView(h.text("${n + 1}", 22f, TEXT, 700).also { x -> x.gravity = Gravity.CENTER; x.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(217, 11, 21, 33)) } }, FrameLayout.LayoutParams(36, 36).apply { leftMargin = 8; topMargin = 8 }) }
            sub = FrameLayout(h.a); sub.background = h.round(Color.argb(247, 15, 26, 44), 30f); sub.visibility = View.GONE; h.place(c, sub, 96, 150, 1728, 830)
            zone = "hub"; paint()
        }
        override fun refresh() { val z = zone; val s = dest; render(); if (z != "hub" && s.isNotEmpty()) open(s) }
        private fun paint() {
            okSub.text = (center()?.name ?: "Nothing yet").uppercase()
            h.ring(ok, zone == "hub", 130f)
            boxes.forEach { (k, v) -> v.background = h.round(if (zone != "hub" && dest == k) Color.argb(51, 121, 214, 233) else Color.argb(204, 23, 41, 59), 20f, 2,
                if (zone != "hub" && dest == k) FOCUS else Color.argb(38, 121, 214, 233)) }
            sub.visibility = if (zone == "hub") View.GONE else View.VISIBLE
            if (zone != "hub") sels.forEachIndexed { k, v -> h.ringTile(v, k == int("s")) }
        }
        private fun open(dir: String) {
            var title = ""; var empty = ""
            when (dir) {
                "down" -> { h.openGrid("All apps", h.all()); return }
                "right" -> { title = "Sources"; items = listOfNotNull(h.live()) + h.sources(); empty = if (d.sources.isNotEmpty()) "The TV reported no inputs." else "Inputs need the helper (a VIDAA TV)." }
                "left" -> { title = "Resume"; items = listOfNotNull(d.np) + h.recent(); empty = "Nothing opened from this Home yet. Apps you open appear here." }
                else -> { title = "Discover"; items = d.rows.flatMap { r -> r.second.mapNotNull { d.app(it) } }.ifEmpty { h.all() }; empty = "Nothing to discover yet." }
            }
            zone = "dest"; dest = dir; sub.removeAllViews()
            h.place(sub, h.text(title, 52f, TEXT, 700), 40, 36)
            if (items.isEmpty()) h.place(sub, h.text(empty, 30f, SUB, 400, 2), 40, 130, 1600)
            sels = items.mapIndexed { n, it -> h.tile(it, 248, 140).also { t -> h.place(sub, t, 40 + (n % 6) * 276, 130 + (n / 6) * 230) } }
            set("s", 0); paint()
        }
        override fun digit(n: Int): Boolean { val it = favs.getOrNull(n - 1); if (zone == "hub" && it != null) { h.act(it); return true }; return false }
        override fun key(k: String): Boolean {
            if (zone == "hub") {
                if (k in listOf("up", "down", "left", "right")) { open(k); return true }
                if (k == "ok") { val it = center(); if (it != null) h.act(it, ok) else h.toast("Open something first: it comes back here."); return true }
                return k == "back"
            }
            val n = items.size
            if (k == "back") { zone = "hub"; dest = ""; paint(); return true }
            if (n == 0) return true
            when (k) { "left" -> set("s", Math.max(0, int("s") - 1)); "right" -> set("s", Math.min(n - 1, int("s") + 1))
                "up" -> if (int("s") >= 6) set("s", int("s") - 6) else { zone = "hub"; dest = "" }; "down" -> set("s", Math.min(n - 1, int("s") + 6))
                "ok" -> { h.act(items[int("s")], sels.getOrNull(int("s"))); return true } }
            paint(); return true
        }
    }

    // ================================================================================= 07 The Time River
    // LEFT/RIGHT earlier/later, UP sources, DOWN all apps, OK do it, BACK back to Now. Only real moments.
    class River(h: NativeHome) : Layout(h) {
        override val hints = listOf("◀ ▶ Moments", "OK Do it", "▲ Sources", "▼ Apps")
        private class M(val whenTxt: String, val what: String, val note: String, val item: Item, val now: Boolean = false)
        private val ms = ArrayList<M>(); private val els = ArrayList<FrameLayout>(); private lateinit var btn: TextView; private var nowAt = 0
        override fun render() {
            ms.clear(); els.clear()
            h.recent().firstOrNull()?.let { ms.add(M(h.hhmm(it.at), "Resume", "Last opened here", it)) }
            val np = d.np; val live = h.live()
            if (np != null) ms.add(M("Now", np.name, if (np.why.isNotEmpty()) "On ${np.why}" else "Playing", np, true))
            else if (live != null) ms.add(M("Now", "Live TV", "Quick access", live, true))
            val sports = opts.optString("sports").takeIf { it.isNotEmpty() }?.let { d.app(it) }
            h.upcoming().take(3).forEach { u ->
                val it = if (u.kind == "match") sports ?: h.none(u.name, "Choose the app a match opens in the phone panel (Layout, Time River)") else h.none(u.name, u.source + " · " + h.until(u.at))
                ms.add(M(if (u.live) "Live" else h.hhmm(u.at), u.name, u.source + if (u.live) "" else " · " + h.until(u.at), it))
            }
            d.scenes.take(2).forEach { ms.add(M("Later", it.name, "Your scene", it)) }
            if (ms.size < 3) h.favorites().take(4 - ms.size).forEach { ms.add(M("Any time", it.name, it.sub, it)) }
            header("", "Your evening", "A meaningful sequence, not an endless feed.")
            val track = FrameLayout(h.a); track.clipChildren = true; h.place(c, track, 140, 360, 1640, 330)
            track.addView(View(h.a).also { it.background = h.round(Color.rgb(59, 106, 134), 3f) }, FrameLayout.LayoutParams(1640, 6).apply { topMargin = 260 })
            ms.forEach { m ->
                val card = FrameLayout(h.a)
                val box = LinearLayout(h.a); box.orientation = LinearLayout.VERTICAL; box.setPadding(26, 26, 26, 26)
                box.background = h.round(Color.argb(217, 23, 41, 59), 20f, if (m.now) 2 else 0, Color.argb(102, 121, 214, 233))
                box.addView(h.text(m.whenTxt.uppercase(), 24f, FOCUS, 500).also { it.letterSpacing = .08f })
                box.addView(h.text(m.what, 38f, TEXT, 700), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
                box.addView(h.text(m.note, 24f, SUB), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6 })
                card.addView(box, FrameLayout.LayoutParams(370, 210))
                card.addView(View(h.a).also { it.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(106, 166, 196)) } }, FrameLayout.LayoutParams(30, 30).apply { leftMargin = 170; topMargin = 248 })
                track.addView(card, FrameLayout.LayoutParams(370, 290)); els.add(card)
            }
            btn = button(""); h.place(c, btn, 140, 760)
            nowAt = Math.max(0, ms.indexOfFirst { it.now })
            set("i", clamp(if (st.has("i")) int("i") else nowAt, 0, Math.max(0, ms.size - 1))); paint()
        }
        private fun paint() {
            val i = int("i"); val first = clamp(i - 1, 0, Math.max(0, ms.size - 4))
            els.forEachIndexed { n, e -> h.anim(e).translationX((n - first) * 400f).start(); e.alpha = if (n < first || n >= first + 4) 0f else 1f
                h.ring(e.getChildAt(0) as View, n == i, 20f)
                (e.getChildAt(1)).background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (n == i) TEXT else Color.rgb(106, 166, 196)) } }
            val m = ms.getOrNull(i)
            btn.text = if (m == null) "Nothing here yet" else "OK  " + if (m.item.type == "none") m.item.why else (m.item.verb.uppercase() + " " + m.item.name)
            btn.background = h.round(if (m == null || m.item.type == "none") Color.rgb(42, 60, 77) else Color.rgb(232, 247, 251), 16f)
            btn.setTextColor(if (m == null || m.item.type == "none") Color.rgb(184, 199, 211) else NativeHome.NIGHT)
        }
        override fun key(k: String): Boolean {
            when (k) { "left", "right" -> { set("i", clamp(int("i") + if (k == "right") 1 else -1, 0, ms.size - 1)); paint() }
                "ok" -> ms.getOrNull(int("i"))?.let { h.act(it.item, btn) }; "up" -> h.openSources(); "down" -> allGrid()
                "back" -> { set("i", nowAt); paint() }; else -> return false }
            return true
        }
    }

    // ===================================================================================== 08 The Gallery
    // LEFT/RIGHT frame, UP about this frame, DOWN the whole collection, OK open, BACK back.
    class Gallery(h: NativeHome) : Layout(h) {
        override val hints = listOf("◀ ▶ Frames", "OK Open", "▲ About this frame", "▼ The full collection")
        override fun background(h: NativeHome) = h.radial(Color.rgb(58, 44, 34), Color.rgb(26, 20, 17), .4f, .3f)
        private var items = listOf<Item>(); private val els = ArrayList<View>(); private lateinit var info: TextView; private var details = false
        override fun render() {
            val n = opts.optString("frames", "4").toIntOrNull() ?: 4; val photo = if (opts.optString("photo") != "off") h.photo() else null
            val out = ArrayList<Item>(); h.live()?.let { out.add(it) }; out.addAll(h.favorites()); h.sources().firstOrNull()?.let { out.add(Math.min(2, out.size), it) }
            items = out.take(if (photo != null) n - 1 else n) + listOfNotNull(photo)
            h.place(c, h.text("THE COLLECTION", 60f, Color.rgb(244, 232, 218), 700).also { it.letterSpacing = .04f }, 96, 168)
            h.place(c, h.text("Curated by you, not an algorithm.", 30f, SUB), 96, 252)
            els.clear()
            val left = (1920 - (items.size * 304 - 34)) / 2
            items.forEachIndexed { k, it ->
                val f = LinearLayout(h.a); f.orientation = LinearLayout.VERTICAL; f.setPadding(14, 14, 14, 14)
                f.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(184, 148, 104), Color.rgb(125, 93, 59))).apply { cornerRadius = 8f }
                val mat = FrameLayout(h.a); mat.setBackgroundColor(Color.rgb(20, 22, 26)); mat.setPadding(16, 16, 16, 16)
                mat.addView(h.tile(it, 210, 300, 24f, false, "tall")); f.addView(mat)
                f.addView(h.text(it.name.uppercase(), 24f, Color.rgb(26, 20, 17), 700).also { t -> t.gravity = Gravity.CENTER; t.background = h.round(Color.rgb(233, 220, 198), 4f); t.setPadding(6, 6, 6, 6) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })
                h.place(c, f, left + k * 304, 360, 270); els.add(f)
            }
            info = h.text("", 30f, Color.rgb(233, 220, 198), 500); info.gravity = Gravity.CENTER; h.place(c, info, 0, 900, 1920)
            set("i", clamp(idx(items, st.optString("id"), int("i")), 0, Math.max(0, items.size - 1))); paint()
        }
        private fun paint() {
            els.forEachIndexed { k, f -> h.ring(f, k == int("i"), 8f, color = WARM); f.translationY = if (k == int("i")) -8f else 0f }
            val it = items.getOrNull(int("i"))
            info.text = if (details && it != null) "${it.name}   ·   ${it.sub}   ·   OK ${it.verb.lowercase()}" else ""
            it?.let { set("id", it.id) }
        }
        override fun key(k: String): Boolean {
            when (k) { "left", "right" -> set("i", clamp(int("i") + if (k == "right") 1 else -1, 0, items.size - 1)); "up" -> details = !details
                "down" -> { h.openGrid("The full collection", listOfNotNull(h.live()) + h.sources() + h.all()); return true }
                "ok" -> { h.act(items.getOrNull(int("i")), els.getOrNull(int("i"))); return true }
                "back" -> if (details) details = false else set("i", 0); else -> return false }
            paint(); return true
        }
    }

    // ============================================================================ 09 Aurora, A Ambient
    // Calm until a key: any arrow shows the strip (the first press never opens anything), OK opens, BACK hides;
    // it hides again by itself after 20 s. Static light, no constant animation. Ambient: today's photo, clock centred.
    class Aurora(h: NativeHome, private val ambient: Boolean) : Layout(h) {
        override val hints = listOf("Any arrow  Show places", "OK Open", "BACK Hide")
        override val calm = true
        override val photo = ambient
        override fun background(h: NativeHome): Drawable = if (ambient) h.radial(Color.rgb(30, 40, 60), NativeHome.NIGHT) else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(NativeHome.NIGHT, Color.rgb(12, 42, 52)))
        private var items = listOf<Item>(); private val els = ArrayList<View>(); private var cels = listOf<View>(); private val ctrl = ArrayList<Pair<String, () -> Unit>>()
        private lateinit var time: TextView; private lateinit var date: TextView; private lateinit var np: TextView; private lateinit var strip: LinearLayout; private lateinit var ctrlRow: LinearLayout
        private var shown = false; private var zone = "strip"
        private val hide = Runnable { shown = false; paint() }
        override fun render() {
            if (!ambient) c.addView(Waves(h.a), FrameLayout.LayoutParams(1920, 1080))
            val col = LinearLayout(h.a); col.orientation = LinearLayout.VERTICAL; col.gravity = if (ambient) Gravity.CENTER_HORIZONTAL else Gravity.START
            time = h.text("", 220f, TEXT, 300); date = h.text("", 34f, Color.rgb(214, 227, 236)); np = h.text("", 28f, Color.rgb(159, 227, 242))
            col.addView(time, LinearLayout.LayoutParams(-2, -2)); col.addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 10 }); col.addView(np, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 18 })
            if (ambient) h.place(c, col, 0, 330, 1920) else h.place(c, col, 120, 320)
            val n = opts.optString("strip", if (ambient) "4" else "5").toIntOrNull() ?: 5
            val out = ArrayList<Item>(); h.live()?.let { out.add(it) }; h.sources().firstOrNull()?.let { out.add(it) }; out.addAll(h.favorites())
            items = out.take(n - 1) + h.allApps()
            strip = LinearLayout(h.a); strip.background = h.round(Color.argb(209, 15, 30, 40), 26f); strip.setPadding(16, 16, 16, 16)
            els.clear()
            items.forEach { it ->
                val b = LinearLayout(h.a); b.orientation = LinearLayout.VERTICAL; b.gravity = Gravity.CENTER; b.background = h.round(Color.argb(230, 23, 41, 59), 16f)
                b.addView(h.text(if (it.badge == "LIVE") "TV" else h.mono(it), 40f, TEXT, 600))
                b.addView(h.text(it.name, 24f, SUB).also { t -> t.gravity = Gravity.CENTER }, LinearLayout.LayoutParams(150, -2))
                strip.addView(b, LinearLayout.LayoutParams(170, 120).apply { leftMargin = 8; rightMargin = 8 }); els.add(b)
            }
            val sw = items.size * 186 + 32
            h.place(c, strip, if (ambient) (1920 - sw) / 2 else 1920 - 120 - sw, 1080 - 120 - 152)
            ctrl.clear(); d.np?.let { p -> ctrl.add(((if (p.playing) "❚❚ " else "▶ ") + p.name) to { h.act(p) }) }
            ctrl.add("Sources" to { h.openSources() }); ctrl.add("Menu" to { h.openMenu() })
            ctrlRow = LinearLayout(h.a); cels = ctrl.map { x -> chip(x.first).also { ctrlRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18 }) } }
            ctrlRow.gravity = if (ambient) Gravity.CENTER_HORIZONTAL else Gravity.END
            h.place(c, ctrlRow, if (ambient) 0 else 120, 1080 - 120 - 152 - 96, 1680 + if (ambient) 240 else 0)   // just above the strip
            set("i", clamp(int("i"), 0, items.size - 1)); tick(); paint()
        }
        override fun refresh() { val s = shown; val z = zone; render(); shown = s; zone = z; paint() }
        override fun tick() {
            val (t, ap) = h.clockText(); time.text = if (ap.isEmpty()) t else "$t $ap"
            date.text = h.dateText() + h.weatherText().let { if (it.isEmpty()) "" else "  ·  $it" }
            np.text = d.np?.let { "♪  " + it.name + if (it.emoji.isNotEmpty()) " — " + it.emoji else "" } ?: ""
        }
        private fun paint() {
            h.anim(strip).alpha(if (shown) 1f else 0f).translationY(if (shown) 0f else 20f).start()
            ctrlRow.alpha = if (shown) 1f else 0f
            els.forEachIndexed { k, v -> h.ring(v, shown && zone == "strip" && k == int("i"), 16f) }
            cels.forEachIndexed { k, v -> h.ring(v, shown && zone == "ctrl" && k == int("c"), 32f) }
            h.a.ui.removeCallbacks(hide); if (shown) h.a.ui.postDelayed(hide, 20_000)
        }
        override fun key(k: String): Boolean {
            if (!shown) { if (k == "back") return true; shown = true; zone = "strip"; paint(); return true }
            if (k == "back") { shown = false; paint(); return true }
            if (zone == "ctrl") {
                when (k) { "left", "right" -> set("c", clamp(int("c") + if (k == "right") 1 else -1, 0, ctrl.size - 1)); "down" -> zone = "strip"
                    "ok" -> { ctrl[int("c")].second(); return true } }
                paint(); return true
            }
            when (k) { "left", "right" -> set("i", clamp(int("i") + if (k == "right") 1 else -1, 0, items.size - 1)); "up" -> zone = "ctrl"
                "down" -> { allGrid(); return true }; "ok" -> { h.act(items[int("i")], els.getOrNull(int("i"))); return true } }
            paint(); return true
        }
    }
    /** Aurora's light: a few still curves and a mountain line (no animation). */
    class Waves(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val hill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(204, 8, 16, 24) }
        override fun onDraw(cv: Canvas) {
            val cols = intArrayOf(Color.rgb(63, 182, 168), Color.rgb(47, 127, 168), Color.rgb(87, 199, 176))
            for (k in 0 until 9) {
                val y = 560f + k * 18; val a = 120f + k * 9
                p.color = cols[k % 3]; p.alpha = ((0.25f + (k % 4) * 0.12f) * 255).toInt(); p.strokeWidth = 2f + (k % 3)
                cv.drawPath(Path().apply { moveTo(0f, y + 60); cubicTo(420f, y + a, 820f, y - a, 1240f, y - 40); quadTo(1600f, y - a, 1920f, y - 90) }, p)
            }
            cv.drawPath(Path().apply { moveTo(0f, 1080f); lineTo(380f, 840f); lineTo(700f, 1000f); lineTo(1080f, 800f); lineTo(1500f, 1010f); lineTo(1920f, 860f); lineTo(1920f, 1080f); close() }, hill)
        }
    }

    // ================================================================================== 10 The Command Deck
    // LEFT/RIGHT favourites, UP TV controls, DOWN all apps, OK do it, BACK back to the dock. Only controls with a
    // verified way to read and set them: the input (helper), playback (media session), the VPN, the weather shown.
    class Deck(h: NativeHome) : Layout(h) {
        override val hints = listOf("◀ ▶ Favourites", "▲ TV controls", "OK Do it", "▼ All apps")
        private var dock = listOf<Item>(); private var dels = listOf<View>(); private val ctrl = ArrayList<Triple<String, String, () -> Unit>>(); private val cels = ArrayList<View>()
        private lateinit var sk: TextView; private lateinit var sname: TextView; private lateinit var ssub: TextView; private lateinit var sbtn: TextView; private var zone = "dock"
        override fun render() {
            h.place(c, h.text("YOUR TV, UNDER CONTROL", 48f, TEXT, 700).also { it.letterSpacing = .04f }, 96, 168)
            val stage = LinearLayout(h.a); stage.orientation = LinearLayout.VERTICAL; stage.setPadding(50, 50, 50, 50)
            stage.background = h.round(Color.argb(217, 17, 39, 58), 28f, 3, Color.argb(140, 121, 214, 233)); h.place(c, stage, 96, 270, 1040, 400)
            sk = h.text("", 26f, FOCUS, 500); sname = h.text("", 100f, TEXT, 700); ssub = h.text("", 30f, SUB, 500); sbtn = button("")
            stage.addView(sk); stage.addView(sname); stage.addView(ssub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 10; bottomMargin = 30 }); stage.addView(sbtn, LinearLayout.LayoutParams(-2, -2))
            ctrl.clear(); cels.clear()
            val last = h.recent().firstOrNull { it.type == "source" }
            if (d.sources.isNotEmpty()) ctrl.add(Triple("Input", last?.let { it.name + " (chosen here)" } ?: "Choose…") { h.openSources() })
            d.np?.let { np -> ctrl.add(Triple("Playback", (if (np.playing) "Playing · " else "Paused · ") + np.name) { h.act(np) }) }
            if (d.regions.length() > 0) {
                var cur = "Local"; for (i in 0 until d.regions.length()) d.regions.optJSONObject(i)?.let { if (it.optString("id") == d.regionNow) cur = it.optString("name") }
                ctrl.add(Triple("VPN", cur) { h.regionMenu() })
            }
            ctrl.add(Triple("Weather", h.weatherText().ifEmpty { "Not set" }) { h.toast("Set the weather place in the phone panel") })
            ctrl.forEachIndexed { n, (k, v, _) ->
                val b = LinearLayout(h.a); b.orientation = LinearLayout.VERTICAL; b.setPadding(26, 16, 26, 16); b.background = h.round(Color.argb(217, 23, 41, 59), 16f)
                b.addView(h.text(k.uppercase(), 22f, FOCUS, 500).also { it.letterSpacing = .08f }); b.addView(h.text(v, 34f, TEXT, 600))
                h.place(c, b, 1190, 270 + n * 112, 634); cels.add(b)
            }
            dock = listOfNotNull(h.live()) + h.favorites().take(5) + h.allApps()
            dels = row(dock, 96, 720, 190, 110, 22)
            zone = if (st.optString("zone") == "ctrl") "ctrl" else "dock"
            set("i", clamp(idx(dock, st.optString("id"), int("i")), 0, dock.size - 1)); paint()
        }
        private fun paint() {
            dels.forEachIndexed { k, v -> h.ringTile(v, zone == "dock" && k == int("i")) }
            cels.forEachIndexed { k, v -> h.ring(v, zone == "ctrl" && k == int("c"), 16f) }
            val it = if (zone == "dock") dock.getOrNull(int("i")) else null; val ct = if (zone == "ctrl") ctrl.getOrNull(int("c")) else null
            sk.text = (if (it != null) when (it.type) { "source" -> "Input"; "all" -> "Everything"; else -> "App" } else "TV control").uppercase()
            sname.text = it?.name ?: ct?.first ?: ""; ssub.text = (it?.sub ?: ct?.second ?: "").uppercase()
            sbtn.text = if (it != null) "OK  " + it.verb.uppercase() else "OK  CHANGE"
            if (it != null) set("id", it.id); set("zone", zone)
        }
        override fun key(k: String): Boolean {
            if (zone == "ctrl") {
                when (k) { "up", "down" -> { val j = int("c") + if (k == "down") 1 else -1; if (j >= ctrl.size) zone = "dock" else set("c", Math.max(0, j)) }
                    "left", "back" -> zone = "dock"; "ok" -> { ctrl[int("c")].third(); return true } }
                paint(); return true
            }
            when (k) { "left", "right" -> set("i", clamp(int("i") + if (k == "right") 1 else -1, 0, dock.size - 1)); "up" -> { zone = "ctrl"; set("c", ctrl.size - 1) }
                "down" -> { allGrid(); return true }; "ok" -> { h.act(dock[int("i")], dels.getOrNull(int("i"))); return true }; "back" -> set("i", 0) }
            paint(); return true
        }
    }

    // ========================================================================================= T Theater
    // The recommended default (PDF p.25 and p.32): today's photo, a headline, what is playing, one row of places.
    class Theater(h: NativeHome) : Layout(h) {
        override val hints = listOf("◀ ▶ Choose", "OK Open", "▲ Now playing", "▼ All apps")
        override val photo = true
        private var rowItems = listOf<Item>(); private var els = listOf<View>(); private var npCard: View? = null; private lateinit var meta: TextView; private var zone = "row"
        override fun render() {
            h.place(c, h.text("YOUR SCREEN. YOUR RULES.", 26f, FOCUS, 500).also { it.letterSpacing = .12f }, 96, 190)
            val title = h.text(opts.optString("headline", "Make tonight your own.").ifEmpty { "Make tonight your own." }, 104f, TEXT, 600, 2)
            title.typeface = android.graphics.Typeface.SERIF; h.place(c, title, 96, 236, 1100)
            meta = h.text("", 28f, Color.rgb(214, 227, 236)); h.place(c, meta, 96, 500, 1100)
            npCard = d.np?.let { np -> nowCard(np).also { h.place(c, it, 1264, 180, 560) } }
            val r = ArrayList(h.favorites().take(5)); h.live()?.let { r.add(Math.min(2, r.size), it) }; h.sources().firstOrNull()?.let { r.add(Math.min(3, r.size), it) }; r.add(h.allApps())
            rowItems = r
            h.place(c, h.text("YOUR FAVOURITES", 24f, SUB, 500).also { it.letterSpacing = .12f }, 96, 652)
            els = row(rowItems, 96, 700, 196, 130, 20)
            zone = "row"; set("i", clamp(idx(rowItems, st.optString("id"), int("i")), 0, rowItems.size - 1)); tick(); paint()
        }
        private fun nowCard(np: Item): View {
            val card = LinearLayout(h.a); card.gravity = Gravity.CENTER_VERTICAL; card.setPadding(20, 20, 20, 20); card.background = h.round(Color.argb(224, 23, 41, 59), 22f)
            val art = android.widget.ImageView(h.a); art.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; art.background = h.round(Color.rgb(47, 72, 88), 14f); art.clipToOutline = true
            np.icon?.let { art.setImageDrawable(it) }; card.addView(art, LinearLayout.LayoutParams(110, 110))
            val t = LinearLayout(h.a); t.orientation = LinearLayout.VERTICAL; t.setPadding(20, 0, 20, 0)
            t.addView(h.text(((if (np.playing) "Now playing" else "Paused") + if (np.why.isNotEmpty()) " · " + np.why else "").uppercase(), 20f, FOCUS, 500))
            t.addView(h.text(np.name, 30f, TEXT, 600)); if (np.emoji.isNotEmpty()) t.addView(h.text(np.emoji, 24f, SUB))
            card.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(h.text(if (np.playing) "❚❚" else "▶", 30f, NativeHome.NIGHT, 700).also { it.gravity = Gravity.CENTER; it.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TEXT) } }, LinearLayout.LayoutParams(70, 70))
            return card
        }
        override fun tick() { meta.text = h.dateText() + h.weatherText().let { if (it.isEmpty()) "" else "  ·  $it" } + if (d.wallTitle.isNotEmpty()) "  ·  " + d.wallTitle else "" }
        private fun paint() {
            els.forEachIndexed { k, v -> h.ringTile(v, zone == "row" && k == int("i")) }
            h.ring(npCard, zone == "np", 22f)
            rowItems.getOrNull(int("i"))?.let { set("id", it.id) }
        }
        override fun key(k: String): Boolean {
            if (zone == "np") { when (k) { "ok" -> { h.act(d.np); return true }; "down", "back" -> zone = "row" }; paint(); return true }
            when (k) { "left", "right" -> set("i", clamp(int("i") + if (k == "right") 1 else -1, 0, rowItems.size - 1)); "up" -> if (npCard != null) zone = "np"
                "down" -> { allGrid(); return true }; "ok" -> { h.act(rowItems[int("i")], els.getOrNull(int("i"))); return true }; "back" -> set("i", 0) }
            paint(); return true
        }
    }

    // ===================================================================================== C Scene Stage
    // The scenes the household allowed on Home, as cards: OK starts one, its result is said on screen.
    class Scenes(h: NativeHome) : Layout(h) {
        override val hints = listOf("◀ ▶ Scenes", "OK Start the scene", "▼ Favourites", "▲ Sources")
        override fun background(h: NativeHome) = h.radial(Color.rgb(58, 42, 74), Color.rgb(18, 15, 28), .5f, .2f)
        private var sc = listOf<Item>(); private val els = ArrayList<View>(); private var favs = listOf<Item>(); private var fels = listOf<View>(); private lateinit var btn: TextView; private var zone = "cards"
        override fun render() {
            h.place(c, h.text("ONE PRESS. A WHOLE EVENING.", 26f, FOCUS, 500).also { it.letterSpacing = .12f }, 96, 168)
            h.place(c, h.text("Set the mood.", 72f, TEXT, 600).also { it.typeface = android.graphics.Typeface.SERIF }, 96, 210)
            h.place(c, h.text("Your scenes, made in the phone panel.", 30f, SUB), 96, 304)
            sc = d.scenes; els.clear()
            sc.forEachIndexed { n, s ->
                val card = LinearLayout(h.a); card.orientation = LinearLayout.VERTICAL; card.setPadding(30, 30, 30, 30)
                card.background = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = 330f; setGradientCenter(.5f, .35f); colors = intArrayOf(s.color, Color.rgb(16, 27, 40)); cornerRadius = 26f }
                card.addView(h.text(s.emoji, 110f, TEXT).also { it.gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10; bottomMargin = 26 })
                card.addView(h.text(s.name, 36f, TEXT, 700)); card.addView(h.text(s.sub, 22f, Color.rgb(216, 207, 227), 400, 2))
                h.place(c, card, 96 + n * 360, 400, 330, 380); els.add(card)
            }
            if (sc.isEmpty()) h.place(c, h.text("No scenes on Home yet.\nIn the phone panel: Layout, Scene Stage, then choose which scenes may start from the TV.", 30f, SUB, 500, 3), 96, 430, 1100)
            btn = button(""); h.place(c, btn, 1300, 300)
            favs = h.favorites().take(7); fels = row(favs, 96, 830, 170, 90, 18)
            zone = if (sc.isEmpty()) "favs" else "cards"
            set("i", clamp(idx(sc, st.optString("id"), int("i")), 0, Math.max(0, sc.size - 1))); paint()
        }
        override fun refresh() { val z = zone; render(); if (sc.isNotEmpty()) zone = z; paint() }
        private fun paint() {
            els.forEachIndexed { k, v -> h.ring(v, zone == "cards" && k == int("i"), 26f, color = TEXT); v.translationY = if (zone == "cards" && k == int("i")) -8f else 0f }
            fels.forEachIndexed { k, v -> h.ringTile(v, zone == "favs" && k == int("f")) }
            val s = sc.getOrNull(int("i")); val f = favs.getOrNull(int("f"))
            btn.text = if (zone == "cards" && s != null) "OK  START  " + s.name.uppercase() else if (zone == "favs" && f != null) "OK  OPEN  " + f.name.uppercase() else ""
            btn.visibility = if (btn.text.isEmpty()) View.INVISIBLE else View.VISIBLE
            s?.let { set("id", it.id) }
        }
        override fun key(k: String): Boolean {
            if (zone == "favs") {
                when (k) { "left", "right" -> set("f", clamp(int("f") + if (k == "right") 1 else -1, 0, favs.size - 1))
                    "up", "back" -> if (sc.isNotEmpty()) zone = "cards" else if (k == "up") { h.openSources(); return true }
                    "ok" -> { h.act(favs.getOrNull(int("f")), fels.getOrNull(int("f"))); return true } }
                paint(); return true
            }
            when (k) { "left", "right" -> set("i", clamp(int("i") + if (k == "right") 1 else -1, 0, sc.size - 1)); "down" -> zone = "favs"
                "up" -> { h.openSources(); return true }; "ok" -> { h.act(sc.getOrNull(int("i")), els.getOrNull(int("i"))); return true }; "back" -> set("i", 0) }
            paint(); return true
        }
    }
}
