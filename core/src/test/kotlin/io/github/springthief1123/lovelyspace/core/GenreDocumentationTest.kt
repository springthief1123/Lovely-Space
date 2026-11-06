package io.github.springthief1123.lovelyspace.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class GenreDocumentationTest {
    @Test fun documentedGenresMatchEveryKeyLabelHostAndPosition() {
        val doc = listOf(File("docs/site-protocol.md"), File("../docs/site-protocol.md")).first { it.isFile }
            .readText().substringAfter("## ドメインとジャンル").substringBefore("## 部屋一覧の行")
        val row = Regex("""^\| ([a-z]+) \| ([^|]+) \| ([a-z0-9.]+) \|$""")
        val documented = doc.lineSequence().mapNotNull { row.matchEntire(it) }.map {
            listOf(it.groupValues[1], it.groupValues[2].trim(), it.groupValues[3])
        }.toList()
        assertEquals(Genres.all.map { listOf(it.key, it.label, it.host) }, documented)
    }
}
