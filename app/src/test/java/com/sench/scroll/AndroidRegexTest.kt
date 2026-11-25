package com.sench.scroll

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Android (ICU) не понимает флаг (?U) — на компьютере он работает, а на телефоне роняет приложение. */
class AndroidRegexTest {
    @Test fun noUnsupportedFlags() {
        val bad = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (Regex("""\(\?[a-zA-Z]*[Uu]""").containsMatchIn(l)) "${f.name}:${i + 1}" else null } }
            .toList()
        assertTrue("Флаг U в регулярках: $bad", bad.isEmpty())
    }
}
