package com.example.partdb

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

enum class Fam(val ru: String) { MOS("MOSFET"), IGBT("IGBT"), BJT("Биполярные"), DIO("Диоды") }

data class Part(
    val mpn: String, val fam: Fam, val sub: String, val maker: String,
    val pkg: String, val pin: String,
    val v: Double?, val i: Double?, val p: Double?, val note: String,
    val hfe1: Double?, val hfe2: Double?, val ft: Double?, val vsat: Double?,
    val vf: Double?, val trr: Double?, val rds: Double?, val vth: Double?, val qg: Double?
) {
    val key = normalize(mpn)
    val copack = sub.endsWith("_d")
    val typeRu: String get() = when (fam) {
        Fam.MOS -> if (sub == "pch") "MOSFET P-канал" else "MOSFET N-канал"
        Fam.IGBT -> if (copack) "IGBT + диод" else "IGBT без диода"
        Fam.BJT -> if (sub == "pnp") "Биполярный PNP" else "Биполярный NPN"
        Fam.DIO -> when (sub) {
            "sch" -> "Диод Шоттки"; "ufr" -> "Диод ultrafast"; "fast" -> "Диод fast"
            "sw" -> "Диод импульсный"; "bridge" -> "Мостовой выпрямитель"; else -> "Диод выпрямительный"
        }
    }
    val url get() = "https://www.google.com/search?q=$mpn+datasheet+pdf"
}

data class Marking(val code: String, val mpn: String, val hint: String) { val key = normalize(code) }

private val HOMO = mapOf('К' to 'k','Т' to 't','В' to 'b','С' to 'c','Р' to 'p','А' to 'a',
    'Е' to 'e','М' to 'm','Н' to 'h','О' to 'o','Х' to 'x','У' to 'y','І' to 'i')

fun normalize(s: String): String = buildString {
    for (ch in s.uppercase()) {
        val c = HOMO[ch] ?: ch.lowercaseChar()
        if (c.isLetterOrDigit()) append(c)
    }
}

fun fmt(v: Double?): String =
    if (v == null) "—" else if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

object Repo {
    var parts: List<Part> = emptyList(); private set

    fun load(ctx: Context) {
        if (parts.isNotEmpty()) return
        parts = ctx.assets.open("parts.csv").bufferedReader().readLines()
            .drop(1).filter { it.isNotBlank() }.map { parse(it) }
    }

    private fun parse(line: String): Part {
        val c = line.split(",")
        fun s(i: Int) = c.getOrNull(i)?.trim().orEmpty()
        fun d(i: Int) = s(i).toDoubleOrNull()
        return Part(s(0), Fam.valueOf(s(1).uppercase()), s(2), s(3), s(4), s(5),
            d(6), d(7), d(8), s(9), d(10), d(11), d(12), d(13), d(14), d(15), d(16), d(17), d(18))
    }

    fun byMpn(mpn: String): Part? = parts.firstOrNull { it.mpn.equals(mpn, true) }
    fun count(f: Fam) = parts.count { it.fam == f }

    fun search(q: String, fam: Fam?): List<Part> {
        val nq = normalize(q)
        return parts.asSequence()
            .filter { fam == null || it.fam == fam }
            .map { p ->
                p to when {
                    nq.isEmpty() -> 0
                    p.key == nq -> 0
                    p.key.startsWith(nq) -> 1
                    p.key.contains(nq) -> 2
                    normalize(p.note).contains(nq) -> 3
                    normalize(p.pkg).contains(nq) -> 4
                    else -> -1
                }
            }
            .filter { it.second >= 0 }
            .sortedWith(compareBy({ it.second }, { it.first.mpn }))
            .map { it.first }.toList()
    }
}

object MarkRepo {
    var all: List<Marking> = emptyList(); private set

    fun load(ctx: Context) {
        if (all.isNotEmpty()) return
        all = runCatching {
            ctx.assets.open("markings.csv").bufferedReader().readLines()
                .drop(1).filter { it.isNotBlank() }.map {
                    val c = it.split(",")
                    Marking(c[0].trim(), c.getOrElse(1) { "" }.trim(), c.getOrElse(2) { "" }.trim())
                }
        }.getOrDefault(emptyList())
    }

    fun search(q: String): List<Marking> {
        val nq = normalize(q)
        return all.filter { nq.isEmpty() || it.key.startsWith(nq) || it.key.contains(nq) }
            .sortedWith(compareBy({ it.code.length }, { it.code }))
    }

    fun forPart(mpn: String): List<Marking> = all.filter { it.mpn.equals(mpn, true) }
}

object Prefs {
    val favs = mutableStateListOf<String>()
    var dark = mutableStateOf(true)
    private var sp: SharedPreferences? = null

    fun load(ctx: Context) {
        sp = ctx.getSharedPreferences("partdb", 0)
        favs.clear(); favs.addAll(sp?.getStringSet("favs", emptySet()) ?: emptySet())
        dark.value = sp?.getBoolean("dark", true) ?: true
    }
    fun toggleFav(mpn: String) {
        if (mpn in favs) favs.remove(mpn) else favs.add(mpn)
        sp?.edit()?.putStringSet("favs", favs.toSet())?.apply()
    }
    fun setDark(v: Boolean) { dark.value = v; sp?.edit()?.putBoolean("dark", v)?.apply() }
}

