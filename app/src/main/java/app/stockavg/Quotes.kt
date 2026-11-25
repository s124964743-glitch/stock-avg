package app.stockavg

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Calendar
import java.util.TimeZone

/** 即時股價（證交所基本市況報導網站）與「股票名稱 → 代號」對照。 */
object Quotes {
    data class Quote(val price: Double, val prev: Double?, val time: String, val traded: Boolean)

    class FetchResult(val map: Map<String, Quote>, val err: String?)

    @Volatile
    private var warmed = false

    init {
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager())
    }

    private fun http(url: String, charset: String = "UTF-8", timeoutMs: Int = 5000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
            )
            c.setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9")
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return String(c.inputStream.use { it.readBytes() }, Charset.forName(charset))
        } finally {
            c.disconnect()
        }
    }

    /** 台股盤中與盤後零股（平日 08:30～14:35）每秒查，其餘時間 15 秒查一次。 */
    fun marketOpen(): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Taipei"))
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return m in (8 * 60 + 30)..(14 * 60 + 35)
    }

    fun fetch(codes: List<String>): FetchResult {
        return try {
            if (!warmed) {
                try {
                    http("https://mis.twse.com.tw/stock/index.jsp")
                } catch (_: Exception) {
                }
                warmed = true
            }
            // 不知道是上市還是上櫃，兩種都問，只會回傳存在的那個
            val ex = codes.flatMap { listOf("tse_$it.tw", "otc_$it.tw") }.joinToString("%7C")
            val body = http("https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=$ex&json=1&delay=0&_=${System.currentTimeMillis()}", timeoutMs = 2500)
            val arr = JSONObject(body).optJSONArray("msgArray") ?: JSONArray()
            val out = HashMap<String, Quote>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val code = o.optString("c")
                if (code.isEmpty()) continue
                val z = o.optString("z").toDoubleOrNull()
                val bid = o.optString("b").split('_').firstOrNull { it.isNotEmpty() }?.toDoubleOrNull()
                val y = o.optString("y").toDoubleOrNull()
                val price = z ?: bid ?: y ?: continue
                out[code] = Quote(price, y, o.optString("t"), z != null)
            }
            FetchResult(out, if (out.isEmpty()) "查不到報價" else null)
        } catch (e: Exception) {
            warmed = false
            FetchResult(emptyMap(), e.message ?: "連線失敗")
        }
    }

    // ---------- 名稱 → 代號 ----------
    private fun norm(s: String) = s.replace(" ", "").replace("　", "").trim()

    private fun toMap(o: JSONObject): Map<String, String> {
        val m = HashMap<String, String>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            m[k] = o.optString(k)
        }
        return m
    }

    fun directory(ctx: Context): Map<String, String> {
        val sp = ctx.getSharedPreferences("stockavg", Context.MODE_PRIVATE)
        val cached = sp.getString("dir", null)
        if (cached != null && System.currentTimeMillis() - sp.getLong("dir_ts", 0L) < 24 * 3600_000L) {
            try {
                return toMap(JSONObject(cached))
            } catch (_: Exception) {
            }
        }
        val m = HashMap<String, String>()
        try {
            val a = JSONArray(http("https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL", timeoutMs = 20000))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                m.putIfAbsent(norm(o.optString("Name")), o.optString("Code"))
            }
        } catch (_: Exception) {
        }
        for (mode in listOf(2, 4)) {
            try {
                val html = http("https://isin.twse.com.tw/isin/C_public.jsp?strMode=$mode", "Big5", 20000)
                Regex("<td[^>]*>\\s*([0-9A-Z]{4,6})[\\u3000 ]+([^<]+?)\\s*</td>").findAll(html).forEach {
                    m.putIfAbsent(norm(it.groupValues[2]), it.groupValues[1])
                }
            } catch (_: Exception) {
            }
        }
        try {
            val j = JSONObject(
                http("https://www.tpex.org.tw/web/stock/aftertrading/daily_close_quotes/stk_quote_result.php?l=zh-tw&o=json", timeoutMs = 20000)
            )
            val rows = j.optJSONArray("aaData") ?: j.optJSONArray("tables")?.optJSONObject(0)?.optJSONArray("data")
            if (rows != null) {
                for (i in 0 until rows.length()) {
                    val r = rows.optJSONArray(i) ?: continue
                    m.putIfAbsent(norm(r.optString(1)), r.optString(0))
                }
            }
        } catch (_: Exception) {
        }
        if (m.isNotEmpty()) {
            sp.edit().putString("dir", JSONObject(m as Map<*, *>).toString()).putLong("dir_ts", System.currentTimeMillis()).apply()
        }
        return m
    }

    fun resolve(ctx: Context, list: List<Holding>): List<Holding> {
        if (list.none { it.code.isEmpty() }) return list
        val dir = directory(ctx)
        return list.map { h ->
            if (h.code.isNotEmpty()) h else {
                val n = norm(h.name)
                val exact = dir[n]
                val code = exact ?: dir.entries.filter { it.key.startsWith(n) }.let { if (it.size == 1) it[0].value else null }
                if (code != null) h.copy(code = code) else h
            }
        }
    }
}
