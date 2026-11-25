package com.sench.scroll

/** Узел с экрана TikTok в простом виде (чтобы логику можно было тестировать без телефона). */
data class SNode(
    val text: String,
    val desc: String,
    val l: Int, val t: Int, val r: Int, val b: Int,
    val clickable: Boolean = false,
    val editable: Boolean = false,
) {
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
    val w get() = r - l
    val h get() = b - t
    val area get() = w.toLong() * h
}

/**
 * Регулярка без встроенных флагов: на Android (ICU) нельзя флаг юникода в скобках, а на ПК без них русские буквы
 * не считаются буквами. Флаги через Pattern работают одинаково и там, и там.
 */
fun rx(p: String): Regex =
    java.util.regex.Pattern.compile(p, java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE).toRegex()

object ScreenReader {
    private val junk = setOf(
        "home", "friends", "inbox", "profile", "for you", "following", "explore", "search", "live", "shop",
        "discover", "stem", "following tab", "for you tab", "create", "back", "more", "share",
        "главная", "друзья", "входящие", "профиль", "рекомендации", "подписки", "поиск", "интересное",
        "магазин", "создать", "назад", "ещё", "поделиться",
        "startseite", "freunde", "posteingang", "profil", "für dich", "folge ich", "entdecken", "suche", "teilen",
    )
    private val ws = Regex("\\s+")
    private val digits = Regex("\\d")
    private val onlyCounter = rx("""^[\d\s.,]+\s*(k|m|b|к|м|тыс\.?|млн\.?|mio\.?|tsd\.?)?$""")
    private val adRx = rx("""(^|[^\p{L}\p{N}_])(sponsored|promoted|gesponsert|werbung|anzeige|patrocinado|sponsorisé|реклама|спонсорск[\p{L}\p{N}_]*|рекламн[\p{L}\p{N}_]+ (пост|видео))([^\p{L}\p{N}_]|$)""")
    private val adExact = setOf("ad", "ads", "реклама", "anzeige", "werbung")
    private val liveRx = rx("""(live\s*now|watch\s*live|tap to watch|смотреть\s+(live|эфир|трансляц)|прямой\s+эфир|в\s+эфире|live\s+ansehen|zum\s+ansehen\s+tippen|нажмите,?\s+чтобы\s+(смотреть|посмотреть|войти))""")
    private val commentRx = rx("""(comment|коммент|kommentar|comentar|commentaire|yorum|komentar)""")
    private val numRx = rx("""(\d{1,3}(?:[.,\u00A0\u202F ]\d{3})+|\d+(?:[.,]\d+)?)\s*(k|m|к|м|тыс\.?|млн\.?|mio\.?|tsd\.?)?(?![\p{L}\p{N}_])""")

    fun clean(s: String) = s.trim().replace(ws, " ")

    /** Все видимые строки сверху вниз, без дублей и без кнопок навигации. */
    fun lines(nodes: List<SNode>): List<String> {
        val out = LinkedHashSet<String>()
        nodes.sortedWith(compareBy({ it.t }, { it.l })).forEach { n ->
            for (s in listOf(n.text, n.desc)) {
                val c = clean(s)
                if (c.length >= 2 && c.lowercase() !in junk) out += c
            }
        }
        return out.toList()
    }

    /** Отпечаток видео: строки без цифр (счётчики меняются) и без «шума» интерфейса. */
    fun fingerprint(lines: List<String>, noise: Set<String> = emptySet()): String =
        lines.asSequence()
            .filter { it !in noise && !onlyCounter.matches(it) }
            .map { it.replace(digits, "") }
            .filter { it.length >= 4 }
            .sorted().joinToString("|")

    fun isAd(lines: List<String>): Boolean =
        lines.any { adExact.contains(it.lowercase()) || (it.length < 160 && adRx.containsMatchIn(it)) }

    private val photoRx = rx("""(photo\s*mode|фоторежим|фото-?пост|слайдшоу|slideshow|fotomodus|^\d{1,2}\s*/\s*\d{1,2}$)""")

    /** Фото-пост / слайдшоу (несколько фото под музыку). */
    fun isPhoto(lines: List<String>): Boolean = lines.any { it.length < 60 && photoRx.containsMatchIn(it) }

    fun isLive(lines: List<String>): Boolean = lines.any { it.length < 120 && liveRx.containsMatchIn(it) }