data class Metric(val label: String, val unit: String, val better: Int, val get: (Part) -> Double?)

object Metrics {
    private val common = listOf(
        Metric("U макс", "В", 1) { it.v },
        Metric("I макс", "А", 1) { it.i },
        Metric("P tot", "Вт", 1) { it.p }
    )
    fun forFam(f: Fam): List<Metric> = common + when (f) {
        Fam.MOS -> listOf(
            Metric("R ds(on)", "мОм", -1) { it.rds }, Metric("U gs(th)", "В", 0) { it.vth },
            Metric("Q g", "нКл", 0) { it.qg })
        Fam.IGBT -> listOf(Metric("U ce(sat)", "В", -1) { it.vsat })
        Fam.BJT -> listOf(
            Metric("hFE min", "", 1) { it.hfe1 }, Metric("hFE max", "", 0) { it.hfe2 },
            Metric("fT", "МГц", 1) { it.ft }, Metric("U ce(sat)", "В", -1) { it.vsat })
        Fam.DIO -> listOf(Metric("U f", "В", -1) { it.vf }, Metric("t rr", "нс", -1) { it.trr })
    }
}

enum class Grade(val ru: String) { DIRECT("Pin-to-pin"), CAUTION("С оговорками") }
data class Hit(val p: Part, val score: Int, val grade: Grade, val warn: List<String>)

object Analog {
    private val W = mapOf("U макс" to 3.0, "I макс" to 2.5, "P tot" to 1.5,
        "hFE min" to 1.2, "hFE max" to 0.6, "fT" to 1.0, "U ce(sat)" to 1.5,
        "R ds(on)" to 3.0, "U gs(th)" to 1.2, "Q g" to 2.0, "U f" to 2.0, "t rr" to 3.0)

    fun find(o: Part, ctx: String = "general", strict: Boolean = true): List<Hit> =
        Repo.parts.filter { it.mpn != o.mpn && it.fam == o.fam }
            .mapNotNull { eval(o, it, ctx, strict) }
            .sortedWith(compareBy({ it.grade.ordinal }, { -it.score }))

    fun eval(o: Part, c: Part, ctx: String, strict: Boolean): Hit? {
        val warn = mutableListOf<String>()
        when (o.fam) {
            Fam.BJT, Fam.MOS -> if (o.sub != c.sub) return null
            Fam.IGBT -> if (o.copack != c.copack) return null
            Fam.DIO -> {
                if ((o.sub == "bridge") != (c.sub == "bridge")) return null
                if (o.sub != "bridge") {
                    if ((o.sub == "sch") != (c.sub == "sch")) return null
                    if (o.sub != "std" && c.sub == "std") return null
                    if (o.sub != c.sub) warn += "иное быстродействие: ${c.sub} вместо ${o.sub}"
                }
            }
        }
        if (o.v != null && c.v != null && c.v < o.v) return null
        if (o.i != null && c.i != null && c.i < o.i) {
            if (strict || c.i < o.i * 0.8) return null
            warn += "меньший ток: ${fmt(c.i)} А вместо ${fmt(o.i)} А"
        }
        if (!o.pkg.equals(c.pkg, true)) warn += "иной корпус: ${c.pkg} вместо ${o.pkg}"
        if (o.pin != c.pin) warn += "распиновка ${c.pin} вместо ${o.pin} — проверить"
        if ("check" in listOf(o.pin, c.pin)) warn += "распиновка не верифицирована"

        var sum = 0.0; var wsum = 0.0
        for (m in Metrics.forFam(o.fam)) {
            val a = m.get(o) ?: continue
            val b = m.get(c) ?: continue
            if (a == 0.0) continue
            var w = W[m.label] ?: 1.0
            if (ctx == "smps" && m.label in listOf("Q g", "t rr")) w *= 1.8
            if (ctx == "audio" && m.label in listOf("hFE min", "fT")) w *= 1.6
            val r = b / a
            val s = when (m.better) {
                1 -> if (r >= 1.0) 1.0 - 0.1 * ln(r).coerceAtMost(3.0) else 0.0
                -1 -> if (r <= 1.0) 1.0 else (2.0 - r).coerceAtLeast(0.0)
                else -> (1.0 - abs(r - 1.0) / 0.5).coerceIn(0.0, 1.0)
            }
            if (m.label == "P tot" && r < 1.0) warn += "меньше P tot: ${fmt(b)} Вт"
            if (m.better == -1 && r > 1.15) warn += "хуже ${m.label}: ${fmt(b)} vs ${fmt(a)} ${m.unit}"
            if (m.label == "Q g" && abs(r - 1.0) > 0.3)
                warn += "Q g ${(100 * (r - 1)).roundToInt()}% — проверить драйвер"
            if (m.label == "U gs(th)" && a <= 2.5 && b > 2.5) warn += "не logic-level: нужен затвор 10 В"
            if (m.label == "hFE min" && b < a * 0.7) warn += "меньший hFE — проверить режим"
            sum += w * s; wsum += w
        }
        if (wsum == 0.0) { sum = 1.0; wsum = 1.0 }
        val score = (100 * sum / wsum).roundToInt()
        return Hit(c, score, if (warn.isEmpty()) Grade.DIRECT else Grade.CAUTION, warn)
    }
}
