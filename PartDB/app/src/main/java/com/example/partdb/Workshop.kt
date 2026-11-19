package com.example.partdb

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/* ============================ ХРАНИЛИЩЕ ============================ */

data class Job(
    val id: String, val device: String, val model: String, val symptom: String,
    val found: String, val replaced: String, val status: String, val date: String, val notes: String
)

object Shop {
    private var sp: SharedPreferences? = null
    val jobs = mutableStateListOf<Job>()
    val steps = mutableStateListOf<String>()
    val safety = mutableStateListOf<String>()
    var section = mutableStateOf(0)
    var calcOpen = mutableStateOf<String?>(null)
    var diagOpen = mutableStateOf<String?>(null)
    var jobEdit = mutableStateOf<Job?>(null)
    var jobForm = mutableStateOf(false)
    var undoMsg = mutableStateOf<String?>(null)
    var undoAct = mutableStateOf<(() -> Unit)?>(null)

    val statuses = listOf("В работе", "Ждёт запчастей", "Готово", "Не подлежит")

    fun init(ctx: Context) {
        if (sp != null) return
        sp = ctx.getSharedPreferences("partdb.shop", 0)
        val p = sp ?: return
        runCatching {
            val a = JSONArray(p.getString("jobs", "[]"))
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                jobs.add(Job(o.optString("id"), o.optString("device"), o.optString("model"),
                    o.optString("symptom"), o.optString("found"), o.optString("replaced"),
                    o.optString("status", "В работе"), o.optString("date"), o.optString("notes")))
            }
        }
        p.getStringSet("steps", emptySet())?.let { steps.addAll(it) }
        p.getStringSet("safety", emptySet())?.let { safety.addAll(it) }
        if (!p.getBoolean("seeded", false)) {
            if (jobs.isEmpty()) {
                jobs.addAll(listOf(
                    Job("s1", "БП ATX 500 Вт", "Chieftec GPA-500S", "Не стартует, дежурка 5 В есть",
                        "Пробит ключ PFC, обрыв резистора затвора 22 Ом, вспух C по +12 В",
                        "SPP20N60C3; 22 Ом 0.5 Вт; 2200 мкФ 16 В low-ESR x2", "Готово", today(),
                        "Первый пуск через лампу 100 Вт. Пульсации после ремонта 40 мВ."),
                    Job("s2", "ИБП 1000 ВА", "Ippon Back Basic 1050", "Не переходит на батарею, писк",
                        "Батарея 12 В 7 Ач просела до 8.4 В, реле переключения подгорело",
                        "АКБ 12 В 9 Ач; реле 12 В 30 А", "Ждёт запчастей", today(),
                        "Ключи инвертора целы, зарядка выдаёт 13.6 В."),
                    Job("s3", "Индукционная плита", "Supra SDI-20", "Ошибка E1, выбивает автомат",
                        "Пробит IGBT и мост, следы перегрева на радиаторе",
                        "H25R1203; GBU806; термопаста", "В работе", today(),
                        "Проверить датчик температуры и вентилятор перед сборкой.")
                ))
            }
            p.edit().putBoolean("seeded", true).apply()
            saveJobs()
        }
    }

    fun today(): String = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())

    fun saveJobs() {
        val a = JSONArray()
        jobs.forEach { j ->
            a.put(JSONObject().put("id", j.id).put("device", j.device).put("model", j.model)
                .put("symptom", j.symptom).put("found", j.found).put("replaced", j.replaced)
                .put("status", j.status).put("date", j.date).put("notes", j.notes))
        }
        sp?.edit()?.putString("jobs", a.toString())?.apply()
    }

    fun upsert(j: Job) {
        val k = jobs.indexOfFirst { it.id == j.id }
        if (k >= 0) jobs[k] = j else jobs.add(0, j)
        saveJobs()
    }

    fun remove(id: String) {
        val k = jobs.indexOfFirst { it.id == id }
        if (k < 0) return
        val j = jobs[k]
        jobs.removeAt(k); saveJobs()
        undoMsg.value = "Запись «${j.device}» удалена"
        undoAct.value = { jobs.add(minOf(k, jobs.size), j); saveJobs(); undoMsg.value = null; undoAct.value = null }
    }

    fun toggleStep(key: String) {
        if (key in steps) steps.remove(key) else steps.add(key)
        sp?.edit()?.putStringSet("steps", steps.toSet())?.apply()
    }

    fun toggleSafety(key: String) {
        if (key in safety) safety.remove(key) else safety.add(key)
        sp?.edit()?.putStringSet("safety", safety.toSet())?.apply()
    }

    fun jobsCsv(): String {
        val h = "date;device;model;status;symptom;found;replaced;notes"
        return h + "\n" + jobs.joinToString("\n") { j ->
            listOf(j.date, j.device, j.model, j.status, j.symptom, j.found, j.replaced, j.notes)
                .joinToString(";") { it.replace(";", ",").replace("\n", " ") }
        }
    }
}

/* ============================ ЧИСЛА И ЕДИНИЦЫ ============================ */

private fun sig(v: Double?): String {
    if (v == null || v.isNaN() || v.isInfinite()) return "—"
    val a = abs(v)
    val s = when {
        a == 0.0 -> return "0"
        a >= 10000 -> String.format(Locale.US, "%.0f", v)
        a >= 100 -> String.format(Locale.US, "%.1f", v)
        a >= 1 -> String.format(Locale.US, "%.2f", v)
        a >= 0.01 -> String.format(Locale.US, "%.4f", v)
        else -> String.format(Locale.US, "%.2e", v)
    }
    return if (s.contains('.') && !s.contains('e')) s.trimEnd('0').trimEnd('.') else s
}

private fun ohm(v: Double?): String = when {
    v == null || !v.isFinite() -> "—"
    abs(v) >= 1e6 -> sig(v / 1e6) + " МОм"
    abs(v) >= 1e3 -> sig(v / 1e3) + " кОм"
    abs(v) < 1 && v != 0.0 -> sig(v * 1e3) + " мОм"
    else -> sig(v) + " Ом"
}

private fun watt(v: Double?): String = when {
    v == null || !v.isFinite() -> "—"
    abs(v) >= 1000 -> sig(v / 1000) + " кВт"
    abs(v) < 1 && v != 0.0 -> sig(v * 1000) + " мВт"
    else -> sig(v) + " Вт"
}

private fun farad(v: Double?): String = when {
    v == null || !v.isFinite() -> "—"
    abs(v) >= 1 -> sig(v) + " Ф"
    abs(v) >= 1e-3 -> sig(v * 1e3) + " мФ"
    abs(v) >= 1e-6 -> sig(v * 1e6) + " мкФ"
    abs(v) >= 1e-9 -> sig(v * 1e9) + " нФ"
    else -> sig(v * 1e12) + " пФ"
}

private fun henry(v: Double?): String = when {
    v == null || !v.isFinite() -> "—"
    abs(v) >= 1e-3 -> sig(v * 1e3) + " мГн"
    abs(v) >= 1e-6 -> sig(v * 1e6) + " мкГн"
    else -> sig(v * 1e9) + " нГн"
}

private fun sec(v: Double?): String = when {
    v == null || !v.isFinite() -> "—"
    abs(v) >= 1 -> sig(v) + " с"
    abs(v) >= 1e-3 -> sig(v * 1e3) + " мс"
    abs(v) >= 1e-6 -> sig(v * 1e6) + " мкс"
    else -> sig(v * 1e9) + " нс"
}

private fun hz(v: Double?): String = when {
    v == null || !v.isFinite() -> "—"
    abs(v) >= 1e6 -> sig(v / 1e6) + " МГц"
    abs(v) >= 1e3 -> sig(v / 1e3) + " кГц"
    else -> sig(v) + " Гц"
}

private val E24 = doubleArrayOf(1.0,1.1,1.2,1.3,1.5,1.6,1.8,2.0,2.2,2.4,2.7,3.0,3.3,3.6,3.9,4.3,4.7,5.1,5.6,6.2,6.8,7.5,8.2,9.1)

private fun e24(v: Double?): String {
    if (v == null || v <= 0 || !v.isFinite()) return "—"
    val dec = floor(log10(v))
    val base = v / 10.0.pow(dec)
    val best = E24.minByOrNull { abs(it - base) } ?: 1.0
    return ohm(best * 10.0.pow(dec))
}

/* ============================ КАЛЬКУЛЯТОРЫ ============================ */

private data class CF(val k: String, val l: String, val u: String, val d: String = "")
private data class CR(val l: String, val v: String, val h: String = "")
private data class Calc(val id: String, val t: String, val s: String, val f: String,
                        val fields: List<CF>, val calc: (Map<String, Double?>) -> List<CR>)

private fun cf(k: String, l: String, u: String, d: String = "") = CF(k, l, u, d)
private fun cr(l: String, v: String, h: String = "") = CR(l, v, h)

