package com.sench.scroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataTest {
    @Test fun parseAiJsonVariants() {
        val plain = """{"author":"@cat","headline":"Кот против ёлки","about":"Кот роняет ёлку.","comments":"Все ржут","best_comment":"«это мой кот»","category":"Животные","vibe":"😹","hype":8,"is_ad":false}"""
        val fenced = "```json\n$plain\n```"
        val chatty = "Вот ответ:\n$plain\nНадеюсь, помог!"
        for (s in listOf(plain, fenced, chatty)) {
            val o = Ai.parseJson(s); assertNotNull(s, o)
            val e = Entry(n = 3, time = 0).withNote(o!!)
            assertEquals("done", e.status); assertEquals("cat", e.author); assertEquals(8, e.hype)
            assertEquals("животные", e.category); assertEquals("", e.bestComment)
        }
        assertNull(Ai.parseJson("просто текст"))
        val bad = Entry(n = 1, time = 0).withNote(Ai.parseJson("""{"hype":99}""")!!)
        assertEquals(10, bad.hype); assertEquals("Видео #1", bad.headline)
    }

    @Test fun configRoundTrip() {
        val c = Config(provider = "groq", keys = mapOf("groq" to "gsk_1", "gemini" to "AIza"), models = mapOf("xai" to "grok-x"),
            videos = 25, watchSec = 14, readComments = false, tone = "chill")
        val b = Config.fromJson(c.toJson())
        assertEquals(c, b)
        assertEquals("gsk_1", b.key(Providers.get("groq")))
        assertEquals("grok-x", b.model(Providers.get("xai")))
        assertEquals(Providers.get("gemini").model, b.model(Providers.get("gemini")))
        assertEquals(Config(), Config.fromJson("мусор"))
    }

    @Test fun journalRoundTrip() {
        val s = SessionRec("abc", 100, 200, "Gemini", listOf(
            Entry(1, 1, raw = "@a\nподпись", status = "done", headline = "Заголовок", hype = 7, vibe = "🔥"),
            Entry(2, 2, raw = "x", status = "error", error = "Лимит"),
        ), skippedAds = 2, summary = "Итог", summaryStatus = "done")
        val back = Journal.parse(Journal.serialize(listOf(s)))
        assertEquals(listOf(s), back)
        assertTrue(Journal.shareText(s).contains("Заголовок"))
    }

    @Test fun prompts() {
        val e = Entry(5, 0, raw = "@a\nподпись", rawComments = "")
        val u = Prompts.videoUser(e, 2)
        assertTrue(u.contains("ВИДЕО #5")); assertTrue(u.contains("Приложен кадр")); assertTrue(u.contains("не открывались"))
        assertTrue(Prompts.videoSystem(Config()).contains("JSON"))
    }
}
