package com.sench.scroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanTest {
    @Test fun textHasNoJunk() {
        val t = Clean.text("Автор @vasya_228 снял #котики; все \"в восторге\" **очень** https://t.co/x (правда)")
        listOf("@", "#", ";", "\"", "*", "http", "(", ")").forEach { assertFalse("$it в «$t»", t.contains(it)) }
        assertTrue(t.contains("vasya228") || t.contains("vasya"))
        assertTrue(t.contains(",")); assertTrue(t.contains("котики"))
    }

    @Test fun commentsWithoutUi() {
        val raw = """
            1 234 комментария
            vasya_228
            ахах лучший кот на свете
            2 ч назад
            Ответить
            1543
            Посмотреть 12 ответов
            petya.official
            а у меня такой же, тоже роняет ёлку
            Нравится автору
            Добавить комментарий...
            katya
            жиза
            5d ago
            Reply
            View 3 replies
        """.trimIndent()
        val c = Clean.comments(raw)
        assertEquals(listOf("ахах лучший кот на свете", "а у меня такой же, тоже роняет ёлку", "жиза"), c)
    }

    @Test fun salvageCutJson() {
        val cut = """{"author":"@cat","headline":"Кот против ёлки","about":"Рыжий кот роняет ёлку, хозяйка в шоке.","comments":"Все смеются и вспоминают сво"""
        val o = Ai.parseJson(cut)
        assertNotNull(o)
        val e = Entry(1, 0).withNote(o!!)
        assertEquals("Кот против ёлки", e.headline)
        assertEquals("cat", e.author)
        assertTrue(e.about.startsWith("Рыжий кот"))
    }

    @Test fun promptSendsCleanComments() {
        val e = Entry(2, 0, raw = "x", rawComments = "vasya_1\nкрутое видео\nОтветить\n3 ч назад")
        val u = Prompts.videoUser(e, 0)
        assertTrue(u.contains("- крутое видео")); assertFalse(u.contains("vasya_1")); assertFalse(u.contains("Ответить"))
    }

    @Test fun namesAreKept() {
        val o = org.json.JSONObject("""{"headline":"Тир-лист пушек ROB RUN","about":"Автор ранжирует пушки в ROB RUN, на первом месте дробовик.","names":"ROB RUN, Павел Дуров"}""")
        val e = Entry(1, 0).withNote(o)
        assertEquals("ROB RUN, Павел Дуров", e.names)
        val arr = Entry(2, 0).withNote(org.json.JSONObject("""{"about":"Текст.","names":["Fortnite","Drake"]}"""))
        assertEquals("Fortnite, Drake", arr.names)
        assertTrue(Prompts.videoSystem(Config()).contains("\"names\""))
    }
}