private val CALCS: List<Calc> = listOf(
    Calc("ohm", "Закон Ома и мощность", "Заполни любые два значения", "U = I·R   P = U·I = I²R = U²/R",
        listOf(cf("u","Напряжение","В"), cf("i","Ток","А"), cf("r","Сопротивление","Ом"), cf("p","Мощность","Вт"))) { m ->
        var u = m["u"]; var i = m["i"]; var r = m["r"]; var p = m["p"]
        repeat(3) {
            if (u == null && i != null && r != null) u = i!! * r!!
            if (u == null && p != null && i != null && i != 0.0) u = p!! / i!!
            if (u == null && p != null && r != null) u = sqrt(p!! * r!!)
            if (i == null && u != null && r != null && r != 0.0) i = u!! / r!!
            if (i == null && p != null && u != null && u != 0.0) i = p!! / u!!
            if (i == null && p != null && r != null && r != 0.0) i = sqrt(p!! / r!!)
            if (r == null && u != null && i != null && i != 0.0) r = u!! / i!!
            if (r == null && u != null && p != null && p != 0.0) r = u!! * u!! / p!!
            if (r == null && p != null && i != null && i != 0.0) r = p!! / (i!! * i!!)
            if (p == null && u != null && i != null) p = u!! * i!!
            if (p == null && i != null && r != null) p = i!! * i!! * r!!
            if (p == null && u != null && r != null && r != 0.0) p = u!! * u!! / r!!
        }
        listOf(cr("Напряжение", if (u == null) "—" else sig(u) + " В"),
            cr("Ток", if (i == null) "—" else sig(i) + " А"),
            cr("Сопротивление", ohm(r)), cr("Мощность", watt(p)))
    },
    Calc("div", "Делитель напряжения", "Опорник TL431, датчики, подтяжки", "Uout = Uin·R2/(R1+R2)",
        listOf(cf("uin","Вход","В","12"), cf("r1","R1 (верхний)","Ом","10000"),
            cf("r2","R2 (нижний)","Ом","2200"), cf("il","Ток нагрузки","мА","0"))) { m ->
        val u = m["uin"]; val a = m["r1"]; val b = m["r2"]; val il = m["il"]
        if (u == null || a == null || b == null || a + b == 0.0) {
            listOf(cr("Заполни Uin, R1, R2", "—"))
        } else {
            val u0 = u * b / (a + b)
            val idiv = u / (a + b)
            val rl = if (il != null && il > 0) u0 / (il / 1000.0) else null
            val par = if (rl != null) b * rl / (b + rl) else b
            val ul = u * par / (a + par)
            listOf(cr("Выход без нагрузки", sig(u0) + " В"),
                cr("Выход под нагрузкой", sig(ul) + " В",
                    if (rl != null) "просадка " + sig(u0 - ul) + " В" else "нагрузка не задана"),
                cr("Ток делителя", sig(idiv * 1000) + " мА"),
                cr("Мощность на R1", watt(idiv * idiv * a)),
                cr("Мощность на R2", watt(u0 * u0 / b)),
                cr("Выходное сопротивление", ohm(a * b / (a + b)), "влияет на точность АЦП"))
        }
    },
    Calc("mos", "Потери MOSFET", "Проводимость + переключение", "Pc = I²·Rds·D;  Psw = ½·U·I·t·f",
        listOf(cf("id","Ток стока","А","10"), cf("rds","R ds(on)","мОм","17.5"),
            cf("d","Скважность","%","45"), cf("f","Частота","кГц","65"),
            cf("uds","Напряжение стока","В","310"), cf("tsw","Время переключения","нс","60"),
            cf("qg","Q g","нКл","63"), cf("vgs","Напряжение затвора","В","12"))) { m ->
        val id = m["id"]; val rds = m["rds"]; val d = m["d"]; val f = m["f"]
        val u = m["uds"]; val t = m["tsw"]; val qg = m["qg"]; val vg = m["vgs"]
        val pc = if (id != null && rds != null && d != null) id * id * rds / 1000.0 * d / 100.0 else null
        val psw = if (u != null && id != null && t != null && f != null) 0.5 * u * id * t * 1e-9 * f * 1e3 else null
        val pg = if (qg != null && vg != null && f != null) qg * 1e-9 * vg * f * 1e3 else null
        val tot = if (pc != null && psw != null) pc + psw else null
        listOf(cr("Потери проводимости", watt(pc)),
            cr("Потери переключения", watt(psw), "растут линейно с частотой"),
            cr("Потери в драйвере", watt(pg), "греется драйвер, не транзистор"),
            cr("Суммарно в ключе", watt(tot), "без учёта потерь диода тела"),
            cr("Rds при 100 °C", if (rds == null) "—" else ohm(rds * 1.6), "типично ×1.5…1.8"))
    },
    Calc("therm", "Радиатор и перегрев", "Tj = Ta + P·(Rjc + Rcs + Rsa)", "Rsa = (Tj−Ta)/P − Rjc − Rcs",
        listOf(cf("p","Рассеиваемая мощность","Вт","25"), cf("ta","Температура среды","°C","45"),
            cf("tj","Предел Tj","°C","125"), cf("rjc","R th j-c","°C/Вт","0.6"),
            cf("rcs","R th c-s (паста/прокладка)","°C/Вт","0.5"), cf("rsa","Радиатор (если есть)","°C/Вт",""))) { m ->
        val p = m["p"]; val ta = m["ta"]; val tj = m["tj"]; val jc = m["rjc"]; val cs = m["rcs"]; val sa = m["rsa"]
        val need = if (p != null && p > 0 && ta != null && tj != null && jc != null && cs != null) (tj - ta) / p - jc - cs else null
        val real = if (p != null && ta != null && jc != null && cs != null && sa != null) ta + p * (jc + cs + sa) else null
        listOf(cr("Нужен радиатор не хуже", if (need == null) "—" else sig(need) + " °C/Вт",
            if (need != null && need < 0) "даже без радиатора перегрев — снизь потери" else "с учётом пасты"),
            cr("Tj с указанным радиатором", if (real == null) "—" else sig(real) + " °C",
                if (real != null && tj != null && real > tj) "ПЕРЕГРЕВ" else "запас есть"),
            cr("Без радиатора (Rsa≈50)",
                if (p != null && ta != null && jc != null && cs != null) sig(ta + p * (jc + cs + 50)) + " °C" else "—"),
            cr("Запас до предела", if (real != null && tj != null) sig(tj - real) + " °C" else "—"))
    },
    Calc("gate", "Драйвер затвора", "P = Qg·Vgs·f;  Ipk = Vgs/(Rg+Rdrv)", "τ ≈ 2.2·R·Ciss",
        listOf(cf("qg","Q g","нКл","63"), cf("vgs","Напряжение драйвера","В","12"),
            cf("f","Частота","кГц","65"), cf("rg","Rg внешний","Ом","10"),
            cf("rd","Сопротивление драйвера","Ом","5"))) { m ->
        val qg = m["qg"]; val v = m["vgs"]; val f = m["f"]; val rg = m["rg"]; val rd = m["rd"]
        val p = if (qg != null && v != null && f != null) qg * 1e-9 * v * f * 1e3 else null
        val rt = (rg ?: 0.0) + (rd ?: 0.0)
        val ipk = if (v != null && rt > 0) v / rt else null
        val ciss = if (qg != null && v != null && v > 0) qg * 1e-9 / v else null
        val tau = if (ciss != null && rt > 0) 2.2 * rt * ciss else null
        listOf(cr("Мощность в драйвере", watt(p), "делится между драйвером и Rg"),
            cr("Мощность на Rg", if (p != null && rg != null && rt > 0) watt(p * rg / rt) else "—",
                "номинал с запасом минимум ×2"),
            cr("Пиковый ток затвора", if (ipk == null) "—" else sig(ipk) + " А"),
            cr("Оценка Ciss", farad(ciss)),
            cr("Время заряда затвора", sec(tau),
                "сравни с периодом " + (if (f != null && f > 0) sec(1.0 / (f * 1e3)) else "—")))
    },
    Calc("ripple", "Пульсации выпрямителя", "ΔU ≈ I·t/C, мост t = 1/(2f)", "C = I/(2f·ΔU)",
        listOf(cf("i","Ток нагрузки","А","2"), cf("f","Частота сети","Гц","50"),
            cf("c","Ёмкость","мкФ","2200"), cf("du","Допустимые пульсации","В","1"))) { m ->
        val i = m["i"]; val f = m["f"]; val c = m["c"]; val du = m["du"]
        val d = if (i != null && f != null && f > 0 && c != null && c > 0) i / (2 * f * c * 1e-6) else null
        val need = if (i != null && f != null && f > 0 && du != null && du > 0) i / (2 * f * du) else null
        listOf(cr("Размах пульсаций", if (d == null) "—" else sig(d) + " В"),
            cr("Нужная ёмкость", farad(need), "под заданные пульсации"),
            cr("Действующее значение пульсаций",
                if (d == null) "—" else sig(d / (2 * sqrt(3.0))) + " В", "треугольник ≈ ΔU/2√3"),
            cr("Ток через конденсатор", if (i == null) "—" else sig(i * 1.8) + " А",
                "ориентировочно, проверь ripple current"))
    },
    Calc("hold", "Время удержания (hold-up)", "ИБП, ATX, PFC: t = C(U1²−U2²)/2P", "энергия E = ½C·U²",
        listOf(cf("c","Ёмкость звена","мкФ","470"), cf("u1","Начальное напряжение","В","390"),
            cf("u2","Минимум для ШИМ","В","300"), cf("p","Мощность нагрузки","Вт","250"))) { m ->
        val c = m["c"]; val u1 = m["u1"]; val u2 = m["u2"]; val p = m["p"]
        val t = if (c != null && u1 != null && u2 != null && p != null && p > 0)
            c * 1e-6 * (u1 * u1 - u2 * u2) / (2 * p) else null
        val e = if (c != null && u1 != null) 0.5 * c * 1e-6 * u1 * u1 else null
        listOf(cr("Время удержания", sec(t), "норма ATX — не менее 16 мс"),
            cr("Запасённая энергия", if (e == null) "—" else sig(e) + " Дж"),
            cr("Полезная энергия",
                if (c != null && u1 != null && u2 != null) sig(0.5 * c * 1e-6 * (u1 * u1 - u2 * u2)) + " Дж" else "—"),
            cr("Ёмкость на 20 мс",
                if (u1 != null && u2 != null && p != null && u1 > u2) farad(2 * p * 0.02 / (u1 * u1 - u2 * u2)) else "—"))
    },
    Calc("rc", "RC-цепь и снаббер", "fc = 1/(2πRC);  R ≈ √(L/C)", "τ = R·C",
        listOf(cf("r","Сопротивление","Ом","10000"), cf("c","Ёмкость","мкФ","0.1"),
            cf("l","Паразитная индуктивность","мкГн","2"), cf("cs","Ёмкость снаббера","нФ","1"))) { m ->
        val r = m["r"]; val c = m["c"]; val l = m["l"]; val cs = m["cs"]
        val tau = if (r != null && c != null) r * c * 1e-6 else null
        val fc = if (tau != null && tau > 0) 1 / (2 * PI * tau) else null
        val fr = if (l != null && cs != null && l > 0 && cs > 0) 1 / (2 * PI * sqrt(l * 1e-6 * cs * 1e-9)) else null
        val z = if (l != null && cs != null && cs > 0) sqrt(l * 1e-6 / (cs * 1e-9)) else null
        listOf(cr("Постоянная времени", sec(tau)), cr("Частота среза", hz(fc)),
            cr("Частота звона", hz(fr), "паразитные L и C снаббера"),
            cr("Демпфирующий R", ohm(z), "≈ характеристическое сопротивление"),
            cr("Потери на снаббере",
                if (cs != null && fr != null && r != null) watt(cs * 1e-9 * r * r * fr) else "—",
                "оценка C·U²·f, уточни по осциллограмме"))
    },
    Calc("shunt", "Токовый шунт", "R = Usense/Imax;  P = I²R", "датчик тока ШИМ, ограничение",
        listOf(cf("i","Максимальный ток","А","5"), cf("us","Порог датчика","мВ","250"))) { m ->
        val i = m["i"]; val us = m["us"]
        val r = if (i != null && i > 0 && us != null) us / 1000.0 / i else null
        val p = if (i != null && r != null) i * i * r else null
        listOf(cr("Сопротивление шунта", ohm(r)), cr("Ближайший ряд E24", e24(r)),
            cr("Рассеиваемая мощность", watt(p), "бери запас ×2…×3"),
            cr("Падение на шунте", if (us == null) "—" else sig(us) + " мВ"),
            cr("Ток при 1 Вт", if (r != null && r > 0) sig(sqrt(1.0 / r)) + " А" else "—"))
    },
    Calc("sp", "Последовательно / параллельно", "Резисторы и конденсаторы", "Rs = ΣR;  1/Rp = Σ1/R",
        listOf(cf("r1","R1","Ом","100"), cf("r2","R2","Ом","220"), cf("r3","R3","Ом",""),
            cf("c1","C1","мкФ","100"), cf("c2","C2","мкФ","100"), cf("c3","C3","мкФ",""))) { m ->
        val rs = listOf(m["r1"], m["r2"], m["r3"]).filterNotNull()
        val cs = listOf(m["c1"], m["c2"], m["c3"]).filterNotNull()
        val rser = if (rs.isEmpty()) null else rs.sum()
        val rpar = if (rs.isEmpty() || rs.any { it == 0.0 }) null else 1.0 / rs.sumOf { 1.0 / it }
        val cpar = if (cs.isEmpty()) null else cs.sum()
        val cser = if (cs.isEmpty() || cs.any { it == 0.0 }) null else 1.0 / cs.sumOf { 1.0 / it }
        listOf(cr("R последовательно", ohm(rser)), cr("R параллельно", ohm(rpar)),
            cr("C параллельно", farad(if (cpar == null) null else cpar * 1e-6)),
            cr("C последовательно", farad(if (cser == null) null else cser * 1e-6), "напряжение складывается"))
    },
    Calc("awg", "Провод AWG ↔ мм²", "Диаметр, сечение, ток, падение", "d = 0.127·92^((36−n)/39)",
        listOf(cf("n","Номер AWG","","18"), cf("len","Длина (туда-обратно)","м","2"), cf("i","Ток","А","5"))) { m ->
        val n = m["n"]; val len = m["len"]; val i = m["i"]
        val d = if (n != null) 0.127 * 92.0.pow((36 - n) / 39.0) else null
        val a = if (d != null) PI * d * d / 4 else null
        val r = if (a != null && a > 0 && len != null) 0.01724 * len / a else null
        listOf(cr("Диаметр", if (d == null) "—" else sig(d) + " мм"),
            cr("Сечение", if (a == null) "—" else sig(a) + " мм²"),
            cr("Сопротивление петли", ohm(r)),
            cr("Падение при токе", if (r != null && i != null) sig(r * i) + " В" else "—"),
            cr("Потери в проводе", if (r != null && i != null) watt(r * i * i) else "—"),
            cr("Ориентировочный ток", if (a == null) "—" else sig(a * 5) + " А",
                "≈5 А/мм², зависит от изоляции и укладки"))
    },
    Calc("led", "Резистор светодиода / стабилитрона", "R = (Uin−Uload)/I", "ближайший ряд E24",
        listOf(cf("uin","Питание","В","12"), cf("ul","Падение на нагрузке","В","3.2"),
            cf("i","Ток","мА","20"), cf("n","Количество последовательно","","1"))) { m ->
        val u = m["uin"]; val ul = m["ul"]; val i = m["i"]; val n = m["n"] ?: 1.0
        val drop = if (u != null && ul != null) u - ul * n else null
        val r = if (drop != null && i != null && i > 0) drop / (i / 1000.0) else null
        val p = if (drop != null && i != null) drop * i / 1000.0 else null
        listOf(cr("Резистор", ohm(r)), cr("Ближайший E24", e24(r)),
            cr("Мощность резистора", watt(p), "ставь с запасом ×2"),
            cr("Падение на резисторе", if (drop == null) "—" else sig(drop) + " В",
                if (drop != null && drop < 1) "малый запас — ток будет нестабилен" else ""),
            cr("КПД цепочки", if (u != null && ul != null && u > 0) sig(100 * ul * n / u) + " %" else "—"))
    }
)

