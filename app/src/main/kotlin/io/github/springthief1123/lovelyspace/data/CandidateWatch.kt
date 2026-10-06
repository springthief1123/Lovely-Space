package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.Room
import java.util.UUID

enum class CandidateMode { EXACT_NAME, DISPLAY_TEXT }

/** 一覧に出ている名前・文字列の候補。人の同一性や現在の在室を判定する機能ではない。 */
data class CandidateRule(val id: String = UUID.randomUUID().toString(), val label: String, val genreKey: String,
    val term: String, val mode: CandidateMode = CandidateMode.EXACT_NAME, val enabled: Boolean = true) {
    fun matches(room: Room): Boolean {
        val name = room.name?.trim() ?: return false
        if (room.genreKey != genreKey || term.isBlank()) return false
        // 公開トリップなどを含む表示文字列は大文字小文字も含め、そのまま比較する。
        return when (mode) { CandidateMode.EXACT_NAME -> name == term.trim(); CandidateMode.DISPLAY_TEXT -> name.contains(term.trim()) }
    }
    val key: String get() = "$id/$genreKey/$mode/${term.trim()}"
}

data class CandidateResult(val ruleKey: String, val at: Long, val page: Int, val lastPage: Int, val rooms: List<Room>)
