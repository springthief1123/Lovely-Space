package io.github.springthief1123.lovelyspace.data

import io.github.springthief1123.lovelyspace.core.Room
import io.github.springthief1123.lovelyspace.core.roomIdentity
import java.util.UUID

enum class CandidateMode { EXACT_NAME, DISPLAY_TEXT }

/**
 * 一覧に出ている名前・文字列の候補。人の同一性や現在の在室を判定する機能ではない。
 * [autoEnterUntil] は、名前が完全に一致する新しい部屋に自動で入室するか（0 は無効、オンは [AUTO_ENTER_ON]。[AutoEntryController.offerNamed]）。
 * 以前は 3 時間の期限を時刻で持っていたので、その記録は期限が来れば止まる。
 * [notify] は新しい部屋を端末通知で知らせるか（オフでもお知らせには残す）。
 */
data class CandidateRule(val id: String = UUID.randomUUID().toString(), val label: String, val genreKey: String,
    val term: String, val mode: CandidateMode = CandidateMode.EXACT_NAME, val enabled: Boolean = true, val autoEnterUntil: Long = 0L,
    val notify: Boolean = true) {
    /** 自動入室が [now] の時点で有効か。名前の完全一致で、候補を探している間だけ。 */
    fun autoEnterActive(now: Long): Boolean = enabled && mode == CandidateMode.EXACT_NAME && now < autoEnterUntil
    fun matches(room: Room): Boolean {
        val name = room.name?.trim() ?: return false
        if (room.genreKey != genreKey || term.isBlank()) return false
        // 公開トリップなどを含む表示文字列は大文字小文字も含め、そのまま比較する。
        return when (mode) { CandidateMode.EXACT_NAME -> name == term.trim(); CandidateMode.DISPLAY_TEXT -> name.contains(term.trim()) }
    }
    val key: String get() = "$id/$genreKey/$mode/${term.trim()}"

    companion object {
        /** 自動入室をオンにした [autoEnterUntil]。期限は設けず、入室できるか利用者が止めるまで続く（Yuya の決定、2026-11-05）。 */
        const val AUTO_ENTER_ON = Long.MAX_VALUE
    }
}

/**
 * 候補条件の一致。[rooms] は最後に読んだページ（[page]）だけでなく、手元にあるそのジャンルの一覧の全ページ（[pagesRead] ページ）から探したもの。
 * [roomPages] は一致した部屋（[roomIdentity]）ごとの、載っていたページ。
 */
data class CandidateResult(val ruleKey: String, val at: Long, val page: Int, val lastPage: Int, val rooms: List<Room>,
    val pagesRead: Int = 1, val roomPages: Map<String, Int> = emptyMap()) {
    /** [room] が載っていたページ。分からなければ最後に読んだページ。 */
    fun pageOf(room: Room): Int = roomPages[roomIdentity(room)] ?: page
}