/* ============================ МАРКИРОВКИ ============================ */

private val EIA96 = intArrayOf(100,102,105,107,110,113,115,118,121,124,127,130,133,137,140,143,147,150,154,158,
    162,165,169,174,178,182,187,191,196,200,205,210,215,221,226,232,237,243,249,255,261,267,274,280,287,294,
    301,309,316,324,332,340,348,357,365,374,383,392,402,412,422,432,442,453,464,475,487,499,511,523,536,549,
    562,576,590,604,619,634,649,665,681,698,715,732,750,768,787,806,825,845,866,887,909,931,953,976)

private val EIAMUL = mapOf('Z' to 0.001, 'Y' to 0.01, 'R' to 0.01, 'X' to 0.1, 'S' to 0.1,
    'A' to 1.0, 'B' to 10.0, 'H' to 10.0, 'C' to 100.0, 'D' to 1000.0, 'E' to 10000.0, 'F' to 100000.0)

private fun decodeSmd(raw: String): List<CR> {
    val s = raw.trim().uppercase(Locale.US).replace(" ", "")
    if (s.isEmpty()) return listOf(cr("Введи код", "например 103, 4R7, 01C, 104"))
    val out = mutableListOf<CR>()
    if (s.length == 3 && s[0].isDigit() && s[1].isDigit() && s[2].isLetter()) {
        val idx = s.substring(0, 2).toIntOrNull()
        val mul = EIAMUL[s[2]]
        if (idx != null && idx in 1..96 && mul != null) {
            out.add(cr("Резистор EIA-96 (1 %)", ohm(EIA96[idx - 1] * mul),
                "код ${s.substring(0,2)} = ${EIA96[idx-1]}, буква ${s[2]} = множитель"))
            return out
        }
    }
    if (s.all { it.isDigit() } && s.length == 3) {
        val ab = s.substring(0, 2).toDouble()
        val c = s.substring(2).toDouble()
        val v = ab * 10.0.pow(c)
        out.add(cr("Резистор", ohm(v), "AB × 10^C"))
        out.add(cr("Конденсатор", farad(v * 1e-12), "код в пикофарадах"))
        out.add(cr("Дроссель", henry(v * 1e-6), "код в микрогенри"))
        return out
    }
    if (s.all { it.isDigit() } && s.length == 4) {
        val abc = s.substring(0, 3).toDouble()
        val d = s.substring(3).toDouble()
        val v = abc * 10.0.pow(d)
        out.add(cr("Резистор 1 %", ohm(v), "ABC × 10^D"))
        out.add(cr("Конденсатор", farad(v * 1e-12)))
        return out
    }
    if (s.contains('R') || s.contains('K') || s.contains('M')) {
        val unit = when { s.contains('R') -> 'R'; s.contains('K') -> 'K'; else -> 'M' }
        val mul = when (unit) { 'K' -> 1e3; 'M' -> 1e6; else -> 1.0 }
        val v = s.replace(unit, '.').toDoubleOrNull()
        if (v != null) {
            out.add(cr("Резистор", ohm(v * mul), "буква = десятичная точка"))
            if (unit == 'R') out.add(cr("Дроссель", henry(v * 1e-6), "4R7 = 4.7 мкГн"))
            return out
        }
    }
    if (s.all { it.isDigit() } && s.length <= 2) {
        val v = s.toDoubleOrNull()
        if (v != null) {
            out.add(cr("Резистор", if (v == 0.0) "перемычка 0 Ом" else ohm(v)))
            return out
        }
    }
    out.add(cr("Не распознано", "проверь формат: 103 · 4R7 · 01C · 1002"))
    return out
}

