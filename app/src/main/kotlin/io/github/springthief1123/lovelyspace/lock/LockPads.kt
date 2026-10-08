package io.github.springthief1123.lovelyspace.lock

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 入力済みの桁数を点で示す。[length] が 0 なら（設定中で桁数が決まっていなければ）入力した数だけ出す。 */
@Composable
internal fun PasscodeDots(entered: Int, length: Int) {
    val count = maxOf(length, entered, 4)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.semantics { contentDescription = "${entered}桁入力済み" }) {
        repeat(count) { index ->
            val filled = index < entered
            Box(
                Modifier.size(14.dp).clip(CircleShape)
                    .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

/** 数字のキーパッド。左下は生体認証（使えるときだけ）、右下は 1 文字消す。 */
@Composable
internal fun PasscodeKeypad(onDigit: (Char) -> Unit, onDelete: () -> Unit, onBiometric: (() -> Unit)?, enabled: Boolean = true) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { row.forEach { digit -> KeypadKey(enabled, digit.toString(), onClick = { onDigit(digit) }) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (onBiometric != null) {
                KeypadKey(true, null, "生体認証で解除", onClick = onBiometric) { Icon(Icons.Outlined.Fingerprint, null) }
            } else {
                Box(Modifier.size(KEY_SIZE))
            }
            KeypadKey(enabled, "0", onClick = { onDigit('0') })
            KeypadKey(enabled, null, "1文字消す", onClick = onDelete) { Icon(Icons.AutoMirrored.Outlined.Backspace, null) }
        }
    }
}

@Composable
private fun KeypadKey(enabled: Boolean, text: String?, description: String? = null, onClick: () -> Unit, icon: @Composable () -> Unit = {}) {
    Box(
        Modifier.size(KEY_SIZE).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        else icon()
    }
}

/** 3×3 の点の番号（0〜8、左上から右へ）を、パッドの大きさ [size] の中の位置 [position] から求める。点の近くでなければ null。 */
internal fun patternDotAt(position: Offset, size: Float): Int? {
    val cell = size / 3
    val column = (position.x / cell).toInt()
    val row = (position.y / cell).toInt()
    if (column !in 0..2 || row !in 0..2) return null
    val center = Offset(cell * column + cell / 2, cell * row + cell / 2)
    return if ((position - center).getDistance() <= cell * 0.38f) row * 3 + column else null
}

/** [from] から [to] へ飛ぶとき、間にある点（まだ使っていなければ）を返す。端末のパターンロックと同じく途中の点も通ったことにする。 */
internal fun patternDotBetween(from: Int, to: Int, used: Collection<Int>): Int? {
    val (r1, c1) = from / 3 to from % 3
    val (r2, c2) = to / 3 to to % 3
    if ((r1 + r2) % 2 != 0 || (c1 + c2) % 2 != 0) return null
    val middle = (r1 + r2) / 2 * 3 + (c1 + c2) / 2
    return middle.takeIf { it != from && it != to && it !in used }
}

/** 指でなぞるパターンの入力欄。指を離したら [onComplete] に点の並びを渡す。 */
@Composable
internal fun PatternPad(onComplete: (List<Int>) -> Unit, enabled: Boolean = true, error: Boolean = false) {
    // TalkBack ではなぞる操作ができないので、点を 1 つずつ選ぶ入力欄に切り替える。
    if (rememberTouchExplorationEnabled()) return AccessiblePatternPad(onComplete, enabled, error)
    val dots = remember { mutableStateListOf<Int>() }
    val complete by rememberUpdatedState(onComplete)
    var finger by remember { mutableStateOf<Offset?>(null) }
    val primary = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outline
    Canvas(
        Modifier.width(260.dp).aspectRatio(1f)
            .semantics { contentDescription = "パターンの入力欄。3×3の点を指でなぞります" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val side = size.width.toFloat()
                fun add(position: Offset) {
                    val dot = patternDotAt(position, side) ?: return
                    if (dot in dots) return
                    dots.lastOrNull()?.let { last -> patternDotBetween(last, dot, dots)?.let(dots::add) }
                    dots.add(dot)
                }
                detectDragGestures(
                    onDragStart = { dots.clear(); finger = it; add(it) },
                    onDrag = { change, _ -> finger = change.position; add(change.position) },
                    onDragEnd = { finger = null; val result = dots.toList(); dots.clear(); if (result.isNotEmpty()) complete(result) },
                    onDragCancel = { finger = null; dots.clear() },
                )
            },
    ) {
        val cell = size.width / 3
        fun center(dot: Int) = Offset(cell * (dot % 3) + cell / 2, cell * (dot / 3) + cell / 2)
        dots.zipWithNext().forEach { (a, b) -> drawLine(primary, center(a), center(b), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round) }
        val last = dots.lastOrNull()
        val current = finger
        if (last != null && current != null) drawLine(primary.copy(alpha = 0.5f), center(last), current, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        repeat(9) { dot ->
            val selected = dot in dots
            drawCircle(if (selected) primary else idle, radius = if (selected) 11.dp.toPx() else 7.dp.toPx(), center = center(dot))
        }
    }
}

/**
 * スクリーンリーダー向けのパターン入力。9 つの点をそれぞれボタンにし、順に選んで「決定」で確かめる。
 * なぞったときと同じく、間を飛ばした点は自動で加えるので、どちらで登録しても同じパターンになる。
 */
@Composable
private fun AccessiblePatternPad(onComplete: (List<Int>) -> Unit, enabled: Boolean, error: Boolean) {
    val dots = remember { mutableStateListOf<Int>() }
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("点を順に選び、「決定」を押します", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                repeat(3) { column ->
                    val dot = row * 3 + column
                    val order = dots.indexOf(dot)
                    val selected = order >= 0
                    Box(
                        Modifier.size(KEY_SIZE).clip(CircleShape)
                            .background(if (selected) (if (error) scheme.errorContainer else scheme.primaryContainer) else scheme.surfaceContainer)
                            .clickable(enabled = enabled && !selected, role = Role.Button) {
                                dots.lastOrNull()?.let { last -> patternDotBetween(last, dot, dots)?.let(dots::add) }
                                dots.add(dot)
                            }
                            .semantics { contentDescription = patternDotLabel(dot) + if (selected) "、${order + 1}番目に選択済み" else "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Text("${order + 1}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { dots.clear() }, enabled = enabled && dots.isNotEmpty()) { Text("やり直す") }
            Button(onClick = { val result = dots.toList(); dots.clear(); onComplete(result) }, enabled = enabled && dots.isNotEmpty()) { Text("決定") }
        }
    }
}

/** 点の位置の読み上げ。例: 「上の段の左の点」。 */
internal fun patternDotLabel(dot: Int): String =
    "${listOf("上", "中", "下")[dot / 3]}の段の${listOf("左", "中央", "右")[dot % 3]}の点"

/** TalkBack などの「タッチで探索」が有効か。切り替えにも追従する。 */
@Composable
internal fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

private val KEY_SIZE = 68.dp
