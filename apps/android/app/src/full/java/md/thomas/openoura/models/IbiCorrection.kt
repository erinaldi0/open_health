package md.thomas.openoura.models

/**
 * Exact Kotlin twin of tools/ibi_correction.py: Oura's on-phone IBI correction
 * (libappecore.so rr_correction_run_ex, as the official app runs it before NSSA).
 * Port of apps/ios/OuraApp/IbiCorrection.swift. Every floating-point step is Float
 * (IEEE float32) in the same order as the Python port, which is verified bit-for-bit
 * against the emulated native code (Tests/NssaParityFixture.json runs it here too).
 * Validity: 0 first beat, 1 valid, 2 invalid (amplitude 0), 3 refilled gap beat.
 */
object IbiCorrection {

    /** raw: (timestamp ms, ibi ms, amplitude) in ring order → (ts, ibi, amp, validity). */
    fun correct(raw: List<Triple<Long, Int, Int>>): List<Beat> {
        val c = State()
        for ((ts, ibi, amp) in raw) c.run(ibi, amp, ts)
        return c.out
    }

    class Beat(val ts: Long, val ibi: Int, val amp: Int, val validity: Int)

    private const val CAP = 64
    private val K60000 = 60000.0f

    private fun hr(ibi: Int): Float = K60000 / ibi.toFloat()

    /**
     * float32 → int, nearest with ties away from zero (FCVTAS); NaN → 0, saturating.
     * Matches ARM FCVTAS instruction exactly.
     */
    fun fcvtas(x: Float): Int {
        if (x.isNaN()) return 0
        val absX = kotlin.math.abs(x)
        val r = kotlin.math.floor(absX + 0.5f).toInt()
        return if (x >= 0) r else -r
    }

    /** float32 → int, truncating toward zero (FCVTZS); NaN → 0, saturating. */
    fun fcvtzs(x: Float): Int {
        if (x.isNaN()) return 0
        val r = kotlin.math.truncate(x).toInt()
        if (r >= 9.2e18f) return Int.MAX_VALUE
        if (r <= -9.2e18f) return Int.MIN_VALUE
        return r
    }

    private class Entry(
        var ts: Long = 0, var ts2: Long = 0,
        var ibi: Int = 0, var amp: Int = 0,
        var ibiOut: Int = 0, var ampOut: Int = 0,
        var validity: Int = 0, var ok: Int = 0, var extra: Int = 0,
    ) {
        fun copy(): Entry {
            val e = Entry(ts, ts2, ibi, amp)
            e.ibiOut = ibiOut
            e.ampOut = ampOut
            e.validity = validity
            e.ok = ok
            e.extra = extra
            return e
        }
    }

    private class RingBuf {
        val slots: Array<Entry> = Array(CAP) { Entry() }
        var head = 0
        var tail = 0
        var full = false

        fun push(e: Entry) {
            slots[head] = e.copy()
            if (full) tail = if (tail + 1 == CAP) 0 else tail + 1
            head = if (head + 1 == CAP) 0 else head + 1
            full = head == tail
        }

        val isEmpty: Boolean get() = !full && head == tail

        fun pop(): Entry? {
            if (isEmpty) return null
            val e = slots[tail].copy()
            full = false
            tail = if (tail + 1 == CAP) 0 else tail + 1
            return e
        }

        val count: Int get() = if (full) CAP else (head + CAP - tail) % CAP

        /** read_from_head(signed char i): 0 = newest, -1 = previous … (the slot itself). */
        fun rfh(i0: Int): Entry {
            val i = ((i0 + 128) and 0xff) - 128
            require(!isEmpty && kotlin.math.abs(i) < CAP) { "native abort in ibirbuffer_read_from_head" }
            val idx = i + CAP + head - 1
            require(idx >= 0) { "native abort in ibirbuffer_read_from_head" }
            return slots[idx % CAP]
        }
    }