private data class Band(val name: String, val color: Color, val digit: Int, val mul: Double, val tol: String, val ppm: String)

private val BANDS = listOf(
    Band("чёрный", Color(0xFF101010), 0, 1.0, "", ""),
    Band("коричневый", Color(0xFF6B3F1D), 1, 10.0, "±1 %", "100 ppm"),
    Band("красный", Color(0xFFD32F2F), 2, 100.0, "±2 %", "50 ppm"),
    Band("оранжевый", Color(0xFFEF6C00), 3, 1e3, "", "15 ppm"),
    Band("жёлтый", Color(0xFFFDD835), 4, 1e4, "", "25 ppm"),
    Band("зелёный", Color(0xFF2E7D32), 5, 1e5, "±0.5 %", ""),
    Band("синий", Color(0xFF1565C0), 6, 1e6, "±0.25 %", "10 ppm"),
    Band("фиолетовый", Color(0xFF7B1FA2), 7, 1e7, "±0.1 %", "5 ppm"),
    Band("серый", Color(0xFF757575), 8, 1e8, "±0.05 %", ""),
    Band("белый", Color(0xFFF5F5F5), 9, 1e9, "", ""),
    Band("золотой", Color(0xFFC9A227), -1, 0.1, "±5 %", ""),
    Band("серебряный", Color(0xFFB0BEC5), -1, 0.01, "±10 %", "")
)
private val DIGITS = BANDS.subList(0, 10)
private val TOLS = listOf(1, 2, 5, 6, 7, 8, 10, 11)
private val TCCS = listOf(1, 2, 3, 4, 6, 7)

/* ============================ ДИАГНОСТИКА ============================ */

data class Fault(val symptom: String, val causes: List<String>, val checks: List<String>, val parts: List<String>)
data class DevClass(val id: String, val name: String, val icon: String, val safety: List<String>, val faults: List<Fault>)

