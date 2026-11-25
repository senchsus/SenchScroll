package com.sench.scroll

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Состояние текущей сессии — для главного экрана и плавающей панели. */
object Live {
    val running = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    val count = MutableStateFlow(0)
    val total = MutableStateFlow(0)
    val status = MutableStateFlow("")
    val sessionId = MutableStateFlow("")
    @Volatile var stopRequested = false
}

/**
 * Очередь для ИИ: видео записываются сразу, а нейронка разбирает их по одному в фоне,
 * соблюдая паузы между запросами (бесплатные лимиты). Так листание не тормозит из-за ИИ.
 */
object AiQueue {
    val status = MutableStateFlow("")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + Crash.handler)
    private var job: Job? = null
    private var lastCall = 0L
    var framesDir: File? = null

    fun kick() {
        if (job?.isActive == true) return
        job = scope.launch { try { loop() } finally { status.value = "" } }
    }

    private sealed class Task {
        data class Video(val sid: String, val e: Entry) : Task()
        data class Summary(val s: SessionRec) : Task()
    }

    private fun next(): Task? {
        for (s in Journal.sessions.value.sortedBy { it.started }) {
            s.entries.firstOrNull { it.status == "pending" }?.let { return Task.Video(s.id, it) }
            if (s.ended > 0 && s.summaryStatus == "pending" &&
                s.entries.none { it.status == "error" && retryable.containsMatchIn(it.error) && "${s.id}_${it.n}" !in autoRetried })
                return Task.Summary(s)
        }
        return null
    }

    /** Видео, которые уже один раз повторяли автоматически. */
    private val autoRetried = HashSet<String>()
    private val retryable = Regex("(?i)(лимит|сервер|таймаут|сети|пустой ответ)")

    /** Ошибки из-за лимита/сбоя — вернуть в очередь один раз. true — есть что повторить. */
    private fun requeueSoftErrors(): Boolean {
        var any = false
        Journal.sessions.value.forEach { s ->
            val ids = s.entries.filter { it.status == "error" && retryable.containsMatchIn(it.error) && autoRetried.add("${s.id}_${it.n}") }.map { it.n }
            if (ids.isNotEmpty()) {
                any = true
                Journal.update(s.id) { r -> r.copy(
                    entries = r.entries.map { if (it.n in ids) it.copy(status = "pending", error = "") else it },
                    summaryStatus = if (r.ended > 0 && r.summaryStatus != "done") "pending" else r.summaryStatus) }
            }
        }
        return any
    }

    private suspend fun loop() {
        while (true) {
            var t0 = next()
            if (t0 == null && requeueSoftErrors()) {
                Steps.add("ИИ: повторяю неразобранные видео через минуту")
                var left = 60
                while (left > 0) { status.value = "Повторю неразобранные видео через $left с"; delay(1000); left-- }
                t0 = next()
            }
            val t = t0 ?: return
            val cfg = Store.cfg.value
            val p = cfg.prov
            if (cfg.key(p).isBlank()) { failAll("Нет ключа для ${p.label} — вставь его в Настройках и нажми «Повторить»"); return }
            val wait = lastCall + p.gapMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            when (t) {
                is Task.Video -> runVideo(cfg, t.sid, t.e)
                is Task.Summary -> runSummary(cfg, t.s)
            }
        }
    }

    private fun frameFile(sid: String, n: Int, k: Int) = framesDir?.let { File(it, "${sid}_${n}_$k.jpg") }

    private fun loadFrames(sid: String, e: Entry): List<ByteArray> =
        (0 until e.frames).mapNotNull { k -> frameFile(sid, e.n, k)?.takeIf { it.exists() }?.readBytes() }

    private fun dropFrames(sid: String, e: Entry) = (0 until e.frames).forEach { k -> frameFile(sid, e.n, k)?.delete() }

    private suspend fun runVideo(cfg: Config, sid: String, e: Entry) {
        status.value = "ИИ разбирает видео #${e.n}…"
        val frames = if (cfg.vision) loadFrames(sid, e) else emptyList()
        var problem = ""
        for ((attempt, temp) in listOf(0.4, 0.15).withIndex()) {
            if (attempt > 0) { status.value = "ИИ ошибся, переспрашиваю про видео #${e.n}…"; delay(cfg.prov.gapMs) }
            val res = withRetry("видео #${e.n}") {
                Ai.ask(cfg, Prompts.videoSystem(cfg), Prompts.videoUser(e, frames.size), frames, json = true, temp = temp,
                    textNoImages = Prompts.videoUser(e, 0))
            }
            val err = res.exceptionOrNull()
            if (err != null) {
                Steps.add("ИИ: видео #${e.n} — ошибка: ${err.message}")
                Journal.updateEntry(sid, e.n) { it.copy(status = "error", error = err.message ?: "Ошибка ИИ") }
                if ((err as? AiException)?.daily == true || (err as? AiException)?.code in setOf(401, 402, 403)) failAll(err.message ?: "Ошибка ИИ")
                return
            }
            val text = res.getOrThrow()
            val o = Ai.parseJson(text)
            val note = o?.let { e.withNote(it) }
            when {
                note == null -> problem = "обрезанный или непонятный ответ"
                note.about.isBlank() -> problem = "пустое описание"
                Garbage.isBad(note.proseForCheck) -> problem = "бессмыслица вместо текста"
                else -> {
                    Journal.updateEntry(sid, e.n) { note.copy(raw = it.raw, rawComments = it.rawComments, thumb = it.thumb, frames = it.frames) }
                    dropFrames(sid, e)
                    return
                }
            }
            Steps.add("ИИ: видео #${e.n} — $problem (попытка ${attempt + 1})")
        }
        Journal.updateEntry(sid, e.n) { it.copy(status = "error",
            error = "ИИ выдал $problem. Попробуй другую нейронку (лучше Gemini) и нажми «Повторить ИИ».") }
    }

    private suspend fun runSummary(cfg: Config, s: SessionRec) {
        if (s.done == 0) {
            Journal.update(s.id) { it.copy(summaryStatus = "error", summary = "") }
            return
        }
        status.value = "ИИ пишет итог сессии…"
        withRetry("итог") {
            val first = Ai.ask(cfg, Prompts.summarySystem(cfg), Prompts.summaryUser(s))
            if (!Garbage.isBad(first)) first else {
                Steps.add("ИИ: итог — бессмыслица, переспрашиваю"); delay(cfg.prov.gapMs)
                Ai.ask(cfg, Prompts.summarySystem(cfg), Prompts.summaryUser(s), temp = 0.15)
                    .also { if (Garbage.isBad(it)) throw AiException("ИИ выдал бессмыслицу вместо итога") }
            }
        }
            .onSuccess { txt -> Journal.update(s.id) { it.copy(summary = Clean.text(txt), summaryStatus = "done") } }
            .onFailure { err -> Steps.add("ИИ: итог — ошибка: ${err.message}"); Journal.update(s.id) { it.copy(summaryStatus = "error") } }
    }

    private suspend fun withRetry(what: String, f: suspend () -> String): Result<String> {
        var attempt = 0
        while (true) {
            lastCall = System.currentTimeMillis()
            try { return Result.success(f()) } catch (e: AiException) {
                attempt++
                val retryable = (e.code == 429 && !e.daily) || e.code in 500..599 || e.code == 408 || e.code == 0 && e.message?.startsWith("Ошибка сети") == true
                if (!retryable || attempt >= 4) return Result.failure(e)
                val ms = (if (e.retryMs > 0) e.retryMs else 12_000L * attempt).coerceIn(3_000, 65_000)
                var left = ms
                while (left > 0) { status.value = "Лимит ИИ — жду ${(left + 999) / 1000} с ($what)"; delay(1000); left -= 1000 }
            } catch (e: Exception) { return Result.failure(e) }
        }
    }

    private fun failAll(msg: String) {
        Journal.sessions.value.forEach { s ->
            if (s.entries.any { it.status == "pending" } || s.summaryStatus == "pending")
                Journal.update(s.id) { r ->
                    r.copy(entries = r.entries.map { if (it.status == "pending") it.copy(status = "error", error = msg) else it },
                        summaryStatus = if (r.summaryStatus == "pending") "error" else r.summaryStatus)
                }
        }
    }
}
