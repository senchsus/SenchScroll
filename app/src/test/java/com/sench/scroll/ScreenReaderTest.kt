package com.sench.scroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReaderTest {
    private val W = 1080; private val H = 2400

    /** Примерно так выглядит экран TikTok для службы спецвозможностей. */
    private fun feed(author: String, caption: String, comments: String = "Read or add comments. 1,234 comments") = listOf(
        SNode("", "LIVE", 40, 120, 140, 200, clickable = true),
        SNode("Following", "", 300, 130, 480, 190), SNode("For You", "", 560, 130, 760, 190),
        SNode("", "Like video. 52.1K likes", 960, 1200, 1060, 1300, clickable = true),
        SNode("52.1K", "", 960, 1300, 1060, 1340),
        SNode("", comments, 960, 1400, 1060, 1500, clickable = true),
        SNode("1234", "", 960, 1500, 1060, 1540),
        SNode("", "Share video", 960, 1620, 1060, 1720, clickable = true),
        SNode(author, "", 40, 1900, 500, 1950),
        SNode(caption, "", 40, 1960, 900, 2080),
        SNode("♬ original sound - $author", "", 40, 2090, 800, 2130),
        SNode("Home", "", 0, 2300, 200, 2400), SNode("Inbox", "", 700, 2300, 900, 2400),
    )

    @Test fun linesSkipNavigation() {
        val l = ScreenReader.lines(feed("@cat_lover", "кот уронил ёлку #кот #fyp"))
        assertTrue(l.contains("@cat_lover"))
        assertTrue(l.contains("кот уронил ёлку #кот #fyp"))
        assertFalse(l.contains("Home")); assertFalse(l.contains("For You")); assertFalse(l.contains("LIVE"))
    }

    @Test fun differentVideosDifferentFingerprint() {
        val a = ScreenReader.fingerprint(ScreenReader.lines(feed("@a", "первое видео про машину")))
        val b = ScreenReader.fingerprint(ScreenReader.lines(feed("@b", "второе видео про собаку")))
        val a2 = ScreenReader.fingerprint(ScreenReader.lines(feed("@a", "первое видео про машину", "Read or add comments. 1,240 comments")))
        assertNotEquals(a, b)
        assertEquals("счётчики не должны менять отпечаток", a, a2)
    }

    @Test fun commentButton() {
        val btn = ScreenReader.findCommentButton(feed("@a", "x"), W, H)
        assertNotNull(btn)
        assertEquals(1234, ScreenReader.commentCount(btn!!))
        val ru = ScreenReader.findCommentButton(feed("@a", "x", "Читать или добавлять комментарии. 3,5 тыс. комментариев"), W, H)
        assertEquals(3500, ScreenReader.commentCount(ru!!))
        val de = ScreenReader.findCommentButton(feed("@a", "x", "Kommentare lesen oder hinzufügen. 12 Kommentare"), W, H)
        assertEquals(12, ScreenReader.commentCount(de!!))
        assertEquals(1500, ScreenReader.commentCount(SNode("", "Comments 1.5K", 0, 0, 1, 1)))
        assertEquals(2300000, ScreenReader.commentCount(SNode("", "2,3 млн комментариев", 0, 0, 1, 1)))
        assertEquals(4512, ScreenReader.commentCount(SNode("", "4 512 комментариев", 0, 0, 1, 1)))
        assertEquals(0, ScreenReader.commentCount(SNode("", "0 comments", 0, 0, 1, 1)))
        // подпись со словом «комментарий» слева не считается кнопкой
        assertNull(ScreenReader.findCommentButton(listOf(SNode("пишите в комментарии", "", 40, 1960, 900, 2080)), W, H))
    }

    @Test fun adsAndLive() {
        assertTrue(ScreenReader.isAd(listOf("@brand", "Sponsored", "Shop now")))
        assertTrue(ScreenReader.isAd(listOf("@brand", "Реклама")))
        assertTrue(ScreenReader.isAd(listOf("Gesponsert")))
        assertFalse(ScreenReader.isAd(listOf("@user", "как я делал рекламу для друга? нет", "#fyp")))
        assertTrue(ScreenReader.isLive(listOf("@streamer", "Tap to watch LIVE")))
        assertTrue(ScreenReader.isLive(listOf("Нажмите, чтобы смотреть LIVE")))
        assertFalse(ScreenReader.isLive(listOf("LIVE", "@user", "обычное видео")))
    }

    @Test fun commentsSheet() {
        val before = ScreenReader.lines(feed("@a", "видео"))
        val after = feed("@a", "видео") + listOf(
            SNode("1 234 comments", "", 400, 900, 700, 960),
            SNode("vasya", "", 120, 1000, 400, 1040), SNode("ахахах лучший кот", "", 120, 1045, 900, 1100),
            SNode("petya", "", 120, 1150, 400, 1190), SNode("у меня такой же", "", 120, 1195, 900, 1250),
            SNode("", "", 40, 2250, 900, 2330, editable = true),
        )
        val nl = ScreenReader.newLines(before, after, H)
        assertTrue(nl.contains("ахахах лучший кот"))
        assertFalse(nl.contains("@a"))
        assertTrue(ScreenReader.commentsOpened(nl, after, H))
        assertFalse(ScreenReader.commentsOpened(emptyList(), feed("@a", "видео"), H))
    }

    @Test fun noiseFilterLearnsInterface() {
        val nf = NoiseFilter()
        val screens = listOf("@a" to "машина", "@b" to "собака", "@c" to "еда", "@d" to "танец")
        screens.forEach { (a, c) -> nf.add(ScreenReader.lines(feed(a, c))) }
        val l = ScreenReader.lines(feed("@e", "новое видео"))
        val noise = nf.noise(l)
        assertTrue(noise.contains("Share video"))
        assertFalse(noise.contains("@e")); assertFalse(noise.contains("новое видео"))
        val text = ScreenReader.forAi(l, noise)
        assertTrue(text.contains("новое видео")); assertFalse(text.contains("Share video"))
    }

    @Test fun photoPosts() {
        assertTrue(ScreenReader.isPhoto(listOf("@a", "1/7", "летний вайб")))
        assertTrue(ScreenReader.isPhoto(listOf("Photo mode")))
        assertFalse(ScreenReader.isPhoto(listOf("@a", "обычное видео про 1/2 пиццы и всё такое, длинная подпись")))
    }

    /** Слайдшоу: много невидимых фото не должны мешать разбору. */
    @Test fun slideshowScreen() {
        val nodes = List(200) { SNode("", "Photo ${it + 1}", 0, 300, 1080, 1800) } +
            listOf(SNode("@trip", "", 40, 1900, 500, 1950), SNode("", "Read or add comments. 15 comments", 960, 1400, 1060, 1500))
        val l = ScreenReader.lines(nodes)
        assertTrue(l.contains("@trip"))
        assertEquals(15, ScreenReader.commentCount(ScreenReader.findCommentButton(nodes, W, H)!!))
    }

    @Test fun likesAndZeroComments() {
        assertEquals(52100, ScreenReader.likeCount(feed("@a", "видео"), W, H))
        // счётчик отдельной строкой под кнопкой
        val nodes = listOf(
            SNode("", "Нравится", 960, 1200, 1060, 1300, clickable = true), SNode("1,2 млн", "", 960, 1300, 1060, 1340),
            SNode("", "Комментарии", 960, 1400, 1060, 1500, clickable = true), SNode("0", "", 960, 1500, 1060, 1540),
        )
        assertEquals(1200000, ScreenReader.likeCount(nodes, W, H))
        val btn = ScreenReader.findCommentButton(nodes, W, H)!!
        assertEquals(0, ScreenReader.countNear(btn, nodes, H))
    }

    @Test fun hypeSpreads() {
        assertEquals(3, Hype.fromLikes(300))
        assertEquals(10, Hype.fromLikes(5_000_000))
        assertTrue(Hype.blend(7, 300) < Hype.blend(7, 2_000_000))
        assertEquals(7, Hype.blend(7, -1))
        assertEquals("52 тыс.", Hype.short(52100))
    }
}
