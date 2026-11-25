package com.sench.scroll

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Отчёт об ошибках: пишется в файл, его можно скинуть из вкладки «Помощь». */
object Crash {
    private var file: File? = null
    private var ctx: Context? = null
    val report = MutableStateFlow("")
    /** Был вылет, который пользователь ещё не видел. */
    val fresh = MutableStateFlow(false)

    val handler = CoroutineExceptionHandler { _, e -> log(e, "ОШИБКА В ФОНЕ") }

    fun install(c: Context) {
        ctx = c.applicationContext
        file = File(c.filesDir, "crash.txt")
        report.value = file?.takeIf { it.exists() }?.readText().orEmpty()
        fresh.value = File(c.filesDir, "crash.new").exists()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log(e, "ВЫЛЕТ (поток ${t.name})")
            try { File(c.filesDir, "crash.new").writeText("1") } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun device(): String {
        val c = ctx
        val tt = c?.let { cx -> TikTok.packages.firstNotNullOfOrNull { p ->
            try { "$p ${cx.packageManager.getPackageInfo(p, 0).versionName}" } catch (_: Exception) { null } } } ?: "TikTok ?"
        val app = try { c?.packageManager?.getPackageInfo(c.packageName, 0)?.versionName } catch (_: Exception) { null }
        return "SenchScroll $app · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · $tt"
    }

    @Synchronized
    fun log(e: Throwable, kind: String = "ОШИБКА", note: String = "") {
        val f = file ?: return
        try {
            val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
            val entry = "=== $kind · ${Fmt.dateTime(System.currentTimeMillis())}\n${device()}\n" +
                (if (note.isNotBlank()) "$note\n" else "") + sw.toString().lines().take(40).joinToString("\n") + "\n\n"
            val old = if (f.exists()) f.readText() else ""
            val text = (old + entry).takeLast(30_000)
            f.writeText(text)
            report.value = text
        } catch (_: Exception) {}
    }

    fun seen() { fresh.value = false; ctx?.let { File(it.filesDir, "crash.new").delete() } }
    fun clear() { file?.delete(); report.value = ""; seen() }
}

/** Журнал шагов последней сессии — чтобы понять, где застряло. Пишется в файл сразу. */
object Steps {
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    val text = MutableStateFlow("")

    fun init(c: Context) {
        file = File(c.filesDir, "steps.txt")
        file?.takeIf { it.exists() }?.readLines()?.takeLast(80)?.let { lines.addAll(it); text.value = it.joinToString("\n") }
    }

    @Synchronized
    fun add(s: String) {
        val c = java.util.Calendar.getInstance()
        val t = "%02d:%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND))
        if (s.startsWith("Старт:")) lines.clear()
        lines.addLast("$t $s"); while (lines.size > 80) lines.removeFirst()
        val all = lines.joinToString("\n")
        text.value = all
        try { file?.writeText(all) } catch (_: Exception) {}
    }
}
