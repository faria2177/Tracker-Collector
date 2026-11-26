package com.trackercollector.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns OCR words of a scanned table into rows of (SL, Name, Designation, Mobile).
 *
 * Strategy
 *  1. Drop rotated / vertical text (e.g. another sheet lying sideways in the photo).
 *  2. Find every Bangladeshi mobile number – each one anchors a table row.
 *  3. Find the header row ("Name", "Designation", "Mobile", "SL" …) that sits above
 *     those numbers and build column boundaries from it, placing each boundary in
 *     the empty vertical gap between the columns of data.
 *  4. Give every data word to its column and to the nearest row (rows may span
 *     several text lines, e.g. "Mohammad / Nurul Amin").
 *  5. If no header is found, fall back to reading each row left-to-right.
 */
object TableParser {

    private enum class Col { SL, NAME, DESIGNATION, MOBILE, OTHER }

    private class Column(var left: Float, var right: Float, val type: Col) {
        var start = Float.NEGATIVE_INFINITY
        var end = Float.POSITIVE_INFINITY
    }

    private class Anchor(val mobile: String, var span: Box, val word: OcrWord) {
        var serial = ""
        val words = HashMap<Col, MutableList<OcrWord>>()
    }

    private val KEEP_SYMBOLS = setOf("-", "&", "/")

    private val MOBILE = Regex("(?:\\+?88)?(01[3-9]\\d{8})")

    private val NAME_KEYS = listOf("name", "applicant", "customer name", "client", "নাম")
    private val DESIG_KEYS = listOf("designation", "desig", "position", "post", "occupation", "profession", "পদবি", "পদবী", "পেশা")
    private val MOBILE_KEYS = listOf("mobile", "phone", "contact", "cell", "mob", "মোবাইল", "ফোন")
    private val SL_KEYS = setOf("sl", "sl.", "s/l", "s.l", "s.l.", "serial", "sn", "s.n", "s.n.", "no", "no.", "#", "ক্রম", "ক্রমিক")

    /** Common designations, used only when there is no header row. */
    private val DESIG_WORDS = listOf(
        "manager", "asst", "assistant", "agm", "dgm", "gm", "officer", "executive", "professor",
        "lecturer", "teacher", "instructor", "engineer", "director", "md", "ceo", "founder",
        "businessman", "business", "specialist", "associate", "leader", "head", "senior", "junior",
        "principal", "doctor", "dr", "accountant", "clerk", "supervisor", "po", "so", "avp", "vp",
        "president", "chairman", "consultant", "analyst", "developer", "staff", "worker", "student"
    )

    fun parse(lines: List<OcrLine>): List<ParsedRow> {
        val usable = lines.map { l ->
            OcrLine(l.words.filter { w -> !isRotated(w) && (w.text.any { it.isLetterOrDigit() } || w.text.trim() in KEEP_SYMBOLS) })
        }.filter { it.words.isNotEmpty() }
        if (usable.isEmpty()) return emptyList()

        val h0 = median(usable.flatMap { it.words }.map { it.box.height }).coerceAtLeast(4f)
        // Drop huge marks (signatures, stamps, watermarks) and straighten a tilted photo.
        val slope = estimateSkew(usable.flatMap { it.words }.filter { it.box.height <= h0 * 3.2f }, h0)
        val clean = usable.map { l ->
            OcrLine(l.words.filter { it.box.height <= h0 * 3.2f }.map { w ->
                val dy = slope * w.box.cx
                OcrWord(w.text, Box(w.box.left, w.box.top - dy, w.box.right, w.box.bottom - dy))
            })
        }.filter { it.words.isNotEmpty() }
        if (clean.isEmpty()) return emptyList()

        val allWords = clean.flatMap { it.words }
        val h = median(allWords.map { it.box.height }).coerceAtLeast(4f)

        val mobiles = findMobiles(clean)
        if (mobiles.isEmpty()) return emptyList()

        val header = findHeader(clean, mobiles, h)
        val rows = if (header != null) parseWithHeader(allWords, mobiles, header, h)
        else parseWithoutHeader(clean, mobiles, h)

        // De-duplicate inside the same page by mobile number.
        val seen = HashSet<String>()
        return rows.filter { seen.add(it.mobile) }
    }

    // ------------------------------------------------------------------ mobiles

