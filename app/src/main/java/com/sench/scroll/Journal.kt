package com.sench.scroll

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Одно просмотренное видео. */
data class Entry(
    val n: Int,
    val time: Long,
    val raw: String = "",
    val rawComments: String = "",
    val frames: Int = 0,             // сколько картинок лежит в кеше для ИИ
    val shots: Int = 0,              // сколько кадров в раскадровке
    val thumb: String = "",          // имя файла превью
    val status: String = "pending",  // pending | done | error
    val error: String = "",
    val author: String = "",
    val headline: String = "",
    val about: String = "",
    val comments: String = "",
    val bestComment: String = "",
    val category: String = "",
    val vibe: String = "",
    val names: String = "",         // названия и имена: игры, песни, люди
    val hype: Int = 0,
    val isAd: Boolean = false,
) {
    fun toJson() = JSONObject().apply {
        put("n", n); put("time", time); put("raw", raw); put("rawComments", rawComments)
        put("frames", frames); put("shots", shots); put("thumb", thumb); put("status", status); put("error", error)
        put("author", author); put("headline", headline); put("about", about); put("comments", comments)
        put("bestComment", bestComment); put("category", category); put("vibe", vibe); put("names", names); put("hype", hype)
        put("isAd", isAd)
    }

    /** Применить ответ ИИ. */
    fun withNote(o: JSONObject): Entry {
        fun str(k: String) = (o.opt(k) as? String).orEmpty()   // списки и объекты не берём
        return copy(
        status = "done", error = "",
        author = Clean.author(str("author")),
        headline = Clean.text(str("headline")).take(90).ifBlank { "Видео #$n" },
        about = Clean.text(str("about")).take(700),
        comments = Clean.text(str("comments")).take(500),
        names = Clean.text(str("names").ifBlank { (o.optJSONArray("names")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }).orEmpty() })
            .replace("\n", ", ").take(160),
        bestComment = "",
        category = Clean.text(str("category")).lowercase().take(20),
        vibe = str("vibe").trim().take(8),
        hype = o.optInt("hype", 5).coerceIn(1, 10),
        isAd = o.optBoolean("is_ad", false),
    )
    }

    /** Текст, по которому проверяем бессмыслицу. */
    val proseForCheck get() = "$headline. $about $comments"

    companion object {
        fun fromJson(o: JSONObject) = Entry(
            n = o.optInt("n"), time = o.optLong("time"), raw = o.optString("raw"),
            rawComments = o.optString("rawComments"), frames = o.optInt("frames"), shots = o.optInt("shots"), thumb = o.optString("thumb"),
            status = o.optString("status", "pending"), error = o.optString("error"),
            author = o.optString("author"), headline = o.optString("headline"), about = o.optString("about"),
            comments = o.optString("comments"), bestComment = o.optString("bestComment"),
            category = o.optString("category"), vibe = o.optString("vibe"), names = o.optString("names"), hype = o.optInt("hype"),
            isAd = o.optBoolean("isAd"),
        )
    }
}

/** Одна сессия листания. */
data class SessionRec(
    val id: String,
    val started: Long,
    val ended: Long = 0,
    val provider: String = "",
    val entries: List<Entry> = emptyList(),
    val skippedAds: Int = 0,
    val skippedLive: Int = 0,
    val summary: String = "",
    val summaryStatus: String = "",   // "" | pending | done | error
    val stopReason: String = "",
) {
    val done get() = entries.count { it.status == "done" }

    fun toJson() = JSONObject().apply {
        put("id", id); put("started", started); put("ended", ended); put("provider", provider)
        put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })
        put("skippedAds", skippedAds); put("skippedLive", skippedLive)
        put("summary", summary); put("summaryStatus", summaryStatus); put("stopReason", stopReason)
    }

    companion object {
        fun fromJson(o: JSONObject): SessionRec {
            val arr = o.optJSONArray("entries") ?: JSONArray()
            return SessionRec(
                id = o.optString("id"), started = o.optLong("started"), ended = o.optLong("ended"),
                provider = o.optString("provider"),
                entries = (0 until arr.length()).map { Entry.fromJson(arr.getJSONObject(it)) },
                skippedAds = o.optInt("skippedAds"), skippedLive = o.optInt("skippedLive"),
                summary = o.optString("summary"), summaryStatus = o.optString("summaryStatus"),
                stopReason = o.optString("stopReason"),
            )
        }
    }
}

/** Журнал: все сессии, новые сверху. Хранится в journal.json. */
object Journal {
    val sessions = MutableStateFlow<List<SessionRec>>(emptyList())
    private var dir: File? = null
    private val lock = Any()

