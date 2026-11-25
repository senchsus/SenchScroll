package com.sench.scroll.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HelpScreen(onEnableA11y: () -> Unit = {}, onAppInfo: () -> Unit = {}, onOpenUrl: (String) -> Unit = {}, onShare: (String) -> Unit = {}) {
    val crash by com.sench.scroll.Crash.report.collectAsState()
    val steps by com.sench.scroll.Steps.text.collectAsState()
    val report = buildString {
        if (steps.isNotBlank()) append("=== ШАГИ ПОСЛЕДНЕЙ СЕССИИ\n").append(steps).append("\n\n")
        append(crash)
    }.trim()
    androidx.compose.runtime.LaunchedEffect(Unit) { com.sench.scroll.Crash.seen() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        GlitchText("ПОМОЩЬ", 28.sp)
        Text("> как запустить за 3 минуты", color = C.sub, fontFamily = Mono, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))

        Step("01", "Возьми бесплатный ключ Gemini",
            "Открой aistudio.google.com/apikey, войди через Google и нажми «Create API key». Скопируй ключ и вставь в Настройки → API-ключ. Карта не нужна.") {
            SmallButton("ОТКРЫТЬ AI STUDIO") { onOpenUrl("https://aistudio.google.com/apikey") }
        }
        Step("02", "Включи спецвозможность",
            "Настройки телефона → Спец. возможности → Установленные приложения (или «Скачанные службы») → SenchScroll → включить. Это нужно, чтобы приложение могло делать свайпы и читать экран TikTok.") {
            SmallButton("ОТКРЫТЬ СПЕЦВОЗМОЖНОСТИ", filled = true, onClick = onEnableA11y)
        }
        Step("02½", "Переключатель серый?",
            "На Android 13+ так защищают приложения, скачанные не из Play Маркета. Открой «О приложении» SenchScroll → меню ⋮ в правом верхнем углу → «Разрешить ограниченные настройки». Потом повтори шаг 02.") {
            SmallButton("О ПРИЛОЖЕНИИ", onClick = onAppInfo)
        }
        Step("03", "Нажми «Запустить»",
            "Откроется TikTok, и нейронка начнёт листать. Положи телефон экраном вверх и не трогай ленту. Слева сверху будет панелька: ❚❚ — пауза, ■ — стоп. Её можно перетащить за точку.")
        Step("04", "Читай журнал",
            "Каждое видео: о чём оно, ник автора, категория, имба-метр, что пишут в комментах и лучший коммент. В конце ИИ пишет итог всей ленты. Журналом можно поделиться.")

        if (report.isNotBlank()) {
            GCard("отчёт для разработчика", accent = true, modifier = Modifier.padding(bottom = 12.dp)) {
                Text("Если что-то не работает — нажми «Поделиться отчётом» и скинь его. Там только техническая информация: модель телефона, версии Android и TikTok и место ошибки в коде.",
                    color = C.sub, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(report.takeLast(1200), color = C.text, fontFamily = Mono, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 18)
                Spacer(Modifier.height(10.dp))
                Row {
                    SmallButton("ПОДЕЛИТЬСЯ ОТЧЁТОМ", filled = true) { onShare(report) }
                    Spacer(Modifier.width(8.dp))
                    SmallButton("ОЧИСТИТЬ") { com.sench.scroll.Crash.clear() }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        GCard("частые вопросы") {
            Faq("Лайкает или пишет что-то?", "Нет. Приложение только листает, открывает комменты и закрывает их. Ничего не лайкает, не пишет и не подписывается.")
            Faq("Какую нейронку выбрать?", "Gemini — бесплатно и смотрит кадры, лучший вариант. Groq и Mistral тоже бесплатные. Grok и Claude работают, но их ключи платные.")
            Faq("Пишет «лимит запросов»?", "У бесплатных ключей есть лимит в минуту и в день. Приложение само ждёт и повторяет. Если кончился дневной лимит — переключись на другую нейронку и нажми «Повторить ИИ» в журнале.")
            Faq("Описания неточные?", "Включи «Нейронка смотрит кадры» (нужен Android 11+) и выбери ИИ с 👁. Без кадров ИИ видит только подпись, хэштеги и комменты.")
            Faq("Застрял на одном видео?", "Иногда TikTok показывает всплывающее окно. Закрой его — сессия продолжится. Если вышел из TikTok, приложение ждёт 2 минуты на паузе.")
            Faq("Работает с TikTok Lite?", "Да: обычный TikTok, TikTok Lite и Douyin.")
            Faq("Есть версия для iPhone?", "Нет — iOS не даёт одним приложениям листать и читать экран других. Это ограничение Apple, обойти его без джейлбрейка нельзя.")
            Faq("Какие данные уходят и куда?", "Текст с экрана TikTok, текст комментариев и кадры видео отправляются только в выбранную тобой нейронку, чтобы она написала описание. Журнал хранится только на телефоне. Разработчику ничего не отправляется.")
        }
        if (com.sench.scroll.Links.PRIVACY_URL.isNotBlank() || com.sench.scroll.Links.EMAIL.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                if (com.sench.scroll.Links.PRIVACY_URL.isNotBlank()) SmallButton("КОНФИДЕНЦИАЛЬНОСТЬ") { onOpenUrl(com.sench.scroll.Links.PRIVACY_URL) }
                if (com.sench.scroll.Links.EMAIL.isNotBlank()) SmallButton("НАПИСАТЬ РАЗРАБОТЧИКУ") { onOpenUrl("mailto:${com.sench.scroll.Links.EMAIL}") }
            }
        }
        Spacer(Modifier.height(16.dp))
        ContactLink(onOpenUrl)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Step(num: String, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    GCard(modifier = Modifier.padding(bottom = 12.dp)) {
        Row {
            Text(num, color = C.red, fontFamily = Glitch, fontSize = 26.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = C.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(text, color = C.sub, fontSize = 14.sp, lineHeight = 19.sp)
                if (action != null) { Spacer(Modifier.height(10.dp)); action() }
            }
        }
    }
}

@Composable
private fun Faq(q: String, a: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("> $q", color = C.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(2.dp))
        Text(a, color = C.sub, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
