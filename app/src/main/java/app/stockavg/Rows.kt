package app.stockavg

import kotlin.math.abs

/**
 * 投資先生「庫存」頁的專用解析。
 * 每檔股票佔兩行：
 *   第一行：名稱 ｜ 市價 ｜ 股數   ｜ 損益
 *   第二行：現股 ｜ 均價 ｜ 可下單數 ｜ 報酬率%
 * 以第二行的「現股」當錨點，往上找同一檔的第一行。
 */
object RowParser {
    private var hdrSharesR: Int? = null
    private var hdrAvgR: Int? = null

    fun reset() {
        hdrSharesR = null
        hdrAvgR = null
    }

    private fun isNameText(t: String): Boolean {
        val s = t.trim()
        return s.isNotEmpty() && s.any { it in '一'..'鿿' || it.isLetter() } &&
            Parser.num(s) == null && !s.contains('%')
    }

    fun parse(cells: List<Cell>): List<Holding> {
        val markers = cells.filter { val t = it.text.trim(); t.length <= 4 && t.startsWith("現股") }
            .sortedBy { it.cy }
        if (markers.isEmpty()) return emptyList()

        val heads = cells.filter {
            when (it.text.trim()) {
                "名稱", "市價", "均價", "股數", "可下單數", "損益" -> true
                else -> false
            }
        }
        heads.firstOrNull { it.text.trim() == "股數" }?.let { hdrSharesR = it.r }
        heads.firstOrNull { it.text.trim() == "均價" }?.let { hdrAvgR = it.r }
        val headBottom = heads.maxOfOrNull { it.cy }

        val out = ArrayList<Holding>()
        for ((i, m) in markers.withIndex()) {
            val lower: Double = if (i > 0) {
                markers[i - 1].cy + (m.cy - markers[i - 1].cy) * 0.3
            } else {
                headBottom?.toDouble() ?: (m.cy - 4.0 * m.h)
            }
            val line1 = cells.filter {
                it !== m && it.cy > lower && it.cy < m.cy - 0.5 * m.h && !(it.text.trim().startsWith("現股") && it.text.trim().length <= 4)
            }
            val line2 = cells.filter { it !== m && abs(it.cy - m.cy) <= 0.6 * m.h }

            val nameCell = line1.filter { isNameText(it.text) }.minByOrNull { it.l } ?: continue

            // 股數：第一行的整數欄（右緣對齊「股數」標題；沒有標題就取第 2 個數字）
            val nums1 = line1.filter { Parser.sharesOf(it.text) != null && !it.text.contains('%') }.sortedBy { it.l }
            val sharesCell = hdrSharesR?.let { r -> nums1.minByOrNull { abs(it.r - r) }?.takeIf { abs(it.r - r) <= 60 } }
                ?: if (nums1.size >= 3) nums1[1] else null

            // 均價：第二行的小數欄（右緣對齊「均價」標題；沒有標題就取第 1 個數字）
            val nums2 = line2.filter { Parser.num(it.text) != null }.sortedBy { it.l }
            val avgCell = hdrAvgR?.let { r -> nums2.minByOrNull { abs(it.r - r) }?.takeIf { abs(it.r - r) <= 60 } }
                ?: nums2.firstOrNull()

            val s = sharesCell?.let { Parser.sharesOf(it.text) } ?: continue
            val a = avgCell?.let { Parser.num(it.text) } ?: continue
            if (s <= 0L || a <= 0.0 || a > 100_000.0) continue
            out.add(Holding("", nameCell.text.trim(), s, a))
        }
        return out.distinctBy { it.key }
    }
}
