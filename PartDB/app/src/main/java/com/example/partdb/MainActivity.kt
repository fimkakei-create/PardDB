@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.partdb

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
        Repo.load(this); MarkRepo.load(this); Prefs.load(this)
        setContent { Theme { App() } }
    }
}

@Composable fun cOk(): Color = if (Prefs.dark.value) Color(0xFF63D69B) else Color(0xFF17925C)
@Composable fun cWarn(): Color = if (Prefs.dark.value) Color(0xFFF0C766) else Color(0xFFA97400)
@Composable fun cBad(): Color = if (Prefs.dark.value) Color(0xFFFF8F8F) else Color(0xFFC0392B)

@Composable
fun Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (Prefs.dark.value) darkColorScheme(
            primary = Color(0xFF8FA2FF), onPrimary = Color(0xFF101A3D),
            secondary = Color(0xFF4ED8C0), background = Color(0xFF0D1015),
            surface = Color(0xFF151A21), surfaceVariant = Color(0xFF1E2530),
            onBackground = Color(0xFFE9EDF4), onSurface = Color(0xFFE9EDF4),
            onSurfaceVariant = Color(0xFF9AA6B6), outline = Color(0xFF2C3441)
        ) else lightColorScheme(
            primary = Color(0xFF4A5BD4), onPrimary = Color.White,
            secondary = Color(0xFF00998A), background = Color(0xFFF3F5F9),
            surface = Color.White, surfaceVariant = Color(0xFFE9EDF5),
            onBackground = Color(0xFF131720), onSurface = Color(0xFF131720),
            onSurfaceVariant = Color(0xFF5C6675), outline = Color(0xFFD9DFEA)
        ),
        shapes = Shapes(extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(22.dp), large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(34.dp))
    ) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
}

sealed interface Scr {
    data class Detail(val p: Part) : Scr
    data class Analogs(val p: Part) : Scr
    data class Pick(val base: Part) : Scr
    data class Compare(val a: Part, val b: Part) : Scr
}

