package app.stockavg

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)
    private val resumed = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ImportBus.state.value is ImportState.Idle) Store.setImporting(this, false)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = Color.Black, contentColor = Color.White) {
                    GlassBackground { App(tick.intValue, resumed.value) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed.value = true
        tick.intValue++
    }

    override fun onPause() {
        super.onPause()
        resumed.value = false
    }
}

private fun String.d(): Double? = replace(",", "").trim().toDoubleOrNull()
private fun f2(v: Double) = String.format(Locale.US, "%,.2f", v)
private fun f0(v: Double) = String.format(Locale.US, "%,.0f", v)
private fun plain(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

@Composable
fun App(tick: Int, resumed: Boolean) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val st by ImportBus.state.collectAsState()

    var holdings by remember { mutableStateOf(Store.load(ctx)) }
    var svcOn by remember { mutableStateOf(Importer.serviceEnabled(ctx)) }
    var selKey by rememberSaveable { mutableStateOf("") }
    var s1Edited by rememberSaveable { mutableStateOf(false) }
    var p1 by rememberSaveable { mutableStateOf("") }
    var s1 by rememberSaveable { mutableStateOf("") }
    var tgt by rememberSaveable { mutableStateOf("") }
    var feeOn by rememberSaveable { mutableStateOf(true) }
    var disc by rememberSaveable { mutableStateOf("6") }

    var dlg by remember { mutableStateOf("") }
    var delKey by remember { mutableStateOf("") }
    var addName by remember { mutableStateOf("") }
    var addShares by remember { mutableStateOf("") }
    var addAvg by remember { mutableStateOf("") }
    var pickList by remember { mutableStateOf(emptyList<AppInfo>()) }
    var prices by remember { mutableStateOf(emptyMap<String, Quotes.Quote>()) }
    var qErr by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(Quotes.marketOpen()) }
    var follow by rememberSaveable { mutableStateOf(true) }

    fun pick(h: Holding) {
        if (selKey != h.key) {
            follow = true
            s1Edited = false
        }
        selKey = h.key
    }

    LaunchedEffect(tick) {
        holdings = Store.load(ctx)
        svcOn = Importer.serviceEnabled(ctx)
    }
    LaunchedEffect(holdings) {
        if (holdings.isNotEmpty() && holdings.none { it.key == selKey }) pick(holdings[0])
    }
    LaunchedEffect(st) {
        if (st is ImportState.Done) {
            holdings = Store.load(ctx)
            dlg = "done"
        } else if (st is ImportState.Failed) {
            dlg = "failed"
        }
    }

    // 名稱 → 股票代號（只需要做一次，結果會存起來）
    LaunchedEffect(holdings.map { it.key to it.code }) {
        if (holdings.any { it.code.isEmpty() }) {
            val cur = holdings
            val res = withContext(Dispatchers.IO) {
                try { Quotes.resolve(ctx, cur) } catch (e: Exception) { cur }
            }
            if (res.map { it.code } != cur.map { it.code }) {
                Store.save(ctx, res)
                holdings = res
            }
        }
    }

    // 每秒查詢持股最新即時價（盤後放慢，App 在背景時暫停）
    val codesKey = holdings.map { it.code }.joinToString(",")
    LaunchedEffect(resumed, codesKey) {
        val codes = holdings.map { it.code }.filter { it.isNotEmpty() }.distinct()
        if (!resumed || codes.isEmpty()) return@LaunchedEffect
        while (true) {
            val t0 = System.currentTimeMillis()
            val res = withContext(Dispatchers.IO) { Quotes.fetch(codes) }
            if (res.map.isNotEmpty()) prices = prices + res.map
            qErr = res.err
            open = Quotes.marketOpen()
            val interval = if (open) 1000L else 15000L
            delay(maxOf(250L, interval - (System.currentTimeMillis() - t0)))
        }
    }

    // 買進價格跟隨即時價（按不按試算都一樣，隨時更新）
    val selH = holdings.firstOrNull { it.key == selKey }
    val live = selH?.let { prices[it.code] }
    // 買進股數預設 = 目前持有股數（自己改過就不再自動帶入）
    LaunchedEffect(selKey, selH?.shares) {
        if (!s1Edited) s1 = selH?.shares?.toString() ?: ""
    }
    LaunchedEffect(live?.price, follow, selKey) {
        if (follow && live != null) p1 = plain(live.price)
    }

    fun startImport() {
        if (!Importer.serviceEnabled(ctx)) {
            dlg = "svc"
            return
        }
        val pkg = Importer.resolvePkg(ctx)
        if (pkg == null) {
            val c = Importer.candidates(ctx)
            if (c.isEmpty()) dlg = "missing" else {
                pickList = c
                dlg = "pick"
            }
            return
        }
        if (!Importer.begin(ctx, pkg)) dlg = "missing"
    }

    // ---- 計算 ----
    val S0 = (selH?.shares ?: 0L).toDouble()
    val A0 = selH?.avg ?: 0.0
    val P = p1.d()
    val X = s1.d()
    val D = disc.d() ?: 10.0
    val r = if (P != null && X != null && P > 0 && X > 0) Calc.after(S0, A0, P, X, feeOn, D) else null
    val T = tgt.d()

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text("零股均價", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("加碼前先算一下，均價會到哪裡", color = Color.White.copy(alpha = 0.84f), fontSize = 14.sp)
        }

        // ---- 匯入 ----
        Glass(Modifier.fillMaxWidth(), strong = true) {
            val busyImport = st is ImportState.Waiting || st is ImportState.Reading
            val label = when (val x = st) {
                is ImportState.Waiting -> "等待庫存頁…　點此取消"
                is ImportState.Reading -> "辨識中 ${x.n} 檔…　點此取消"
                else -> "一鍵匯入持股"
            }
            GlassButton(label, Modifier.fillMaxWidth().height(58.dp), primary = !busyImport, shimmer = busyImport) {
                if (busyImport) Importer.cancel(ctx) else {
                    ImportBus.state.value = ImportState.Idle
                    startImport()
                }
            }
        }

        // ---- 持股 ----
        Glass(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("我的持股", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    "＋ 手動新增", color = Accent, fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        addName = ""; addShares = ""; addAvg = ""; dlg = "add"
                    }.padding(6.dp),
                )
            }
            if (holdings.isEmpty()) {
                Text("還沒有持股。按上面的「一鍵匯入持股」，或手動新增。", color = Color.White.copy(alpha = 0.80f), fontSize = 14.sp)
            }
            holdings.forEach { h ->
                val sel = h.key == selKey
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable { pick(h) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            h.title, fontSize = 16.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (sel) Accent else Color.White,
                        )
                        Text("${String.format(Locale.US, "%,d", h.shares)} 股　均價 ${f2(h.avg)}", color = Color.White.copy(alpha = 0.84f), fontSize = 13.sp)
                        val q = prices[h.code]
                        if (q != null && h.avg > 0) {
                            val pct = (q.price - h.avg) / h.avg * 100
                            Text(
                                "現價 ${f2(q.price)}　" + (if (pct >= 0) "+" else "−") + String.format(Locale.US, "%.2f", Math.abs(pct)) + "%",
                                color = if (pct >= 0) Up else Down, fontSize = 13.sp,
                            )
                        }
                    }
                    Text("✕", color = Color.White.copy(alpha = 0.72f), fontSize = 16.sp,
                        modifier = Modifier.clickable { delKey = h.key; dlg = "del" }.padding(10.dp))
                }
            }
        }

        // ---- 計算機 ----
        Glass(Modifier.fillMaxWidth()) {
            Text("加碼試算", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (selH != null) {
                Text(
                    "${selH.title}　持有 ${String.format(Locale.US, "%,d", selH.shares)} 股　均價 ${f2(selH.avg)}",
                    color = Color.White.copy(alpha = 0.84f), fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GField("買進股數", s1, { s1 = it; s1Edited = true }, Modifier.weight(1f), KeyboardType.Number)
                GField("買進價格", p1, { p1 = it; follow = false }, Modifier.weight(1f))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        follow = !follow
                        if (follow && live != null) p1 = plain(live.price)
                    }
                    .padding(top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tail = when {
                    live != null -> "　" + f2(live.price) + (if (live.time.isNotEmpty()) "（${live.time}）" else "") +
                        (if (!open) " 盤後" else "")
                    selH != null && selH.code.isEmpty() -> "　找不到這檔的股票代號"
                    qErr != null -> "　連線中…（$qErr）"
                    else -> "　取得中…"
                }
                Text(
                    (if (follow) "● 跟隨即時價" else "○ 手動價格（點此改回跟隨即時價）") + tail,
                    color = if (follow) Good else Color.White.copy(alpha = 0.84f), fontSize = 13.sp,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("手續費算進成本", modifier = Modifier.weight(1f), fontSize = 14.sp)
                Switch(
                    checked = feeOn,
                    onCheckedChange = { feeOn = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF34C759),
                        checkedThumbColor = Color.White,
                        checkedBorderColor = Color.Transparent,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.22f),
                        uncheckedThumbColor = Color.White,
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }
            if (feeOn) {
                GField("手續費折數（6 = 6折）", disc, { disc = it }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(14.dp))

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("加碼後新均價", color = Color.White.copy(alpha = 0.84f), fontSize = 13.sp)
                Text(if (r != null) f2(r.avg) else "—", fontSize = 46.sp, fontWeight = FontWeight.Bold)
                if (r != null && S0 > 0 && A0 > 0) {
                    val dv = r.avg - A0
                    Text(
                        (if (dv <= 0) "比原本降了 " else "比原本升了 ") + f2(Math.abs(dv)) +
                            "（" + String.format(Locale.US, "%.2f", Math.abs(dv) / A0 * 100) + "%）",
                        color = if (dv <= 0) Good else Bad, fontSize = 14.sp,
                    )
                }
            }
            if (r != null) {
                Spacer(Modifier.height(12.dp))
                KV("本次買進金額", f0(r.amount))
                if (r.fee > 0) KV("手續費", f0(r.fee))
                KV("加碼後總成本", f0(r.totalCost))
                KV("加碼後總股數", f0(r.totalShares) + " 股")
            }
        }

        // ---- 目標均價 ----
        Glass(Modifier.fillMaxWidth()) {
            Text("想把均價拉到多少？", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            GField("目標均價（用上方買進價格算）", tgt, { tgt = it }, Modifier.fillMaxWidth())
            if (T != null) {
                Spacer(Modifier.height(10.dp))
                val n = if (P != null && P > 0) Calc.sharesForTarget(S0, A0, P, T, feeOn, D) else null
                if (n != null && P != null) {
                    val q = Calc.after(S0, A0, P, n.toDouble(), feeOn, D)
                    KV("需要買進", String.format(Locale.US, "%,d", n) + " 股")
                    KV("需要資金", f0(q.amount + q.fee))
                    KV("屆時均價", f2(q.avg))
                } else {
                    Text("無法達成：目標要介於「買進價」和「目前均價」之間。", color = Bad, fontSize = 14.sp)
                }
            }
        }

        // ---- 不同股數對照 ----
        if (P != null && P > 0 && S0 > 0) {
            Glass(Modifier.fillMaxWidth()) {
                Text("不同買進股數對照", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                listOf(50, 100, 150, 200, 300, 500, 1000).forEach { n ->
                    val q = Calc.after(S0, A0, P, n.toDouble(), feeOn, D)
                    val dv = q.avg - A0
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("$n 股", Modifier.weight(1f), fontSize = 15.sp)
                        Text(f2(q.avg), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            (if (dv <= 0) "  ▼" else "  ▲") + f2(Math.abs(dv)),
                            color = if (dv <= 0) Good else Bad, fontSize = 13.sp,
                        )
                    }
                }
            }
        }

        // ---- 進階 ----
        Glass(Modifier.fillMaxWidth()) {
            Text("辨識不到持股？", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "按「一鍵匯入持股」進到庫存頁後回來，複製畫面資料貼給開發者，就能對準你的畫面調整辨識。",
                color = Color.White.copy(alpha = 0.80f), fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            GlassButton("複製最近一次讀到的畫面資料", Modifier.fillMaxWidth()) {
                val d = Store.dump(ctx)
                if (d.isEmpty()) Toast.makeText(ctx, "還沒有讀到任何畫面", Toast.LENGTH_SHORT).show()
                else {
                    clip.setText(AnnotatedString(d))
                    Toast.makeText(ctx, "已複製", Toast.LENGTH_SHORT).show()
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("持股資料只存在這支手機；網路只用來查即時股價（只送出股票代號）。", color = Color.White.copy(alpha = 0.68f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(20.dp))
    }

    // ---- 對話框 ----
    when (dlg) {
        "done" -> GlassDialog({ dlg = ""; ImportBus.state.value = ImportState.Idle }) {
            Text("已匯入成功", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Good)
            Spacer(Modifier.height(6.dp))
            Text("共 ${(st as? ImportState.Done)?.n ?: holdings.size} 檔持股已更新。", fontSize = 15.sp)
            Spacer(Modifier.height(14.dp))
            GlassButton("好", Modifier.fillMaxWidth(), primary = true) { dlg = ""; ImportBus.state.value = ImportState.Idle }
        }
        "failed" -> GlassDialog({ dlg = ""; ImportBus.state.value = ImportState.Idle }) {
            Text("匯入失敗", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Bad)
            Spacer(Modifier.height(6.dp))
            Text((st as? ImportState.Failed)?.msg ?: "", fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            GlassButton("知道了", Modifier.fillMaxWidth()) { dlg = ""; ImportBus.state.value = ImportState.Idle }
        }
        "svc" -> GlassDialog({ dlg = "" }) {
            Text("先開啟無障礙權限", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "1. 按下方按鈕，到「已下載的 App」找到「零股均價・持股讀取」並開啟。\n\n" +
                    "2. 如果開關是灰色、提示「受限制的設定」：回到手機「設定 → 應用程式 → 零股均價」，" +
                    "右上角 ⋮ 選「允許受限制的設定」，再回來開啟。\n\n" +
                    "只在你按「一鍵匯入持股」時才會讀取，而且只讀投資先生的庫存頁。",
                fontSize = 14.sp, color = Color.White.copy(alpha = 0.96f),
            )
            Spacer(Modifier.height(14.dp))
            GlassButton("前往無障礙設定", Modifier.fillMaxWidth(), primary = true) {
                dlg = ""
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Spacer(Modifier.height(8.dp))
            GlassButton("稍後", Modifier.fillMaxWidth()) { dlg = "" }
        }
        "missing" -> GlassDialog({ dlg = "" }) {
            Text("找不到投資先生", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("請確認手機已安裝「投資先生」app。", fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            GlassButton("好", Modifier.fillMaxWidth()) { dlg = "" }
        }
        "pick" -> GlassDialog({ dlg = "" }) {
            Text("請選擇投資先生", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            pickList.forEach { a ->
                Text(
                    a.label, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().clickable {
                        dlg = ""
                        Store.setTarget(ctx, a.pkg)
                        Importer.begin(ctx, a.pkg)
                    }.padding(vertical = 12.dp),
                )
            }
            GlassButton("取消", Modifier.fillMaxWidth()) { dlg = "" }
        }
        "del" -> GlassDialog({ dlg = "" }) {
            val h = holdings.firstOrNull { it.key == delKey }
            Text("刪除 ${h?.title ?: ""}？", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("取消", Modifier.weight(1f)) { dlg = "" }
                GlassButton("刪除", Modifier.weight(1f), primary = true) {
                    Store.remove(ctx, delKey)
                    holdings = Store.load(ctx)
                    if (selKey == delKey) selKey = ""
                    dlg = ""
                }
            }
        }
        "add" -> GlassDialog({ dlg = "" }) {
            Text("手動新增持股", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            GField("股票（例：台燿 6274）", addName, { addName = it }, Modifier.fillMaxWidth(), KeyboardType.Text)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GField("股數", addShares, { addShares = it }, Modifier.weight(1f), KeyboardType.Number)
                GField("均價", addAvg, { addAvg = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("取消", Modifier.weight(1f)) { dlg = "" }
                GlassButton("儲存", Modifier.weight(1f), primary = true) {
                    val sh = addShares.d()?.toLong()
                    val av = addAvg.d()
                    if (addName.isNotBlank() && sh != null && sh > 0 && av != null && av > 0) {
                        val (name, code) = Parser.splitNameCode(addName)
                        val h = Holding(code, name, sh, av)
                        Store.upsert(ctx, h)
                        holdings = Store.load(ctx)
                        pick(h)
                        dlg = ""
                    } else Toast.makeText(ctx, "請填完整", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, Modifier.weight(1f), color = Color.White.copy(alpha = 0.84f), fontSize = 14.sp)
        Text(v, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
