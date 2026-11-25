package com.sench.scroll

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Нейронка, к которой ходит приложение. */
data class Provider(
    val id: String,
    val label: String,
    val kind: String,          // gemini | openai | anthropic
    val base: String,
    val model: String,
    val keyUrl: String,
    val free: Boolean,
    val vision: Boolean,       // умеет смотреть кадры
    val desc: String,
    val gapMs: Long,           // пауза между запросами, чтобы не упираться в лимит
    val prefer: List<String>,  // по каким словам искать замену, если модель удалили
)

object Providers {
    val all = listOf(
        Provider(
            "gemini", "Gemini", "gemini", "https://generativelanguage.googleapis.com/v1beta",
            "gemini-flash-latest", "https://aistudio.google.com/apikey", true, true,
            "Google. Бесплатный ключ за минуту, карта не нужна. Смотрит кадры из видео — лучший вариант.",
            6500, listOf("flash-latest", "flash-lite-latest", "flash"),
        ),
        Provider(
            "groq", "Groq", "openai", "https://api.groq.com/openai/v1",
            "openai/gpt-oss-120b", "https://console.groq.com/keys", true, false,
            "Очень быстрый, бесплатный ключ. Кадры НЕ видит — только текст: модели Groq со зрением отключены. Для кадров бери Gemini.",
            2500, listOf("gpt-oss-120b", "llama-3.3-70b", "gpt-oss"),
        ),
        Provider(
            "mistral", "Mistral", "openai", "https://api.mistral.ai/v1",
            "mistral-small-latest", "https://console.mistral.ai/api-keys", true, true,
            "Европейский ИИ, бесплатный режим (нужно подтвердить телефон). Видит кадры.",
            2200, listOf("mistral-small", "mistral-medium", "pixtral"),
        ),
        Provider(
            "openrouter", "OpenRouter", "openai", "https://openrouter.ai/api/v1",
            "openrouter/free", "https://openrouter.ai/keys", true, false,
            "Много моделей через один ключ. Бесплатные — около 50 запросов в день. Только текст.",
            3500, listOf("free"),
        ),
        Provider(
            "xai", "Grok", "openai", "https://api.x.ai/v1",
            "grok-4-1-fast-non-reasoning", "https://console.x.ai", false, true,
            "Grok от xAI. Ключ платный — нужен баланс на console.x.ai. Видит кадры.",
            1200, listOf("fast-non-reasoning", "fast", "grok-4", "grok"),
        ),
        Provider(
            "claude", "Claude", "anthropic", "https://api.anthropic.com/v1",
            "claude-haiku-5-5", "https://console.anthropic.com/settings/keys", false, true,
            "Claude от Anthropic. Ключ платный — нужен баланс в консоли Anthropic. Видит кадры.",
            1200, listOf("haiku", "sonnet"),
        ),
    )

    fun get(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}

data class Config(
    val provider: String = "gemini",
    val keys: Map<String, String> = emptyMap(),
    val models: Map<String, String> = emptyMap(),
    val videos: Int = 15,
    val watchSec: Int = 10,
    val randomize: Boolean = true,
    val readComments: Boolean = true,
    val deepComments: Boolean = false,
    val vision: Boolean = true,
    val shots: Int = 6,                  // кадров с видео (склеиваются в одну картинку)
    val skipAds: Boolean = true,
    val skipLive: Boolean = true,
    val tone: String = "fun",            // fun | chill
    val openJournalAtEnd: Boolean = true,
    val a11yConsent: Boolean = false,    // согласие с раскрытием данных спецвозможности (требование Google Play)
) {
    fun key(p: Provider) = keys[p.id].orEmpty().trim()
    fun model(p: Provider) = models[p.id]?.trim()?.takeIf { it.isNotEmpty() } ?: p.model
    val prov get() = Providers.get(provider)

    fun toJson(): String = JSONObject().apply {
        put("provider", provider)
        put("keys", JSONObject(keys))
        put("models", JSONObject(models))
        put("videos", videos); put("watchSec", watchSec); put("randomize", randomize)
        put("readComments", readComments); put("deepComments", deepComments)
        put("vision", vision); put("shots", shots); put("skipAds", skipAds); put("skipLive", skipLive)
        put("tone", tone); put("openJournalAtEnd", openJournalAtEnd); put("a11yConsent", a11yConsent)
    }.toString()

    companion object {
        private fun JSONObject?.toMap(): Map<String, String> {
            if (this == null) return emptyMap()
            val m = LinkedHashMap<String, String>()
            keys().forEach { k -> m[k] = optString(k) }
            return m
        }

        fun fromJson(s: String?): Config {
            if (s.isNullOrBlank()) return Config()
            return try {
                val o = JSONObject(s); val d = Config()
                Config(
                    provider = o.optString("provider", d.provider),
                    keys = o.optJSONObject("keys").toMap(),
                    models = o.optJSONObject("models").toMap(),
                    videos = o.optInt("videos", d.videos).coerceIn(1, 200),
                    watchSec = o.optInt("watchSec", d.watchSec).coerceIn(3, 60),
                    randomize = o.optBoolean("randomize", d.randomize),
                    readComments = o.optBoolean("readComments", d.readComments),
                    deepComments = o.optBoolean("deepComments", d.deepComments),
                    vision = o.optBoolean("vision", d.vision),
                    shots = o.optInt("shots", d.shots).coerceIn(1, 9),
                    skipAds = o.optBoolean("skipAds", d.skipAds),
                    skipLive = o.optBoolean("skipLive", d.skipLive),
                    tone = o.optString("tone", d.tone),
                    openJournalAtEnd = o.optBoolean("openJournalAtEnd", d.openJournalAtEnd),
                    a11yConsent = o.optBoolean("a11yConsent", false),
                )
            } catch (_: Exception) { Config() }
        }
    }
}

/** Настройки сохраняются сразу при любом изменении. */
object Store {
    val cfg = MutableStateFlow(Config())
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        var c = Config.fromJson(prefs()?.getString("cfg", null))
        val g = c.models["groq"].orEmpty()
        if (g.contains("llama-4") || g.contains("llama-3.1-8b")) c = c.copy(models = c.models - "groq")
        cfg.value = c
    }

    private fun prefs() = appCtx?.getSharedPreferences("sench", Context.MODE_PRIVATE)

    fun update(f: (Config) -> Config) {
        val n = f(cfg.value)
        cfg.value = n
        prefs()?.edit()?.putString("cfg", n.toJson())?.apply()
    }
}

object TikTok {
    val packages = listOf(
        "com.zhiliaoapp.musically",      // TikTok (глобальный)
        "com.ss.android.ugc.trill",      // TikTok (Азия)
        "com.zhiliaoapp.musically.go",   // TikTok Lite
        "com.ss.android.ugc.aweme",      // Douyin
    )

    fun installed(ctx: Context): String? =
        packages.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }
}