    fun init(ctx: Context) {
        dir = ctx.filesDir
        val f = File(ctx.filesDir, "journal.json")
        if (f.exists()) sessions.value = parse(f.readText())
        // если приложение убили посреди сессии — закрываем её
        synchronized(lock) {
            sessions.value = sessions.value.map {
                if (it.ended == 0L) it.copy(ended = System.currentTimeMillis(), stopReason = "Сессия прервалась",
                    summaryStatus = if (it.entries.isEmpty()) "" else "pending") else it
            }
        }
    }

    fun parse(s: String): List<SessionRec> = try {
        val a = JSONArray(s); (0 until a.length()).map { SessionRec.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    fun serialize(list: List<SessionRec>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    private fun save() {
        val d = dir ?: return
        val text = serialize(sessions.value)
        try {
            val tmp = File(d, "journal.json.tmp"); tmp.writeText(text); tmp.renameTo(File(d, "journal.json"))
        } catch (_: Exception) {}
    }

    fun get(id: String) = sessions.value.firstOrNull { it.id == id }

    fun begin(provider: String): SessionRec {
        val s = SessionRec(id = System.currentTimeMillis().toString(36), started = System.currentTimeMillis(), provider = provider)
        synchronized(lock) { sessions.value = listOf(s) + sessions.value; save() }
        return s
    }

    fun update(id: String, f: (SessionRec) -> SessionRec) = synchronized(lock) {
        sessions.value = sessions.value.map { if (it.id == id) f(it) else it }
        save()
    }

    fun updateEntry(id: String, n: Int, f: (Entry) -> Entry) =
        update(id) { s -> s.copy(entries = s.entries.map { if (it.n == n) f(it) else it }) }

    fun addEntry(id: String, e: Entry) = update(id) { it.copy(entries = it.entries + e) }

    fun end(id: String, reason: String) = update(id) {
        it.copy(ended = System.currentTimeMillis(), stopReason = reason, summaryStatus = if (it.entries.isEmpty()) "" else "pending")
    }

    /** Вернуть ошибки в очередь. */
    fun retry(id: String) = update(id) { s ->
        s.copy(
            entries = s.entries.map { if (it.status == "error") it.copy(status = "pending", error = "") else it },
            summaryStatus = if (s.entries.isNotEmpty() && s.ended > 0 && s.summaryStatus != "done") "pending" else s.summaryStatus,
        )
    }

    /** Заново прогнать всю сессию через ИИ (например, другой нейронкой). */
    fun redoAll(id: String) = update(id) { s ->
        s.copy(entries = s.entries.map { it.copy(status = "pending", error = "") },
            summaryStatus = if (s.ended > 0 && s.entries.isNotEmpty()) "pending" else s.summaryStatus, summary = "")
    }

    fun redoSummary(id: String) = update(id) { it.copy(summaryStatus = "pending") }

    fun delete(id: String) {
        synchronized(lock) { sessions.value = sessions.value.filter { it.id != id }; save() }
        dir?.let { d -> File(d, "thumbs").listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() } }
    }

    fun clear() {
        synchronized(lock) { sessions.value = emptyList(); save() }
        dir?.let { File(it, "thumbs").deleteRecursively() }
    }

    fun thumbFile(name: String): File? = dir?.let { File(File(it, "thumbs"), name) }?.takeIf { name.isNotBlank() && it.exists() }

    /** Текст для «Поделиться». */
    fun shareText(s: SessionRec): String = buildString {
        append("📼 SenchScroll — сессия ").append(Fmt.dateTime(s.started)).append('\n')
        if (s.summary.isNotBlank()) append('\n').append(s.summary.trim()).append("\n")
        s.entries.filter { it.status == "done" }.forEach { e ->
            append("\n#").append(e.n).append(' ').append(e.vibe).append(' ').append(e.headline)
            if (e.author.isNotBlank()) append(" (").append(e.author).append(')')
            if (e.names.isNotBlank()) append("\n🏷 ").append(e.names)
            append('\n').append(e.about)
            if (e.comments.isNotBlank()) append("\n💬 ").append(e.comments)
            append('\n')
        }
    }
}

object Fmt {
    private fun two(i: Int) = i.toString().padStart(2, '0')
    fun dateTime(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "${two(c.get(java.util.Calendar.DAY_OF_MONTH))}.${two(c.get(java.util.Calendar.MONTH) + 1)} · " +
            "${two(c.get(java.util.Calendar.HOUR_OF_DAY))}:${two(c.get(java.util.Calendar.MINUTE))}"
    }
    fun dur(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 60) "${s / 60} мин ${s % 60} с" else "$s с" }
}
