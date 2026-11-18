@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.partdb

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Repo.load(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

sealed interface Scr {
    object Search : Scr
    data class Detail(val p: Part) : Scr
    data class Analogs(val p: Part) : Scr
    data class Pick(val base: Part) : Scr
    data class Compare(val a: Part, val b: Part) : Scr
}

private val GREEN = Color(0xFF66BB6A)
private val RED = Color(0xFFEF5350)
private val YELLOW = Color(0xFFFFCA28)

@Composable
fun App() {
    val stack = remember { mutableStateListOf<Scr>(Scr.Search) }
    val cur = stack.last()
    BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(when (cur) {
                is Scr.Search -> "PartDB - ${Repo.parts.size} шт."
                is Scr.Detail -> cur.p.mpn
                is Scr.Analogs -> "Аналоги: ${cur.p.mpn}"
                is Scr.Pick -> "С чем сравнить?"
                is Scr.Compare -> "Сравнение"
            }) },
            navigationIcon = {
                if (stack.size > 1) TextButton({ stack.removeAt(stack.lastIndex) }) { Text("<-") }
            })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (cur) {
                is Scr.Search -> SearchScreen { stack.add(Scr.Detail(it)) }
                is Scr.Detail -> DetailScreen(cur.p,
                    { stack.add(Scr.Analogs(cur.p)) }, { stack.add(Scr.Pick(cur.p)) })
                is Scr.Analogs -> AnalogsScreen(cur.p) { stack.add(Scr.Compare(cur.p, it)) }
                is Scr.Pick -> SearchScreen { stack.add(Scr.Compare(cur.base, it)) }
                is Scr.Compare -> CompareScreen(cur.a, cur.b)
            }
        }
    }
}

private fun brief(p: Part): String = buildString {
    append("${fmt(p.v)} В / ${fmt(p.i)} А")
    p.rds?.let { append(" / ${fmt(it)} мОм") }
    p.hfe1?.let { append(" / hFE ${fmt(it)}...${fmt(p.hfe2)}") }
    p.trr?.let { append(" / trr ${fmt(it)} нс") }
    p.vf?.let { append(" / ${fmt(it)} В") }
}

@Composable
fun SearchScreen(onPick: (Part) -> Unit) {
    var q by remember { mutableStateOf("") }
    var fam by remember { mutableStateOf<Fam?>(null) }
    val list = remember(q, fam) { Repo.search(q, fam) }
    Column(Modifier.padding(12.dp)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Маркировка: IRFZ44N, 2N3904, SS34, 1AM") })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(fam == null, { fam = null }, { Text("Все") })
            Fam.entries.forEach { f ->
                FilterChip(fam == f, { fam = if (fam == f) null else f }, { Text(f.ru) })
            }
        }
        Text("Найдено: ${list.size}", style = MaterialTheme.typography.labelSmall)
        LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.mpn }) { p ->
                Column(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 8.dp)) {
                    Text(p.mpn, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${p.typeRu} / ${p.pkg} / ${brief(p)}", fontSize = 12.sp)
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                }
            }
        }
        Text("Справочные значения. Перед заменой сверяйтесь с даташитом.",
            fontSize = 11.sp, color = YELLOW)
    }
}

@Composable
fun ParamGrid(vararg ps: Part) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Параметр", Modifier.weight(1.2f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            ps.forEach { Text(it.mpn, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        }
        HorizontalDivider()
        listOf("Корпус", "Распиновка").forEach { label ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(label, Modifier.weight(1.2f), fontSize = 13.sp)
                ps.forEach { p ->
                    val v = if (label == "Корпус") p.pkg else p.pin
                    val other = if (label == "Корпус") ps[0].pkg else ps[0].pin
                    val diff = ps.size == 2 && v != other
                    Text(v, Modifier.weight(1f), fontSize = 13.sp,
                        color = if (diff) YELLOW else Color.Unspecified)
                }
            }
        }
        Metrics.forFam(ps[0].fam).forEach { m ->
            val vals = ps.map { m.get(it) }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(m.label + if (m.unit.isEmpty()) "" else " (${m.unit})",
                    Modifier.weight(1.2f), fontSize = 13.sp)
                vals.forEachIndexed { idx, v ->
                    var col = Color.Unspecified
                    if (ps.size == 2 && m.better != 0 && v != null && vals[1 - idx] != null) {
                        val o = vals[1 - idx]!!
                        if (v != o) col = if (if (m.better > 0) v > o else v < o) GREEN else RED
                    }
                    Text(fmt(v), Modifier.weight(1f), fontSize = 13.sp, color = col,
                        fontWeight = FontWeight.Medium)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun DetailScreen(p: Part, onAnalogs: () -> Unit, onCompare: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.padding(12.dp).fillMaxSize()) {
        Text(p.typeRu, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("${p.maker} / ${p.pkg} / выводы ${p.pin}", fontSize = 13.sp)
        if (p.note.isNotEmpty()) Text("NB: ${p.note}", fontSize = 12.sp, color = YELLOW)
        Spacer(Modifier.height(10.dp))
        ParamGrid(p)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onAnalogs, Modifier.weight(1f)) { Text("Аналоги") }
            OutlinedButton(onCompare, Modifier.weight(1f)) { Text("Сравнить") }
        }
        TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url))) },
            Modifier.fillMaxWidth()) { Text("Открыть даташит (браузер)") }
    }
}

@Composable
fun AnalogsScreen(o: Part, onPick: (Part) -> Unit) {
    var ctxMode by remember { mutableStateOf("general") }
    var strict by remember { mutableStateOf(true) }
    val hits = remember(o, ctxMode, strict) { Analog.find(o, ctxMode, strict) }
    Column(Modifier.padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("general" to "Общее", "smps" to "Импульсный БП", "audio" to "Аудио").forEach { pair ->
                FilterChip(ctxMode == pair.first, { ctxMode = pair.first }, { Text(pair.second) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(strict, { strict = it })
            Text("Строгий режим (ток не ниже оригинала)", fontSize = 12.sp)
        }
        Text("Найдено: ${hits.size}", style = MaterialTheme.typography.labelSmall)
        LazyColumn {
            items(hits, key = { it.p.mpn }) { h ->
                Column(Modifier.fillMaxWidth().clickable { onPick(h.p) }.padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h.p.mpn, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            modifier = Modifier.weight(1f))
                        Text("${h.score}%", fontSize = 13.sp)
                    }
                    Text(h.grade.ru + " / " + brief(h.p), fontSize = 12.sp,
                        color = if (h.grade == Grade.DIRECT) GREEN else YELLOW)
                    h.warn.take(3).forEach { Text("! $it", fontSize = 11.sp, color = RED) }
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
fun CompareScreen(a: Part, b: Part) {
    Column(Modifier.padding(12.dp)) {
        if (a.fam != b.fam) Text("Разные семейства - сравнение только справочное",
            color = RED, fontSize = 12.sp)
        ParamGrid(a, b)
        Spacer(Modifier.height(8.dp))
        Text("Зелёный = лучше, красный = хуже, жёлтый = корпус/распиновка отличаются.", fontSize = 11.sp)
        Analog.eval(a, b, "general", false)?.warn?.forEach { Text("! $it", fontSize = 12.sp, color = RED) }
    }
}