    /** Кнопка комментариев — справа, небольшая, с подписью про комменты. */
    fun findCommentButton(nodes: List<SNode>, w: Int, h: Int): SNode? =
        nodes.filter { n ->
            !n.editable && (commentRx.containsMatchIn(n.desc) || commentRx.containsMatchIn(n.text)) &&
                n.cx > w * 0.6 && n.cy > h * 0.25 && n.cy < h * 0.93 &&
                n.h in 1..(h * 0.15).toInt() && n.w in 1..(w * 0.3).toInt()
        }.minByOrNull { it.area }

    /** Сколько комментов написано на кнопке (null — не понятно). */
    fun commentCount(n: SNode): Int? = parseCount(n.desc) ?: parseCount(n.text)

    /** Сколько на кнопке, а если на ней нет числа — число прямо под ней (так TikTok часто рисует счётчики). */
    fun countNear(btn: SNode, nodes: List<SNode>, h: Int): Int? =
        commentCount(btn) ?: nodes.filter { n ->
            n !== btn && onlyCounter.matches(clean(n.text.ifBlank { n.desc })) &&
                n.t >= btn.b - 12 && n.t <= btn.b + (h * 0.05).toInt() && kotlin.math.abs(n.cx - btn.cx) < btn.w
        }.minByOrNull { it.t }?.let { parseCount(it.text.ifBlank { it.desc }) }

    private val likeRx = rx("""(like|нрав|лайк|gefällt|curtir|j'aime|me gusta)""")

    /** Кнопка лайка — справа, над кнопкой комментов. */
    fun likeCount(nodes: List<SNode>, w: Int, h: Int): Int? {
        val btn = nodes.filter { n ->
            !n.editable && (likeRx.containsMatchIn(n.desc) || likeRx.containsMatchIn(n.text)) &&
                n.cx > w * 0.6 && n.cy > h * 0.2 && n.cy < h * 0.9 && n.h in 1..(h * 0.15).toInt() && n.w in 1..(w * 0.3).toInt()
        }.minByOrNull { it.area } ?: return null
        return countNear(btn, nodes, h)
    }

    fun parseCount(s0: String): Int? {
        for (s in listOf(s0)) {
            val m = numRx.find(s) ?: continue
            val raw = m.groupValues[1]
            val suffix = m.groupValues[2].lowercase().trimEnd('.')
            // «1,234» / «1 234» без «тыс.» — это разделитель тысяч, а «3,5 тыс.» — дробь
            val grouped = suffix.isEmpty() && Regex("""^\d{1,3}([.,\u00A0\u202F ]\d{3})+$""").matches(raw)
            val v = (if (grouped) raw.filter { it.isDigit() } else raw.replace(',', '.').filter { it.isDigit() || it == '.' }).toDoubleOrNull() ?: continue
            val mul = when (suffix) {
                "k", "к", "тыс", "tsd" -> 1_000.0
                "m", "м", "млн", "mio" -> 1_000_000.0
                else -> 1.0
            }
            return (v * mul).toInt()
        }
        return null
    }

    /** Строки, которые появились после открытия комментов (в нижней части экрана). */
    fun newLines(before: List<String>, after: List<SNode>, h: Int): List<String> {
        val old = before.toHashSet()
        return lines(after.filter { it.t > h * 0.15 }).filter { it !in old }
    }

    fun commentsOpened(newLines: List<String>, after: List<SNode>, h: Int): Boolean =
        newLines.size >= 2 || after.any { it.editable && it.cy > h * 0.6 }

    fun forAi(lines: List<String>, noise: Set<String>, limit: Int = 2200): String {
        val sb = StringBuilder()
        for (l in lines) {
            if (l in noise) continue
            if (sb.length + l.length > limit) break
            sb.append(l).append('\n')
        }
        return sb.toString().trim()
    }
}

/**
 * Учится отличать интерфейс TikTok от содержимого видео: строки, которые есть почти на каждом
 * экране (кнопки, вкладки), — это шум. Их не шлём ИИ и не учитываем в отпечатке.
 */
class NoiseFilter {
    private val seen = HashMap<String, Int>()
    private var screens = 0

    fun add(lines: List<String>) {
        screens++
        lines.map { it.replace(Regex("\\d+"), "#") }.toSet().forEach { seen[it] = (seen[it] ?: 0) + 1 }
    }

    fun noise(lines: List<String>): Set<String> {
        if (screens < 3) return emptySet()
        return lines.filter { (seen[it.replace(Regex("\\d+"), "#")] ?: 0) >= screens * 0.6 }.toSet()
    }
}