    private fun findMobiles(lines: List<OcrLine>): List<Anchor> {
        val out = ArrayList<Anchor>()
        for (line in lines) {
            // Merge neighbouring digit-only words ("01747 226223") before matching.
            var i = 0
            val ws = line.words
            while (i < ws.size) {
                val d = digitize(ws[i].text)
                if (d == null) { i++; continue }
                var acc = d
                var box = ws[i].box
                var j = i + 1
                while (j < ws.size && acc.length < 13) {
                    val n = digitize(ws[j].text) ?: break
                    if (acc.length + n.length > 14) break
                    acc += n; box = box.union(ws[j].box); j++
                }
                val m = MOBILE.find(acc) ?: MOBILE.find(d)
                if (m != null) {
                    val num = m.groupValues[1]
                    val useBox = if (MOBILE.find(d) != null) ws[i].box else box
                    out += Anchor(num, useBox, OcrWord(num, useBox))
                    i = if (MOBILE.find(d) != null) i + 1 else j
                } else i++
            }
        }
        return out.sortedBy { it.span.cy }
    }

    /** Returns only the digits of a number-like word (fixing O→0, l/I→1), or null. */
    private fun digitize(raw: String): String? {
        val t = raw.trim().trimEnd(',', ';', '.', ')').trimStart('(', '+')
        if (t.isEmpty()) return null
        if (t.contains(',') || t.contains('/')) return null       // money like 7,00,000/-
        val mapped = buildString {
            for (c in t) when (c) {
                'O', 'o', 'D', 'Q' -> append('0')
                'l', 'I', '|', 'i', '!' -> append('1')
                'S' -> append('5')
                'B' -> append('8')
                '-', '.', ' ', '–' -> {}
                else -> append(c)
            }
        }
        if (mapped.isEmpty() || !mapped.all { it.isDigit() }) return null
        // Require the original word to be mostly digits, so names never turn into numbers.
        val digitCount = t.count { it.isDigit() }
        if (digitCount < max(2, (t.length * 0.6).toInt())) return null
        return mapped
    }

    // ------------------------------------------------------------------ header

    private class HeaderResult(val columns: List<Column>, val bottom: Float)

    private fun findHeader(lines: List<OcrLine>, mobiles: List<Anchor>, h: Float): HeaderResult? {
        val words = lines.flatMap { it.words }
        val keyWords = words.filter { classify(norm(it.text)) in setOf(Col.DESIGNATION, Col.NAME, Col.MOBILE) }
        if (keyWords.isEmpty()) return null

        var best: HeaderResult? = null
        var bestScore = 0
        for (kw in keyWords) {
            val wh = max(kw.box.height, h)
            val band = words.filter { abs(it.box.cy - kw.box.cy) <= wh * 1.3f && it.box.cy < (mobiles.firstOrNull { a -> a.span.top > kw.box.bottom }?.span?.top ?: Float.MAX_VALUE) }
            val cols = buildColumns(band, wh)
            val types = cols.map { it.type }.toSet()
            if (Col.MOBILE !in types && Col.DESIGNATION !in types && Col.NAME !in types) continue
            val bottom = band.maxOf { it.box.bottom }
            val below = mobiles.filter { it.span.top > bottom - wh * 0.3f }
            if (below.isEmpty()) continue
            // How many of the numbers below line up with the header's mobile column?
            val mobCol = cols.firstOrNull { it.type == Col.MOBILE }
            val aligned = if (mobCol != null) below.count {
                it.span.right > mobCol.left - wh * 4 && it.span.left < mobCol.right + wh * 4
            } else below.size
            val score = aligned * 10 + types.count { it != Col.OTHER }
            if (score > bestScore) { bestScore = score; best = HeaderResult(cols, bottom) }
        }
        return best
    }

    /** Groups header words into columns by horizontal gaps and names each column. */
    private fun buildColumns(band: List<OcrWord>, h: Float): List<Column> {
        val sorted = band.sortedBy { it.box.left }
        val groups = ArrayList<MutableList<OcrWord>>()
        var curRight = Float.NEGATIVE_INFINITY
        for (w in sorted) {
            if (groups.isEmpty() || w.box.left - curRight > h * 0.9f) {
                groups += mutableListOf(w); curRight = w.box.right
            } else {
                groups.last() += w; curRight = max(curRight, w.box.right)
            }
        }
        val cols = groups.map { g ->
            val text = g.sortedWith(compareBy({ it.box.top }, { it.box.left })).joinToString(" ") { norm(it.text) }
            Column(g.minOf { it.box.left }, g.maxOf { it.box.right }, classifyGroup(text))
        }
        // Keep only the first column of each important type.
        val used = HashSet<Col>()
        return cols.map { c ->
            if (c.type != Col.OTHER && !used.add(c.type)) Column(c.left, c.right, Col.OTHER) else c
        }
    }