private val DEVICES = listOf(
    DevClass("atx", "БП ATX / компьютера", "🖥",
        listOf("Конденсатор 400 В держит заряд минутами — разряжай резистором 10–50 кОм / 5 Вт и проверяй вольтметром",
            "Первое включение только через лампу накаливания 60–100 Вт в разрыв фазы",
            "Осциллограф на горячую сторону — через развязывающий трансформатор, иначе земля щупа устроит КЗ",
            "Работай одной рукой, вторая в кармане; стол сухой, коврик изолирующий"),
        listOf(
            Fault("Не стартует, дежурка +5VSB есть",
                listOf("Супервизор держит защиту по OVP/UVP", "Обрыв цепи запуска ШИМ",
                    "Пробит основной ключ или его драйвер", "Высохли конденсаторы питания ШИМ"),
                listOf("Измерить +5VSB под нагрузкой 0.5 А — должно быть 4.9…5.2 В",
                    "Проверить PS_ON (зелёный): в дежурке 3…5 В, при замыкании на GND — 0 В",
                    "Замкнуть PS_ON на GND через 100 Ом, наблюдать реакцию лампы",
                    "Проверить питание ШИМ (обычно 12…18 В) и стартовый резистор",
                    "Прозвонить ключи и мост, проверить затворные резисторы"),
                listOf("SPP20N60C3", "MJE13007", "UF4007", "GBU806")),
            Fault("Вздутые конденсаторы, перезагрузки под нагрузкой",
                listOf("Высох электролит по вторичке (высокий ESR)", "Перегрев из-за забитого вентилятора",
                    "Диоды сборки греются, деградирует пайка"),
                listOf("Измерить ESR на месте или выпаяв, сравнить с нормой для номинала",
                    "Заменить на low-ESR 105 °C того же номинала и напряжения не ниже",
                    "Проверить пульсации осциллографом: норма ATX ≤ 120 мВ по +12 В",
                    "Пропаять выводы дросселя и диодных сборок, осмотреть потемневшие места"),
                listOf("MBR20100CT", "MBR2045CT", "STPS3045CW")),
            Fault("Сгорел предохранитель, ключ в КЗ",
                listOf("Пробой ключа PFC/полумоста", "Пробит мост или диод PFC",
                    "Обрыв снаббера, выброс напряжения", "Межвитковое в трансформаторе"),
                listOf("Не менять предохранитель до нахождения причины",
                    "Прозвонить мост по всем четырём плечам",
                    "Проверить ключ и диод PFC, затворный резистор и снаббер",
                    "Осмотреть трансформатор: запах, потемнение, следы дуги",
                    "Первый пуск через лампу с выпаянным ключом — проверить ШИМ"),
                listOf("IRFP460", "SPP20N60C3", "MUR860", "GBU806", "UF4007")),
            Fault("Свист или писк под нагрузкой",
                listOf("Дроссель работает в слышимом диапазоне (burst mode)",
                    "Просели конденсаторы обратной связи", "Нестабильная петля ОС"),
                listOf("Проверить конденсаторы цепи ОС и питания ШИМ",
                    "Прозвонить оптрон и опорник во вторичной цепи",
                    "Нагрузить БП на 50 % — писк должен исчезнуть",
                    "Проверить плотность намотки и лак дросселя"),
                listOf("UF4007", "MBR20100CT"))
        )),
    DevClass("adapter", "Адаптер / зарядка ноутбука", "🔌",
        listOf("Корпус часто ультразвуковой — вскрывать аккуратно, не повредив изоляцию",
            "Сетевой конденсатор разряжать резистором, не отвёрткой",
            "Проверять изоляцию между первичкой и вторичкой, зазоры не менее 6 мм"),
        listOf(
            Fault("Нет выходного напряжения",
                listOf("Обрыв сетевого шнура или предохранителя", "Пробит интегральный ключ",
                    "Обрыв стартового резистора", "КЗ по выходу (диод/конденсатор)"),
                listOf("Проверить напряжение на сетевом конденсаторе: около 310 В",
                    "Прозвонить выходной диод и конденсатор на КЗ",
                    "Проверить стартовую цепь и питание ШИМ",
                    "Осмотреть трансформатор и оптопару"),
                listOf("MJE13003", "UF4007", "SS34", "GBU406")),
            Fault("Циклический запуск (hiccup), щелчки",
                listOf("Перегрузка по выходу", "Высох конденсатор питания ШИМ",
                    "Пробой выходного диода", "Уход номинала токового шунта"),
                listOf("Отключить нагрузку и попробовать пуск вхолостую",
                    "Заменить конденсатор питания ШИМ (обычно 10…47 мкФ)",
                    "Проверить выходной диод Шоттки и его теплоотвод",
                    "Измерить шунт токового датчика — уход номинала вверх"),
                listOf("SS34", "US1M", "UF4007")),
            Fault("Занижено напряжение, греется корпус",
                listOf("Просели выходные конденсаторы", "Уход опорника/делителя ОС",
                    "Плохой контакт в разъёме или кабеле"),
                listOf("Измерить напряжение под нагрузкой и без",
                    "Проверить ESR выходных конденсаторов",
                    "Проверить делитель обратной связи и опорник",
                    "Прозвонить кабель на падение под током"),
                listOf("SS34", "SS54", "MBR2545CT"))
        )),
    DevClass("ups", "ИБП / источник бесперебойного питания", "🔋",
        listOf("Батарея даёт сотни ампер в КЗ — снимать клеммы, инструмент изолировать",
            "Инверторная часть гальванически связана с сетью — не «землить» щуп осциллографа",
            "После отключения ждать разряда звена постоянного тока, измерять вольтметром"),
        listOf(
            Fault("Не переходит на батарею",
                listOf("Батарея деградировала", "Залипло или не срабатывает реле переключения",
                    "Неисправна цепь детекции сети", "Пробиты ключи инвертора"),
                listOf("Измерить напряжение АКБ без нагрузки и под нагрузкой 5 А",
                    "Проверить срабатывание реле и его контакты",
                    "Проверить сигнал детекции сети на плате управления",
                    "Прозвонить силовые ключи и драйверы"),
                listOf("IRF3205", "IRF1404", "IRF4905", "IRLB3034")),
            Fault("Батарея не заряжается",
                listOf("Неисправна цепь заряда", "Обрыв термопредохранителя в батарее",
                    "Уход напряжения заряда, АКБ сульфатирована"),
                listOf("Измерить напряжение заряда: типично 13.5…13.8 В для 12 В АКБ",
                    "Проверить ток заряда в начале цикла",
                    "Прозвонить диод и ключ зарядной цепи",
                    "Проверить АКБ нагрузочной вилкой"),
                listOf("IRF4905", "MUR860", "MBR20100CT")),
            Fault("Пробиты ключи инвертора",
                listOf("Перегрев из-за высохшей термопасты", "Отказ драйвера, сквозной ток",
                    "КЗ в трансформаторе или нагрузке", "Отсутствие демпферных цепей"),
                listOf("Заменить ключи комплектом, не по одному",
                    "Проверить затворные резисторы и драйверы до подачи питания",
                    "Обновить термопасту, проверить прижим к радиатору",
                    "Первый пуск через лампу и с ограничением тока"),
                listOf("IRF3205", "IRFB3207", "IRF1405", "IRGP50B60PD1"))
        )),
    DevClass("induction", "Индукционная плита", "🍳",
        listOf("Сетевой конденсатор и звено 310 В опасны — разряжать резистором",
            "Катушка создаёт сильное поле: убирать карты, часы, металлические инструменты",
            "Не включать без посуды и без установленного радиатора IGBT"),
        listOf(
            Fault("Ошибка E0/E1, не видит посуду",
                listOf("Обрыв датчика температуры катушки", "Неисправен датчик тока или напряжения",
                    "Уход параметров резонансного контура"),
                listOf("Прозвонить NTC катушки: типично 100 кОм при 25 °C",
                    "Проверить резонансный конденсатор (обычно 0.27…0.33 мкФ 1200 В)",
                    "Проверить делители датчиков сети и тока",
                    "Осмотреть катушку на межвитковое и потемнение"),
                listOf("H20R1203", "H25R1203", "FGA25N120ANTD")),
            Fault("Выбивает автомат, пробит IGBT",
                listOf("Пробой IGBT и моста одновременно", "Отказ драйвера затвора",
                    "Перегрев, деградация термопасты", "Обрыв демпферного диода"),
                listOf("Заменить IGBT и мост комплектом",
                    "Проверить драйвер и затворный резистор до пуска",
                    "Проверить резонансный конденсатор на утечку",
                    "Первый пуск через лампу 100 Вт без посуды, затем с посудой"),
                listOf("H25R1203", "SKW25N120", "K40T1202", "GBU806", "FGA25N120ANTD"))
        )),
    DevClass("welder", "Сварочный инвертор", "⚡",
        listOf("Звено постоянного тока 310…600 В, ёмкость киловаттная — разряд обязателен",
            "Работать в очках: пробой электролита и корпуса ключа даёт осколки",
            "Первый пуск через лампу и без сварочных кабелей"),
        listOf(
            Fault("Нет сварочного тока, вентилятор крутится",
                listOf("Нет запуска ШИМ", "Обрыв цепи обратной связи по току",
                    "Пробиты ключи полумоста", "Неисправен датчик тока"),
                listOf("Проверить питание ШИМ и стартовую цепь",
                    "Осциллографом посмотреть импульсы на затворах (через развязку)",
                    "Прозвонить ключи и диоды обратного хода",
                    "Проверить датчик тока и его питание"),
                listOf("FGH40N60SFD", "FGH60N60SMD", "STGW30NC60WD", "MUR1560")),
            Fault("Уходит в защиту через несколько секунд",
                listOf("Перегрев радиатора, забит вентилятор", "Уход шунта или датчика тока",
                    "Просели конденсаторы звена", "Плохая пайка силовых выводов"),
                listOf("Проверить термодатчик на радиаторе и работу вентилятора",
                    "Измерить ёмкость и ESR конденсаторов звена",
                    "Пропаять силовые выводы и шины",
                    "Проверить ток холостого хода"),
                listOf("IRGP50B60PD1", "MUR1560", "GBJ2510"))
        )),
    DevClass("led", "LED-драйвер / подсветка ТВ", "💡",
        listOf("Выход драйвера — сотни вольт постоянного тока, конденсатор держит заряд",
            "Не включать без нагрузки: повышенное напряжение пробьёт ключ",
            "Проверять линейки светодиодов тестером с ограничением тока"),
        listOf(
            Fault("Подсветка не горит или мигает",
                listOf("Обрыв одной линейки — уход в защиту", "Пробит ключ драйвера",
                    "Высох конденсатор выхода", "Неисправен датчик тока"),
                listOf("Измерить выходное напряжение при пуске: резкий рост = обрыв нагрузки",
                    "Проверить каждую линейку тестером светодиодов",
                    "Прозвонить ключ и диод, проверить шунт",
                    "Заменить светодиоды комплектом, не по одному"),
                listOf("MJE13003", "US1M", "ES1J", "SS34", "MB6S")),
            Fault("Мерцание, полосы на изображении",
                listOf("Пульсации из-за просевшего конденсатора", "Нестабильная обратная связь",
                    "Плохой контакт в разъёме подсветки"),
                listOf("Измерить пульсации на выходе драйвера",
                    "Заменить электролит на low-ESR 105 °C",
                    "Проверить пайку разъёмов и шлейфов"),
                listOf("ES1J", "US1M", "MB6S"))
        ))
)

/* ============================ ЦОКОЛЁВКИ ============================ */

private val PINSETS = listOf(
    "GDS" to "MOSFET TO-220/TO-247", "BCE" to "BJT TO-220", "EBC" to "BJT TO-92 (2N…)",
    "CBE" to "BJT TO-92 (BC…)", "ECB" to "BD139/140", "GCE" to "IGBT TO-247",
    "BEC" to "SOT-23 биполярный", "GSD" to "SOT-23 MOSFET", "SGD" to "2N7000 TO-92",
    "SO8" to "SO-8 MOSFET", "AK" to "Диод 2 вывода", "AKA" to "Двойной диод TO-220",
    "BR" to "Мостовой выпрямитель", "SER" to "BAV99", "CC" to "BAV70", "CA" to "BAW56"
)

private fun pinNames(pin: String): List<String> = when (pin) {
    "BCE" -> listOf("B · база", "C · коллектор", "E · эмиттер")
    "EBC" -> listOf("E · эмиттер", "B · база", "C · коллектор")
    "CBE" -> listOf("C · коллектор", "B · база", "E · эмиттер")
    "ECB" -> listOf("E · эмиттер", "C · коллектор", "B · база")
    "GDS" -> listOf("G · затвор", "D · сток", "S · исток")
    "SGD" -> listOf("S · исток", "G · затвор", "D · сток")
    "GCE" -> listOf("G · затвор", "C · коллектор", "E · эмиттер")
    "BEC" -> listOf("B · база", "E · эмиттер", "C · коллектор")
    "GSD" -> listOf("G · затвор", "S · исток", "D · сток")
    "SO8" -> listOf("1–3 · исток", "4 · затвор", "5–8 · сток")
    "AK" -> listOf("A · анод", "K · катод (полоса)")
    "AKA" -> listOf("A1 · анод 1", "K · общий катод", "A2 · анод 2")
    "BR" -> listOf("~ вход", "+ выход", "~ вход", "− выход")
    "SER" -> listOf("A1", "K2", "K1+A2")
    "CC" -> listOf("A1", "A2", "K общий")
    "CA" -> listOf("K1", "K2", "A общий")
    else -> listOf("не подтверждено")
}

