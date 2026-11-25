package com.sench.scroll

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AiException(msg: String, val code: Int = 0, val retryMs: Long = 0, val daily: Boolean = false) : Exception(msg)

object Ai {
    /** Низкая «креативность» — иначе слабые модели начинают выдумывать слова. */
    @Volatile private var temperature = 0.4
    /** Модели, которые подобрали взамен удалённых (до перезапуска). */
    private val noVision = HashSet<String>()

    suspend fun ask(
        cfg: Config, system: String, text: String,
        images: List<ByteArray> = emptyList(), json: Boolean = false, temp: Double = 0.4,
        textNoImages: String? = null,
    ): String = withContext(Dispatchers.IO) {
        temperature = temp
        val p = cfg.prov
        val key = cfg.key(p)
        if (key.isBlank()) throw AiException("Нет ключа для ${p.label} — вставь его в Настройках")
        val imgs = if (p.vision && p.id !in noVision) images else emptyList()
        var model = cfg.model(p)
        // если картинки не уходят (нейронка их не видит) — не пишем ей, что они приложены
        fun t(i: List<ByteArray>) = if (i.isEmpty() && images.isNotEmpty() && textNoImages != null) textNoImages else text
        try {
            call(p, key, model, system, t(imgs), imgs, json)
        } catch (e: AiException) {
            when {
                isModelGone(e) -> {
                    val alt = discover(p, key) ?: throw AiException("Модель «$model» не найдена. Впиши другую в Настройках.", e.code)
                    model = alt
                    Store.update { c -> c.copy(models = c.models + (p.id to alt)) }
                    call(p, key, model, system, t(imgs), imgs, json)
                }
                p.kind == "gemini" && e.code == 429 && !model.contains("lite") -> {
                    try { call(p, key, "gemini-flash-lite-latest", system, t(imgs), imgs, json) }
                    catch (e2: AiException) { if (isModelGone(e2)) throw e else throw e2 }
                }
                // Groq и другие отклоняют ответ, если модель ошиблась в строгом JSON — просим без строгого режима
                json && isJsonModeFail(e) -> call(p, key, model, system, t(imgs), imgs, false)
                imgs.isNotEmpty() && isNoVision(e) -> {
                    noVision += p.id
                    call(p, key, model, system, t(emptyList()), emptyList(), json)
                }
                else -> throw e
            }
        }
    }

    private fun isModelGone(e: AiException) = e.code == 404 ||
        (e.code == 400 && Regex("(?i)model.{0,40}(not.?found|does not exist|decommission|deprecated|invalid|unknown)").containsMatchIn(e.message ?: ""))

    private fun isJsonModeFail(e: AiException) = e.code in 400..422 &&
        Regex("(?i)(failed to generate json|failed_generation|json_validate|response_format|valid json)").containsMatchIn(e.message ?: "")

    private fun isNoVision(e: AiException) = e.code in 400..422 &&
        Regex("(?i)(image|vision|multimodal|content.{0,20}string)").containsMatchIn(e.message ?: "")

