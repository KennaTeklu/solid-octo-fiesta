package com.peakform.fitness.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Confetti — native canvas confetti (legacy canvas-confetti wrapper, PF L56289–56330):
 * burst from y≈0.6, 5-color palette fallback, and the session cap of 3 bursts
 * ("no login bombardment").
 */
object Confetti {
    private const val SESSION_CAP = 3
    @Volatile var burstsThisSession = 0

    val PALETTE = listOf(
        Color(0xFF2563EB), Color(0xFFF59E0B), Color(0xFF10B981),
        Color(0xFFEF4444), Color(0xFF8B5CF6),
    )

    /** Returns true if the burst was allowed (cap not exhausted). */
    fun burstAllowed(): Boolean = burstsThisSession < SESSION_CAP

    data class Particle(
        val x: Float, val y: Float, val vx: Float, val vy: Float,
        val size: Float, val color: Color, val spin: Float, val phase: Float,
    )

    fun makeParticles(count: Int, width: Float, height: Float, originY: Float = 0.62f, rng: Random = Random.Default): List<Particle> {
        val out = mutableListOf<Particle>()
        repeat(count) {
            val angle = rng.nextDouble(-Math.PI, 0.0)
            val speed = (rng.nextFloat() * 0.55f + 0.35f) * (width / 6f)
            out.add(
                Particle(
                    x = width * rng.nextFloat(),
                    y = height * originY,
                    vx = (cos(angle) * speed).toFloat(),
                    vy = (sin(angle) * speed).toFloat(),
                    size = rng.nextFloat() * 7f + 4f,
                    color = PALETTE[rng.nextInt(PALETTE.size)],
                    spin = rng.nextFloat() * 360f,
                    phase = rng.nextFloat() * 360f,
                )
            )
        }
        return out
    }
}

/** Full-screen confetti overlay; host it at the top of AppShell with [trigger] as a counter. */
@Composable
fun ConfettiOverlay(trigger: Int, modifier: Modifier = Modifier) {
    if (trigger <= 0) return
    var particles by remember(trigger) { mutableStateOf(listOf<Confetti.Particle>()) }
    var progress by remember(trigger) { mutableStateOf(0f) }
    val allowed = remember(trigger) { Confetti.burstAllowed().also { if (it) Confetti.burstsThisSession++ } }
    val anim by animateFloatAsState(
        targetValue = if (allowed) 1f else 0f,
        animationSpec = tween(durationMillis = 1800, easing = LinearEasing),
        finishedListener = { p -> progress = p },
        label = "confetti",
    )
    if (!allowed) return

    LaunchedEffect(trigger) {
        progress = 0f
        val steps = 60
        for (i in 0..steps) {
            progress = i / steps.toFloat()
            kotlinx.coroutines.delay(30)
        }
        particles = emptyList()
    }

    Canvas(modifier.fillMaxSize()) {
        if (particles.isEmpty() && progress in 0.001f..0.999f) {
            particles = Confetti.makeParticles(150, size.width, size.height)
        }
        particles.forEach { p ->
            val t = progress
            val x = p.x + p.vx * t * 2.2f
            val y = p.y + p.vy * t * 2.2f + 1400f * t * t // gravity
            val alpha = (1f - t).coerceIn(0f, 1f)
            if (alpha > 0f && y < size.height + 40f) {
                rotate(p.spin * t + p.phase, pivot = Offset(x, y)) {
                    drawRect(
                        color = p.color.copy(alpha = alpha),
                        topLeft = Offset(x - p.size / 2, y - p.size / 4),
                        size = androidx.compose.ui.geometry.Size(p.size, p.size / 2),
                    )
                }
            }
        }
    }
}
