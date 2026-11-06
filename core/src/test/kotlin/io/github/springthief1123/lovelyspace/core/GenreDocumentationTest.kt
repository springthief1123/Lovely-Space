package io.github.springthief1123.lovelyspace.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class GenreDocumentationTest {
    @Test fun documentedGenresMatchEveryKeyLabelHostAndPosition() {
        val doc = listOf(File("docs/site-protocol.md"), File("../docs/site-protocol.md")).first { it.isFile }
            .readText().substringAfter("## ドメインとジャンル").substringBefore("## 部屋一覧の行")
        val documented = doc.substringAfter("| キー | 名称 | ホスト |\n", "")
            .lineSequence().drop(1).takeWhile { it.startsWith("|") }
            .map { it.split('|').drop(1).dropLast(1).map(String::trim) }.toList()
        assertEquals(Genres.all.map { listOf(it.key, it.label, it.host) }, documented)
    }
}
