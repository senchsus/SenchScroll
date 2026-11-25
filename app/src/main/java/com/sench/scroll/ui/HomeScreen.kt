package com.sench.scroll.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sench.scroll.AiQueue
import com.sench.scroll.Fmt
import com.sench.scroll.Journal
import com.sench.scroll.Live
import com.sench.scroll.Store

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    a11yOn: Boolean,
    tiktok: String?,
    onEnableA11y: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenHelp: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenSession: (String) -> Unit,
    message: String = "",
    onOpenUrl: (String) -> Unit = {},
) {
    val cfg by Store.cfg.collectAsState()
    val running by Live.running.collectAsState()
    val paused by Live.paused.collectAsState()
    val count by Live.count.collectAsState()
    val total by Live.total.collectAsState()
    val status by Live.status.collectAsState()
    val aiStatus by AiQueue.status.collectAsState()
    val sessions by Journal.sessions.collectAsState()
    val crashed by com.sench.scroll.Crash.fresh.collectAsState()
    val p = cfg.prov
    val hasKey = cfg.key(p).isNotBlank()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Spacer(Modifier.height(8.dp))
        GlitchText("SENCHSCROLL", 31.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("> нейронка смотрит TikTok за тебя", color = C.sub, fontFamily = Mono, fontSize = 13.sp)
            BlinkCursor()
        }
        Spacer(Modifier.height(18.dp))

        if (crashed) {
            GCard("приложение вылетало", accent = true, modifier = Modifier.clickable(onClick = onOpenHelp)) {
                Text("В прошлый раз что-то сломалось. Отчёт об ошибке — во вкладке «Помощь», скинь его разработчику.", color = C.text, fontSize = 14.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(6.dp))
                Text("открыть отчёт →", color = C.red, fontFamily = Mono, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
        }

        if (running) {
            GCard("сессия идёт", accent = true) {
                Row(verticalAlignment = Alignment.Bottom) {
                    GlitchText("%02d".format(count), 46.sp)
                    Text(" / %02d видео".format(total), color = C.sub, fontFamily = Mono, fontSize = 16.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).background(C.line)) {
                    Box(Modifier.fillMaxWidth(if (total > 0) count / total.toFloat() else 0f).height(6.dp).background(C.red))
                }
                Spacer(Modifier.height(10.dp))
                Text(if (paused) "⏸ пауза" else status, color = C.text, fontSize = 14.sp)
                if (aiStatus.isNotBlank()) Text(aiStatus, color = C.sub, fontFamily = Mono, fontSize = 12.sp)
                val steps by com.sench.scroll.Steps.text.collectAsState()
                if (steps.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(steps.lines().takeLast(4).joinToString("\n"), color = C.faint, fontFamily = Mono, fontSize = 10.sp, lineHeight = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallButton(if (paused) "▶ ПРОДОЛЖИТЬ" else "❚❚ ПАУЗА") { Live.paused.value = !Live.paused.value }
                    SmallButton("■ СТОП", filled = true, onClick = onStop)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        GCard("система") {
            StatusRow("СПЕЦВОЗМОЖНОСТЬ", if (a11yOn) "включена" else "выключена — без неё листать нельзя", a11yOn,
                if (a11yOn) null else "ВКЛЮЧИТЬ", onEnableA11y)
            StatusRow("TIKTOK", if (tiktok != null) "найден" else "не установлен", tiktok != null)
            StatusRow("НЕЙРОНКА", "${p.label} · " + if (hasKey) "ключ есть" else "нужен ключ", hasKey,
                if (hasKey) "СМЕНИТЬ" else "НАСТРОИТЬ", onOpenSettings)
        }
        Spacer(Modifier.height(12.dp))

        GCard("сессия") {
            Text("Сколько видео посмотреть", color = C.text, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 25, 50).forEach { n -> Chip("$n", cfg.videos == n) { Store.update { it.copy(videos = n) } } }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Секунд на видео", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("${cfg.watchSec} с", color = C.red, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Slider(
                value = cfg.watchSec.toFloat(), onValueChange = { v -> Store.update { it.copy(watchSec = v.toInt()) } },
                valueRange = 5f..30f, steps = 24,
                colors = SliderDefaults.colors(thumbColor = C.red, activeTrackColor = C.red, inactiveTrackColor = C.line,
                    activeTickColor = C.red, inactiveTickColor = C.line),
            )
            val minutes = (cfg.videos * (cfg.watchSec + if (cfg.readComments) 5 else 2)) / 60.0
            Text("≈ %.0f мин на всю сессию".format(maxOf(1.0, minutes)), color = C.sub, fontFamily = Mono, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            SwitchRow("Читать комментарии", "Открывает комменты, читает и закрывает", cfg.readComments) { v -> Store.update { it.copy(readComments = v) } }
            SwitchRow("Нейронка смотрит кадры",
                if (p.vision) "Нейронка видит раскадровку видео — описания точнее" else "${p.label} не видит картинки — только текст",
                cfg.vision) { v -> Store.update { it.copy(vision = v) } }
            if (cfg.vision && p.vision) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)) {
                    Text("Кадров с видео", color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(2, 4, 6, 9).forEach { n -> Chip("$n", cfg.shots == n) { Store.update { it.copy(shots = n) } } }
                    }
                }
                Text("Склеиваются в одну картинку — запрос к ИИ всё равно один на видео", color = C.faint, fontSize = 11.sp, lineHeight = 14.sp)
            }
            SwitchRow("Пропускать рекламу и эфиры", null, cfg.skipAds) { v -> Store.update { it.copy(skipAds = v, skipLive = v) } }
        }
        Spacer(Modifier.height(16.dp))

        if (message.isNotBlank()) {
            Text(message, color = C.red, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (running) BigButton("■  ОСТАНОВИТЬ", outline = true, onClick = onStop)
        else BigButton("▶  ЗАПУСТИТЬ", onClick = onStart)
        Spacer(Modifier.height(6.dp))
        Text("Откроется TikTok — просто положи телефон. Сверху будет панелька с паузой и стопом.",
            color = C.faint, fontSize = 12.sp, lineHeight = 16.sp)

        val last = sessions.firstOrNull()
        if (last != null) {
            Spacer(Modifier.height(18.dp))
            GCard(if (running && last.id == Live.sessionId.value) "прямо сейчас в журнале" else "последняя сессия",
                modifier = Modifier.clickable { onOpenSession(last.id) }) {
                Text("${Fmt.dateTime(last.started)} · ${last.entries.size} видео", color = C.sub, fontFamily = Mono, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                if (last.summary.isNotBlank()) {
                    Text(last.summary, color = C.text, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                } else {
                    last.entries.takeLast(3).reversed().forEach { e ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text("#%02d ".format(e.n), color = C.red, fontFamily = Mono, fontSize = 13.sp)
                            Text(
                                when (e.status) { "done" -> "${e.vibe} ${e.headline}"; "error" -> "ошибка ИИ"; else -> "ИИ смотрит…" },
                                color = if (e.status == "done") C.text else C.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("открыть журнал →", color = C.red, fontFamily = Mono, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        ContactLink(onOpenUrl)
        Spacer(Modifier.height(16.dp))
    }
}

/** Тихая строчка с Telegram разработчика. */
@Composable
fun ContactLink(onOpenUrl: (String) -> Unit) {
    Text(
        "telegram: @${com.sench.scroll.Links.TELEGRAM}",
        color = C.faint, fontFamily = Mono, fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().clickable { onOpenUrl("https://t.me/${com.sench.scroll.Links.TELEGRAM}") }.padding(vertical = 8.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