    fun median7U16(v: IntArray): Int {
        val M = 0xffff
        var w8 = v[0]; var w9 = v[1]; var w12 = v[2]
        var w10 = if (w9 > w8) w9 else w8
        val w13 = if (w9 < w8) w9 else w8
        var w11 = 0
        if (w9 == M) { w10 = M; w9 = w8 } else { w9 = w13 }
        if (w12 == M) { w8 = w10; w10 = M }
        else if (w12 >= w10) { w8 = w10; w10 = w12 }
        else { w8 = if (w12 > w9) w12 else w9; w9 = if (w12 < w9) w12 else w9 }
        w12 = v[3]
        if (w12 == M) { w11 = w8; w8 = w10; w10 = M }
        else if (w12 >= w10) { w11 = w8; w8 = w10; w10 = w12 }
        else if (w12 >= w8) { w11 = w8; w8 = w12 }
        else { w11 = if (w12 > w9) w12 else w9; w9 = if (w12 < w9) w12 else w9 }
        w12 = v[4]
        if (w12 >= w10) { w9 = w8; w8 = w10 }
        else if (w12 >= w8) { w9 = w8; w8 = w12 }
        else {
            val w10Old = w11
            val t = if (w12 > w9) w12 else w9
            w11 = if (w12 < w10Old) t else w11
            w9 = if (w12 > w10Old) w12 else w10Old
        }
        w10 = v[5]
        if (w10 < w8) {
            val a8 = w11; val a11 = w9
            val t = if (w10 > a8) w10 else a8
            w9 = if (w10 < a11) t else w9
            w8 = if (w10 > a11) w10 else a11
        }
        w10 = v[6]
        val t = if (w10 > w9) w10 else w9
        return if (w10 < w8) t else w8
    }

    fun s16(x0: Int): Int {
        val x = x0 and 0xffff
        return if (x and 0x8000 != 0) x - 0x10000 else x
    }

    fun median7S16(v: IntArray): Int {
        val M = 0x7fff
        var w8 = s16(v[0])
        val w9r = s16(v[1])
        var w12 = s16(v[2])
        val w10u = w9r and 0xffff
        val w13 = if (w9r > w8) w9r else w8
        val w14 = if (w9r < w8) w9r else w8
        var w9: Int; var w10: Int; var w11 = 0
        if (w10u == M) { w9 = w10u; w10 = w8 } else { w9 = w13; w10 = w14 }
        if (w12 == M) { w8 = w9; w9 = M }
        else if (w12 >= s16(w9)) { w8 = w9; w9 = w12 }
        else {
            val w10s = s16(w10)
            w8 = if (w12 > w10s) w12 else w10s
            w10 = if (w12 < w10s) w12 else w10s
        }
        w12 = s16(v[3])
        if (w12 == M) { w11 = w8; w8 = w9; w9 = M }
        else if (w12 >= s16(w9)) { w11 = w8; w8 = w9; w9 = w12 }
        else if (w12 >= s16(w8)) { w11 = w8; w8 = w12 }
        else {
            val w10s = s16(w10)
            w11 = if (w12 > w10s) w12 else w10s
            w10 = if (w12 < w10s) w12 else w10s
        }
        w12 = s16(v[4])
        if (w12 >= s16(w9)) { w10 = w8; w8 = w9 }
        else if (w12 >= s16(w8)) { w10 = w8; w8 = w12 }
        else {
            val w9s = s16(w10); val w10s = s16(w11)
            val t = if (w12 > w9s) w12 else w9s
            w11 = if (w12 < w10s) t else w11
            w10 = if (w12 > w10s) w12 else w10s
        }
        w9 = s16(v[5])
        if (w9 < s16(w8)) {
            val a8 = s16(w11); val a11 = s16(w10)
            val t = if (w9 > a8) w9 else a8
            w10 = if (w9 < a11) t else w10
            w8 = if (w9 > a11) w9 else a11
        }
        w9 = s16(v[6])
        val w10s = s16(w10)
        val t = if (w9 > w10s) w9 else w10s
        return if (w9 < s16(w8)) t else w8
    }

    private class State {
        val buf1 = RingBuf(); val buf2 = RingBuf()
        var count = 0
        val maxOut = 4; val maxGap = 5000
        var prevIbi = 1000; var nOut = 0; var nValid = 0
        val interpTs = false; val hrMode = true
        var prevHr = 60.0f
        val maxHr = 150.0f
        var badRun = 0; var savedBadRun = 0
        val dlIbi = IntArray(7); val dlRawAmp = IntArray(7)
        val dlAmp = IntArray(7); val dlVal = IntArray(7)
        val dlTs = LongArray(7); val dlExtra = IntArray(7)
        var mePrevHr = 0.0f
        val meMaxHr = 150.0f
        val meFlag = true
        var mePrevIbi = 1000
        var nowTs: Long = 0
        var msFlag = true
        val out = ArrayList<Beat>()

