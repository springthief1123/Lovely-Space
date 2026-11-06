package io.github.springthief1123.lovelyspace.ui.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.springthief1123.lovelyspace.data.PinAlert
import io.github.springthief1123.lovelyspace.ui.components.QuietFilterChip

/** 自動入室を切り替えたときの知らせ。 */
internal fun autoEntryNotice(on: Boolean): String =
    if (on) "空いたら自動で入室します（入室できたら自動入室は終わります）" else "自動入室をやめました（空いたら知らせます）"

/**
 * ピンの自動入室を 1 タップで切り替えるチップ。ピンはどれも空いたら知らせるので、選ぶのは自動で入室するかだけ（Yuya の決定、2026-11-06）。
 * オンの間は淡いローズの面で示す。入室できたら自動入室は終わり、チップはオフに戻る（[io.github.springthief1123.lovelyspace.data.PinRepository.finishAutoEntry]）。
 */
@Composable
internal fun AutoEntryChip(alert: PinAlert, enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val on = alert == PinAlert.AUTO
    QuietFilterChip(selected = on, onClick = { onToggle(!on) }, label = "自動入室", enabled = enabled,
        leadingIcon = Icons.Outlined.MeetingRoom, modifier = modifier.semantics { stateDescription = if (on) "オン" else "オフ" })
}
