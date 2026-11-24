package app.stockavg

import kotlin.math.abs

data class Cell(val text: String, val l: Int, val t: Int, val r: Int, val b: Int) {
    val cx: Int get() = (l + r) / 2
    val cy: Int get() = (t + b) / 2
    val w: Int get() = r - l
    val h: Int get() = b - t
}

class Col(val kind: Int, val cell: Cell) {
    val parts: List<String> = cell.text.split('/', '／', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}

class Anchor(val cell: Cell, val code: String, var name: String)

class ParseResult(val holdings: List<Holding>, val cols: List<Col>?)

/**
 * 從畫面上的文字方塊（含座標）辨識「股票 / 股數 / 均價」。
 * 兩種版面都支援：
 *  1. 表格式：有「股數」「均價」之類的欄位標題，數字對齊各欄。
 *  2. 卡片式：每檔股票底下有「股數 150」「均價 900.09」之類的標籤。
 */
object Parser {
    private val codeRe = Regex("^\\d{4,6}[A-Z]?$")
    private val nameCodeRe = Regex("^(.*?\\S)\\s*[(（\\[]?\\s*(\\d{4,6}[A-Z]?)\\s*[)）\\]]?$")
    private val letterRe = Regex("[\\u4e00-\\u9fffA-Za-z]")
    private val cjkRe = Regex("[\\u4e00-\\u9fff]")
    private val numRe = Regex("^[+\\-]?(\\d{1,3}(,\\d{3})+|\\d+)(\\.\\d+)?$")
    private val sharesRe = Regex("^(?:(\\d+)張)?(?:(\\d+)股?)?$")
    private val firstNumRe = Regex("\\d[\\d,]*(?:\\.\\d+)?")

    private val avgKeys = listOf("均價", "成本價", "平均成本", "平均價")
    private val shareKeys = listOf("股數", "庫存", "持股", "數量")
    private val otherKeys = listOf(
        "現價", "市價", "損益", "報酬", "市值", "成本", "漲跌", "參考", "成交", "預估", "可用", "現值", "比重",
    )

    fun num(t: String): Double? {
        val s = t.trim().removeSuffix("元").removeSuffix("股").trim()
        return if (numRe.matches(s)) s.replace(",", "").toDoubleOrNull() else null
    }

    /** 股數：支援「150」「150股」「2張」「1張150股」。 */
    fun sharesOf(t: String): Long? {
        val s = t.replace(",", "").replace(" ", "")
        if (s.isEmpty()) return null
        val m = sharesRe.matchEntire(s) ?: return null
        val z = m.groupValues[1].toLongOrNull()
        val g = m.groupValues[2].toLongOrNull()
        if (z == null && g == null) return null
        return (z ?: 0L) * 1000L + (g ?: 0L)
    }

    private fun isNumLike(t: String) = num(t) != null || sharesOf(t) != null

    /** 1 = 股數欄/標籤，2 = 均價欄/標籤，3 = 其他欄位標題，0 = 都不是 */
    fun kindOf(t: String): Int {
        val s = t.replace(" ", "")
        if (s.length > 14 || s.isEmpty()) return 0
        if (avgKeys.any { s.contains(it) }) return 2
        if (shareKeys.any { s.contains(it) }) return 1
        if (otherKeys.any { s.contains(it) }) return 3
        return 0
    }

    fun splitNameCode(input: String): Pair<String, String> {
        val t = input.trim()
        if (codeRe.matches(t)) return Pair("", t)
        val m = nameCodeRe.matchEntire(t)
        if (m != null && letterRe.containsMatchIn(m.groupValues[1])) {
            return Pair(m.groupValues[1].trim(), m.groupValues[2])
        }
        return Pair(t, "")
    }

    // ---------- 股票錨點 ----------
    private fun findAnchors(cells: List<Cell>): List<Anchor> {
        val combined = ArrayList<Anchor>()
        val plain = ArrayList<Cell>()
        for (c in cells) {
            val t = c.text.trim()
            if (codeRe.matches(t)) {
                plain.add(c)
                continue
            }
            if (num(t) != null) continue
            val m = nameCodeRe.matchEntire(t) ?: continue
            if (!letterRe.containsMatchIn(m.groupValues[1])) continue
            if (kindOf(t) != 0) continue
            combined.add(Anchor(c, m.groupValues[2], m.groupValues[1].trim()))
        }
        if (combined.isNotEmpty()) return combined.distinctBy { it.code }
        if (plain.isEmpty()) return emptyList()

        val clusters = ArrayList<MutableList<Cell>>()
        for (c in plain.sortedBy { it.l }) {
            val g = clusters.firstOrNull { abs(it[0].l - c.l) <= 40 }
            if (g != null) g.add(c) else clusters.add(mutableListOf(c))
        }
        val best = clusters.sortedWith(
            compareByDescending<MutableList<Cell>> { it.size }.thenBy { it[0].l }
        ).first()
        return best.map { Anchor(it, it.text.trim(), findName(it, cells)) }.distinctBy { it.code }
    }

    private fun findName(a: Cell, cells: List<Cell>): String {
        var bestText = ""
        var bestD = Int.MAX_VALUE
        for (c in cells) {
            if (c === a) continue
            val t = c.text.trim()
            if (!cjkRe.containsMatchIn(t) || kindOf(t) != 0 || isNumLike(t) || t.length > 16) continue
            val dy = a.cy - c.cy
            val above = dy >= -a.h / 2 && dy <= a.h * 3 && abs(c.l - a.l) <= 60
            val sameLine = abs(dy) <= a.h * 0.6 && (c.r <= a.l + 5 || c.l >= a.r - 5)
            if (above || sameLine) {
                val d = abs(dy) + abs(c.l - a.l) / 4
                if (d < bestD) {
                    bestD = d
                    bestText = t
                }
            }
        }
        return bestText
    }

