package io.github.springthief1123.lovelyspace.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.springthief1123.lovelyspace.disguise.Hsv
import io.github.springthief1123.lovelyspace.disguise.formatHexColor
import io.github.springthief1123.lovelyspace.disguise.parseHexColor
import io.github.springthief1123.lovelyspace.disguise.symbolColorOn
import io.github.springthief1123.lovelyspace.ui.theme.LovelyShapes
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 色の見本を [columns] 個ずつ並べた選択。読み上げではラジオボタンとして `#RRGGBB` と選択状態を伝える。
 * 白など背景に溶ける色も見分けられるよう、見本はすべて境界線で囲む。
 * 押せる範囲は見本の円ではなく列の幅いっぱい（高さ 48dp 以上）にし、隣の見本との間に押せない隙間を作らない。
 */
@Composable
internal fun ColorSwatchGrid(colors: List<Int>, columns: Int, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        colors.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { color ->
                    ColorSwatch(color, selected = color == selected, onClick = { onSelect(color) }, Modifier.weight(1f))
                }
                // 最後の行が欠けても、見本の大きさを他の行とそろえる。
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(LovelyShapes.control)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = formatHexColor(color) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(color))
                .border(if (selected) 3.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = Color(symbolColorOn(color)), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * カラーホイール（色相・彩度）と明るさのバー、`#RRGGBB` の入力。
 * ホイールの状態は HSV で持ち、灰色や黒を選んでも色相を失わない（外から色が変わったときだけ合わせ直す）。
 */
@Composable
internal fun ColorWheelPicker(color: Int, onChange: (Int) -> Unit) {
    var hsv by remember { mutableStateOf(Hsv.of(color)) }
    LaunchedEffect(color) { if (hsv.toColor() != color) hsv = Hsv.of(color) }
    fun update(next: Hsv) {
        hsv = next
        onChange(next.toColor())
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HueSaturationWheel(hsv, onPick = { hue, saturation -> update(hsv.copy(hue = hue, saturation = saturation)) })
        BrightnessBar(hsv, onPick = { value -> update(hsv.copy(value = value)) })
        HexColorField(color, onChange)
    }
}

@Composable
private fun HueSaturationWheel(hsv: Hsv, onPick: (hue: Float, saturation: Float) -> Unit) {
    val pick by rememberUpdatedState(onPick)
    val outline = MaterialTheme.colorScheme.outline
    fun pickAt(position: Offset, width: Int, height: Int) {
        val dx = position.x - width / 2f
        val dy = position.y - height / 2f
        val radius = minOf(width, height) / 2f
        val hue = ((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()) + 360f) % 360f
        pick(hue, (hypot(dx, dy) / radius).coerceIn(0f, 1f))
    }
    Canvas(
        Modifier
            .fillMaxWidth(0.78f)
            .widthIn(max = 320.dp)
            .aspectRatio(1f)
            .pointerInput(Unit) { detectTapGestures { pickAt(it, size.width, size.height) } }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { pickAt(it, size.width, size.height) }) { change, _ ->
                    change.consume()
                    pickAt(change.position, size.width, size.height)
                }
            }
            .semantics {
                contentDescription = "カラーホイール"
                stateDescription = "色相${hsv.hue.roundToInt()}度、彩度${(hsv.saturation * 100).roundToInt()}%"
            },
    ) {
        val radius = size.minDimension / 2f
        // 角度 0（3 時の方向）から時計回りに赤→黄→緑→シアン→青→マゼンタ。中心ほど白く（彩度 0）なる。
        drawCircle(
            Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red), center),
            radius,
        )
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, radius), radius)
        if (hsv.value < 1f) drawCircle(Color.Black.copy(alpha = 1f - hsv.value), radius)
        drawCircle(outline, radius, style = Stroke(1.dp.toPx()))
        val angle = Math.toRadians(hsv.hue.toDouble())
        val distance = hsv.saturation * radius
        drawThumb(Offset(center.x + (cos(angle) * distance).toFloat(), center.y + (sin(angle) * distance).toFloat()), Color(hsv.toColor()))
    }
}

@Composable
private fun BrightnessBar(hsv: Hsv, onPick: (Float) -> Unit) {
    val pick by rememberUpdatedState(onPick)
    val outline = MaterialTheme.colorScheme.outline
    val full = Color(hsv.copy(value = 1f).toColor())
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("明るさ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(Unit) {
                    val inset = size.height / 2f
                    fun valueAt(x: Float) = ((x - inset) / (size.width - inset * 2)).coerceIn(0f, 1f)
                    detectTapGestures { pick(valueAt(it.x)) }
                }
                .pointerInput(Unit) {
                    val inset = size.height / 2f
                    fun valueAt(x: Float) = ((x - inset) / (size.width - inset * 2)).coerceIn(0f, 1f)
                    detectDragGestures(onDragStart = { pick(valueAt(it.x)) }) { change, _ ->
                        change.consume()
                        pick(valueAt(change.position.x))
                    }
                }
                // 読み上げでは音量の上下と同じ操作で明るさを変えられる。
                .semantics {
                    contentDescription = "明るさ"
                    progressBarRangeInfo = ProgressBarRangeInfo(hsv.value, 0f..1f)
                    setProgress { target ->
                        pick(target.coerceIn(0f, 1f))
                        true
                    }
                },
        ) {
            val inset = size.height / 2f
            val corner = CornerRadius(inset, inset)
            drawRoundRect(Brush.horizontalGradient(listOf(Color.Black, full)), cornerRadius = corner)
            drawRoundRect(outline, cornerRadius = corner, style = Stroke(1.dp.toPx()))
            drawThumb(Offset(inset + (size.width - inset * 2) * hsv.value, center.y), Color(hsv.toColor()))
        }
    }
}

/** 選んでいる位置のつまみ。どの色の上でも見えるよう、白い輪の外側を細く黒で縁取る。 */
private fun DrawScope.drawThumb(position: Offset, color: Color) {
    val radius = 11.dp.toPx()
    drawCircle(Color.Black.copy(alpha = 0.45f), radius + 1.5.dp.toPx(), position)
    drawCircle(Color.White, radius, position)
    drawCircle(color, radius - 3.dp.toPx(), position)
}

@Composable
private fun HexColorField(color: Int, onChange: (Int) -> Unit) {
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(formatHexColor(color).removePrefix("#")) }
    // 見本やホイールで色が変わったら入力欄も合わせる（入力中の途中の値は残す）。
    LaunchedEffect(color) { if (parseHexColor(text) != color) text = formatHexColor(color).removePrefix("#") }
    val valid = parseHexColor(text) != null
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
            parseHexColor(text)?.let(onChange)
        },
        label = { Text("カラーコード") },
        prefix = { Text("#") },
        singleLine = true,
        isError = !valid,
        supportingText = { Text(if (valid) "16進数 6 桁（例：1A73E8）" else "0〜9・A〜F を 6 桁で入力してください") },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth(),
    )
}
