package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ai.CoreState
import com.example.ui.theme.BumbleAmber
import com.example.ui.theme.BumbleChampagne
import com.example.ui.theme.BumbleCrimson
import com.example.ui.theme.BumbleGold
import com.example.ui.theme.BumbleGoldBright
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class OrbitParticle(
    val baseAngleDeg: Float,
    val radiusFraction: Float,
    val sizeDp: Float,
    val speedMultiplier: Float,
    val alphaBase: Float
)

/**
 * Official Bumble AI Golden Holographic Core.
 * Renders multi-layer rotating HUD rings, circular audio-reactive waveform,
 * glowing energy particles, and radiant central nucleus.
 */
@Composable
fun HolographicCore(
    coreState: CoreState,
    audioAmplitude: Float,
    animationsEnabled: Boolean,
    particleDensity: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val smoothedAmp by animateFloatAsState(
        targetValue = audioAmplitude.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
        label = "amp"
    )

    val primaryRingColor by animateColorAsState(
        targetValue = when (coreState) {
            CoreState.ERROR -> BumbleCrimson
            CoreState.THINKING -> BumbleGoldBright
            CoreState.SPEAKING -> BumbleChampagne
            else -> BumbleGold
        },
        animationSpec = tween(350),
        label = "ringColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "bumbleCore")

    val slowAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "slowAngle"
    )

    val fastAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fastAngle"
    )

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val particles = remember {
        List(28) { idx ->
            OrbitParticle(
                baseAngleDeg = (idx * 360f / 28f) + ((idx * 17) % 23),
                radiusFraction = 0.34f + ((idx * 13) % 10) * 0.015f,
                sizeDp = 1.4f + ((idx * 7) % 4) * 0.65f,
                speedMultiplier = if (idx % 2 == 0) 1f else -1.35f,
                alphaBase = 0.38f + ((idx * 11) % 5) * 0.11f
            )
        }
    }

    val stateDesc = when (coreState) {
        CoreState.IDLE -> "Bumble Core Idle. Tap to speak to Gemini."
        CoreState.LISTENING -> "Bumble Core Listening. Tap to stop listening."
        CoreState.THINKING -> "Bumble Core Thinking."
        CoreState.SPEAKING -> "Bumble Core Speaking. Tap to stop speaking."
        CoreState.ERROR -> "Bumble Core Error. Tap to configure or retry."
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .testTag("holographic_core_button")
            .semantics { contentDescription = stateDesc }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, color = BumbleGoldBright),
                role = Role.Button,
                onClick = onClick
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val minDim = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)

            val animScale = if (animationsEnabled) {
                when (coreState) {
                    CoreState.IDLE -> 0.98f + (pulse - 1f) * 0.4f
                    CoreState.LISTENING -> 1.0f + (pulse - 1f) * 0.7f + smoothedAmp * 0.08f
                    CoreState.THINKING -> 1.02f + (pulse - 1f) * 0.9f
                    CoreState.SPEAKING -> 1.02f + smoothedAmp * 0.14f
                    CoreState.ERROR -> 0.97f
                }
            } else {
                1.0f
            }

            val outerRadius = minDim * 0.44f
            val midRadius = minDim * 0.34f
            val waveRadius = minDim * 0.255f
            val nucleusRadius = minDim * 0.155f * animScale

            val rotMultiplier = when (coreState) {
                CoreState.THINKING -> 2.1f
                CoreState.SPEAKING -> 1.5f
                CoreState.LISTENING -> 1.25f
                else -> 1.0f
            }
            val cwAngle = if (animationsEnabled) (slowAngle * rotMultiplier) % 360f else 0f
            val ccwAngle = if (animationsEnabled) (fastAngle * rotMultiplier) % 360f else 0f

            // 1. Ambient Holographic Golden Halo
            val haloColor = if (coreState == CoreState.ERROR) BumbleCrimson else BumbleAmber
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        haloColor.copy(alpha = 0.28f + smoothedAmp * 0.24f),
                        BumbleGold.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = outerRadius * 1.15f
                ),
                radius = outerRadius * 1.15f,
                center = center
            )

            // 2. Outer Segmented HUD Arcs
            rotate(degrees = cwAngle, pivot = center) {
                val arcSize = Size(outerRadius * 2f, outerRadius * 2f)
                val arcTopLeft = Offset(center.x - outerRadius, center.y - outerRadius)
                val arcStroke = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                val segments = listOf(12f to 68f, 102f to 58f, 192f to 74f, 288f to 52f)
                segments.forEach { (start, sweep) ->
                    drawArc(
                        color = primaryRingColor.copy(alpha = 0.68f),
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = arcStroke
                    )
                }
                // Faint precision hairline ring
                drawCircle(
                    color = primaryRingColor.copy(alpha = 0.18f),
                    radius = outerRadius * 1.05f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // 3. Counter-Rotating Telemetry Ring with Radial Ticks
            rotate(degrees = ccwAngle, pivot = center) {
                val midArcSize = Size(midRadius * 2f, midRadius * 2f)
                val midTopLeft = Offset(center.x - midRadius, center.y - midRadius)
                drawArc(
                    color = BumbleGoldBright.copy(alpha = 0.45f),
                    startAngle = 25f,
                    sweepAngle = 125f,
                    useCenter = false,
                    topLeft = midTopLeft,
                    size = midArcSize,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = BumbleGoldBright.copy(alpha = 0.45f),
                    startAngle = 205f,
                    sweepAngle = 125f,
                    useCenter = false,
                    topLeft = midTopLeft,
                    size = midArcSize,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                val tickCount = 36
                for (i in 0 until tickCount) {
                    val rad = (i * 10.0) * PI / 180.0
                    val innerR = midRadius * 0.93f
                    val outerR = if (i % 3 == 0) midRadius * 1.04f else midRadius * 0.99f
                    val p1 = Offset(
                        center.x + (innerR * cos(rad)).toFloat(),
                        center.y + (innerR * sin(rad)).toFloat()
                    )
                    val p2 = Offset(
                        center.x + (outerR * cos(rad)).toFloat(),
                        center.y + (outerR * sin(rad)).toFloat()
                    )
                    drawLine(
                        color = primaryRingColor.copy(alpha = if (i % 3 == 0) 0.55f else 0.22f),
                        start = p1,
                        end = p2,
                        strokeWidth = if (i % 3 == 0) 1.5.dp.toPx() else 0.9.dp.toPx()
                    )
                }
            }

            // 4. Animated Circular Audio-Reactive Waveform
            val barCount = 64
            val waveIntensity = when (coreState) {
                CoreState.SPEAKING -> 0.45f + smoothedAmp * 0.95f
                CoreState.LISTENING -> 0.22f + smoothedAmp * 0.85f
                CoreState.THINKING -> 0.36f
                CoreState.ERROR -> 0.14f
                CoreState.IDLE -> 0.08f
            }
            for (i in 0 until barCount) {
                val angle = (i.toFloat() / barCount) * 2f * PI.toFloat()
                val harmonic = (
                    sin(angle * 4f + wavePhase) * 0.45f +
                        cos(angle * 7f - wavePhase * 1.4f) * 0.35f +
                        0.5f
                    ).coerceIn(0.15f, 1.25f)
                val barLen = minDim * 0.055f * waveIntensity * harmonic
                val rStart = waveRadius - barLen * 0.25f
                val rEnd = waveRadius + barLen
                val startPt = Offset(center.x + rStart * cos(angle), center.y + rStart * sin(angle))
                val endPt = Offset(center.x + rEnd * cos(angle), center.y + rEnd * sin(angle))
                drawLine(
                    color = if (coreState == CoreState.ERROR) {
                        BumbleCrimson.copy(alpha = 0.65f)
                    } else {
                        BumbleGoldBright.copy(alpha = (0.35f + waveIntensity * 0.55f).coerceAtMost(0.95f))
                    },
                    start = startPt,
                    end = endPt,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 5. Glowing Orbiting Particles
            val activeParticleCount = (particles.size * particleDensity.coerceIn(0f, 1.5f)).toInt()
                .coerceIn(0, particles.size)
            for (i in 0 until activeParticleCount) {
                val p = particles[i]
                val thetaDeg = p.baseAngleDeg + (cwAngle * p.speedMultiplier)
                val thetaRad = thetaDeg * PI / 180.0
                val orbitR = minDim * p.radiusFraction * (1f + smoothedAmp * 0.08f)
                val pos = Offset(
                    center.x + (orbitR * cos(thetaRad)).toFloat(),
                    center.y + (orbitR * sin(thetaRad)).toFloat()
                )
                val brightnessBoost = if (coreState == CoreState.THINKING || coreState == CoreState.SPEAKING) 0.35f else 0f
                val alpha = (p.alphaBase + brightnessBoost).coerceIn(0f, 1f)
                val pColor = if (coreState == CoreState.ERROR) BumbleCrimson else BumbleGoldBright
                drawCircle(
                    color = pColor.copy(alpha = alpha * 0.35f),
                    radius = p.sizeDp.dp.toPx() * 2.2f,
                    center = pos
                )
                drawCircle(
                    color = BumbleChampagne.copy(alpha = alpha),
                    radius = p.sizeDp.dp.toPx(),
                    center = pos
                )
            }

            // 6. Central Radiant Golden Core Orb
            val coreGlowColors = if (coreState == CoreState.ERROR) {
                listOf(
                    BumbleChampagne.copy(alpha = 0.8f),
                    BumbleCrimson.copy(alpha = 0.92f),
                    BumbleAmber.copy(alpha = 0.6f)
                )
            } else {
                listOf(
                    BumbleChampagne,
                    BumbleGoldBright,
                    BumbleGold,
                    BumbleAmber.copy(alpha = 0.85f)
                )
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = coreGlowColors,
                    center = Offset(center.x - nucleusRadius * 0.18f, center.y - nucleusRadius * 0.18f),
                    radius = nucleusRadius * 1.15f
                ),
                radius = nucleusRadius,
                center = center
            )

            // Crisp inner golden energy rim
            drawCircle(
                color = BumbleChampagne.copy(alpha = 0.65f),
                radius = nucleusRadius * 1.08f,
                center = center,
                style = Stroke(width = 1.4.dp.toPx())
            )
        }
    }
}
