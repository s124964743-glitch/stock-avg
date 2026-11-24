package app.stockavg

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast

/**
 * 只在使用者按下「一鍵匯入持股」之後工作：
 * 等使用者登入並進入庫存頁 → 辨識 → 自動往下捲動讀完整份清單 → 存檔 → 提示「已匯入成功」。
 */
class ReaderService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: ReaderService? = null
    }

    private val h = Handler(Looper.getMainLooper())
    private var overlay: TextView? = null
    private var phase = 0 // 0 等待庫存頁，1 讀取中
    private val found = LinkedHashMap<String, Holding>()
    private var noNew = 0
    private var scrolls = 0
    private var scanPending = false
    private var lastSig = ""
    private var cols: List<Col>? = null
    private var gen = 0

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        removeOverlay()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    fun beginImport() {
        gen++
        phase = 0
        found.clear()
        noNew = 0
        scrolls = 0
        lastSig = ""
        cols = null
        scanPending = false
        showOverlay("請登入投資先生並進入「庫存」頁\n我會自動辨識（點此取消）") { Importer.cancel(this); backToApp() }
        val g = gen
        h.postDelayed({
            if (g == gen && Store.importing(this) && phase == 0) {
                fail("等太久了，已取消。請再按一次「一鍵匯入持股」。")
            }
        }, 5 * 60 * 1000L)
    }

    fun endImport() {
        gen++
        removeOverlay()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || !Store.importing(this) || phase != 0) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg != Store.targetPkg(this)) return
        scheduleScan(700)
    }

    private fun scheduleScan(delay: Long) {
        if (scanPending) return
        scanPending = true
        val g = gen
        h.postDelayed({
            scanPending = false
            if (g == gen) scan()
        }, delay)
    }

    private fun scan() {
        if (!Store.importing(this)) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != Store.targetPkg(this)) return
        val cells = ArrayList<Cell>()
        collect(root, cells, 0)
        Store.setDump(this, cells.joinToString("\n") { "${it.text.replace('\n', ' ')}\t${it.l},${it.t},${it.r},${it.b}" })
        val res = Parser.parse(cells, cols)
        if (res.cols != null) cols = res.cols

        if (phase == 0) {
            if (res.holdings.isEmpty()) return
            // 等畫面穩定（連續兩次辨識結果相同）再開始，避免讀到載入中的半成品
            val sig = res.holdings.joinToString("|") { "${it.key}:${it.shares}:${it.avg}" }
            if (sig != lastSig) {
                lastSig = sig
                scheduleScan(800)
                return
            }
            phase = 1
            found.clear()
            noNew = 0
            scrolls = 0
        }

        var added = 0
        for (x in res.holdings) {
            if (!found.containsKey(x.key)) added++
            found[x.key] = x
        }
        ImportBus.state.value = ImportState.Reading(found.size)
        showOverlay("辨識中…已讀到 ${found.size} 檔\n（點此取消）") { Importer.cancel(this); backToApp() }

        noNew = if (added == 0) noNew + 1 else 0
        if (noNew >= 2 || scrolls >= 30) {
            finish()
            return
        }
        val sc = findScrollable(root)
        if (sc == null || !sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            finish()
            return
        }
        scrolls++
        val g = gen
        h.postDelayed({ if (g == gen) scan() }, 900)
    }

    private fun finish() {
        val list = found.values.toList()
        if (list.isEmpty()) {
            fail("沒有辨識到持股。請確認停在「庫存」頁，並到 App 裡複製畫面資料給開發者調整。")
            return
        }
        Store.save(this, list)
        Store.setLastImport(this, System.currentTimeMillis())
        Store.setImporting(this, false)
        phase = 0
        ImportBus.state.value = ImportState.Done(list.size)
        showOverlay("✓ 已匯入成功・${list.size} 檔\n點此回到均價計算機") { backToApp(); removeOverlay() }
        Toast.makeText(this, "已匯入成功（${list.size} 檔）", Toast.LENGTH_LONG).show()
        backToApp()
        val g = gen
        h.postDelayed({ if (g == gen) removeOverlay() }, 10_000)
    }

    private fun fail(msg: String) {
        Store.setImporting(this, false)
        phase = 0
        ImportBus.state.value = ImportState.Failed(msg)
        showOverlay("匯入失敗\n點此回到均價計算機") { backToApp(); removeOverlay() }
        backToApp()
        val g = gen
        h.postDelayed({ if (g == gen) removeOverlay() }, 8_000)
    }

    private fun backToApp() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (_: Exception) {
        }
    }

    // ---------- 讀取畫面 ----------
    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<Cell>, depth: Int) {
        if (n == null || depth > 40) return
        if (n.isVisibleToUser) {
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty()) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) addCells(t, r, out)
            }
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    private fun addCells(t: String, r: Rect, out: MutableList<Cell>) {
        val lines = t.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 1 && Parser.kindOf(t) == 0) {
            val lh = r.height() / lines.size
            lines.forEachIndexed { i, s ->
                out.add(Cell(s, r.left, r.top + i * lh, r.right, r.top + (i + 1) * lh))
            }
        } else {
            val c = Cell(t, r.left, r.top, r.right, r.bottom)
            if (out.none { it.text == c.text && it.l == c.l && it.t == c.t }) out.add(c)
        }
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var guard = 0
        while (q.isNotEmpty() && guard++ < 3000) {
            val n = q.removeFirst()
            if (n.isScrollable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea) {
                    bestArea = area
                    best = n
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return best
    }

    // ---------- 浮動提示 ----------
    private fun showOverlay(text: String, onTap: () -> Unit) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        var v = overlay
        if (v == null) {
            v = TextView(this)
            v.setTextColor(0xFFFFFFFF.toInt())
            v.textSize = 14f
            v.gravity = Gravity.CENTER
            val bg = GradientDrawable()
            bg.cornerRadius = 28 * d
            bg.setColor(0xE61A1F2B.toInt())
            bg.setStroke((1 * d).toInt().coerceAtLeast(1), 0x66FFFFFF)
            v.background = bg
            v.setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = (56 * d).toInt()
            try {
                wm.addView(v, lp)
            } catch (_: Exception) {
                return
            }
            overlay = v
        }
        v.text = text
        v.setOnClickListener { onTap() }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
    }
}
