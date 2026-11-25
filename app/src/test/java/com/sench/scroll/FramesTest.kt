package com.sench.scroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramesTest {
    @Test fun gridColumns() {
        assertEquals(2, Frames.cols(2)); assertEquals(2, Frames.cols(4)); assertEquals(3, Frames.cols(6)); assertEquals(3, Frames.cols(9))
    }

    @Test fun promptMentionsStoryboard() {
        val u = Prompts.videoUser(Entry(1, 0, raw = "x", frames = 1, shots = 6), 1)
        assertTrue(u.contains("раскадровка: 6 кадров"))
    }

    @Test fun configKeepsShots() {
        assertEquals(9, Config.fromJson(Config(shots = 9).toJson()).shots)
        assertEquals(6, Config.fromJson("{}").shots)
    }
}
