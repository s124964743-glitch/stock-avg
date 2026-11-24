package app.stockavg

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Holding(val code: String, val name: String, val shares: Long, val avg: Double) {
    val key: String get() = if (code.isNotEmpty()) code else name
    val title: String
        get() = if (code.isNotEmpty() && name.isNotEmpty()) "$name $code" else name.ifEmpty { code }
}

sealed interface ImportState {
    object Idle : ImportState
    object Waiting : ImportState
    data class Reading(val n: Int) : ImportState
    data class Done(val n: Int) : ImportState
    data class Failed(val msg: String) : ImportState
}

object ImportBus {
    val state = MutableStateFlow<ImportState>(ImportState.Idle)
}

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("stockavg", Context.MODE_PRIVATE)

    fun load(c: Context): List<Holding> = try {
        val a = JSONArray(sp(c).getString("holdings", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Holding(o.optString("code"), o.optString("name"), o.optLong("shares"), o.optDouble("avg"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(c: Context, list: List<Holding>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("code", it.code).put("name", it.name).put("shares", it.shares).put("avg", it.avg))
        }
        sp(c).edit().putString("holdings", a.toString()).apply()
    }

    fun upsert(c: Context, h: Holding) {
        val list = load(c).toMutableList()
        val i = list.indexOfFirst { it.key == h.key }
        if (i >= 0) list[i] = h else list.add(h)
        save(c, list)
    }

    fun remove(c: Context, key: String) = save(c, load(c).filter { it.key != key })

    fun importing(c: Context) = sp(c).getBoolean("importing", false)
    fun setImporting(c: Context, v: Boolean) = sp(c).edit().putBoolean("importing", v).apply()

    fun targetPkg(c: Context): String = sp(c).getString("target", "") ?: ""
    fun setTarget(c: Context, p: String) = sp(c).edit().putString("target", p).apply()

    fun lastImport(c: Context) = sp(c).getLong("last_import", 0L)
    fun setLastImport(c: Context, t: Long) = sp(c).edit().putLong("last_import", t).apply()

    fun dump(c: Context): String = sp(c).getString("dump", "") ?: ""
    fun setDump(c: Context, s: String) = sp(c).edit().putString("dump", s).apply()
}

object Calc {
    data class Result(
        val avg: Double,
        val amount: Double,
        val fee: Double,
        val totalShares: Double,
        val totalCost: Double,
    )

    fun fee(amount: Double, on: Boolean, disc: Double): Double =
        if (!on) 0.0 else maxOf(1.0, Math.round(amount * 0.001425 * disc / 10.0).toDouble())

    fun after(s0: Double, a0: Double, p: Double, x: Double, on: Boolean, disc: Double): Result {
        val amount = p * x
        val f = fee(amount, on, disc)
        val cost = s0 * a0 + amount + f
        val total = s0 + x
        return Result(cost / total, amount, f, total, cost)
    }

    /** 要買幾股才能讓均價達到目標；無法達成回傳 null。 */
    fun sharesForTarget(s0: Double, a0: Double, p: Double, t: Double, on: Boolean, disc: Double): Long? {
        val down = a0 > t && p < t
        val up = a0 < t && p > t
        if (s0 <= 0 || !(down || up)) return null
        var n = Math.ceil(s0 * (a0 - t) / (t - p)).toLong().coerceAtLeast(1)
        var guard = 0
        fun ok(k: Long): Boolean {
            val v = after(s0, a0, p, k.toDouble(), on, disc).avg
            return if (down) v <= t else v >= t
        }
        while (!ok(n) && guard++ < 20000) n++
        return if (ok(n)) n else null
    }
}
