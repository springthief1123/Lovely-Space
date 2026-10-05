package io.github.springthief1123.lovelyspace.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import io.github.springthief1123.lovelyspace.ui.theme.LocalLovelyColors

val LocalLovelyHazeState = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun ProvideLovelyHazeState(state: HazeState, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLovelyHazeState provides state, content = content)
}

@Composable
fun LovelyGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val lovely = LocalLovelyColors.current
    val hazeState = LocalLovelyHazeState.current
    val glassModifier = if (hazeState != null) {
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = MaterialTheme.colorScheme.background,
                tint = HazeTint(lovely.glassTint.copy(alpha = 0.55f)),
                blurRadius = 24.dp,
                noiseFactor = 0f,
            ),
        )
    } else {
        Modifier.background(lovely.glassTint)
    }

    Box(
        modifier = modifier
            .shadow(8.dp, shape, clip = false)
            .clip(shape)
            .then(glassModifier)
            .border(BorderStroke(1.dp, lovely.glassBorder), shape),
        contentAlignment = contentAlignment,
        content = content,
    )
}

@Composable
fun LovelyGlassFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "glass-fab-scale")

    LovelyGlassSurface(
        modifier = modifier
            .size(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = CircleShape,
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = "部屋を作る",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
