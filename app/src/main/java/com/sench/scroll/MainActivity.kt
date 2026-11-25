package com.sench.scroll

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sench.scroll.ui.C
import com.sench.scroll.ui.HelpScreen
import com.sench.scroll.ui.HomeScreen
import com.sench.scroll.ui.JournalList
import com.sench.scroll.ui.Mono
import com.sench.scroll.ui.SenchTheme
import com.sench.scroll.ui.SessionScreen
import com.sench.scroll.ui.SettingsScreen
import com.sench.scroll.ui.scanlines

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
        Steps.init(this)
        Store.init(this)
        Journal.init(this)
        AiQueue.framesDir = java.io.File(cacheDir, "frames")
        AiQueue.kick()
    }
}

/** Навигация: вкладка и открытая сессия. */
object Nav {
    var tab by mutableIntStateOf(0)
    var session by mutableStateOf<String?>(null)
}

class MainActivity : ComponentActivity() {
    private var tiktok by mutableStateOf<String?>(null)
    private var message by mutableStateOf("")
    private var disclosure by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        lifecycle.addObserver(LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tiktok = TikTok.installed(this) })
        setContent {
            SenchTheme {
                val a11y by ScrollService.connected.collectAsState()
                Root(
                    a11yOn = a11y, tiktok = tiktok, message = message,
                    onEnableA11y = { if (Store.cfg.value.a11yConsent) openA11y() else disclosure = true },
                    onStart = { start() },
                    onStop = { ScrollService.instance?.stopNow("Остановлено") ?: run { Live.running.value = false } },
                    onShare = { share(it) },
                    onOpenUrl = { url -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} },
                    onAppInfo = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                    onReport = { report(it) },
                )
                if (disclosure) Disclosure(
                    onAgree = { disclosure = false; Store.update { it.copy(a11yConsent = true) }; openA11y() },
                    onCancel = { disclosure = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i?.getStringExtra("session")?.let { Nav.tab = 1; Nav.session = it }
    }

    private fun openA11y() {
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
    }

    private fun start() {
        message = ""
        val cfg = Store.cfg.value
        val svc = ScrollService.instance
        val pkg = TikTok.installed(this)
        when {
            svc == null -> {
                message = "Сначала включи спецвозможность SenchScroll (кнопка «Включить» выше)."
                if (cfg.a11yConsent) openA11y() else disclosure = true
            }
            pkg == null -> message = "TikTok не установлен на этом телефоне."
            cfg.key(cfg.prov).isBlank() -> { message = "Вставь ключ ${cfg.prov.label} в Настройках — без него ИИ не сможет писать журнал."; Nav.tab = 2 }
            else -> {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                if (!svc.start(cfg, pkg)) message = "Сессия уже идёт."
            }
        }
    }

    private fun report(e: Entry) {
        val body = "Жалоба на ответ ИИ (видео #${e.n}):\n\n${e.headline}\n${e.about}\n${e.comments}\n\nЧто не так: "
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Links.EMAIL}")).apply {
            putExtra(Intent.EXTRA_SUBJECT, "SenchScroll: жалоба на ответ ИИ"); putExtra(Intent.EXTRA_TEXT, body)
        }
        try { startActivity(i) } catch (_: Exception) { share(body) }
    }

    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Поделиться журналом"))
    }
}

@Composable
fun Root(
    a11yOn: Boolean, tiktok: String?, message: String,
    onEnableA11y: () -> Unit, onStart: () -> Unit, onStop: () -> Unit,
    onShare: (String) -> Unit, onOpenUrl: (String) -> Unit, onAppInfo: () -> Unit,
    onReport: (Entry) -> Unit = {},
) {
    BackHandler(enabled = Nav.session != null || Nav.tab != 0) {
        if (Nav.session != null) Nav.session = null else Nav.tab = 0
    }
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth().scanlines(0.10f)) {
            when (Nav.tab) {
                0 -> HomeScreen(a11yOn, tiktok, onEnableA11y, onStart, onStop, onOpenHelp = { Nav.tab = 3 },
                    onOpenSettings = { Nav.tab = 2 }, onOpenSession = { Nav.tab = 1; Nav.session = it }, message = message, onOpenUrl = onOpenUrl)
                1 -> {
                    val s = Nav.session
                    if (s == null) JournalList { Nav.session = it } else SessionScreen(s, onBack = { Nav.session = null }, onShare = onShare, onReport = onReport)
                }
                2 -> SettingsScreen(onOpenUrl)
                else -> HelpScreen(onEnableA11y, onAppInfo, onOpenUrl, onShare)
            }
        }
        BottomBar()
    }
}

@Composable
fun BottomBar() {
    val running by Live.running.collectAsState()
    Row(Modifier.fillMaxWidth().background(C.panel).border(1.dp, C.line).padding(vertical = 6.dp)) {
        listOf("▶" to "ГЛАВНАЯ", "▤" to "ЖУРНАЛ", "⚙" to "НАСТРОЙКИ", "?" to "ПОМОЩЬ").forEachIndexed { i, (ic, label) ->
            val sel = Nav.tab == i
            Column(
                Modifier.weight(1f).clickable { Nav.tab = i; if (i == 1) Nav.session = null }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (i == 0 && running) "●" else ic, color = if (sel || (i == 0 && running)) C.red else C.faint, fontSize = 18.sp)
                Spacer(Modifier.height(2.dp))
                Text(label, color = if (sel) C.text else C.faint, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(0.4f).height(2.dp).background(if (sel) C.red else C.panel))
            }
        }
    }
}

/** Явное раскрытие: что делает спецвозможность и какие данные берёт. Показывается до перехода в настройки. */
@Composable
fun Disclosure(onAgree: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        containerColor = C.card, titleContentColor = C.text, textContentColor = C.sub,
        title = { Text("Спецвозможность SenchScroll") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text("Чтобы листать TikTok за тебя, SenchScroll использует API специальных возможностей Android (AccessibilityService).", color = C.text, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text("Что делает: только во время сессии, которую ты сам запускаешь, — свайпает ленту TikTok, открывает и закрывает комментарии.", fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("Какие данные берёт: текст на экране TikTok (ник, подпись, хэштеги, музыка), текст комментариев и, если включено, кадры видео.", fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("Куда отправляет: только в нейронку, которую ты выбрал в настройках, чтобы она написала описание. Журнал хранится только на телефоне. Разработчику ничего не отправляется. Другие приложения не читаются.", fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("Ничего не лайкает, не пишет и не подписывается. Выключить можно в любой момент в настройках спецвозможностей.", fontSize = 13.sp)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onAgree) { Text("СОГЛАСЕН", color = C.red, fontFamily = Mono, fontWeight = FontWeight.Bold) } },
        dismissButton = { androidx.compose.material3.TextButton(onCancel) { Text("НЕ СЕЙЧАС", color = C.sub, fontFamily = Mono) } },
    )
}

