package com.sench.scroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.random.Random

class ScrollService : AccessibilityService() {

    companion object {
        @Volatile var instance: ScrollService? = null
        val connected = MutableStateFlow(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + Crash.handler)
    private var job: Job? = null
    private var pkg = TikTok.packages.first()
    private var overlay: View? = null
    private var overlayText: TextView? = null
    private var pauseBtn: TextView? = null

    override fun onServiceConnected() {
        instance = this
        connected.value = true
    }

    /** Какое приложение сейчас на экране — по событиям системы (быстро и без зависаний). */
    @Volatile private var lastPkg: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = event.packageName?.toString() ?: return
            // клавиатура и шторка не считаются уходом из TikTok
            if (p != packageName && !p.contains("inputmethod") && !p.contains("keyboard") && p != "com.android.systemui") lastPkg = p
        }
    }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        stopNow("Спецвозможность выключили")
        instance = null; connected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null; connected.value = false
        hideOverlay(); scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- управление

    fun start(cfg: Config, tiktok: String): Boolean {
        if (job?.isActive == true) return false
        pkg = tiktok
        Live.stopRequested = false; Live.paused.value = false
        Live.count.value = 0; Live.total.value = cfg.videos
        Live.running.value = true
        job = scope.launch {
            var reason = "Готово"
            val s = Journal.begin(Providers.get(cfg.provider).label)
            Live.sessionId.value = s.id
            try { reason = session(cfg, s.id) } catch (e: kotlinx.coroutines.CancellationException) { reason = "Остановлено"
            } catch (e: Exception) { reason = "Ошибка: ${e.message ?: e.javaClass.simpleName}" }
            finish(cfg, s.id, reason)
        }
        return true
    }

    fun stopNow(reason: String = "Остановлено") {
        Live.stopRequested = true
        Live.status.value = reason
        Steps.add("Нажат стоп")
        hideOverlay()
        val j = job
        if (j?.isActive != true) { Live.running.value = false; return }
        // если сессия не отреагировала мягко за 1,5 с — обрываем жёстко
        scope.launch { delay(1500); if (j.isActive) { Steps.add("Жёсткая остановка"); j.cancel() } }
    }

    private fun finish(cfg: Config, sid: String, reason: String) {
        Steps.add("Конец: $reason")
        Journal.end(sid, reason)
        AiQueue.kick()
        Live.running.value = false; Live.paused.value = false
        Live.status.value = reason
        hideOverlay()
        val n = Journal.get(sid)?.entries?.size ?: 0
        notifyDone(sid, n, reason)
        if (cfg.openJournalAtEnd && n > 0) openApp(sid)
    }

    // ---------------------------------------------------------------- сессия

    private suspend fun session(cfg: Config, sid: String): String {
        showOverlay()
        setStatus("Открываю TikTok…")
        Steps.add("Старт: $pkg, видео ${cfg.videos}, ${cfg.watchSec} с, Android ${Build.VERSION.SDK_INT}")
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return "TikTok не найден"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { startActivity(launch); Steps.add("TikTok запущен") } catch (e: Exception) { Steps.add("Не смог открыть TikTok: ${e.message}") }
        // ждём TikTok до 8 с; если система не сказала, что он открыт, — всё равно начинаем
        val seen = waitFor(8_000) { fg().let { it == null || it == pkg } }
        Steps.add(if (seen) "TikTok на экране (fg=${fg()})" else "Не вижу TikTok (fg=${fg()}), пробую всё равно")
        if (!pause(2500)) return "Остановлено"

        val noise = NoiseFilter()
        var lastFp = ""; var same = 0; var swipes = 0
        var count = 0
        val maxSwipes = cfg.videos * 3 + 10

        var errors = 0
        while (count < cfg.videos && swipes < maxSwipes) {
          try {
            if (!ensureTikTok()) return "Ты вышел из TikTok — сессия на паузе слишком долго"
            if (!pause(900)) return "Остановлено"

            val (w, h) = screen()
            var nodes = snapshot()
            var lines = ScreenReader.lines(nodes)
            noise.add(lines)
            val fp = ScreenReader.fingerprint(lines, noise.noise(lines))
            Steps.add("Экран: элементов ${nodes.size}, строк ${lines.size}, комменты ${if (ScreenReader.findCommentButton(nodes, w, h) != null) "есть" else "нет"}" +
                (if (lines.isNotEmpty()) " · «${lines.firstOrNull { it.startsWith("@") } ?: lines.first().take(30)}»" else ""))
            if (fp.isNotEmpty() && fp == lastFp) {
                same++
                if (same >= 6) return "Лента не листается — может, вылезло окно в TikTok?"
                setStatus("Экран не сменился, листаю ещё раз…")
                swipe(); swipes++
                continue
            }
            same = 0; lastFp = fp

            if (cfg.skipAds && ScreenReader.isAd(lines)) {
                Journal.update(sid) { it.copy(skippedAds = it.skippedAds + 1) }
                setStatus("Реклама — пропускаю ⏭")
                if (!pause(1200)) return "Остановлено"
                swipe(); swipes++; continue
            }
            if (cfg.skipLive && ScreenReader.isLive(lines)) {
                Journal.update(sid) { it.copy(skippedLive = it.skippedLive + 1) }
                setStatus("Эфир (LIVE) — пропускаю ⏭")
                if (!pause(1000)) return "Остановлено"
                swipe(); swipes++; continue
            }

            count++
            Live.count.value = count
            val photo = ScreenReader.isPhoto(lines)
            setStatus(if (photo) "Листаю фото-пост $count из ${cfg.videos} 🖼" else "Смотрю видео $count из ${cfg.videos} 👁")

            // сколько смотреть
            var watch = cfg.watchSec * 1000L
            if (cfg.randomize) watch = (watch * Random.nextDouble(0.75, 1.35)).toLong()
            val started = System.currentTimeMillis()

            // кадры: равномерно по всему просмотру, сразу уменьшаем (память) — потом склеим в одну картинку
            val frames = ArrayList<Bitmap>()
            val canShot = Build.VERSION.SDK_INT >= 30 && cfg.vision
            val want = if (!canShot) 0 else minOf(cfg.shots, 1 + ((watch - 1200) / 750).toInt()).coerceAtLeast(1)
            var reread = false
            for (k in 0 until want) {
                val at = if (want == 1) 1500L else 1200L + (watch - 1500) * k / (want - 1)
                val wait = at - (System.currentTimeMillis() - started)
                if (wait > 0 && !pause(wait)) return "Остановлено"
                shot()?.let { full -> frames += Frames.cell(full); full.recycle() }
                if (!reread && System.currentTimeMillis() - started > watch * 6 / 10) {
                    nodes = snapshot(); lines = (lines + ScreenReader.lines(nodes)).distinct(); reread = true
                }
            }
            if (!reread) {
                val mid = watch * 6 / 10 - (System.currentTimeMillis() - started)
                if (mid > 0 && !pause(mid)) return "Остановлено"
                // ещё раз читаем экран — подпись могла раскрыться
                nodes = snapshot(); lines = (lines + ScreenReader.lines(nodes)).distinct()
            }
            val rest = watch - (System.currentTimeMillis() - started)
            if (rest > 0 && !pause(rest)) return "Остановлено"

            // комментарии
            var comments = ""
            if (cfg.readComments && fg().let { it == null || it == pkg }) {
                setStatus("Читаю комменты к видео $count 💬")
                comments = readComments(lines, nodes, w, h, cfg.deepComments)
            }

            // в журнал
            val e = Entry(
                n = count, time = System.currentTimeMillis(),
                raw = (if (photo) "[ФОТО-ПОСТ / СЛАЙДШОУ: несколько фото под музыку, это не видео]\n" else "") +
                    ScreenReader.forAi(lines, noise.noise(lines)),
                rawComments = comments.take(3500),
                frames = saveFrames(sid, count, frames),
                shots = frames.size,
                thumb = saveThumb(sid, count, frames.firstOrNull()),
            )
            frames.forEach { it.recycle() }
            Journal.addEntry(sid, e)
            AiQueue.kick()

            if (count < cfg.videos) { setStatus("Листаю дальше ⬆"); swipe(); swipes++ }
            errors = 0
          } catch (e: kotlinx.coroutines.CancellationException) { throw e
          } catch (e: Exception) {
            Crash.log(e, "ОШИБКА НА ВИДЕО", "видео №${count}")
            errors++
            if (errors >= 4) return "Слишком много ошибок подряд — отчёт во вкладке «Помощь»"
            setStatus("Что-то пошло не так, листаю дальше")
            pause(800)
            if (fg().let { it == null || it == pkg }) { swipe(); swipes++ }
          }
        }
        return if (count >= cfg.videos) "Готово: просмотрено $count видео" else "Остановлено на $count видео"
    }

    private suspend fun readComments(before: List<String>, beforeNodes: List<SNode>, w: Int, h: Int, deep: Boolean): String {
        val btn = ScreenReader.findCommentButton(beforeNodes, w, h) ?: return ""
        if (ScreenReader.commentCount(btn) == 0) return ""
        tap(btn.cx.toFloat(), btn.cy.toFloat())
        if (!pause(1800)) return ""
        var after = snapshot()
        val got = LinkedHashSet(ScreenReader.newLines(before, after, h))
        if (!ScreenReader.commentsOpened(got.toList(), after, h)) return ""
        if (deep) {
            swipe(fromY = 0.82f, toY = 0.42f)
            if (pause(1400)) {
                after = snapshot(); got += ScreenReader.newLines(before, after, h)
            }
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        pause(900)
        // если окно комментов всё ещё открыто — закрываем ещё раз
        val check = snapshot()
        if (fg().let { it == null || it == pkg } && check.any { it.editable && it.cy > h * 0.6 } && ScreenReader.newLines(before, check, h).size >= 3) {
            performGlobalAction(GLOBAL_ACTION_BACK); pause(700)
        }
        return got.joinToString("\n")
    }

    // ---------------------------------------------------------------- ожидание и пауза

    /** Подождать, учитывая паузу и стоп. false — сессию остановили. */
    private suspend fun pause(ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            if (Live.stopRequested) return false
            if (Live.paused.value) { delay(250); continue }
            val step = minOf(200L, left); delay(step); left -= step
        }
        while (Live.paused.value && !Live.stopRequested) delay(250)
        return !Live.stopRequested
    }

    private suspend fun waitFor(ms: Long, cond: suspend () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (Live.stopRequested) return false
            if (cond()) return true
            delay(300)
        }
        return cond()
    }

    /** Если пользователь ушёл из TikTok — ждём до 2 минут. */
    private suspend fun ensureTikTok(): Boolean {
        val now = fg()
        if (now == null || now == pkg) return true
        Steps.add("На экране не TikTok: $now")
        val prev = Live.status.value
        setStatus("⏸ Вернись в TikTok — я жду")
        val ok = waitFor(120_000) { fg().let { it == null || it == pkg } }
        if (ok) { setStatus(prev); delay(1200) }
        return ok
    }

    /**
     * Что на экране. Спрашиваем систему в фоне и с таймаутом — раньше этот запрос шёл в главном
     * потоке и мог подвесить панельку с кнопками. null — «не знаю», это не считается уходом из TikTok.
     */
    private suspend fun fg(): String? {
        val fromWindows = kotlinx.coroutines.withTimeoutOrNull(2500) {
            withContext(Dispatchers.Default) {
                try {
                    val apps = windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                    val w = apps.firstOrNull { it.isActive || it.isFocused } ?: apps.firstOrNull()
                    w?.root?.packageName?.toString()
                } catch (_: Exception) { null }
            }
        }
        return fromWindows ?: lastPkg
    }

    private fun setStatus(s: String) {
        if (s != Live.status.value) Steps.add(s)
        Live.status.value = s
        overlayText?.post { overlayText?.text = "${Live.count.value}/${Live.total.value} · $s" }
    }

    // ---------------------------------------------------------------- экран

    private fun screen(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private var visited = 0
    private var deadline = 0L

    private suspend fun snapshot(): List<SNode> = withContext(Dispatchers.Default) {
        val out = ArrayList<SNode>()
        try {
            val roots = try { windows.mapNotNull { it.root } } catch (_: Exception) { emptyList() }
                .ifEmpty { listOfNotNull(rootInActiveWindow) }
            visited = 0; deadline = System.currentTimeMillis() + 2500
            for (r in roots) {
                if (r.packageName?.toString() != pkg) continue
                walk(r, out, 0)
            }
        } catch (e: Exception) { Crash.log(e, "ОШИБКА ЧТЕНИЯ ЭКРАНА") }
        out
    }

    private fun walk(n: AccessibilityNodeInfo, out: MutableList<SNode>, depth: Int) {
        if (out.size > 1500 || depth > 70 || ++visited > 4000 || System.currentTimeMillis() > deadline) return
        // невидимые ветки (соседние видео, скрытые фото слайдшоу) не обходим — их бывают тысячи
        if (!n.isVisibleToUser) return
        run {
            val txt = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            if (txt.isNotBlank() || d.isNotBlank() || n.isEditable) {
                val rc = Rect(); n.getBoundsInScreen(rc)
                if (rc.width() > 0 && rc.height() > 0)
                    out += SNode(txt, d, rc.left, rc.top, rc.right, rc.bottom, n.isClickable, n.isEditable)
            }
        }
        for (i in 0 until n.childCount) {
            val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
            walk(c, out, depth + 1)
        }
    }

    // ---------------------------------------------------------------- жесты

    private suspend fun gesture(path: Path, dur: Long): Boolean = suspendCancellableCoroutine { c ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
        }, null)
        if (!ok && c.isActive) c.resume(false)
    }

    private suspend fun swipe(fromY: Float = 0.78f, toY: Float = 0.22f): Boolean {
        val (w, h) = screen()
        val x = w * Random.nextDouble(0.42, 0.58).toFloat()
        val p = Path().apply {
            moveTo(x, h * fromY)
            lineTo(x + Random.nextInt(-25, 25), h * toY)
        }
        return gesture(p, Random.nextLong(230, 380))
    }

    private suspend fun tap(x: Float, y: Float): Boolean =
        gesture(Path().apply { moveTo(x, y); lineTo(x + 1, y + 1) }, 60)

    // ---------------------------------------------------------------- кадры

    private suspend fun shot(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        overlay?.visibility = View.INVISIBLE
        delay(80)
        val bmp = suspendCancellableCoroutine<Bitmap?> { c ->
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(res: ScreenshotResult) {
                        val hb = Bitmap.wrapHardwareBuffer(res.hardwareBuffer, res.colorSpace)
                        val sb = try { hb?.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }
                        res.hardwareBuffer.close()
                        if (c.isActive) c.resume(sb)
                    }
                    override fun onFailure(code: Int) { if (c.isActive) c.resume(null) }
                })
            } catch (_: Exception) { if (c.isActive) c.resume(null) }
        }
        overlay?.visibility = View.VISIBLE
        return bmp
    }

    private fun jpeg(b: Bitmap, width: Int, q: Int): ByteArray {
        val s = if (b.width > width) Bitmap.createScaledBitmap(b, width, (b.height * width.toFloat() / b.width).toInt(), true) else b
        val out = ByteArrayOutputStream(); s.compress(Bitmap.CompressFormat.JPEG, q, out)
        if (s !== b) s.recycle()
        return out.toByteArray()
    }

    private suspend fun saveFrames(sid: String, n: Int, frames: List<Bitmap>): Int = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "frames").apply { mkdirs() }
        AiQueue.framesDir = dir
        if (frames.isEmpty()) return@withContext 0
        // одна картинка-раскадровка = один запрос к ИИ, сколько бы кадров ни было
        val sheet = Frames.sheet(frames)
        File(dir, "${sid}_${n}_0.jpg").writeBytes(jpeg(sheet, 1092, 75))
        if (sheet !== frames.first()) sheet.recycle()
        1
    }

    private suspend fun saveThumb(sid: String, n: Int, b: Bitmap?): String = withContext(Dispatchers.IO) {
        if (b == null) return@withContext ""
        val dir = File(filesDir, "thumbs").apply { mkdirs() }
        val name = "${sid}_$n.jpg"
        File(dir, name).writeBytes(jpeg(b, 240, 70))
        name
    }

    // ---------------------------------------------------------------- плавающая панель поверх TikTok

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val red = Color.parseColor("#FF1F3D")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6070607")); setStroke(dp(1), red); cornerRadius = dp(4).toFloat()
            }
        }
        val dot = TextView(this).apply { text = "●"; setTextColor(red); textSize = 11f; setPadding(0, 0, dp(6), 0) }
        val txt = TextView(this).apply {
            text = "0/${Live.total.value} · старт"; setTextColor(Color.parseColor("#F4ECEE")); textSize = 11f
            typeface = Typeface.MONOSPACE; maxWidth = dp(190); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        fun btn(label: String, onTap: () -> Unit) = TextView(this).apply {
            text = label; setTextColor(Color.parseColor("#070607")); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; minWidth = dp(30); setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply { setColor(red); cornerRadius = dp(3).toFloat() }
            setOnClickListener { onTap() }
        }
        val pb = btn("❚❚") {
            Live.paused.value = !Live.paused.value
            pauseBtn?.text = if (Live.paused.value) "▶" else "❚❚"
            overlayText?.text = "${Live.count.value}/${Live.total.value} · " + if (Live.paused.value) "пауза" else Live.status.value
        }
        val sb = btn("■") { stopNow("Остановлено кнопкой ■") }
        box.addView(dot); box.addView(txt)
        box.addView(pb, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        box.addView(sb, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(5) })

        val (_, h) = screen()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = (h * 0.13).toInt() }

        // перетаскивание за точку/текст
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        val drag = View.OnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ox = lp.x; oy = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (ev.rawX - sx).toInt(); lp.y = oy + (ev.rawY - sy).toInt()
                    try { wm.updateViewLayout(box, lp) } catch (_: Exception) {}; true
                }
                else -> true
            }
        }
        dot.setOnTouchListener(drag); txt.setOnTouchListener(drag)
        try { wm.addView(box, lp); overlay = box; overlayText = txt; pauseBtn = pb } catch (_: Exception) {}
    }

    private fun hideOverlay() {
        overlay?.let { v -> try { getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {} }
        overlay = null; overlayText = null; pauseBtn = null
    }

    // ---------------------------------------------------------------- конец сессии

    private fun openApp(sid: String) {
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("session", sid)
            })
        } catch (_: Exception) {}
    }

    private fun notifyDone(sid: String, n: Int, reason: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("done", "Сессии", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("session", sid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, "done") else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        nm.notify(7, b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("SenchScroll: $n видео в журнале")
            .setContentText("$reason. ИИ дописывает описания и итог.")
            .setContentIntent(pi).setAutoCancel(true).build())
    }
}
