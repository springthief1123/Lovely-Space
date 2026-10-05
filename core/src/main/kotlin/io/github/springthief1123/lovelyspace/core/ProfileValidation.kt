package io.github.springthief1123.lovelyspace.core

fun validProfile(name: String, sex: Int, years: Int?): Boolean =
    name.isNotBlank() && sex in 1..2 && (years == null || years in 18..99)

/** 本家の待機文の数え方（半角1、全角2）。UTF-16単位で本家の入力欄と合わせる。 */
fun messageWidth(message: String): Int = message.sumOf { c ->
    if (c.code < 0x80 || c.code in 0xFF61..0xFF9F) 1 else 2
}
