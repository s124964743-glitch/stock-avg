package app.stockavg

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.math.abs

/**
 * 只在使用者按下「一鍵匯入持股」之後工作：
 * 一看到投資先生「庫存」頁就開始辨識 → 自動捲動讀完整份清單 → 存檔 → 提示「已匯入成功」。
 * 先讀畫面文字；讀不到文字時改用截圖 + 文字辨識（OCR）。
 */
class ReaderService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: ReaderService? = null
        private const val OV_RED = 0xE6E5303B.toInt()
        private const val OV_GREEN = 0xE62FB35E.toInt()
    }

    private val h = Handler(Looper.getMainLooper())
    private var overlay: TextView? = null
    private var phase = 0 // 0 等待庫存頁，1 讀取中
    private val found = LinkedHashMap<String, Holding>()
    private var noNew = 0
    private var scrolls = 0
    private var scanPending = false
    private var busy = false
    private var lastSig = ""
    private var gen = 0
    private var overlayLp: WindowManager.LayoutParams? = null
    private var overlayBg: GradientDrawable? = null
    private var tapAction: (() -> Unit)? = null
    private var ovX = 0
    private var ovY = -1

    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

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
        scanPending = false
        busy = false
        RowParser.reset()
        showOverlay("自動辨識庫存頁")
        val g = gen
        h.postDelayed({
            if (g == gen && Store.importing(this) && phase == 0) {
                fail("等太久了，已取消。請再按一次「一鍵匯入持股」。")
            }
        }, 5 * 60 * 1000L)
    }

    fun endImport() {
        gen++
        busy = false
        removeOverlay()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || !Store.importing(this) || phase != 0) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg != Store.targetPkg(this)) return
        scheduleScan(600)
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
        if (!Store.importing(this) || busy) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != Store.targetPkg(this)) return
        val cells = ArrayList<Cell>()
        collect(root, cells, 0)
        if (cells.size >= 8 || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            process(cells, root, false)
            return
        }
        // 畫面讀不到文字：改用截圖 + OCR
        busy = true
        val g = gen
        ocr { oc ->
            if (g == gen && Store.importing(this)) process(oc, root, true) else busy = false
        }
    }

    private fun process(cells: List<Cell>, root: AccessibilityNodeInfo, ocrMode: Boolean) {
        busy = false
        Store.setDump(
            this,
            (if (ocrMode) "[OCR]\n" else "[文字]\n") +
                cells.joinToString("\n") { "${it.text.replace('\n', ' ')}\t${it.l},${it.t},${it.r},${it.b}" },
        )
        var list = RowParser.parse(cells)
        if (list.isEmpty()) list = Parser.parse(cells, null).holdings

        if (phase == 0) {
            if (list.isEmpty()) return
            // 等畫面穩定（連續兩次辨識結果相同）再開始，避免讀到載入中的半成品
            val sig = list.joinToString("|") { "${it.key}:${it.shares}:${it.avg}" }
            if (sig != lastSig) {
                lastSig = sig
                scheduleScan(700)
                return
            }
            phase = 1
            found.clear()
            noNew = 0
            scrolls = 0
        }

        var added = 0
        for (x in list) {
            if (!found.containsKey(x.key)) added++
            found[x.key] = x
        }
        ImportBus.state.value = ImportState.Reading(found.size)
        showOverlay("辨識中 ${found.size} 檔")

        noNew = if (added == 0) noNew + 1 else 0
        if (noNew >= 2 || scrolls >= 30) {
            finish()
            return
        }
        val sc = if (ocrMode) null else findScrollable(root)
        val moved = if (ocrMode) swipe() else sc != null && sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        if (!moved) {
            finish()
            return
        }
        scrolls++
        val g = gen
        h.postDelayed({ if (g == gen) scan() }, 1100)
    }

    private fun finish() {
        if (found.isEmpty()) {
            fail("沒有辨識到持股。請確認停在「庫存」頁，並到 App 裡複製畫面資料給開發者調整。")
            return
        }
        val old = Store.load(this)
        val list = found.values.map { n ->
            val c = old.firstOrNull { it.key == n.key }?.code ?: ""
            if (c.isNotEmpty()) n.copy(code = c) else n
        }
        Store.save(this, list)
        Store.setLastImport(this, System.currentTimeMillis())
        Store.setImporting(this, false)
        phase = 0
        ImportBus.state.value = ImportState.Done(list.size)
        showOverlay("✓ 已匯入成功・${list.size} 檔", OV_GREEN) { backToApp(); removeOverlay() }
        Toast.makeText(this, "已匯入成功（${list.size} 檔）", Toast.LENGTH_LONG).show()
        backToApp()
        val g = gen
        h.postDelayed({ if (g == gen) removeOverlay() }, 10_000)
    }

    private fun fail(msg: String) {
        Store.setImporting(this, false)
        phase = 0
        busy = false
        ImportBus.state.value = ImportState.Failed(msg)
        showOverlay("匯入失敗", OV_RED) { backToApp(); removeOverlay() }
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

    // ---------- 讀取畫面文字 ----------
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
        if (lines.isEmpty()) return
        val lh = r.height() / lines.size
        lines.forEachIndexed { i, s ->
            val c = Cell(s, r.left, r.top + i * lh, r.right, r.top + (i + 1) * lh)
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

    private fun swipe(): Boolean {
        val dm = resources.displayMetrics
        val p = Path()
        p.moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.72f)
        p.lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.40f)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 350))
            .build()
        return dispatchGesture(g, null, null)
    }

    // ---------- 截圖 + OCR ----------
    private fun ocr(cb: (List<Cell>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            busy = false
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val bmp = try {
                    Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                } catch (_: Exception) {
                    null
                }
                try {
                    r.hardwareBuffer.close()
                } catch (_: Exception) {
                }
                if (bmp == null) {
                    busy = false
                    return
                }
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { t -> cb(toCells(t)) }
                    .addOnFailureListener { busy = false }
            }

            override fun onFailure(errorCode: Int) {
                busy = false
            }
        })
    }

    /** 把 OCR 的字元/單字依位置併成「同一行、靠得近」的文字方塊。 */
    private fun toCells(t: Text): List<Cell> {
        val els = ArrayList<Cell>()
        for (b in t.textBlocks) for (l in b.lines) for (e in l.elements) {
            val r = e.boundingBox ?: continue
            els.add(Cell(e.text, r.left, r.top, r.right, r.bottom))
        }
        val lines = ArrayList<MutableList<Cell>>()
        for (e in els.sortedBy { it.cy }) {
            val ln = lines.lastOrNull()
            if (ln != null && abs(ln[0].cy - e.cy) <= e.h * 0.5) ln.add(e) else lines.add(mutableListOf(e))
        }
        val out = ArrayList<Cell>()
        for (ln in lines) {
            ln.sortBy { it.l }
            var cur = ln[0]
            for (k in 1 until ln.size) {
                val n = ln[k]
                if (n.l - cur.r < 0.8 * maxOf(cur.h, n.h)) {
                    cur = Cell(cur.text + n.text, cur.l, minOf(cur.t, n.t), n.r, maxOf(cur.b, n.b))
                } else {
                    out.add(cur)
                    cur = n
                }
            }
            out.add(cur)
        }
        return out
    }

    // ---------- 浮動提示（可拖曳；辨識中點擊無作用，避免誤觸） ----------
    private fun showOverlay(text: String, color: Int = OV_RED, onTap: (() -> Unit)? = null) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        tapAction = onTap
        var v = overlay
        if (v == null) {
            v = TextView(this)
            v.setTextColor(0xFFFFFFFF.toInt())
            v.typeface = android.graphics.Typeface.DEFAULT_BOLD
            v.textSize = 12f
            v.maxLines = 1
            v.gravity = Gravity.CENTER
            val bg = GradientDrawable()
            bg.cornerRadius = 20 * d
            bg.setColor(color)
            overlayBg = bg
            bg.setStroke((1 * d).toInt().coerceAtLeast(1), 0x66FFFFFF)
            v.background = bg
            v.setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.x = ovX
            lp.y = if (ovY >= 0) ovY else (40 * d).toInt()
            overlayLp = lp
            v.setOnTouchListener(object : View.OnTouchListener {
                var sx = 0f
                var sy = 0f
                var ox = 0
                var oy = 0
                var moved = false
                override fun onTouch(view: View, e: MotionEvent): Boolean {
                    val l = overlayLp ?: return false
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            sx = e.rawX
                            sy = e.rawY
                            ox = l.x
                            oy = l.y
                            moved = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (e.rawX - sx).toInt()
                            val dy = (e.rawY - sy).toInt()
                            if (abs(dx) > 12 || abs(dy) > 12) moved = true
                            if (moved) {
                                l.x = ox + dx
                                l.y = (oy + dy).coerceAtLeast(0)
                                ovX = l.x
                                ovY = l.y
                                try {
                                    wm.updateViewLayout(view, l)
                                } catch (_: Exception) {
                                }
                            }
                        }
                        MotionEvent.ACTION_UP -> if (!moved) tapAction?.invoke()
                    }
                    return true
                }
            })
            try {
                wm.addView(v, lp)
            } catch (_: Exception) {
                return
            }
            overlay = v
        }
        v.text = text
        overlayBg?.setColor(color)
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
        overlayLp = null
        overlayBg = null
    }
}
