package io.github.springthief1123.lovelyspace.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.springthief1123.lovelyspace.LovelySpaceApp
import io.github.springthief1123.lovelyspace.Routes
import io.github.springthief1123.lovelyspace.data.PinState
import io.github.springthief1123.lovelyspace.data.PinView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * いま本家に部屋があるピンの数（空いている・満室・立て直しかも）。まだ確かめていないピンと、閉じたまま似た部屋も無いピンは数えない。
 * ボトムナビの「ピン」のバッジに出し、1 件以上ならピンのアイコンを塗りつぶす（Yuya の依頼、2026-11-06）。
 */
internal fun livePinCount(pins: List<PinView>): Int =
    pins.count { it.state == PinState.OPEN || it.state == PinState.FULL || it.similar != null }

/** バッジの文字。ナビの小さなバッジに収まるよう 99 を超えたら「99+」。 */
internal fun pinBadgeText(count: Int): String = if (count > 99) "99+" else "$count"

/** 刺さる動きは、見つける画面でピンしたときだけ（ピンのタブでの引き継ぎ・元に戻すでは動かさない）。 */
internal fun shouldStabPinIcon(currentRoute: String?): Boolean = currentRoute == Routes.ROOMS || currentRoute == Routes.SEARCH

/**
 * ボトムナビの「ピン」のアイコン。見つける画面で部屋をピンすると、少し浮いてからプスッと刺さるように下へ沈んで戻る。
 * 動くのはアイコンだけで、下の「ピン」の文字とバッジは動かさない。部屋のあるピンがあれば塗りつぶしにし、右上に極小の件数を出す。
 */
@Composable
internal fun PinTabIcon(currentRoute: String?, tint: Color) {
    val app = LocalContext.current.applicationContext as LovelySpaceApp
    val pins by app.pins.pins.collectAsStateWithLifecycle()
    val live = livePinCount(pins)
    // 0 件になって消えていく間は、最後に出していた件数のまま消す。
    val shown = remember { intArrayOf(0) }
    if (live > 0) shown[0] = live
    val route by rememberUpdatedState(currentRoute)
    val offsetY = remember { Animatable(0f) }
    val squash = remember { Animatable(1f) }
    LaunchedEffect(app.pins) {
        app.pins.added.collect {
            if (!shouldStabPinIcon(route)) return@collect
            offsetY.snapTo(0f)
            squash.snapTo(1f)
            coroutineScope {
                launch {
                    // 単位は dp。上へ持ち上げて、勢いよく下へ刺し、少し跳ね返って止まる。
                    offsetY.animateTo(0f, keyframes {
                        durationMillis = PIN_STAB_MS
                        -6f at 140
                        -6.5f at 190
                        3.5f at 270
                        -1f at 360
                        0.6f at 430
                    })
                }
                launch {
                    // 刺さった瞬間だけ縦に少し縮める。
                    squash.animateTo(1f, keyframes {
                        durationMillis = PIN_STAB_MS
                        1f at 230
                        0.86f at 280
                        1.04f at 360
                    })
                }
            }
        }
    }
    Box(Modifier.size(22.dp)) {
        Icon(if (live > 0) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = null, tint = tint,
            modifier = Modifier.size(22.dp).graphicsLayer {
                translationY = offsetY.value.dp.toPx()
                scaleY = squash.value
                // 針の先（下端）を軸に縮める。
                transformOrigin = TransformOrigin(0.5f, 1f)
            })
        AnimatedVisibility(live > 0, Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-3).dp),
            enter = fadeIn() + scaleIn(initialScale = 0.6f), exit = fadeOut() + scaleOut(targetScale = 0.6f)) {
            val text = pinBadgeText(shown[0])
            Box(Modifier.heightIn(min = 13.dp).widthIn(min = 13.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .padding(horizontal = 3.dp)
                .clearAndSetSemantics { contentDescription = "部屋のあるピン${shown[0]}件" },
                contentAlignment = Alignment.Center) {
                Text(text, color = MaterialTheme.colorScheme.onPrimary, fontSize = 8.sp, lineHeight = 8.sp,
                    style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

private const val PIN_STAB_MS = 520