@Composable
fun App() {
    var tab by remember { mutableStateOf(0) }
    val stack = remember { mutableStateListOf<Scr>() }
    val cur = stack.lastOrNull()
    BackHandler(cur != null) { stack.removeAt(stack.lastIndex) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (cur != null) TopAppBar(
                title = {
                    Text(when (cur) {
                        is Scr.Detail -> cur.p.mpn
                        is Scr.Analogs -> "Аналоги"
                        is Scr.Pick -> "С чем сравнить?"
                        is Scr.Compare -> "Сравнение"
                        else -> "PartDB"
                    }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton({ stack.removeAt(stack.lastIndex) }) { Text("‹", fontSize = 28.sp) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = {
            if (cur == null) NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf(Triple("Каталог", "🔎", 0), Triple("SMD", "🏷", 1), Triple("Избранное", "★", 2))
                    .forEach { item ->
                        NavigationBarItem(
                            selected = tab == item.third,
                            onClick = { tab = item.third },
                            icon = { Text(item.second, fontSize = 17.sp) },
                            label = { Text(item.first, fontSize = 11.sp) })
                    }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                cur is Scr.Detail -> DetailScreen(cur.p,
                    { stack.add(Scr.Analogs(cur.p)) }, { stack.add(Scr.Pick(cur.p)) })
                cur is Scr.Analogs -> AnalogsScreen(cur.p) { stack.add(Scr.Compare(cur.p, it)) }
                cur is Scr.Pick -> CatalogScreen { stack.add(Scr.Compare(cur.base, it)) }
                cur is Scr.Compare -> CompareScreen(cur.a, cur.b)
                tab == 0 -> CatalogScreen { stack.add(Scr.Detail(it)) }
                tab == 1 -> SmdScreen { stack.add(Scr.Detail(it)) }
                else -> FavScreen { stack.add(Scr.Detail(it)) }
            }
        }
    }
}

private fun brief(p: Part): String = buildString {
    append("${fmt(p.v)} В")
    p.i?.let { append(" · ${fmt(it)} А") }
    p.rds?.let { append(" · ${fmt(it)} мОм") }
    p.hfe1?.let { append(" · hFE ${fmt(it)}…${fmt(p.hfe2)}") }
    p.trr?.let { append(" · trr ${fmt(it)} нс") }
    p.vf?.let { append(" · Vf ${fmt(it)} В") }
}

@Composable
fun tint(f: Fam): Color = when (f) {
    Fam.MOS -> MaterialTheme.colorScheme.primary
    Fam.IGBT -> cWarn()
    Fam.BJT -> MaterialTheme.colorScheme.secondary
    Fam.DIO -> cOk()
}

@Composable
fun Tag(text: String, c: Color) {
    Surface(shape = CircleShape, color = c.copy(alpha = 0.14f)) {
        Text(text, color = c, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp))
    }
}

@Composable
fun Tile(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = Modifier.fillMaxWidth().padding(vertical = 5.dp)
    Card(if (onClick != null) m.clickable { onClick() } else m,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun Header(title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(sub, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
            IconButton({ Prefs.setDark(!Prefs.dark.value) }) {
                Text(if (Prefs.dark.value) "☀" else "🌙", fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun SearchBar(q: String, onQ: (String) -> Unit, hint: String) {
    TextField(q, onQ, Modifier.fillMaxWidth(), singleLine = true, shape = CircleShape,
        placeholder = { Text(hint, fontSize = 13.5.sp) },
        leadingIcon = { Text("🔍", fontSize = 15.sp) },
        trailingIcon = { if (q.isNotEmpty()) IconButton({ onQ("") }) { Text("✕", fontSize = 14.sp) } },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary))
}

@Composable
fun Chip(text: String, sel: Boolean, onClick: () -> Unit) {
    FilterChip(sel, onClick, { Text(text, fontSize = 12.5.sp) }, shape = CircleShape, border = null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
}

@Composable
fun PartTile(p: Part, onClick: () -> Unit) {
    Tile(onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.mpn, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (p.mpn in Prefs.favs) Text("★", color = cWarn(), fontSize = 16.sp)
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(p.typeRu, tint(p.fam))
            Tag(p.pkg, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(9.dp))
        Text(brief(p), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CatalogScreen(onPick: (Part) -> Unit) {
    var q by remember { mutableStateOf("") }
    var fam by remember { mutableStateOf<Fam?>(null) }
    val list = remember(q, fam) { Repo.search(q, fam) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Header("Каталог",
            "${Repo.parts.size} деталей · MOSFET ${Repo.count(Fam.MOS)} · IGBT ${Repo.count(Fam.IGBT)}")
        SearchBar(q, { q = it }, "IRFZ44N, FGA25N120, GBU806…")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Все", fam == null) { fam = null }
            Fam.entries.forEach { f -> Chip(f.ru, fam == f) { fam = if (fam == f) null else f } }
        }
        Text("Найдено: ${list.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(list, key = { it.mpn }) { PartTile(it) { onPick(it) } }
        }
    }
}

@Composable
fun SmdScreen(onPick: (Part) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(q) { MarkRepo.search(q) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Header("SMD-коды", "${MarkRepo.all.size} маркировок · код на корпусе → деталь")
        SearchBar(q, { q = it }, "1AM, 702, J3Y, M7, A7…")
        Spacer(Modifier.height(12.dp))
        Text("Найдено: ${list.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(list) { m ->
                val p = Repo.byMpn(m.mpn)
                Tile(if (p != null) ({ onPick(p) }) else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                            Text(m.code, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.mpn, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            if (m.hint.isNotEmpty()) Text(m.hint, fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (p != null) Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun FavScreen(onPick: (Part) -> Unit) {
    val list = Prefs.favs.mapNotNull { Repo.byMpn(it) }.sortedBy { it.mpn }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Header("Избранное", "${list.size} отмеченных деталей")
        if (list.isEmpty()) Tile {
            Text("Пока пусто", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Открой деталь и нажми ☆ — она сохранится здесь даже после перезапуска.",
                fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f)) { items(list, key = { it.mpn }) { PartTile(it) { onPick(it) } } }
    }
}

@Composable
fun ParamGrid(vararg ps: Part) {
    val cs = MaterialTheme.colorScheme
    val ok = cOk(); val warn = cWarn(); val bad = cBad()
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text("Параметр", Modifier.weight(1.15f), fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
            ps.forEach { Text(it.mpn, Modifier.weight(1f), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
        }
        data class Row3(val label: String, val vals: List<String>, val cols: List<Color>)
        val rows = mutableListOf<Row3>()
        listOf("Корпус", "Распиновка").forEach { label ->
            val vals = ps.map { if (label == "Корпус") it.pkg else it.pin }
            rows += Row3(label, vals, vals.map { if (ps.size == 2 && vals[0] != vals[1]) warn else cs.onSurface })
        }
        Metrics.forFam(ps[0].fam).forEach { m ->
            val vals = ps.map { m.get(it) }
            val cols = vals.mapIndexed { idx, v ->
                if (ps.size == 2 && m.better != 0 && v != null && vals[1 - idx] != null) {
                    val o = vals[1 - idx]!!
                    if (v == o) cs.onSurface else if (if (m.better > 0) v > o else v < o) ok else bad
                } else cs.onSurface
            }
            rows += Row3(m.label + if (m.unit.isEmpty()) "" else ", ${m.unit}", vals.map { fmt(it) }, cols)
        }
        rows.forEach { r ->
            Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(r.label, Modifier.weight(1.15f), fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                    r.vals.forEachIndexed { k, v ->
                        Text(v, Modifier.weight(1f), fontSize = 12.5.sp, color = r.cols[k],
                            fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
fun DetailScreen(p: Part, onAnalogs: () -> Unit, onCompare: () -> Unit) {
    val ctx = LocalContext.current
    val codes = MarkRepo.forPart(p.mpn)
    LazyColumn(Modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.mpn, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tag(p.typeRu, tint(p.fam))
                        Tag(p.pkg, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                    IconButton({ Prefs.toggleFav(p.mpn) }) {
                        Text(if (p.mpn in Prefs.favs) "★" else "☆", fontSize = 22.sp,
                            color = if (p.mpn in Prefs.favs) cWarn() else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Tile { ParamGrid(p) }
            Spacer(Modifier.height(10.dp))
            Tile {
                Text("${p.maker} · выводы ${p.pin}", fontSize = 13.sp)
                if (p.note.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(p.note, fontSize = 12.5.sp, color = cWarn())
                }
                if (codes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        codes.forEach { Tag("SMD ${it.code}", MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(onAnalogs, Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) {
                Text("Подобрать аналог", fontSize = 15.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onCompare, Modifier.weight(1f).height(48.dp), shape = CircleShape) { Text("Сравнить") }
                OutlinedButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url))) },
                    Modifier.weight(1f).height(48.dp), shape = CircleShape) { Text("Даташит") }
            }
            Spacer(Modifier.height(12.dp))
            Text("Справочные значения. Перед заменой сверяйтесь с даташитом.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AnalogsScreen(o: Part, onPick: (Part) -> Unit) {
    var mode by remember { mutableStateOf("general") }
    var strict by remember { mutableStateOf(true) }
    val hits = remember(o, mode, strict) { Analog.find(o, mode, strict) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(o.mpn, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(brief(o), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("general" to "Общее", "smps" to "Импульсный БП", "audio" to "Аудио").forEach { x ->
                Chip(x.second, mode == x.first) { mode = x.first }
            }
        }
        Tile {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(strict, { strict = it })
                Spacer(Modifier.width(8.dp))
                Text("Строгий режим: ток не ниже оригинала", fontSize = 12.5.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Найдено: ${hits.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(hits, key = { it.p.mpn }) { h ->
                Tile({ onPick(h.p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h.p.mpn, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        Tag("${h.score}%", if (h.grade == Grade.DIRECT) cOk() else cWarn())
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(h.grade.ru + " · " + brief(h.p), fontSize = 12.sp,
                        color = if (h.grade == Grade.DIRECT) cOk() else cWarn())
                    h.warn.take(3).forEach { Text("• $it", fontSize = 11.5.sp, color = cBad()) }
                }
            }
        }
    }
}

@Composable
fun CompareScreen(a: Part, b: Part) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("${a.mpn}  vs  ${b.mpn}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (a.fam != b.fam) Text("Разные семейства — сравнение справочное", color = cBad(), fontSize = 12.sp)
        Tile { ParamGrid(a, b) }
        val hit = Analog.eval(a, b, "general", false)
        if (hit != null && hit.warn.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Tile { hit.warn.forEach { Text("• $it", fontSize = 12.sp, color = cBad()) } }
        }
        Spacer(Modifier.height(10.dp))
        Text("Зелёный — лучше, красный — хуже, жёлтый — корпус/распиновка отличаются.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
