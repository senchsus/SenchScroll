package com.sench.scroll.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sench.scroll.Ai
import com.sench.scroll.Journal
import com.sench.scroll.Providers
import com.sench.scroll.Store
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onOpenUrl: (String) -> Unit = {}) {
    val cfg by Store.cfg.collectAsState()
    val p = cfg.prov
    var showKey by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = C.red, unfocusedBorderColor = C.line, cursorColor = C.red,
        focusedTextColor = C.text, unfocusedTextColor = C.text, focusedLabelColor = C.red, unfocusedLabelColor = C.sub,
        focusedContainerColor = C.panel, unfocusedContainerColor = C.panel,
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        GlitchText("НАСТРОЙКИ", 28.sp)
        Text("> всё сохраняется сразу", color = C.sub, fontFamily = Mono, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))

        GCard("нейронка") {
            Providers.all.forEach { pr ->
                val sel = pr.id == p.id
                val shape = CutCornerShape(topEnd = 10.dp)
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
                        .background(if (sel) C.card2 else C.panel)
                        .border(1.dp, if (sel) C.red else C.line, shape)
                        .clickable { Store.update { it.copy(provider = pr.id) }; test = "" }
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CutCornerShape(3.dp)).background(if (sel) C.red else C.line))
                        Spacer(Modifier.width(10.dp))
                        Text(pr.label, color = C.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Tag(if (pr.free) "БЕСПЛАТНО" else "ПЛАТНО", if (pr.free) C.ok else C.red)
                        if (pr.vision) { Spacer(Modifier.width(6.dp)); Tag("👁 КАДРЫ", C.cyan) }
                    }
                    if (sel) {
                        Spacer(Modifier.height(6.dp))
                        Text(pr.desc, color = C.sub, fontSize = 13.sp, lineHeight = 17.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = cfg.keys[p.id].orEmpty(),
                onValueChange = { v -> Store.update { it.copy(keys = it.keys + (p.id to v.trim())) }; test = "" },
                label = { Text("API-ключ ${p.label}") }, singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { Text(if (showKey) "скрыть" else "показать", color = C.red, fontFamily = Mono, fontSize = 11.sp,
                    modifier = Modifier.clickable { showKey = !showKey }.padding(8.dp)) },
                colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            )
            Text("получить ключ →", color = C.red, fontFamily = Mono, fontSize = 12.sp,
                modifier = Modifier.clickable { onOpenUrl(p.keyUrl) }.padding(vertical = 8.dp))
            OutlinedTextField(
                value = cfg.models[p.id].orEmpty(),
                onValueChange = { v -> Store.update { it.copy(models = it.models + (p.id to v.trim())) } },
                label = { Text("Модель (пусто = ${p.model})") }, singleLine = true,
                colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            )
            Text("Если модель пропадёт, приложение само подберёт новую.", color = C.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallButton(if (testing) "ПРОВЕРЯЮ…" else "ПРОВЕРИТЬ ИИ", filled = true) {
                    if (testing) return@SmallButton
                    testing = true; test = ""
                    scope.launch {
                        test = try {
                            "✓ " + Ai.ask(Store.cfg.value, "Ты тестовый бот. Отвечай одной короткой фразой по-русски.", "Скажи, что связь есть, и добавь эмодзи.").take(120)
                        } catch (e: Exception) { "✗ " + (e.message ?: "ошибка") }
                        testing = false
                    }
                }
            }
            if (test.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(test, color = if (test.startsWith("✓")) C.ok else C.red, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        GCard("листание") {
            SwitchRow("Как живой человек", "Время на видео немного разное, свайпы не одинаковые", cfg.randomize) { v -> Store.update { it.copy(randomize = v) } }
            SwitchRow("Читать комментарии", null, cfg.readComments) { v -> Store.update { it.copy(readComments = v) } }
            SwitchRow("Листать комменты глубже", "Прокручивает список комментов один раз — больше мнений, но дольше", cfg.deepComments) { v -> Store.update { it.copy(deepComments = v) } }
            SwitchRow("Нейронка смотрит кадры", "Нужен Android 11+ и ИИ с 👁", cfg.vision) { v -> Store.update { it.copy(vision = v) } }
            SwitchRow("Пропускать рекламу", null, cfg.skipAds) { v -> Store.update { it.copy(skipAds = v) } }
            SwitchRow("Пропускать эфиры (LIVE)", null, cfg.skipLive) { v -> Store.update { it.copy(skipLive = v) } }
            SwitchRow("Открыть журнал в конце", "После сессии сам вернёт тебя в приложение", cfg.openJournalAtEnd) { v -> Store.update { it.copy(openJournalAtEnd = v) } }
        }
        Spacer(Modifier.height(12.dp))

        GCard("журнал") {
            Text("Стиль описаний", color = C.text, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("😎 прикольный", cfg.tone == "fun") { Store.update { it.copy(tone = "fun") } }
                Chip("🧾 спокойный", cfg.tone != "fun") { Store.update { it.copy(tone = "chill") } }
            }
            Spacer(Modifier.height(14.dp))
            SmallButton("ОЧИСТИТЬ ЖУРНАЛ") { confirmClear = true }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        containerColor = C.card, titleContentColor = C.text, textContentColor = C.sub,
        title = { Text("Очистить журнал?") },
        text = { Text("Удалятся все сессии, описания и превью. Вернуть не получится.") },
        confirmButton = { TextButton({ confirmClear = false; Journal.clear() }) { Text("ОЧИСТИТЬ", color = C.red, fontFamily = Mono) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("ОТМЕНА", color = C.sub, fontFamily = Mono) } },
    )
}