    private fun call(p: Provider, key: String, model: String, system: String, text: String, imgs: List<ByteArray>, json: Boolean): String =
        when (p.kind) {
            "gemini" -> gemini(p, key, model, system, text, imgs, json)
            "anthropic" -> anthropic(p, key, model, system, text, imgs)
            else -> openai(p, key, model, system, text, imgs, json)
        }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun gemini(p: Provider, key: String, model: String, system: String, text: String, imgs: List<ByteArray>, json: Boolean): String {
        val parts = JSONArray()
        imgs.forEach { parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64(it)))) }
        parts.put(JSONObject().put("text", text))
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", temperature).put("maxOutputTokens", 8192).apply {
                if (json) put("responseMimeType", "application/json")
            })
        val m = model.removePrefix("models/")
        val r = http("${p.base}/models/$m:generateContent", mapOf("x-goog-api-key" to key), body.toString())
        val o = JSONObject(r)
        val cands = o.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val block = o.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw AiException(if (block.isNotBlank()) "Gemini отказался отвечать ($block)" else "Gemini вернул пустой ответ")
        }
        val ps = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until ps.length()) {
            val part = ps.getJSONObject(i)
            if (!part.optBoolean("thought")) sb.append(part.optString("text"))
        }
        return sb.toString().trim().ifBlank { throw AiException("Gemini вернул пустой ответ") }
    }

    private fun openai(p: Provider, key: String, model: String, system: String, text: String, imgs: List<ByteArray>, json: Boolean): String {
        val user: Any = if (imgs.isEmpty()) text else JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", text))
            imgs.forEach {
                put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${b64(it)}")))
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("max_tokens", 1200)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        if (json && p.id in setOf("groq", "mistral", "xai")) body.put("response_format", JSONObject().put("type", "json_object"))
        val headers = mutableMapOf("Authorization" to "Bearer $key")
        if (p.id == "openrouter") { headers["HTTP-Referer"] = "https://github.com/senchsus"; headers["X-Title"] = "SenchScroll" }
        val r = JSONObject(http("${p.base}/chat/completions", headers, body.toString()))
        val ch = r.optJSONArray("choices")
        if (ch == null || ch.length() == 0) throw AiException(errText(r) ?: "${p.label} вернул пустой ответ")
        return ch.getJSONObject(0).optJSONObject("message")?.optString("content").orEmpty().trim()
            .ifBlank { throw AiException("${p.label} вернул пустой ответ") }
    }

    private fun anthropic(p: Provider, key: String, model: String, system: String, text: String, imgs: List<ByteArray>): String {
        val content = JSONArray()
        imgs.forEach {
            content.put(JSONObject().put("type", "image").put("source",
                JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64(it))))
        }
        content.put(JSONObject().put("type", "text").put("text", text))
        val body = JSONObject().put("model", model).put("max_tokens", 1200).put("temperature", temperature).put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val r = JSONObject(http("${p.base}/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body.toString()))
        val arr = r.optJSONArray("content") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until arr.length()) arr.getJSONObject(i).let { if (it.optString("type") == "text") sb.append(it.optString("text")) }
        return sb.toString().trim().ifBlank { throw AiException("Claude вернул пустой ответ") }
    }

    /** Найти рабочую модель, если стандартную убрали. */
    private fun discover(p: Provider, key: String): String? = try {
        val ids = when (p.kind) {
            "gemini" -> {
                val a = JSONObject(http("${p.base}/models?pageSize=200", mapOf("x-goog-api-key" to key), null)).optJSONArray("models") ?: JSONArray()
                (0 until a.length()).map { a.getJSONObject(it) }
                    .filter { m -> m.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true }
                    .map { it.optString("name").removePrefix("models/") }
                    .filter { n -> listOf("image", "tts", "audio", "live", "embed", "robotics").none { n.contains(it) } }
            }
            "anthropic" -> {
                val a = JSONObject(http("${p.base}/models", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), null)).optJSONArray("data") ?: JSONArray()
                (0 until a.length()).map { a.getJSONObject(it).optString("id") }
            }
            else -> {
                val a = JSONObject(http("${p.base}/models", mapOf("Authorization" to "Bearer $key"), null)).optJSONArray("data") ?: JSONArray()
                (0 until a.length()).map { a.getJSONObject(it).optString("id") }
                    .filter { n -> listOf("whisper", "tts", "embed", "guard", "image", "imagine", "moderation", "ocr").none { n.contains(it) } }
            }
        }
        p.prefer.firstNotNullOfOrNull { want -> ids.firstOrNull { it.contains(want) } } ?: ids.firstOrNull()
    } catch (_: Exception) { null }

    private fun errText(o: JSONObject?): String? {
        if (o == null) return null
        val e = o.opt("error")
        return when (e) {
            is JSONObject -> e.optString("message").ifBlank { null }
            is String -> e
            else -> o.optString("message").ifBlank { null }
        }
    }

    private fun http(url: String, headers: Map<String, String>, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000; c.readTimeout = 90_000
            c.requestMethod = if (body == null) "GET" else "POST"
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = errText(try { JSONObject(text) } catch (_: Exception) { null }) ?: text.take(200).ifBlank { "HTTP $code" }
                val retry = c.getHeaderField("retry-after")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                    ?: Regex("""retry in ([\d.]+)s""", RegexOption.IGNORE_CASE).find(msg)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                    ?: 0L
                val daily = code == 429 && Regex("(?i)(per.?day|daily|PerDay)").containsMatchIn(text)
                throw AiException(human(code, msg), code, retry, daily)
            }
            return text
        } catch (e: AiException) { throw e
        } catch (e: java.net.UnknownHostException) { throw AiException("Нет интернета")
        } catch (e: java.net.SocketTimeoutException) { throw AiException("ИИ слишком долго думал (таймаут)", 408)
        } catch (e: Exception) { throw AiException("Ошибка сети: ${e.message ?: e.javaClass.simpleName}")
        } finally { c.disconnect() }
    }

    private fun human(code: Int, msg: String) = when (code) {
        400 -> "Запрос не принят: ${msg.take(160)}"
        401, 403 -> "Ключ не подходит (${msg.take(120)})"
        402 -> "На аккаунте ИИ нет денег/кредитов"
        404 -> "Модель не найдена: ${msg.take(120)}"
        429 -> "Лимит запросов ИИ: ${msg.take(140)}"
        in 500..599 -> "Сервер ИИ упал ($code), попробую ещё"
        else -> "Ошибка $code: ${msg.take(160)}"
    }

    /** Достать JSON из ответа модели (иногда он в ```json ... ```). */
    fun parseJson(s: String): JSONObject? {
        val t = s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a >= 0 && b > a) try { return JSONObject(t.substring(a, b + 1)) } catch (_: Exception) {}
        return salvage(t)
    }

    /** Если ответ обрезался на середине — достаём те поля, что успели прийти. */
    fun salvage(t: String): JSONObject? {
        if (!t.contains('{')) return null
        val o = JSONObject()
        for (k in listOf("author", "headline", "about", "comments", "category", "vibe")) {
            val m = Regex("\"$k\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)").find(t) ?: continue
            o.put(k, m.groupValues[1].replace("\\n", " ").replace("\\\"", "").trim())
        }
        Regex("\"hype\"\\s*:\\s*(\\d+)").find(t)?.let { o.put("hype", it.groupValues[1].toInt()) }
        if (Regex("\"is_ad\"\\s*:\\s*true").containsMatchIn(t)) o.put("is_ad", true)
        return if (o.has("about") || o.has("headline")) o else null
    }
}

