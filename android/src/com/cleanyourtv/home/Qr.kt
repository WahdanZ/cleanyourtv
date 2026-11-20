package com.cleanyourtv.home

/**
 * A small QR code encoder (byte mode, error correction level M, versions 1-10): helper/qr.py, line for line, for the
 * Admin button's codes when the Chromecast serves its own panel. matrix(text) -> rows of "1" (dark) and "0" (light).
 */
object Qr {
    private val ECC = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

    private fun bit(x: Int, i: Int) = (x shr i) and 1 != 0

    private fun rawModules(v: Int): Int {
        var n = (16 * v + 128) * v + 64
        if (v >= 2) { val a = v / 7 + 2; n -= (25 * a - 10) * a - 55; if (v >= 7) n -= 36 }
        return n
    }

    private fun dataCodewords(v: Int) = rawModules(v) / 8 - ECC[v] * BLOCKS[v]

    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) { z = (z shl 1) xor ((z shr 7) * 0x11D); z = z xor (((y shr i) and 1) * x) }
        return z
    }

    private fun rsDivisor(degree: Int): IntArray {
        val r = IntArray(degree); r[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in r.indices) { r[j] = gfMul(r[j], root); if (j + 1 < r.size) r[j] = r[j] xor r[j + 1] }
            root = gfMul(root, 0x02)
        }
        return r
    }

    private fun rsRemainder(data: List<Int>, div: IntArray): List<Int> {
        val r = ArrayList(List(div.size) { 0 })
        for (b in data) {
            val f = b xor r.removeAt(0)
            r.add(0)
            for (i in div.indices) r[i] = r[i] xor gfMul(div[i], f)
        }
        return r
    }

    private fun codewords(data: ByteArray): Pair<Int, List<Int>> {
        var ver = 1
        var countBits = 8
        while (true) {
            countBits = if (ver < 10) 8 else 16
            if (4 + countBits + 8 * data.size <= dataCodewords(ver) * 8) break
            ver++
            if (ver > 10) throw IllegalArgumentException("too long for this QR encoder")
        }
        val bits = ArrayList(listOf(0, 1, 0, 0))
        for (i in countBits - 1 downTo 0) bits.add((data.size shr i) and 1)
        for (b in data) { val u = b.toInt() and 0xff; for (i in 7 downTo 0) bits.add((u shr i) and 1) }
        val cap = dataCodewords(ver) * 8
        repeat(minOf(4, cap - bits.size)) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)
        val words = ArrayList<Int>()
        var i = 0
        while (i < bits.size) { var w = 0; for (k in 0 until 8) w = (w shl 1) or bits[i + k]; words.add(w); i += 8 }
        var pad = 0xEC
        while (words.size < dataCodewords(ver)) { words.add(pad); pad = pad xor 0xEC xor 0x11 }
        val nb = BLOCKS[ver]; val eccLen = ECC[ver]
        val raw = rawModules(ver) / 8
        val short = nb - raw % nb
        val shortLen = raw / nb
        val div = rsDivisor(eccLen)
        val blocks = ArrayList<List<Int>>()
        var k = 0
        for (b in 0 until nb) {
            var dat: List<Int> = words.subList(k, k + shortLen - eccLen + (if (b < short) 0 else 1)).toList()
            k += dat.size
            val ecc = rsRemainder(dat, div)
            if (b < short) dat = dat + 0
            blocks.add(dat + ecc)
        }
        val out = ArrayList<Int>()
        for (x in blocks[0].indices) for ((j, blk) in blocks.withIndex()) if (x != shortLen - eccLen || j >= short) out.add(blk[x])
        return Pair(ver, out)
    }

    private fun mask(m: Int, x: Int, y: Int): Boolean = when (m) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> x * y % 2 + x * y % 3 == 0
        6 -> (x * y % 2 + x * y % 3) % 2 == 0
        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
    }

    private fun penalty(mod: Array<BooleanArray>): Int {
        val n = mod.size
        var score = 0
        for (dir in 0..1) for (a in 0 until n) {
            var run = 0; var prev: Boolean? = null
            for (b in 0 until n) {
                val v = if (dir == 0) mod[a][b] else mod[b][a]
                run = if (v == prev) run + 1 else 1
                prev = v
                if (run == 5) score += 3 else if (run > 5) score += 1
            }
        }
        for (y in 0 until n - 1) for (x in 0 until n - 1) {
            val c = mod[y][x]
            if (c == mod[y][x + 1] && c == mod[y + 1][x] && c == mod[y + 1][x + 1]) score += 3
        }
        var dark = 0
        for (r in mod) for (v in r) if (v) dark++
        score += Math.abs(dark * 20 - n * n * 10) / (n * n) * 10
        return score
    }

    fun matrix(text: String): List<String> {
        val (ver, words) = codewords(text.toByteArray(Charsets.UTF_8))
        val size = ver * 4 + 17
        var mod = Array(size) { BooleanArray(size) }
        val fn = Array(size) { BooleanArray(size) }
        fun put(x: Int, y: Int, dark: Boolean) { mod[y][x] = dark; fn[y][x] = true }
        for (i in 0 until size) { put(6, i, i % 2 == 0); put(i, 6, i % 2 == 0) }
        for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4))
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx; val y = cy + dy
                if (x in 0 until size && y in 0 until size) put(x, y, maxOf(Math.abs(dx), Math.abs(dy)) !in setOf(2, 4))
            }
        if (ver > 1) {
            val n = ver / 7 + 2
            val step = (ver * 8 + n * 3 + 5) / (n * 4 - 4) * 2
            val pos = (List(n - 1) { size - 7 - it * step } + 6).reversed()
            for ((i, a) in pos.withIndex()) for ((j, b) in pos.withIndex()) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) put(a + dx, b + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
        }
        fun formatBits(m: Int) {
            val data = (0 shl 3) or m
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem shr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            for (i in 0 until 6) put(8, i, bit(bits, i))
            put(8, 7, bit(bits, 6)); put(8, 8, bit(bits, 7)); put(7, 8, bit(bits, 8))
            for (i in 9 until 15) put(14 - i, 8, bit(bits, i))
            for (i in 0 until 8) put(size - 1 - i, 8, bit(bits, i))
            for (i in 8 until 15) put(8, size - 15 + i, bit(bits, i))
            put(8, size - 8, true)
        }
        formatBits(0)
        if (ver >= 7) {
            var rem = ver
            repeat(12) { rem = (rem shl 1) xor ((rem shr 11) * 0x1F25) }
            val bits = (ver shl 12) or rem
            for (i in 0 until 18) { val a = size - 11 + i % 3; val b = i / 3; put(a, b, bit(bits, i)); put(b, a, bit(bits, i)) }
        }
        var i = 0
        var r0 = size - 1
        while (r0 > 0) {
            val right = if (r0 <= 6) r0 - 1 else r0
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val y = if ((right + 1) and 2 == 0) size - 1 - vert else vert
                if (!fn[y][x] && i < words.size * 8) { mod[y][x] = bit(words[i shr 3], 7 - (i and 7)); i++ }
            }
            r0 -= 2
        }
        var best: Array<BooleanArray>? = null
        var bestScore = Int.MAX_VALUE
        for (m in 0 until 8) {
            val saved = Array(size) { mod[it].copyOf() }
            mod = Array(size) { y -> BooleanArray(size) { x -> saved[y][x] xor (!fn[y][x] && mask(m, x, y)) } }
            formatBits(m)
            val s = penalty(mod)
            if (s < bestScore) { bestScore = s; best = Array(size) { mod[it].copyOf() } }
            mod = saved
        }
        return best!!.map { row -> row.joinToString("") { if (it) "1" else "0" } }
    }
}