    private fun classifyGroup(text: String): Col {
        if (DESIG_KEYS.any { text.contains(it) }) return Col.DESIGNATION
        if (MOBILE_KEYS.any { k -> text.split(' ').any { it.startsWith(k) } }) return Col.MOBILE
        if (NAME_KEYS.any { text.contains(it) }) return Col.NAME
        if (text.split(' ').any { it.startsWith("number") }) return Col.MOBILE
        if (text.split(' ').any { it in SL_KEYS } && text.length <= 10) return Col.SL
        return Col.OTHER
    }

    private fun classify(t: String): Col = classifyGroup(t)

    // ------------------------------------------------------------------ with header

    private fun parseWithHeader(all: List<OcrWord>, mobiles: List<Anchor>, header: HeaderResult, h: Float): List<ParsedRow> {
        val cols = header.columns.sortedBy { it.left }
        val anchorsBelow = mobiles.filter { it.span.top > header.bottom - h * 0.3f }
        if (anchorsBelow.isEmpty()) return emptyList()
        val lastY = anchorsBelow.maxOf { it.span.bottom } + h * 3
        val data = all.filter { it.box.cy > header.bottom && it.box.cy < lastY }

        // Column boundaries: inside the gap between header i and header i+1, choose the x
        // that the fewest data words cross (the empty gutter between columns).
        for (i in 0 until cols.size - 1) {
            val a = cols[i]; val b = cols[i + 1]
            val lo = min(a.right, b.left); val hi = max(a.right, b.left)
            val x = bestGap(data, lo, hi)
            a.end = x; b.start = x
        }
        fun colOf(w: OcrWord): Column {
            val x = w.box.left + min(w.box.width, h) * 0.5f
            return cols.firstOrNull { x >= it.start && x < it.end } ?: cols.last()
        }

        val mobCol = cols.firstOrNull { it.type == Col.MOBILE }
        val anchors = anchorsBelow.filter { mobCol == null || colOf(it.word) === mobCol || abs(it.span.cx - mobCol.left) < h * 10 }
            .ifEmpty { anchorsBelow }

        // Extend each row's vertical span with its SL number.
        val slCol = cols.firstOrNull { it.type == Col.SL }
        if (slCol != null) {
            val slWords = data.filter { colOf(it) === slCol }
                .mapNotNull { w -> serialOf(w.text)?.let { w to it } }
            // Greedy one-to-one matching: closest (serial word, row) pairs first.
            val pairs = ArrayList<Triple<Float, Pair<OcrWord, String>, Anchor>>()
            for (sw in slWords) for (a in anchors) {
                val d = abs(sw.first.box.cy - a.span.cy)
                if (d <= h * 2.2f) pairs += Triple(d, sw, a)
            }
            val usedW = HashSet<OcrWord>(); val usedA = HashSet<Anchor>()
            for ((_, sw, a) in pairs.sortedBy { it.first }) {
                if (sw.first in usedW || a in usedA) continue
                usedW += sw.first; usedA += a
                a.serial = sw.second
                a.span = a.span.union(Box(a.span.left, sw.first.box.top, a.span.right, sw.first.box.bottom))
            }
        }

        for (w in data) {
            val c = colOf(w)
            if (c.type != Col.NAME && c.type != Col.DESIGNATION) continue
            var best: Anchor? = null; var bestD = Float.MAX_VALUE
            for (a in anchors) {
                val d = when {
                    w.box.cy < a.span.top -> a.span.top - w.box.cy
                    w.box.cy > a.span.bottom -> w.box.cy - a.span.bottom
                    else -> 0f
                } + abs(w.box.cy - a.span.cy) * 0.01f
                if (d < bestD) { bestD = d; best = a }
            }
            if (best != null && bestD <= h * 2.2f) best.words.getOrPut(c.type) { mutableListOf() } += w
        }

        return anchors.map { a ->
            ParsedRow(
                serial = a.serial,
                name = cleanName(joinWords(a.words[Col.NAME].orEmpty(), h)),
                designation = cleanText(joinWords(a.words[Col.DESIGNATION].orEmpty(), h)),
                mobile = a.mobile
            )
        }
    }

    private fun bestGap(data: List<OcrWord>, lo: Float, hi: Float): Float {
        if (hi - lo < 1f) return (lo + hi) / 2f
        val steps = 48
        val counts = IntArray(steps + 1)
        for (s in 0..steps) {
            val x = lo + (hi - lo) * s / steps
            counts[s] = data.count { it.box.left < x && it.box.right > x }
        }
        val minC = counts.min()
        // Middle of the longest run with the minimum count.
        var bestStart = 0; var bestLen = 0; var s = 0
        while (s <= steps) {
            if (counts[s] == minC) {
                var e = s; while (e + 1 <= steps && counts[e + 1] == minC) e++
                if (e - s + 1 > bestLen) { bestLen = e - s + 1; bestStart = s }
                s = e + 1
            } else s++
        }
        val mid = bestStart + (bestLen - 1) / 2f
        return lo + (hi - lo) * mid / steps
    }