        fun var7Buf1(): Float {
            val hs = (-6..0).map { hr(buf1.rfh(it).ibi) }
            var s = hs[0]
            for (h in hs.drop(1)) s = s + h
            val mean = s / 7.0f
            var acc: Float? = null
            for (h in hs) { val d = h - mean; val d2 = d * d; acc = acc?.plus(d2) ?: d2 }
            return acc!! / 6.0f
        }

        fun var7Dl(): Float {
            val hs: List<Float> = dlIbi.map { if (it != 0) hr(it) else 0.0f }
            var s = if (dlIbi[0] == 0) 0.0f else hs[0]
            for (i in 1..6) if (dlIbi[i] != 0) s = s + hs[i]
            val mean = s / 7.0f
            var acc: Float? = null
            for (h in hs) { val d = h - mean; val d2 = d * d; acc = acc?.plus(d2) ?: d2 }
            return acc!! / 6.0f
        }

        fun medianError() {
            if (dlVal[3] != 1) return
            val w22 = dlIbi[3]
            val s8 = hr(w22)
            if (mePrevHr == 0.0f) mePrevHr = s8
            val med = s16(median7S16(dlIbi))
            val tot = dlIbi.sumOf { s16(it) }
            val mean = tot.toFloat() / 7.0f
            val s1 = K60000 / med.toFloat()
            val s0 = K60000 / fcvtzs(mean).toFloat()
            var bad = false
            if (meFlag) {
                if (s8 <= 30.0f || s8 > meMaxHr || kotlin.math.abs(s8 - s1) > 18.0f) bad = true
                else if (s8 > (mePrevHr + 23.0f) + 4.0f) bad = true
                else if (s8 < mePrevHr * 0.625f) bad = true
                else if (s8 < (mePrevHr + -16.0f) + -4.0f) bad = true
                else {
                    val d = s0 - s8
                    if (d * d > var7Dl() * 5.0f) bad = true
                }
            } else {
                if (s8 <= 30.0f || s8 > meMaxHr || kotlin.math.abs(s8 - s1) > 18.0f) bad = true
                else if (kotlin.math.abs(w22 - mePrevIbi) * 100 > mePrevIbi * 25) bad = true
                else {
                    val d = s0 - s8
                    if (d * d > var7Dl() * 5.0f) bad = true
                    else if (dlExtra[3] >= 7) bad = true
                }
            }
            if (bad) dlVal[3] = 2
            mePrevHr = s8
            mePrevIbi = w22
        }

        fun flush(start: Int, n: Int, dec: Boolean) {
            var w20 = start
            var w23 = n.toLong()
            while (true) {
                val e = buf1.rfh(w20)
                val o = e.copy()
                o.ibiOut = e.ibi; o.ampOut = 0; o.validity = 2; o.ts2 = e.ts
                buf2.push(o)
                e.ibi = 0; e.amp = 0; e.validity = 0
                buf1.pop()
                if (dec) badRun = (badRun - 1) and 0xff
                w23 = (w23 - 1) and 0xffffffffL
                w20 += 1
                if ((w23 and 0xffL) == 0L) break
            }
        }

        fun afterGap() {
            if (badRun > 60) flush(-63, badRun, true)
            emitLast()
        }

        fun emitLast() {
            val e = buf1.rfh(-3)
            if (e.ok == 1) {
                val o = e.copy()
                o.validity = 1; o.ibiOut = e.ibi; o.ampOut = e.amp; o.ts2 = e.ts
                buf2.push(o)
            }
        }

        fun run(ibi0: Int, amp0: Int, ts: Long) {
            val ibi = ibi0 and 0xffff; val amp = amp0 and 0xffff
            nowTs = ts
            if (ibi != 0) ingest(ibi, amp, 0)
            output()
        }