@Composable
private fun PackageDrawing(pin: String, accent: Color, metal: Color) {
    val body = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val w = size.width
        val h = size.height
        fun leg(x: Float, y0: Float, y1: Float) =
            drawLine(metal, Offset(x, y0), Offset(x, y1), strokeWidth = 9f)
        when (pin) {
            "AK" -> {
                drawRoundRect(body, Offset(w * 0.28f, h * 0.3f), Size(w * 0.44f, h * 0.34f), CornerRadius(14f, 14f))
                drawRect(metal, Offset(w * 0.63f, h * 0.3f), Size(w * 0.06f, h * 0.34f))
                drawLine(metal, Offset(w * 0.08f, h * 0.47f), Offset(w * 0.28f, h * 0.47f), strokeWidth = 9f)
                drawLine(metal, Offset(w * 0.72f, h * 0.47f), Offset(w * 0.92f, h * 0.47f), strokeWidth = 9f)
            }
            "BR" -> {
                drawRoundRect(body, Offset(w * 0.16f, h * 0.16f), Size(w * 0.68f, h * 0.42f), CornerRadius(16f, 16f))
                listOf(0.26f, 0.42f, 0.58f, 0.74f).forEach { leg(w * it, h * 0.58f, h * 0.84f) }
            }
            "SO8" -> {
                drawRoundRect(body, Offset(w * 0.3f, h * 0.18f), Size(w * 0.4f, h * 0.6f), CornerRadius(12f, 12f))
                drawCircle(accent, 9f, Offset(w * 0.35f, h * 0.26f))
                for (k in 0..3) {
                    val y = h * (0.26f + k * 0.15f)
                    drawRect(metal, Offset(w * 0.2f, y - 6f), Size(w * 0.1f, 12f))
                    drawRect(metal, Offset(w * 0.7f, y - 6f), Size(w * 0.1f, 12f))
                }
            }
            "BEC", "GSD", "SER", "CC", "CA" -> {
                drawRoundRect(body, Offset(w * 0.34f, h * 0.2f), Size(w * 0.32f, h * 0.5f), CornerRadius(10f, 10f))
                drawRect(metal, Offset(w * 0.24f, h * 0.28f), Size(w * 0.1f, 14f))
                drawRect(metal, Offset(w * 0.24f, h * 0.54f), Size(w * 0.1f, 14f))
                drawRect(metal, Offset(w * 0.66f, h * 0.41f), Size(w * 0.1f, 14f))
            }
            "EBC", "CBE", "ECB", "SGD" -> {
                drawRoundRect(body, Offset(w * 0.3f, h * 0.14f), Size(w * 0.4f, h * 0.46f), CornerRadius(26f, 26f))
                listOf(0.38f, 0.5f, 0.62f).forEach { leg(w * it, h * 0.6f, h * 0.86f) }
            }
            else -> {
                drawRoundRect(body, Offset(w * 0.28f, h * 0.16f), Size(w * 0.44f, h * 0.46f), CornerRadius(12f, 12f))
                drawRoundRect(metal, Offset(w * 0.36f, h * 0.06f), Size(w * 0.28f, h * 0.12f), CornerRadius(6f, 6f))
                drawCircle(body, 12f, Offset(w * 0.5f, h * 0.12f))
                listOf(0.36f, 0.5f, 0.64f).forEach { leg(w * it, h * 0.62f, h * 0.88f) }
            }
        }
    }
}

/* ============================ UI: ОБЩЕЕ ============================ */

@Composable
private fun NumField(label: String, unit: String, value: String, onChange: (String) -> Unit, mod: Modifier = Modifier) {
    OutlinedTextField(value, onChange, mod, singleLine = true,
        label = { Text(if (unit.isEmpty()) label else "$label, $unit", fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(16.dp))
}

@Composable
private fun ResultRows(rows: List<CR>) {
    rows.forEach { r ->
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.l, Modifier.weight(1f), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.v, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cOk())
                }
                if (r.h.isNotEmpty()) Text(r.h, fontSize = 11.sp, color = cWarn())
            }
        }
    }
}

@Composable
private fun <T> Picker(label: String, options: List<T>, current: T, text: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text("$label: ${text(current)}", fontSize = 13.sp, maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { item ->
                DropdownMenuItem({ Text(text(item)) }, { open = false; onPick(item) })
            }
        }
    }
}

private fun share(ctx: Context, text: String, title: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title)
    ctx.startActivity(Intent.createChooser(i, title))
}

/* ============================ UI: МАСТЕРСКАЯ ============================ */

@Composable
fun WorkshopScreen(onOpenPart: (Part) -> Unit, onPinout: (Part) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Shop.undoMsg.value) {
        if (Shop.undoMsg.value != null) { delay(8000); Shop.undoMsg.value = null; Shop.undoAct.value = null }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("Мастерская", "расчёты · маркировки · диагностика · журнал")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Расчёты", "Маркировки", "Диагностика", "Журнал", "Цоколёвка").forEachIndexed { k, t ->
                Chip(t, Shop.section.value == k) { Shop.section.value = k }
            }
        }
        Box(Modifier.weight(1f)) {
            when (Shop.section.value) {
                0 -> CalcSection(ctx)
                1 -> MarkSection(ctx)
                2 -> DiagSection(onOpenPart)
                3 -> JobSection(ctx, onOpenPart)
                else -> PinSection(onOpenPart)
            }
        }
    }
    if (Shop.jobForm.value) JobDialog()
}

/* ---------- расчёты ---------- */