    // ------------------------------------------------------------------ without header

    private fun parseWithoutHeader(lines: List<OcrLine>, mobiles: List<Anchor>, h: Float): List<ParsedRow> {
        val words = lines.flatMap { it.words }
        return mobiles.map { a ->
            val rowWords = words.filter {
                abs(it.box.cy - a.span.cy) <= h * 0.6f && it.box.right <= a.span.left + 2f
            }.sortedBy { it.box.left }.toMutableList()

            var serial = ""
            if (rowWords.isNotEmpty()) serialOf(rowWords.first().text)?.let { serial = it; rowWords.removeAt(0) }
            // Split into cells by large horizontal gaps.
            val cells = ArrayList<MutableList<OcrWord>>()
            for (w in rowWords) {
                if (cells.isEmpty() || w.box.left - cells.last().last().box.right > h * 1.2f) cells += mutableListOf(w)
                else cells.last() += w
            }
            var name: String
            var desig: String
            if (cells.size >= 2) {
                name = cells[0].joinToString(" ") { it.text }
                desig = cells[1].joinToString(" ") { it.text }
            } else {
                val toks = rowWords.map { it.text }
                // Search from the 2nd word so "Md." at the start of a name is not taken as "MD".
                val idx = toks.drop(1).indexOfFirst { t -> norm(t).trimEnd('.') in DESIG_WORDS }
                    .let { if (it < 0) toks.size else it + 1 }
                name = toks.take(idx).joinToString(" ")
                desig = toks.drop(idx).joinToString(" ")
            }
            ParsedRow(serial, cleanName(name), cleanText(desig), a.mobile)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun joinWords(ws: List<OcrWord>, h: Float): String {
        if (ws.isEmpty()) return ""
        val sorted = ws.sortedBy { it.box.cy }
        val lines = ArrayList<MutableList<OcrWord>>()
        for (w in sorted) {
            val l = lines.lastOrNull()
            if (l != null && abs(l.map { it.box.cy }.average().toFloat() - w.box.cy) < h * 0.55f) l += w
            else lines += mutableListOf(w)
        }
        return lines.joinToString(" ") { l -> l.sortedBy { it.box.left }.joinToString(" ") { it.text } }
    }

    /** "1", "12.", "i", "l)" → serial number text, else null. */
    private fun serialOf(raw: String): String? {
        val t = raw.trim().trimEnd('.', ')', '|', '_').trimStart('|', '(')
        if (t.isEmpty() || t.length > 3) return null
        val d = t.map { c -> when (c) { 'i', 'I', 'l', '|', '!' -> '1'; 'O', 'o' -> '0'; else -> c } }.joinToString("")
        return if (d.all { it.isDigit() }) d.trimStart('0').ifEmpty { null } else null
    }

    private fun cleanText(s: String) = s.replace(Regex("\\s+"), " ")
        .replace(Regex("^[^\\p{L}\\p{N}]+"), "")
        .replace(Regex("[^\\p{L}\\p{N}.)]+$"), "")
        .trim()

    private fun cleanName(s: String): String {
        var t = cleanText(s)
        t = t.replace(Regex("^\\d{1,3}[.)]?\\s+"), "")      // stray serial in front
        return t
    }

    private fun norm(s: String) = s.lowercase().trim().trim(':', '|', ',', ';')

    private fun isRotated(w: OcrWord): Boolean {
        val len = w.text.trim().length
        return len >= 3 && w.box.height > w.box.width * 1.2f
    }

    /** Median vertical slope between horizontally neighbouring words (photo tilt). */
    private fun estimateSkew(words: List<OcrWord>, h: Float): Float {
        val slopes = ArrayList<Float>()
        for (w in words) {
            val n = words.filter { o ->
                o !== w && o.box.cx - w.box.cx > h * 2 && o.box.cx - w.box.cx < h * 25 &&
                    abs(o.box.cy - w.box.cy) < h * 0.9f
            }.minByOrNull { it.box.cx - w.box.cx } ?: continue
            slopes += (n.box.cy - w.box.cy) / (n.box.cx - w.box.cx)
        }
        if (slopes.size < 3) return 0f
        val m = median(slopes)
        return if (abs(m) < 0.003f) 0f else m.coerceIn(-0.2f, 0.2f)
    }

    private fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted(); return s[s.size / 2]
    }
}
