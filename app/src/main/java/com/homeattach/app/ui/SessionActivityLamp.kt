package com.homeattach.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

private val ACTIVITY_LAMP_GREEN = Color(0xFF4CAF50)
private const val ACTIVITY_LAMP_PULSE_MS = 750L

/** A dim connected light that rapidly flashes whenever [outputActive] is true. */
@Composable
internal fun SessionActivityLamp(
    outputActive: Boolean,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    val flashAlpha by rememberInfiniteTransition(label = "terminal activity lamp")
        .animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 110, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "terminal activity lamp alpha",
        )
    val color = when {
        outputActive -> ACTIVITY_LAMP_GREEN.copy(alpha = flashAlpha)
        connected -> ACTIVITY_LAMP_GREEN.copy(alpha = 0.32f)
        else -> Color.Gray
    }
    Box(modifier = modifier.background(color, CircleShape))
}

/** Pulses once when the server reports that a session has emitted new PTY output. */
@Composable
internal fun rememberSessionOutputActivity(outputSequence: Long): Boolean {
    var previousSequence by remember { mutableLongStateOf(outputSequence) }
    var outputActive by remember { mutableStateOf(false) }

    LaunchedEffect(outputSequence) {
        val changed = outputSequence > previousSequence
        previousSequence = outputSequence
        if (!changed) return@LaunchedEffect

        outputActive = true
        delay(ACTIVITY_LAMP_PULSE_MS)
        outputActive = false
    }
    return outputActive
}