        fun ingest(ibi: Int, amp: Int, extra: Int) {
            count += 1
            val newEntry = Entry(ts = nowTs, ibi = ibi, amp = amp, ok = 1, extra = extra)
            buf1.push(newEntry)
            if (count == 1) {
                val o = newEntry.copy()
                o.ibiOut = ibi; o.ampOut = amp; o.validity = 0; o.ts2 = nowTs
                if ((nowTs - 1).toULong() <= 99_999_999_998uL) msFlag = false
                buf2.push(o)
                return
            }
            if (count < 5) return
            val e = buf1.rfh(-3)
            val s8 = if (e.ibi != 0) hr(e.ibi) else K60000
            val n = buf1.count
            val update: Boolean
            if (n <= 6) {
                var bad = false
                if (hrMode) {
                    if (s8 <= 30.0f || s8 > maxHr) bad = true
                    else if (s8 < prevHr * 0.625f || s8 > prevHr + 18.0f) bad = true
                    else if (s8 < prevHr + -25.0f) bad = true
                } else {
                    if (s8 <= 30.0f || s8 > maxHr) bad = true
                    else if (kotlin.math.abs(e.ibi - prevIbi) * 100 > prevIbi * 20) bad = true
                    else if (e.extra >= 7) bad = true
                }
                if (bad) { e.ok = 0; badRun = (badRun + 1) and 0xff }
                else { e.ok = 1; savedBadRun = badRun; badRun = 0 }
                update = true
            } else {
                val m = median7U16(IntArray(7) { buf1.rfh(it - 6).ibi })
                val mm = if (m and 0xfffe != 0) m else 1
                val s9 = K60000 / mm.toFloat()
                val tot = (-6..0).reduce { acc, i -> acc + buf1.rfh(i).ibi }
                val mean = tot.toFloat() / 7.0f
                var s0 = if (mean != 0.0f) K60000 / mean else 0.0f
                s0 = s0 - s8
                var bad = false
                if (s8 <= 30.0f || s8 > maxHr || kotlin.math.abs(s8 - s9) > 14.0f) bad = true
                else if (hrMode) {
                    if (s8 < prevHr * 0.625f || s8 > prevHr + 23.0f) bad = true
                    else if (s8 < prevHr + -16.0f) bad = true
                    else if (s0 * s0 > var7Buf1() * 1.5f) bad = true
                } else {
                    if (kotlin.math.abs(e.ibi - prevIbi) * 100 > prevIbi * 20) bad = true
                    else if (s0 * s0 > var7Buf1() * 1.5f) bad = true
                    else if (e.extra >= 7) bad = true
                }
                if (bad) {
                    e.ok = 0; badRun = (badRun + 1) and 0xff
                    update = badRun >= 4
                } else {
                    e.ok = 1; savedBadRun = badRun; badRun = 0
                    update = true
                }
            }
            if (update) { prevHr = s8; prevIbi = e.ibi }
            val a = buf1.rfh(-3); val b = buf1.rfh(-4)
            if (a.ok == 1 && b.ok == 0) { gap(); return }
            if (badRun > 60) flush(-63, badRun, true)
            emitLast()
        }

        fun gap() {
            var w24 = 0
            var g: Int
            while (true) {
                g = w24 - 4
                if (buf1.rfh(g).ok != 0) break
                val w8 = w24 - 5
                w24 -= 1
                if (!(-63 <= w8 && w8 <= -1)) {
                    if (badRun > 60) flush(-63, badRun, true)
                    else if (savedBadRun != 0) flush(-3 - savedBadRun, savedBadRun, false)
                    emitLast()
                    return
                }
            }
            var w21 = w24 - 3
            val ge = buf1.rfh(g)
            val gi = ge.ibi; val gts = ge.ts
            val le = buf1.rfh(-3)
            val li = le.ibi; val lts = le.ts
            var sumbad = 0
            if (g <= -5) for (k in w21 until -3) sumbad += buf1.rfh(k).ibi
            val w11 = li + gi
            var nfill = fcvtas(sumbad.toFloat() / (w11 shr 1).toFloat())
            if (nfill <= 1) nfill = 1
            val nbad = -w24
            val same = nfill == nbad
            if (sumbad > maxGap || (1 - w24) > maxOut) {
                var lastTs: Long = 0
                if (buf1.rfh(w21).ok == 0) {
                    while (true) {
                        val e = buf1.rfh(w21)
                        val o = e.copy()
                        o.ibiOut = e.ibi; o.ampOut = 0; o.validity = 2; o.ts2 = e.ts
                        buf2.push(o)
                        w21 += 1
                        lastTs = e.ts
                        if (buf1.rfh(w21).ok != 0) break
                    }
                }
                if (maxGap != 0) buf2.push(Entry(ts2 = lastTs, validity = 2))
                afterGap()
                return
            }
            val step = fcvtas((li - gi).toFloat() / (nfill + 1).toFloat())
            val end = nfill + w24 - 4
            var cur = step + gi
            var cum = 0
            var lastTs: Long = 0
            var w20 = g
            while (true) {
                val w22 = w20 + 1
                val e = buf1.rfh(w22)
                var o = e.copy()
                if (nbad >= nfill || w22 < -3) lastTs = e.ts else o = Entry(ts = lastTs)
                o.ibiOut = cur and 0xffff
                o.ampOut = 0
                o.validity = 3
                buf2.push(o)
                w20 += 1
                cum += cur
                cur += step
                if (w20 >= end) break
            }
            if (interpTs && (w11 >= 2)) {
                restoreOrValidate(same, nfill, g, end, w21)
                return
            }
            val diff = sumbad - cum
            var q = diff.toFloat() / nfill.toFloat()
            q = if (diff < 0) kotlin.math.ceil(q) else kotlin.math.floor(q)
            val w11q = fcvtzs(q)
            val scale = if (!msFlag) 1000.0f else 1.0f
            var r = diff - nfill * w11q
            var w23 = 1 - nfill
            var keep = same
            var cts = 0L
            var w24b = g
            while (true) {
                val e2 = buf2.rfh(w23)
                val v: Int
                when {
                    r == 0 -> v = e2.ibiOut + w11q
                    r < 0 -> { v = (w11q - 1) + e2.ibiOut; r += 1 }
                    else -> { v = (w11q + 1) + e2.ibiOut; r -= 1 }
                }
                val masked = v and 0xffff
                e2.ibiOut = masked
                cts += masked.toLong()
                val t = fcvtas(cts.toFloat() / scale).toLong() + gts
                e2.ts2 = if (t.toULong() > lts.toULong()) lts else t
                if (keep) {
                    val d = kotlin.math.abs(buf1.rfh(end).ibi - e2.ibiOut)
                    keep = d < 126
                } else keep = false
                w24b += 1
                w23 += 1
                if (w24b >= end) break
            }
            if (keep) { restoreOrValidate(true, nfill, g, end, w21); return }
            afterGap()
        }

