package io.github.springthief1123.lovelyspace.ui

import io.github.springthief1123.lovelyspace.core.HttpStatusException
import java.io.IOException

/** 通信の失敗を画面に出す文にする。 */
fun describeError(e: Exception): String = when (e) {
    is io.github.springthief1123.lovelyspace.core.chat.RoomPageUnavailableException -> e.message.orEmpty()
    is HttpStatusException -> "ラブルームから応答エラーが返りました（${e.code}）"
    is IOException -> "通信できませんでした。電波の状態を確認してください"
    else -> "読み込みに失敗しました（${e.javaClass.simpleName}）"
}
