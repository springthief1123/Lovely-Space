package io.github.springthief1123.lovelyspace.core

/** 待機メッセージの保存名を自動で付けるときの長さ（文字数）。一覧の 1 行に収まる程度。 */
const val MESSAGE_LABEL_LENGTH = 12

/** プロフィールの保存名。空欄なら名前をそのまま使う。 */
fun profilePresetLabel(label: String, name: String): String =
    label.trim().ifEmpty { name.trim() }

/**
 * 待機メッセージの保存名。空欄なら本文の先頭 [MESSAGE_LABEL_LENGTH] 文字から付け、続きがあれば「…」を足す。
 * 絵文字などのサロゲートペアを途中で切らないよう、コードポイント単位で数える。連続する空白は 1 つにまとめる。
 */
fun messagePresetLabel(label: String, message: String): String {
    label.trim().takeIf { it.isNotEmpty() }?.let { return it }
    val text = message.trim().replace(Regex("\\s+"), " ")
    val count = text.codePointCount(0, text.length)
    if (count <= MESSAGE_LABEL_LENGTH) return text
    return text.substring(0, text.offsetByCodePoints(0, MESSAGE_LABEL_LENGTH)).trimEnd() + "…"
}