@Composable
private fun CalcSection(ctx: Context) {
    val calc = CALCS.firstOrNull { it.id == Shop.calcOpen.value }
    if (calc == null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(CALCS, key = { it.id }) { c ->
                Tile({ Shop.calcOpen.value = c.id }) {
                    Text(c.t, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text(c.s, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Tag(c.f, MaterialTheme.colorScheme.primary)
                }
            }
        }
        return
    }
    val vals = remember(calc.id) {
        mutableStateMapOf<String, String>().apply { calc.fields.forEach { put(it.k, it.d) } }
    }
    val parsed = calc.fields.associate { it.k to vals[it.k]?.replace(',', '.')?.trim()?.toDoubleOrNull() }
    val rows = calc.calc(parsed)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ Shop.calcOpen.value = null }) { Text("‹ Все расчёты") }
            Spacer(Modifier.weight(1f))
            IconButton({ calc.fields.forEach { vals[it.k] = "" } }) { Text("✕", fontSize = 15.sp) }
        }
        Text(calc.t, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(calc.f, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Tile {
            calc.fields.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    pair.forEach { f ->
                        NumField(f.l, f.u, vals[f.k] ?: "", { vals[f.k] = it }, Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(9.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Tile { ResultRows(rows) }
        Spacer(Modifier.height(10.dp))
        Button({
            share(ctx, calc.t + "\n" + calc.fields.joinToString("\n") { "${it.l}: ${vals[it.k]} ${it.u}" } +
                "\n\n" + rows.joinToString("\n") { "${it.l}: ${it.v}" }, "Расчёт PartDB")
        }, Modifier.fillMaxWidth().height(48.dp), shape = CircleShape) { Text("Поделиться расчётом") }
        Spacer(Modifier.height(24.dp))
    }
}

/* ---------- маркировки ---------- */

@Composable
private fun MarkSection(ctx: Context) {
    var code by remember { mutableStateOf("103") }
    var bands by remember { mutableStateOf(4) }
    var d1 by remember { mutableStateOf(1) }
    var d2 by remember { mutableStateOf(0) }
    var d3 by remember { mutableStateOf(0) }
    var mul by remember { mutableStateOf(2) }
    var tol by remember { mutableStateOf(10) }
    var tcc by remember { mutableStateOf(1) }
    var value by remember { mutableStateOf("") }

    val digits = if (bands == 4) d1 * 10 + d2 else d1 * 100 + d2 * 10 + d3
    val res = digits * BANDS[mul].mul
    val tolTxt = BANDS[tol].tol.ifEmpty { "±20 %" }
    val tccTxt = if (bands == 6) BANDS[tcc].ppm else ""

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Tile {
            Text("Код на корпусе", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("103 · 4R7 · 01C · 1002 · 104") }, shape = RoundedCornerShape(16.dp))
            Spacer(Modifier.height(10.dp))
            ResultRows(decodeSmd(code))
        }
        Spacer(Modifier.height(12.dp))
        Tile {
            Text("Цветовая маркировка резистора", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            ResistorDrawing(bands, d1, d2, d3, mul, tol, tcc)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 5, 6).forEach { n -> Chip("$n полосы", bands == n) { bands = n } }
            }
            Spacer(Modifier.height(10.dp))
            BandPicker("1-я цифра", DIGITS, DIGITS[d1]) { d1 = DIGITS.indexOf(it) }
            BandPicker("2-я цифра", DIGITS, DIGITS[d2]) { d2 = DIGITS.indexOf(it) }
            if (bands >= 5) BandPicker("3-я цифра", DIGITS, DIGITS[d3]) { d3 = DIGITS.indexOf(it) }
            BandPicker("множитель", BANDS, BANDS[mul]) { mul = BANDS.indexOf(it) }
            BandPicker("допуск", TOLS.map { BANDS[it] }, BANDS[tol]) { tol = BANDS.indexOf(it) }
            if (bands == 6) BandPicker("ТКС", TCCS.map { BANDS[it] }, BANDS[tcc]) { tcc = BANDS.indexOf(it) }
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(13.dp)) {
                    Text(ohm(res) + "  " + tolTxt + (if (tccTxt.isNotEmpty()) "  $tccTxt" else ""),
                        fontSize = 19.sp, fontWeight = FontWeight.Bold, color = cOk())
                    Text("ряд E24: " + e24(res), fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                NumField("Значение", "Ом", value, { value = it }, Modifier.weight(1f))
                OutlinedButton({
                    val v = value.replace(',', '.').trim().toDoubleOrNull()
                    if (v != null && v > 0) {
                        val n = if (bands == 4) 2 else 3
                        val m = (floor(log10(v)).toInt() - (n - 1)).coerceIn(-2, 9)
                        val d = round(v / 10.0.pow(m)).toInt()
                        val s = d.toString().padStart(n, '0').takeLast(n)
                        d1 = s[0].digitToInt()
                        d2 = s[1].digitToInt()
                        if (n == 3) d3 = s[2].digitToInt()
                        mul = if (m == -1) 10 else if (m == -2) 11 else m.coerceIn(0, 9)
                    }
                }, shape = RoundedCornerShape(16.dp)) { Text("→ полосы") }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    d1 = (1..9).random(); d2 = (0..9).random(); d3 = (0..9).random()
                    mul = (0..6).random(); tol = TOLS.random(); tcc = TCCS.random()
                }, Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("Случайно") }
                OutlinedButton({
                    bands = 4; d1 = 1; d2 = 0; d3 = 0; mul = 2; tol = 10; tcc = 1; value = ""
                }, Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("Сброс") }
            }
            Spacer(Modifier.height(8.dp))
            Button({
                share(ctx, "Резистор $bands полосы: ${ohm(res)} $tolTxt $tccTxt\nполосы: " +
                    listOfNotNull(BANDS[d1].name, BANDS[d2].name,
                        if (bands >= 5) BANDS[d3].name else null, BANDS[mul].name, BANDS[tol].name,
                        if (bands == 6) BANDS[tcc].name else null).joinToString(" · "), "Маркировка PartDB")
            }, Modifier.fillMaxWidth(), shape = CircleShape) { Text("Поделиться") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BandPicker(label: String, options: List<Band>, current: Band, onPick: (Band) -> Unit) {
    Column(Modifier.padding(bottom = 9.dp)) {
        Text(label + " · " + current.name, fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { b ->
                val on = b.name == current.name
                Surface(shape = RoundedCornerShape(10.dp), color = b.color,
                    border = if (on) BorderStroke(2.dp, cOk()) else null,
                    modifier = Modifier.size(if (on) 34.dp else 28.dp).clickable { onPick(b) }) {}
            }
        }
    }
}

@Composable
private fun ResistorDrawing(bands: Int, d1: Int, d2: Int, d3: Int, mul: Int, tol: Int, tcc: Int) {
    val bodyCol = Color(0xFFD8C9A8)
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        val w = size.width
        val h = size.height
        drawLine(Color(0xFFB0BEC5), Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 8f)
        drawRoundRect(bodyCol, Offset(w * 0.16f, h * 0.22f), Size(w * 0.68f, h * 0.56f), CornerRadius(24f, 24f))
        val cols = mutableListOf(BANDS[d1].color, BANDS[d2].color)
        if (bands >= 5) cols.add(BANDS[d3].color)
        cols.add(BANDS[mul].color)
        cols.add(BANDS[tol].color)
        if (bands == 6) cols.add(BANDS[tcc].color)
        val start = w * 0.23f
        val step = w * 0.46f / (cols.size + 0.6f)
        cols.forEachIndexed { k, c ->
            val x = if (k == cols.size - 1 && bands != 6) w * 0.76f else start + k * step
            drawRect(c, Offset(x, h * 0.22f), Size(w * 0.035f, h * 0.56f))
        }
    }
}

/* ---------- диагностика ---------- */

@Composable
private fun DiagSection(onOpenPart: (Part) -> Unit) {
    val dev = DEVICES.firstOrNull { it.id == Shop.diagOpen.value }
    if (dev == null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(DEVICES, key = { it.id }) { d ->
                Tile({ Shop.diagOpen.value = d.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.icon, fontSize = 22.sp)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("${d.faults.size} типовых неисправности · ${d.safety.size} правила ТБ",
                                fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TextButton({ Shop.diagOpen.value = null }) { Text("‹ Все устройства") }
        Text(dev.icon + " " + dev.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Tile {
            Text("Безопасность", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cBad())
            Spacer(Modifier.height(8.dp))
            dev.safety.forEachIndexed { k, s ->
                val key = dev.id + "|s|" + k
                Row(Modifier.fillMaxWidth().clickable { Shop.toggleSafety(key) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top) {
                    Text(if (key in Shop.safety) "☑" else "☐", fontSize = 17.sp,
                        color = if (key in Shop.safety) cOk() else cBad())
                    Spacer(Modifier.width(9.dp))
                    Text(s, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Отмечено " + dev.safety.indices.count { (dev.id + "|s|" + it) in Shop.safety } +
                " из " + dev.safety.size, fontSize = 11.sp, color = cWarn())
        }
        Spacer(Modifier.height(12.dp))
        dev.faults.forEachIndexed { fi, f ->
            Tile {
                Text(f.symptom, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = cWarn())
                Spacer(Modifier.height(8.dp))
                Text("Вероятные причины", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                f.causes.forEach { Text("• $it", fontSize = 12.5.sp) }
                Spacer(Modifier.height(10.dp))
                Text("Порядок проверки", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                f.checks.forEachIndexed { ci, c ->
                    val key = dev.id + "|" + fi + "|" + ci
                    val on = key in Shop.steps
                    Row(Modifier.fillMaxWidth().clickable { Shop.toggleStep(key) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top) {
                        Text(if (on) "☑" else "☐", fontSize = 17.sp,
                            color = if (on) cOk() else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(9.dp))
                        Text(c, fontSize = 12.5.sp,
                            color = if (on) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Text("Пройдено " + f.checks.indices.count { (dev.id + "|" + fi + "|" + it) in Shop.steps } +
                    " из " + f.checks.size, fontSize = 11.sp, color = cOk())
                if (f.parts.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Что обычно меняют", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        f.parts.forEach { m ->
                            val p = Repo.byMpn(m)
                            Surface(shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                modifier = Modifier.clickable(enabled = p != null) { if (p != null) onOpenPart(p) }) {
                                Text(m, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

/* ---------- журнал ---------- */

@Composable
private fun JobSection(ctx: Context, onOpenPart: (Part) -> Unit) {
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<String?>(null) }
    var sortDesc by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<Job?>(null) }
    val list = Shop.jobs.filter {
        (filter == null || it.status == filter) &&
        (q.isBlank() || listOf(it.device, it.model, it.symptom, it.found, it.replaced, it.notes)
            .any { s -> s.contains(q, true) })
    }.sortedBy { it.date.split(".").reversed().joinToString("") }.let { if (sortDesc) it.reversed() else it }

    Column(Modifier.fillMaxSize()) {
        SearchBar(q, { q = it }, "Поиск по устройству, симптому, деталям…")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Все", filter == null) { filter = null }
            Shop.statuses.forEach { s -> Chip(s, filter == s) { filter = if (filter == s) null else s } }
            Chip(if (sortDesc) "Новые ↑" else "Старые ↓", false) { sortDesc = !sortDesc }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                Shop.jobEdit.value = Job(System.currentTimeMillis().toString(), "", "", "", "", "", "В работе", Shop.today(), "")
                Shop.jobForm.value = true
            }, Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("＋ Запись") }
            OutlinedButton({ share(ctx, Shop.jobsCsv(), "Журнал ремонтов PartDB") },
                Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("⇩ Экспорт") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Записей: ${list.size} из ${Shop.jobs.size}", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (Shop.undoMsg.value != null) {
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Shop.undoMsg.value ?: "", Modifier.weight(1f), fontSize = 12.5.sp)
                    TextButton({ Shop.undoAct.value?.invoke() }) { Text("Отменить", color = cWarn()) }
                }
            }
        }
        if (list.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Tile {
                Text("Записей нет", fontWeight = FontWeight.SemiBold)
                Text("Добавь ремонт — история хранится на телефоне и выгружается в CSV.",
                    fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(list, key = { it.id }) { j ->
                Tile({ detail = j }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(j.device.ifBlank { "Без названия" }, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(j.model + " · " + j.date, fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Tag(j.status, when (j.status) {
                            "Готово" -> cOk()
                            "Ждёт запчастей" -> cWarn()
                            "Не подлежит" -> cBad()
                            else -> MaterialTheme.colorScheme.primary
                        })
                    }
                    if (j.symptom.isNotBlank()) {
                        Spacer(Modifier.height(7.dp))
                        Text(j.symptom, fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedButton({ Shop.jobEdit.value = j; Shop.jobForm.value = true },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("✎", fontSize = 13.sp) }
                        OutlinedButton({ Shop.remove(j.id) }, shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("🗑", fontSize = 13.sp) }
                        if (j.replaced.isNotBlank()) {
                            val first = j.replaced.split(";", ",").first().trim()
                            val p = Repo.byMpn(first)
                            if (p != null) OutlinedButton({ onOpenPart(p) }, shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                Text(p.mpn, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    val dj = detail
    if (dj != null) {
        AlertDialog({ detail = null },
            confirmButton = { TextButton({ detail = null }) { Text("Закрыть") } },
            dismissButton = {
                TextButton({ Shop.jobEdit.value = dj; Shop.jobForm.value = true; detail = null }) { Text("Изменить") }
            },
            title = { Text(dj.device.ifBlank { "Запись" }, fontSize = 18.sp) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    listOf("Модель" to dj.model, "Дата" to dj.date, "Статус" to dj.status,
                        "Симптом" to dj.symptom, "Найдено" to dj.found, "Заменено" to dj.replaced, "Заметки" to dj.notes)
                        .filter { it.second.isNotBlank() }.forEach { pair ->
                            Text(pair.first, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(pair.second, fontSize = 13.5.sp)
                            Spacer(Modifier.height(9.dp))
                        }
                }
            })
    }
}

@Composable
private fun JobDialog() {
    val j = Shop.jobEdit.value ?: return
    var device by remember(j.id) { mutableStateOf(j.device) }
    var model by remember(j.id) { mutableStateOf(j.model) }
    var symptom by remember(j.id) { mutableStateOf(j.symptom) }
    var found by remember(j.id) { mutableStateOf(j.found) }
    var replaced by remember(j.id) { mutableStateOf(j.replaced) }
    var notes by remember(j.id) { mutableStateOf(j.notes) }
    var status by remember(j.id) { mutableStateOf(j.status) }
    var date by remember(j.id) { mutableStateOf(j.date) }
    var err by remember { mutableStateOf(false) }
    AlertDialog({ Shop.jobForm.value = false },
        confirmButton = {
            TextButton({
                if (device.isBlank()) err = true else {
                    Shop.upsert(j.copy(device = device.trim(), model = model.trim(), symptom = symptom.trim(),
                        found = found.trim(), replaced = replaced.trim(), notes = notes.trim(),
                        status = status, date = date.trim()))
                    Shop.jobForm.value = false
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton({ Shop.jobForm.value = false }) { Text("Отмена") } },
        title = { Text(if (Shop.jobs.any { it.id == j.id }) "Запись ремонта" else "Новый ремонт", fontSize = 18.sp) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(device, { device = it; err = false }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Устройство *") }, isError = err, shape = RoundedCornerShape(14.dp))
                if (err) Text("Укажи устройство", fontSize = 11.sp, color = cBad())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Модель") }, shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(8.dp))
                Picker("Статус", Shop.statuses, status, { it }, { status = it })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Дата") }, shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(symptom, { symptom = it }, Modifier.fillMaxWidth(),
                    label = { Text("Симптом") }, minLines = 2, shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(found, { found = it }, Modifier.fillMaxWidth(),
                    label = { Text("Что найдено") }, minLines = 2, shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(replaced, { replaced = it }, Modifier.fillMaxWidth(),
                    label = { Text("Заменено (через ;)") }, minLines = 2, shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(),
                    label = { Text("Заметки, измерения") }, minLines = 2, shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(6.dp))
                Text("Первая деталь из «Заменено» станет ссылкой на карточку в каталоге.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
}

/* ---------- цоколёвки ---------- */

@Composable
private fun PinSection(onOpenPart: (Part) -> Unit) {
    var pin by remember { mutableStateOf("GDS") }
    var q by remember { mutableStateOf("") }
    val found = remember(q) { if (q.isBlank()) emptyList<Part>() else Repo.search(q, null).take(6) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Tile {
            Text("Распиновка корпуса", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Picker("Тип", PINSETS, PINSETS.first { it.first == pin }, { it.second }) { pin = it.first }
            Spacer(Modifier.height(10.dp))
            PackageDrawing(pin, cOk(), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            pinNames(pin).forEachIndexed { k, n ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("${k + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cOk())
                        Spacer(Modifier.width(12.dp))
                        Text(n, fontSize = 13.sp)
                    }
                }
            }
            Text(if (pin in listOf("BEC", "GSD", "SO8", "SER", "CC", "CA")) "вид сверху"
                else "вид со стороны маркировки",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Tile {
            Text("Проверить конкретную деталь", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(9.dp))
            SearchBar(q, { q = it }, "IRFZ44N, BD139, MBR20100CT…")
            Spacer(Modifier.height(8.dp))
            found.forEach { p ->
                Row(Modifier.fillMaxWidth()
                    .clickable { if (p.pin != "check") pin = p.pin }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.mpn, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(p.pkg + " · " + p.pin, fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ onOpenPart(p) }) { Text("карточка") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun PinoutScreen(p: Part) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Text(p.mpn, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(p.pkg + " · " + p.typeRu, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (p.pin == "check") {
            Tile {
                Text("Распиновка не подтверждена", color = cWarn(), fontWeight = FontWeight.SemiBold)
                Text("Открой даташит и уточни порядок выводов в карточке детали.", fontSize = 12.5.sp)
            }
        } else {
            Tile {
                PackageDrawing(p.pin, cOk(), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                pinNames(p.pin).forEachIndexed { k, n ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Text("${k + 1}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cOk())
                            Spacer(Modifier.width(12.dp))
                            Text(n, fontSize = 13.5.sp)
                        }
                    }
                }
                Text(if (p.pin in listOf("BEC", "GSD", "SO8", "SER", "CC", "CA")) "вид сверху"
                    else "вид со стороны маркировки",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Tile {
            Text("Проверка тестером", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            val tips = when (p.fam) {
                Fam.MOS -> listOf("Диодный режим: S→D показывает тело диода (0.4…0.7 В), D→S — обрыв",
                    "Затвор изолирован: G→S и G→D — обрыв в обе стороны",
                    "Зарядить затвор плюсом на G, минусом на S — D→S начнёт звониться",
                    "Разрядить затвор перемычкой G-S перед установкой")
                Fam.IGBT -> listOf("G→E — обрыв, ёмкость затвора заряжается тестером",
                    "C→E без заряда затвора — обрыв (или диод, если co-pack)",
                    "Со встроенным диодом E→C звонится как диод",
                    "После заряда G прибор открывается — проверить и разрядить")
                Fam.BJT -> listOf("Две p-n структуры от базы: B→E и B→C как диоды",
                    "NPN: плюс на базу; PNP: минус на базу",
                    "C→E в обе стороны — обрыв (кроме дарлингтона с диодом)",
                    "hFE-режим тестера подтверждает усиление и распиновку")
                Fam.DIO -> listOf("Прямое падение: Шоттки 0.2…0.4 В, кремний 0.5…0.8 В, ultrafast 0.9…1.5 В",
                    "Обратное направление — обрыв",
                    "Мост: проверять все четыре плеча отдельно",
                    "Пробой в обе стороны или обрыв в обе — замена")
            }
            tips.forEach { Text("• $it", fontSize = 12.5.sp) }
        }
        Spacer(Modifier.height(12.dp))
        Button({
            share(ctx, p.mpn + " · " + p.pkg + "\n" +
                pinNames(p.pin).mapIndexed { k, n -> "${k + 1} — $n" }.joinToString("\n"), "Цоколёвка PartDB")
        }, Modifier.fillMaxWidth().height(48.dp), shape = CircleShape) { Text("Поделиться цоколёвкой") }
        Spacer(Modifier.height(24.dp))
    }
}