    // ---------- 欄位標題 ----------
    private fun detectCols(cells: List<Cell>, firstY: Int): List<Col>? {
        val hs = cells.filter { it.cy < firstY && kindOf(it.text) != 0 && !isNumLike(it.text) }
        if (hs.isEmpty()) return null
        val near = hs.maxOf { it.cy }
        val th = hs.map { it.h }.average()
        val row = hs.filter { it.cy >= near - 2.5 * th }
        return row.map { Col(kindOf(it.text), it) }
    }

    private fun colDist(c: Cell, h: Cell) = minOf(abs(c.cx - h.cx), abs(c.l - h.l), abs(c.r - h.r))

    private fun pickStacked(list: List<Cell>?, col: Col, keys: List<String>): Cell? {
        if (list.isNullOrEmpty()) return null
        val idx = col.parts.indexOfFirst { p -> keys.any { p.contains(it) } }.coerceAtLeast(0)
        if (idx == 0) return list[0]
        return list.getOrNull(idx)
    }

    private fun build(a: Anchor, sharesCell: Cell?, avgCell: Cell?): Holding? {
        val s = sharesCell?.let { sharesOf(it.text) } ?: return null
        val v = avgCell?.let { num(it.text) } ?: return null
        if (s <= 0L || s > 1_000_000_000L || v <= 0.0 || v > 100_000.0) return null
        return Holding(a.code, a.name, s, v)
    }

    fun parse(cells: List<Cell>, cached: List<Col>?): ParseResult {
        val anchors = findAnchors(cells).sortedBy { it.cell.cy }
        if (anchors.isEmpty()) return ParseResult(emptyList(), cached)

        val anchorCells = anchors.map { it.cell }.toSet()
        val numeric = cells.filter { it !in anchorCells && isNumLike(it.text) }
        val cols: List<Col>? = if (cached.isNullOrEmpty()) detectCols(cells, anchors[0].cell.cy) else cached
        val out = ArrayList<Holding>()

        val shareCol = cols?.firstOrNull { it.kind == 1 }
        val avgCol = cols?.firstOrNull { it.kind == 2 }

        if (cols != null && shareCol != null && avgCol != null) {
            // 表格式：每個數字歸到最近的一檔股票，再依欄位標題分欄
            val gap = (1 until anchors.size).minOfOrNull { anchors[it].cell.cy - anchors[it - 1].cell.cy } ?: 0
            val maxD = if (gap > 0) gap * 0.7 else anchors[0].cell.h * 4.0
            val rows = anchors.map { ArrayList<Cell>() }
            for (n in numeric) {
                var bi = -1
                var bd = Double.MAX_VALUE
                for (i in anchors.indices) {
                    val d = abs(n.cy - anchors[i].cell.cy).toDouble()
                    if (d < bd) {
                        bd = d
                        bi = i
                    }
                }
                if (bi >= 0 && bd <= maxD) rows[bi].add(n)
            }
            for (i in anchors.indices) {
                val byCol = rows[i].groupBy { n -> cols.minByOrNull { colDist(n, it.cell) }!! }
                val sCell = pickStacked(byCol[shareCol]?.sortedBy { it.cy }, shareCol, shareKeys)
                val aCell = pickStacked(byCol[avgCol]?.sortedBy { it.cy }, avgCol, avgKeys)
                build(anchors[i], sCell, aCell)?.let { out.add(it) }
            }
            if (out.isNotEmpty()) return ParseResult(out, cols)
        }

        // 卡片式：每檔股票到下一檔之間的範圍內，找「股數 / 均價」標籤旁的數字
        for (i in anchors.indices) {
            val a = anchors[i]
            val top = a.cell.cy - a.cell.h * 0.6
            val bottom = if (i + 1 < anchors.size) anchors[i + 1].cell.cy - anchors[i + 1].cell.h * 0.6 else Double.MAX_VALUE
            val band = cells.filter { it !== a.cell && it.cy >= top && it.cy < bottom }
            val nums = band.filter { isNumLike(it.text) }
            var sh: Long? = null
            var av: Double? = null
            for (lab in band) {
                val k = kindOf(lab.text)
                if (k != 1 && k != 2) continue
                val inline = firstNumRe.find(lab.text)?.value
                if (k == 1 && sh == null) {
                    sh = if (inline != null) sharesOf(inline) else valueFor(lab, nums)?.let { sharesOf(it.text) }
                } else if (k == 2 && av == null) {
                    av = if (inline != null) num(inline) else valueFor(lab, nums)?.let { num(it.text) }
                }
            }
            if (sh != null && av != null && sh > 0 && av > 0 && av <= 100_000.0) {
                out.add(Holding(a.code, a.name, sh, av))
            }
        }
        return ParseResult(out, cols)
    }

    private fun valueFor(label: Cell, nums: List<Cell>): Cell? {
        var best: Cell? = null
        var bs = Int.MAX_VALUE
        for (n in nums) {
            val sameLine = abs(n.cy - label.cy) <= label.h * 0.7 && n.l >= label.r - 5
            val below = n.t >= label.b - 5 && abs(n.cx - label.cx) <= maxOf(label.w, 150)
            val score = when {
                sameLine -> n.l - label.r
                below -> 10_000 + (n.t - label.b)
                else -> continue
            }
            if (score < bs) {
                bs = score
                best = n
            }
        }
        return best
    }
}
