package com.sench.scroll

/** Чистка текста: без собак, решёток, точек с запятой, кавычек, ссылок и прочих значков. */
object Clean {
    private val url = rx("""(https?://\S+|www\.\S+)""")
    private val handle = rx("""@([\p{L}\p{N}_.]+)""")
    private val tag = rx("""#([\p{L}\p{N}_]+)""")
    private val junkChars = Regex("[\"«»“”„‘’`*_~|()\\[\\]{}<>^=+$%&\\\\/]")
    private val spaces = Regex("[ \t]+")

    /** Обычный текст описания: запятые, точки, ! ? : и тире остаются. */
    fun text(s: String): String = s
        .replace(url, "")
        .replace(handle, "$1")
        .replace(tag, "$1")
        .replace(";", ",")
        .replace(junkChars, "")
        .replace(Regex(",\\s*,"), ",")
        .lines().joinToString("\n") { it.replace(spaces, " ").trim().trimStart('-', '•', '–', '—', ' ') }
        .replace(Regex("\n{3,}"), "\n\n")
        .replace(Regex("\\s+([,.!?:])"), "$1")
        .trim()

    /** Имя автора без собаки. */
    fun author(s: String) = text(s).substringBefore('\n').trim().take(40)

    // --------------------------------------------------- комментарии

    private val uiRx = rx("""^(ответить|reply|antworten|responder|répondre|скрыть|hide|ausblenden|ещё|more|mehr|""" +
        """посмотреть\s+(ещё\s+)?\d*.*ответ.*|view\s+(more|\d+)\s+repl.*|\d+\s+(weitere\s+)?antwort.*|""" +
        """автор|creator|ersteller|нравится автору|liked by creator|vom ersteller geliked|закреплено|pinned|angeheftet|""" +
        """добавить комментарий.*|add comment.*|kommentar hinzufügen.*|[\d\s.,]+\s*(k|к|тыс\.?)?\s*(комментар\S*|comments?|kommentare?)|комментарии|comments|kommentare|""" +
        """перевести|see translation|übersetzung anzeigen|показать перевод|отправить|send|senden)$""")
    private val timeRx = rx("""^(\d+\s*(с|сек|мин|ч|д|дн|нед|мес|г|s|m|h|d|w|y|min|std|tag|tage|wo)\.?(\s*(назад|ago))?|только что|just now|gerade eben|vor\s+\d+\s*\S+|вчера|yesterday|gestern|\d{1,2}[-./]\d{1,2}([-./]\d{2,4})?)$""")
    private val counterRx = rx("""^[\d\s.,]+\s*(k|m|к|м|тыс\.?|млн\.?)?$""")
    private val nickRx = rx("""^[\p{L}\p{N}_.]{2,30}$""")

    /** Похоже ли на ник (одно слово с цифрами, точками или подчёркиванием, или латиница в нижнем регистре). */
    fun looksLikeNick(s: String): Boolean =
        !s.contains(' ') && nickRx.matches(s) && (s.any { it.isDigit() || it == '_' || it == '.' } ||
            (s.all { it in 'a'..'z' || it.isDigit() } && s.length >= 4))

    /**
     * Из сырого текста окна комментов достаём только сами комментарии: без ников, кнопок,
     * времени и счётчиков. Комментарий с «·» (ник · время) обрезаем.
     */
    fun comments(raw: String, limit: Int = 30): List<String> {
        val out = LinkedHashSet<String>()
        for (line0 in raw.lines()) {
            val line = line0.trim().substringBefore(" · ").trim()
            if (line.length < 2) continue
            if (uiRx.matches(line) || timeRx.matches(line) || counterRx.matches(line) || looksLikeNick(line)) continue
            if (line.startsWith("@") && !line.contains(' ')) continue
            out += line.take(220)
            if (out.size >= limit) break
        }
        return out.toList()
    }
}

/** Ловит бессмыслицу, которую иногда выдают слабые модели: выдуманные слова, буквы вперемешку, куски JSON. */
object Garbage {
    private val word = rx("""[\p{L}]+""")
    private val russian = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"

    private fun badWord(w: String): Boolean {
        if (w.length > 19) return true
        // заглавные посреди слова: «аГАбМКен», «ЖЗМАИаКендн»
        val flips = w.drop(1).zipWithNext().count { (a, b) -> a.isLowerCase() && b.isUpperCase() }
        if (flips >= 1 && w.length > 4) return true
        val cyr = w.count { it in '\u0400'..'\u04FF' }
        // не русские кириллические буквы (њ, ђ, ћ …) или кириллица вперемешку с латиницей
        if (w.any { it in '\u0400'..'\u04FF' && it !in russian && it !in "іїєґІЇЄҐ" }) return true
        if (cyr in 1 until w.length && w.any { it in 'a'..'z' || it in 'A'..'Z' }) return true
        return false
    }

    fun isBad(text: String): Boolean {
        if (text.isBlank()) return false
        if (Regex("""(\[\s*\{|\{\s*"|\[\s*"|"\s*:\s*")""").containsMatchIn(text)) return true
        val words = word.findAll(text).map { it.value }.filter { it.length >= 3 }.toList()
        if (words.isEmpty()) return false
        val bad = words.count { badWord(it) }
        return bad >= 3 || bad.toDouble() / words.size > 0.08
    }
}
