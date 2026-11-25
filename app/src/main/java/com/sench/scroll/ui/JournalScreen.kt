package com.sench.scroll.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sench.scroll.AiQueue
import com.sench.scroll.Entry
import com.sench.scroll.Fmt
import com.sench.scroll.Journal
import com.sench.scroll.SessionRec

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalList(onOpen: (String) -> Unit) {
    val sessions by Journal.sessions.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            GlitchText("ЖУРНАЛ", 30.sp)
            Text("> что нейронка насмотрела за тебя", color = C.sub, fontFamily = Mono, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
        }
        if (sessions.isEmpty()) item {
            GCard {
                Text("Пока пусто.", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Запусти сессию на главной — после неё тут появятся описания всех видео, что пишут в комментах и итог ленты.",
                    color = C.sub, fontSize = 14.sp, lineHeight = 19.sp)
            }
        }
        items(sessions, key = { it.id }) { s -> SessionCard(s) { onOpen(s.id) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(s: SessionRec, onClick: () -> Unit) {
    GCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.dateTime(s.started), color = C.red, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("${s.entries.size} видео", color = C.text, fontFamily = Mono, fontSize = 13.sp)
        }
        Spacer(Modifier.height(4.dp))
        val extra = buildList {
            add(s.provider)
            if (s.skippedAds > 0) add("реклама: ${s.skippedAds}")
            if (s.ended > 0) add(Fmt.dur(s.ended - s.started)) else add("идёт")
        }.joinToString(" · ")
        Text(extra, color = C.sub, fontFamily = Mono, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        when {
            s.summary.isNotBlank() -> Text(s.summary, color = C.text, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            s.entries.isEmpty() -> Text(s.stopReason.ifBlank { "Без видео" }, color = C.sub, fontSize = 13.sp)
            else -> Text(s.entries.filter { it.status == "done" }.take(3).joinToString("  ") { "${it.vibe} ${it.headline}" }.ifBlank { "ИИ разбирает видео…" },
                color = C.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val cats = s.entries.filter { it.category.isNotBlank() }.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }.take(4)
        if (cats.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                cats.forEach { Tag("${it.key} ×${it.value}") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionScreen(id: String, onBack: () -> Unit, onShare: (String) -> Unit, onReport: (Entry) -> Unit = {}) {
    val sessions by Journal.sessions.collectAsState()
    val aiStatus by AiQueue.status.collectAsState()
    val s = sessions.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
    if (s == null) { androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }; return }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("← назад", color = C.red, fontFamily = Mono, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onBack).padding(vertical = 6.dp))
            Spacer(Modifier.height(4.dp))
            GlitchText("СЕССИЯ", 28.sp)
            Text("${Fmt.dateTime(s.started)} · ${s.entries.size} видео · ${s.provider}", color = C.sub, fontFamily = Mono, fontSize = 12.sp)
            if (s.stopReason.isNotBlank()) Text(s.stopReason, color = C.faint, fontFamily = Mono, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("ПОДЕЛИТЬСЯ", filled = true) { onShare(Journal.shareText(s)) }
                if (s.entries.any { it.status == "error" } || s.summaryStatus == "error")
                    SmallButton("ПОВТОРИТЬ ИИ") { Journal.retry(s.id); AiQueue.kick() }
                if (s.ended > 0 && s.entries.isNotEmpty()) SmallButton("ПЕРЕПИСАТЬ") { Journal.redoAll(s.id); AiQueue.kick() }
                SmallButton("УДАЛИТЬ") { confirmDelete = true }
            }
            if (aiStatus.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(aiStatus, color = C.sub, fontFamily = Mono, fontSize = 12.sp) }
        }
        item {
            GCard("итог ленты", accent = true) {
                when (s.summaryStatus) {
                    "done" -> Text(s.summary, color = C.text, fontSize = 15.sp, lineHeight = 21.sp)
                    "pending" -> Text("ИИ пишет итог… (после того как разберёт все видео)", color = C.sub, fontSize = 14.sp)
                    "error" -> Text("Итог не получился — нажми «Повторить ИИ».", color = C.red, fontSize = 14.sp)
                    else -> Text(if (s.ended == 0L) "Итог появится, когда сессия закончится." else "Нет видео — нечего подводить.", color = C.sub, fontSize = 14.sp)
                }
                if (s.summaryStatus == "done") {
                    Spacer(Modifier.height(8.dp))
                    Text("переписать итог ↻", color = C.red, fontFamily = Mono, fontSize = 11.sp,
                        modifier = Modifier.clickable { Journal.redoSummary(s.id); AiQueue.kick() })
                }
            }
        }
        items(s.entries.reversed(), key = { it.n }) { e -> EntryCard(e, onReport) }
        item { Spacer(Modifier.height(20.dp)) }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = C.card, titleContentColor = C.text, textContentColor = C.sub,
        title = { Text("Удалить сессию?") },
        text = { Text("Описания и превью этой сессии удалятся без возможности вернуть.") },
        confirmButton = { TextButton({ confirmDelete = false; Journal.delete(s.id); onBack() }) { Text("УДАЛИТЬ", color = C.red, fontFamily = Mono) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("ОТМЕНА", color = C.sub, fontFamily = Mono) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryCard(e: Entry, onReport: (Entry) -> Unit = {}) {
    GCard {
        Row {
            Thumb(e)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#%02d".format(e.n), color = C.red, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (e.author.isNotBlank()) Text("  ${e.author}", color = C.sub, fontFamily = Mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                when (e.status) {
                    "pending" -> {
                        Text("ИИ смотрит видео…", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("описание появится через пару секунд", color = C.sub, fontSize = 12.sp)
                    }
                    "error" -> {
                        Text("ИИ не смог разобрать", color = C.red, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(e.error, color = C.sub, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    else -> {
                        Text("${e.vibe} ${e.headline}", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (e.category.isNotBlank()) Tag(e.category)
                            if (e.isAd) Tag("реклама", C.cyan)
                        }
                        Spacer(Modifier.height(6.dp))
                        HypeBar(e.hype)
                    }
                }
            }
        }
        if (e.status == "done") {
            if (e.names.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text("🏷 " + e.names, color = C.red, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text(e.about, color = C.text, fontSize = 14.sp, lineHeight = 20.sp)
            if (e.comments.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Label("в комментах")
                Spacer(Modifier.height(4.dp))
                Text(e.comments, color = C.text, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("⚑ пожаловаться на ответ ИИ", color = C.faint, fontFamily = Mono, fontSize = 10.sp,
                modifier = Modifier.clickable { onReport(e) }.padding(vertical = 4.dp))
            if (false) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().background(C.panel).padding(10.dp)) {
                    Box(Modifier.width(3.dp).height(36.dp).background(C.red))
                    Spacer(Modifier.width(10.dp))
                    Text("«${e.bestComment}»", color = C.text, fontSize = 13.sp, fontStyle = FontStyle.Italic, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun Thumb(e: Entry) {
    val shape = CutCornerShape(topEnd = 8.dp)
    val bmp = remember(e.thumb) {
        Journal.thumbFile(e.thumb)?.let { f -> try { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() } catch (_: Exception) { null } }
    }
    Box(Modifier.size(width = 64.dp, height = 112.dp).clip(shape).background(C.panel).border(1.dp, C.redDim, shape).scanlines(0.22f),
        contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("▶", color = C.redDim, fontSize = 24.sp)
    }
}