object Prompts {
    private fun tone(cfg: Config) = if (cfg.tone == "fun")
        "Пиши живо, на «ты», с юмором и молодёжным сленгом в меру (имба, кринж, жиза, база), можно эмодзи. Без оскорблений и пошлости."
    else "Пиши спокойно, понятно и по делу, без сленга."

    fun videoSystem(cfg: Config) = """
Ты — SenchScroll, ИИ, который листает TikTok вместо пользователя и ведёт для него журнал.
Тебе дают сырой текст с экрана видео (ник, подпись, хэштеги, музыка, счётчики лайков, кусочки интерфейса), иногда кадры из видео и сырой текст из комментариев.
Твоя задача — сделать СВОДКУ своими словами: о чём было видео и что в целом думают люди в комментариях. ${tone(cfg)}
Пиши по-русски. Не выдумывай: если чего-то не видно — так и скажи. Интерфейс TikTok («Подписаться», «Поделиться» и т.п.) не пересказывай. Если это фото-пост (слайдшоу), опиши фотографии и так и напиши, что это фото.
ПРАВИЛА ТЕКСТА:
- только обычный текст, предложения с точками и запятыми;
- НЕ копируй комментарии и подписи дословно, НЕ перечисляй комменты списком, НЕ пиши ники обычных комментаторов;
- НО ОБЯЗАТЕЛЬНО называй конкретные названия и имена: игры, песни, фильмы, бренды, приложения, известных людей, блогеров, места. Хэштеги — подсказка (например, robrun — это игра ROB RUN, durov — Павел Дуров). Название из подписи или кадров пиши как есть;
- без символов @ # ; кавычек, звёздочек, скобок, ссылок и хэштегов;
- в комментах обобщай: какое настроение, о чём спорят, над чем шутят, с чем соглашаются.
ГЛАВНОЕ — КОНКРЕТИКА. Пиши факты из видео, а не общие слова:
- рейтинг, тир-лист, топ или сравнение — назови, что на первом месте и что хуже всего;
- гайд, совет, лайфхак — назови сам совет;
- если видны итог, результат, счёт, цена, название игры, песни, товара или места — назови их;
- шутка или мем — перескажи саму шутку или ситуацию так, чтобы было смешно без видео;
- читай текст на кадрах (надписи, субтитры, таблицы, тир-листы) и используй его;
- плохо: «автор оценивает оружие в мобильной игре». Хорошо: «автор ранжирует пушки в ROB RUN, на первом месте дробовик, хуже всего пистолет».
ЗАПРЕЩЕНЫ пустые фразы: «в ролике показано», «сопровождается оригинальным звуком», «подано в лёгком стиле», «типичный вирусный клип», «быстрые смены кадров», «ролик выглядит как». Про музыку пиши, только если это известная песня и она важна для видео.
Если кадров нет и из текста суть не понять — скажи об этом одним коротким предложением и не гадай.
ШКАЛА hype (будь строгим, оценки должны РАЗЛИЧАТЬСЯ, средняя около 5):
1–2 — скучно, бессмысленно, кринж или реклама; 3–4 — проходняк, таких тысячи; 5–6 — норм, можно глянуть;
7–8 — реально круто, смешно или полезно; 9 — очень сильное видео; 10 — шедевр, ставь крайне редко.
Не ставь всем 6–7. Учитывай реакцию в комментах. Количество лайков тебе дают для понимания, но оценивай само видео.
Пиши грамотно, только настоящими русскими словами, понятными предложениями. Каждое поле — обычная строка, не список и не объект.
Ответ — строго один JSON без пояснений и без markdown, все поля короткие:
{"author":"имя автора без @ или пусто","names":"все конкретные названия и имена из видео через запятую: игра, песня, бренд, человек, место; пусто, если их нет","headline":"цепляющий заголовок до 7 слов, с названием, если оно есть","about":"3–4 предложения своими словами: что происходит в видео и в чём суть","comments":"2–3 предложения своими словами: общее настроение и главные мнения в комментах, или пусто, если комментов нет","category":"одно слово: юмор, игры, музыка, танцы, еда, животные, спорт, лайфстайл, обучение, новости, тренды, реклама или другое","vibe":"один эмодзи","hype":число от 1 до 10 по шкале ниже,"is_ad":true или false}
""".trim()

