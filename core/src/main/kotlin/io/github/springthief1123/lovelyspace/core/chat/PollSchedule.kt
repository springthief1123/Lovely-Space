package io.github.springthief1123.lovelyspace.core.chat

import kotlin.math.ceil

/**
 * 新着取得の間隔。本家の `2shot.js`（countReloadTimer / getNewLog / getNewLog_onLoadCallBack）と同じ規則で決める。
 *
 * - 取得を始めるたびに間隔を 1 秒（待機中で負荷が高いときは `ceil(負荷/100)` 秒）延ばす。上限は [ChatState.reloadIntervalMaxSeconds]
 * - 新着があり 2 人そろっていれば、次は 2 秒後
 * - 発言すると間隔を初期値に戻す
 *
 * `live=1` の取得は新着が出るまでサーバーが応答を保留するので、会話中はこの間隔より応答待ちの時間が支配的になる。
 */
class PollSchedule(initial: ChatState) {
    private var ini = initial.reloadIntervalInitialSeconds
    private var max = initial.reloadIntervalMaxSeconds
    private var setting = ini
    private var count = 0

    /** 取得を始める直前に呼ぶ。 */
    fun onPollStarted(state: ChatState) {
        val step = if (!state.isFilledRoom && state.loadAverage > 100) ceil(state.loadAverage / 100.0).toInt() else 1
        setting = (setting + step).coerceAtMost(max)
        count = setting
    }

    /** 発言する直前に呼ぶ。 */
    fun onSend() {
        setting = ini
        count = setting
    }

    /** 応答を受けたら呼ぶ。次の取得までの待ち時間（ミリ秒）を返す。 */
    fun onResponse(update: ChatUpdate): Long {
        ini = update.state.reloadIntervalInitialSeconds
        max = update.state.reloadIntervalMaxSeconds
        if (setting < ini) {
            setting = ini
            count = ini
        }
        if (update.hasNewLines && update.state.isFilledRoom) count = 2
        return count * 1000L
    }

    /** 取得に失敗したときの待ち時間（ミリ秒）。 */
    fun onFailure(): Long = setting.coerceAtLeast(ini) * 1000L
}
