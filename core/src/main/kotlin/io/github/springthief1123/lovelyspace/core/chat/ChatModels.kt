package io.github.springthief1123.lovelyspace.core.chat

import java.time.LocalTime

/**
 * 入室中（または待機中）の部屋を指す。[pwd] はその部屋での本人用トークンで、
 * 部屋作成・入室のたびに発行される。URL に含まれるので外へ出さないこと。
 */
data class ChatRoomRef(
    val host: String,
    val roomId: Long,
    val pwd: String,
    val genreKey: String,
) {
    val pageUrl: String get() = "https://$host/2shot.php?room_id=$roomId&pwd=$pwd"

    override fun toString(): String = "ChatRoomRef(host=$host, roomId=$roomId, genreKey=$genreKey)"
}

/** チャットログの 1 行。 */
data class ChatLine(
    /** 発言者名。システムのお知らせでは null。 */
    val speaker: String?,
    /** 女性の発言は名前が色付きで出る。男性・不明は区別できないため false。 */
    val speakerIsFemale: Boolean,
    /** 本文（改行は `\n`）。お知らせでは「◯◯さんが入室しました」などの文。 */
    val text: String,
    val time: LocalTime?,
    /** 本文に含まれる画像の URL。 */
    val imageUrls: List<String> = emptyList(),
) {
    val isNotice: Boolean get() = speaker == null
}

/** 部屋を開いた時点の状態（`2shot.php` の初回表示）。 */
data class ChatPage(
    val room: ChatRoomRef,
    val title: String,
    /** 自分の表示名（発言欄の前に出る名前）。 */
    val myName: String?,
    /** 部屋の作成者なら true（閉鎖・相手の退室などができる）。 */
    val isOwner: Boolean,
    val isPublic: Boolean,
    /** 直近のログ。新しい順（サイトの表示と同じ）。 */
    val lines: List<ChatLine>,
    val state: ChatState,
    /** 待機メッセージ（募集文）。 */
    val waitingMessage: String?,
    /** 作成者が相手を退室させられるか（本家の「相手を退室」ボタンが出ているか）。 */
    val canBanGuest: Boolean = false,
    /** 作成者に公開・非公開の切り替えが出ているか。 */
    val canChangePublic: Boolean = false,
)

/** ページと ajax 応答の両方で更新される値。 */
data class ChatState(
    /** 次の ajax.php に渡すログの読み出し位置。 */
    val fromSize: Long,
    /** 無言で部屋が閉じられるまでの残り秒。 */
    val mugonLimitSeconds: Int? = null,
    /** 部屋の利用制限時間の残り秒。 */
    val roomLimitSeconds: Int? = null,
    /** 2 人そろっているか。 */
    val isFilledRoom: Boolean = false,
    /** サイトの負荷。100 を超えると更新間隔を延ばす。 */
    val loadAverage: Int = 0,
    /** 新着なしが続いたときの更新間隔の初期値（秒）。 */
    val reloadIntervalInitialSeconds: Int = 5,
    /** 更新間隔の上限（秒）。 */
    val reloadIntervalMaxSeconds: Int = 600,
)

/** `ajax.php` の 1 回分の応答。 */
data class ChatUpdate(
    val state: ChatState,
    /** 新着ログ。古い順（サイトはこの順に画面の先頭へ積む）。 */
    val newLines: List<ChatLine>,
    /** true なら新着を足す前に表示中のログを消す（作成者が発言をクリアした）。 */
    val clearLog: Boolean,
    /** 入室者が来た（待機中の作成者側に届く）。 */
    val someoneEntered: Boolean,
    /** 相手が退室した。 */
    val guestLeft: Boolean,
    /** 相手を退室させられる状態か。 */
    val canBanGuest: Boolean,
    /** 部屋が終了した理由。null でなければ以後の更新は不要。 */
    val endMessage: String?,
    /** お知らせ欄（空なら非表示）。 */
    val information: String,
    /** 覗き（ROM）の人数。非表示設定なら null。 */
    val romCount: Int?,
    val isPublic: Boolean?,
) {
    val hasNewLines: Boolean get() = newLines.isNotEmpty() || clearLog
}

/** 入室前画面（`/PreEnterRoom`）のフォーム。 */
data class EntryForm(
    val host: String,
    val roomId: Long,
    val genreKey: String,
    /** この画面で発行された入室用トークン。 */
    val pwd: String,
    /** 待機者の紹介文（「◯◯ (25) さん 女 (Android 一時ID xxxxx) が待機中です」）。 */
    val hostDescription: String,
    val waitingMessage: String,
    /** ロボット除け認証（Turnstile / hCaptcha）が求められているか。 */
    val requiresCaptcha: Boolean,
    /** フォームの既定の名前（前回入力した名前が入っていることがある）。 */
    val defaultName: String,
    /** 入室前画面の文字コード。フォームはこの文字コードで送る。 */
    val formCharset: String = "UTF-8",
)

data class EntryProfile(
    val name: String,
    /** 1=男, 2=女 */
    val sex: Int,
    /** null は「秘密」。 */
    val years: Int?,
)

sealed interface EntryResult {
    data class Entered(val room: ChatRoomRef) : EntryResult

    /** 入室できなかった（満室になった、認証が必要、入力エラーなど）。[message] はサイトの表示。 */
    data class Rejected(val message: String) : EntryResult
}
