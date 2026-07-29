package com.homeattach.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay

private val LAMP_ACTIVE = Color(0xFF00E676)
private val LAMP_CONNECTED = Color(0xFF2E7D4F)
private val LAMP_OFFLINE = Color(0xFF6B7280)

/** One lit tick. Longer than the host's activity cadence so a steady stream reads as steadily on. */
private const val ACTIVITY_LAMP_PULSE_MS = 900L
private const val ACTIVITY_LAMP_BREATH_MS = 900

/**
 * A session's activity light: solid dot, plus a halo that breathes while output is arriving.
 *
 * The dot itself never changes opacity. An alpha-flickering dot was unreadable in a list — with
 * several sessions on screen the eye cannot tell a dim lamp from a lamp mid-blink, so "which of
 * these is busy" took a second look. Keeping the core solid and putting all the motion in a halo
 * around it means state is legible from the fill and activity from the movement, and the two never
 * compete: bright solid + halo is busy, dim solid is attached and quiet, hollow ring is neither.
 *
 * Sized by [modifier]; the halo uses the outer third of that size, so the visible dot is smaller
 * than the box it is given.
 */
@Composable
internal fun SessionActivityLamp(
    outputActive: Boolean,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    val breath by rememberInfiniteTransition(label = "session activity lamp")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = ACTIVITY_LAMP_BREATH_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "session activity lamp halo",
        )

    Canvas(modifier) {
        val outer = size.minDimension / 2f
        val core = outer * 0.62f
        when {
            outputActive -> {
                drawCircle(
                    color = LAMP_ACTIVE.copy(alpha = 0.14f + 0.30f * breath),
                    radius = core + (outer - core) * breath,
                )
                drawCircle(color = LAMP_ACTIVE, radius = core)
            }

            connected -> drawCircle(color = LAMP_CONNECTED, radius = core)

            else -> drawCircle(
                color = LAMP_OFFLINE.copy(alpha = 0.55f),
                radius = core * 0.82f,
                style = Stroke(width = core * 0.42f),
            )
        }
    }
}

/**
 * Turns the host's activity counter for one session into a lit interval.
 *
 * The counter is the only thing the wire carries — it says "this session emitted output again",
 * with no duration attached — so how long that stays visible is decided here, once, for every lamp
 * on every screen.
 */
@Composable
internal fun rememberSessionOutputActivity(activityTick: Long): Boolean {
    var previousTick by remember { mutableLongStateOf(activityTick) }
    var outputActive by remember { mutableStateOf(false) }

    LaunchedEffect(activityTick) {
        val changed = activityTick > previousTick
        previousTick = activityTick
        if (!changed) return@LaunchedEffect

        outputActive = true
        delay(ACTIVITY_LAMP_PULSE_MS)
        outputActive = false
    }
    return outputActive
}