        fun restoreOrValidate(keep: Boolean, nfill: Int, g: Int, end: Int, w21: Int) {
            if (!keep) { afterGap(); return }
            var w23 = g
            var w24 = 1 - nfill
            while (true) {
                w23 += 1
                val v = buf1.rfh(w23).ibi
                buf2.rfh(w24).ibiOut = v
                w24 += 1
                if (w23 >= end) break
            }
            val li = buf1.rfh(-3).ibi
            val gi = buf1.rfh(g).ibi
            val s9 = (K60000 / minOf(li, gi).toFloat()) + 34.5f
            var mn = 0xffff; var mx = 0
            if (w21 <= -4) {
                for (i in w21 until -3) {
                    val v = buf1.rfh(i).ibi
                    if (mn >= v) mn = v
                    if (mx < v) mx = v
                }
            }
            val s8 = K60000 / mn.toFloat()
            var good = false
            if (s8 < s9) {
                var s0 = (K60000 / maxOf(li, gi).toFloat()) + -24.0f
                val lim = Float.fromBits(0x41f0cccd)
                if (s0 < lim) s0 = lim
                val s1 = K60000 / mx.toFloat()
                if (s1 > s0) good = true
            }
            var w22 = 1 - nfill
            var w23b = g
            while (true) {
                val e2 = buf2.rfh(w22)
                e2.validity = if (good) 1 else 2
                if (good) { e2.ampOut = e2.amp; e2.ts2 = e2.ts }
                w23b += 1
                w22 += 1
                if (w23b >= end) break
            }
            afterGap()
        }

        fun output() {
            if (buf2.isEmpty) return
            var outs = 0
            while (true) {
                if (outs >= maxOut) return
                val e = buf2.pop() ?: error("native assert: pop failed")
                if (e.ibiOut != 0) {
                    shiftAppend(dlIbi, e.ibiOut)
                    shiftAppend(dlRawAmp, e.amp)
                    shiftAppend(dlAmp, e.ampOut)
                    shiftAppend(dlVal, e.validity)
                    shiftAppend(dlTs, e.ts2)
                    shiftAppend(dlExtra, e.extra)
                    if (dlIbi[0] != 0) medianError()
                    if (dlIbi[3] != 0) {
                        nOut = (nOut + 1) and 0xffff
                        if (dlExtra[3] <= 1 && dlVal[3] == 1) nValid = (nValid + 1) and 0xffff
                        out.add(Beat(dlTs[3], dlIbi[3], dlAmp[3], dlVal[3] and 0xff))
                        outs += 1
                    }
                }
                if (buf2.isEmpty) return
            }
        }

        private fun shiftAppend(a: IntArray, v: Int) {
            for (i in 0..5) a[i] = a[i + 1]
            a[6] = v
        }

        private fun shiftAppend(a: LongArray, v: Long) {
            for (i in 0..5) a[i] = a[i + 1]
            a[6] = v
        }
    }
}