    fun videoUser(e: Entry, frames: Int) = buildString {
        append("ВИДЕО #").append(e.n)
        if (e.likes >= 0) append("\nЛайков: ").append(e.likes)
        append("\n\nТЕКСТ С ЭКРАНА:\n").append(e.raw.ifBlank { "(TikTok не отдал текст)" })
        val cm = Clean.comments(e.rawComments)
        append("\n\nКОММЕНТАРИИ (").append(cm.size).append(" шт., сделай по ним сводку, не цитируй):\n")
        append(if (cm.isEmpty()) "(комменты не открывались или их нет)" else cm.joinToString("\n") { "- $it" })
        append("\n\n").append(when {
            frames > 0 && e.shots > 1 -> "Приложена раскадровка: ${e.shots} кадров из видео в одной картинке, пронумерованы по порядку (слева направо, сверху вниз). " +
                "По ним пойми, что происходит в видео от начала до конца, и опиши это. Номера кадров в тексте не упоминай."
            frames > 0 -> "Приложен кадр из видео. Опиши, что на нём реально видно."
            else -> "Кадров нет — опирайся на текст."
        })
    }

    fun summarySystem(cfg: Config) = """
Ты — SenchScroll. Пользователь не смотрел TikTok сам — ты пролистал за него ленту и записал каждое видео.
Сделай итог сессии по-русски. ${tone(cfg)}
Формат (обычный текст: без markdown, без символов @ # ; кавычек и звёздочек, без ников):
1) 3–4 предложения: что было в ленте, какие темы и тренды попадались.
2) Строка «Топ-3:» и три лучших видео по одному короткому предложению (с номерами видео).
3) Строка «Что обсуждают в комментах:» — одно предложение.
4) Строка «Вайб ленты:» — эмодзи и пара слов.
Пиши конкретно: называй, что именно было (игры, кто топ-1 в рейтингах, суть шуток, главные новости), без общих слов вроде «мемный вайб» и «лёгкий юмор».
Всего до 900 символов.
""".trim()

    fun summaryUser(s: SessionRec) = buildString {
        append("Просмотрено видео: ").append(s.entries.size)
        if (s.skippedAds > 0) append(", пропущено рекламы: ").append(s.skippedAds)
        append("\n\n")
        s.entries.filter { it.status == "done" }.forEach { e ->
            append("#").append(e.n).append(" [").append(e.category).append(", ").append(e.hype).append("/10] ")
            append(e.headline)
            if (e.names.isNotBlank()) append(" [").append(e.names).append("]")
            append(" — ").append(e.about)
            if (e.comments.isNotBlank()) append(" Комменты: ").append(e.comments)
            append('\n')
        }
    }
}
