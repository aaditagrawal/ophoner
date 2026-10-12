package dev.ophoner.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ophoner.ui.theme.LocalUiPrefs
import dev.ophoner.ui.theme.MascotStyle
import dev.ophoner.ui.theme.tokens
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * The Ophoner mascot: a flat, round kawaii blob. Expressions carry state — idle (blinking
 * pill eyes and a smile), happy `^ ^`, working (glancing at its prop), sleepy, curious,
 * and failed `>_<` — and a prop chip names the action (magnifier, terminal, globe…).
 */
enum class MascotFace {
    /** Idle: pill eyes, small smile (blinks). */
    Open,
    /** Done / delighted: `^ ^` with an open grin. */
    Happy,
    /** Playful: one eye closed, side smirk. */
    Wink,
    /** Working: glancing at its prop, small `o` mouth. */
    Focus,
    /** Pondering: eyes up, slanted "hmm" mouth. */
    Thinking,
    /** Looking things up: cat mouth `ω`. */
    Curious,
    /** Big eyes, round `O` mouth. */
    Surprised,
    /** Reading: half-lidded eyes looking down, flat mouth. */
    Reading,
    /** Writing: concentrating, tongue out. */
    Writing,
    /** Dozing: closed eyes, tiny snore. */
    Sleepy,
    /** Failed: `>_<` with a wobbly mouth. */
    Error,
}

private val EyeInk = Color(0xFF16181D)

/** Faces with open eyes blink by default. */
private val BlinkingFaces = setOf(
    MascotFace.Open, MascotFace.Curious, MascotFace.Surprised, MascotFace.Thinking,
    MascotFace.Focus, MascotFace.Writing, MascotFace.Wink,
)

@Composable
fun Mascot(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    face: MascotFace = MascotFace.Open,
    color: Color = tokens().accent,
    blink: Boolean = face in BlinkingFaces,
    bounce: Boolean = false,
    @DrawableRes prop: Int? = null,
    contentDescription: String? = null,
) {
    val style = LocalUiPrefs.current.mascotStyle

    // Natural blinking: irregular gaps, occasionally a double blink. Only runs when asked,
    // so static mascots in long chat lists cost nothing per frame.
    val eyeOpen = remember { Animatable(1f) }
    if (blink) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(Random.nextLong(2_200, 5_200))
                repeat(if (Random.nextFloat() < 0.22f) 2 else 1) {
                    eyeOpen.animateTo(0.08f, tween(70))
                    eyeOpen.animateTo(1f, tween(120))
                    delay(90)
                }
            }
        }
    }

    val squash = if (bounce) {
        val transition = rememberInfiniteTransition(label = "mascot-bounce")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "squash",
        ).value
    } else {
        0f
    }

    Box(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription }
                else Modifier,
            ),
    ) {
        Canvas(
            modifier = Modifier
                .size(size)
                .then(
                    if (bounce) {
                        Modifier.graphicsLayer {
                            // Squash on landing, stretch at the top of the hop.
                            scaleX = 1f + 0.05f * squash
                            scaleY = 1f - 0.06f * squash
                            translationY = -this.size.height * 0.06f * (1f - squash)
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            drawBody(color, style)
            drawFace(face, eyeOpen.value, style)
        }
        if (prop != null) {
            PropChip(
                prop = prop,
                size = size * 0.48f,
                tint = if (face == MascotFace.Error) tokens().danger else color,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = size * 0.12f, y = size * 0.08f),
            )
        }
    }
}

@Composable
private fun PropChip(
    @DrawableRes prop: Int,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val t = tokens()
    Box(
        modifier = modifier
            .size(size)
            .shadow(3.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(0.12f), spotColor = Color.Black.copy(0.12f))
            .clip(CircleShape)
            .background(t.raised)
            .border(1.dp, t.line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(icon = prop, contentDescription = null, tint = lerp(tint, t.ink, 0.15f), size = size * 0.58f)
    }
}

/** Flat, fully 2D body: a solid disc, optionally with a white sticker edge. */
private fun DrawScope.drawBody(color: Color, style: MascotStyle) {
    val r = size.minDimension / 2f
    when (style) {
        MascotStyle.SOFT -> drawCircle(color = color, radius = r, center = center)
        MascotStyle.STICKER -> {
            drawCircle(color = Color.White, radius = r, center = center)
            drawCircle(color = color, radius = r * 0.88f, center = center)
        }
    }
}

private val Tongue = Color(0xFFFF7A8A)

/**
 * Kawaii face in flat shapes. Eyes set the mood; the mouth does most of the talking.
 * [eyeOpen] squashes open eyes for blinking.
 */
private fun DrawScope.drawFace(face: MascotFace, eyeOpen: Float, style: MascotStyle) {
    val r = size.minDimension / 2f * (if (style == MascotStyle.STICKER) 0.88f else 1f)
    val cx = center.x
    // Big features, set a little low on the face.
    val eyeY = center.y + r * 0.0f
    val mouthY = eyeY + r * 0.34f
    val gap = r * 0.33f
    val ink = EyeInk
    val line = Stroke(width = r * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val eyeW = r * 0.19f
    val eyeH = r * 0.29f

    // — Eyes —
    fun pill(c: Offset, open: Float, scale: Float = 1f, catchlight: Boolean = true) {
        val w = eyeW * scale
        val h = eyeH * scale
        scale(scaleX = 1f, scaleY = open.coerceAtLeast(0.12f), pivot = c) {
            drawRoundRect(ink, Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(w / 2))
        }
        if (catchlight && open > 0.6f) {
            drawCircle(Color.White, radius = w * 0.22f, center = Offset(c.x + w * 0.14f, c.y - h * 0.20f))
        }
    }
    fun pills(look: Offset = Offset.Zero, scale: Float = 1f, open: Float = eyeOpen) {
        for (side in listOf(-1f, 1f)) pill(Offset(cx + side * gap, eyeY) + look, open, scale)
    }
    fun arc(c: Offset, up: Boolean) {
        val w = r * 0.26f
        drawArc(
            color = ink,
            startAngle = if (up) 200f else 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(c.x - w / 2, c.y - w / 2 + if (up) w * 0.25f else -w * 0.25f),
            size = Size(w, w),
            style = line,
        )
    }
    fun arcs(up: Boolean) {
        for (side in listOf(-1f, 1f)) arc(Offset(cx + side * gap, eyeY), up)
    }
    fun lidded(look: Offset) {
        // Half-lidded: a short pill capped by a flat lid line.
        for (side in listOf(-1f, 1f)) {
            val c = Offset(cx + side * gap, eyeY) + look
            val w = eyeW
            val h = eyeH * 0.55f
            drawRoundRect(ink, Offset(c.x - w / 2, c.y - h / 2 + eyeH * 0.12f), Size(w, h), CornerRadius(w / 2))
            drawLine(ink, Offset(c.x - w * 0.85f, c.y - eyeH * 0.05f), Offset(c.x + w * 0.85f, c.y - eyeH * 0.05f), line.width, StrokeCap.Round)
        }
    }
    fun squeezed() {
        for (side in listOf(-1f, 1f)) {
            val c = Offset(cx + side * gap, eyeY)
            val e = r * 0.11f
            drawPath(
                Path().apply {
                    moveTo(c.x - side * e, c.y - e)
                    lineTo(c.x + side * e, c.y)
                    lineTo(c.x - side * e, c.y + e)
                },
                ink,
                style = line,
            )
        }
    }

    // — Mouths —
    fun smile(width: Float = 0.24f, dx: Float = 0f) {
        val w = r * width
        drawArc(ink, 20f, 140f, false, Offset(cx + dx - w / 2, mouthY - w * 0.55f), Size(w, w * 0.8f), style = line)
    }
    fun grin() {
        // Open D-shaped grin with a little tongue.
        val w = r * 0.34f
        val top = mouthY - w * 0.28f
        drawArc(ink, 0f, 180f, true, Offset(cx - w / 2, top - w / 2), Size(w, w))
        drawArc(Tongue, 20f, 140f, true, Offset(cx - w * 0.26f, top + w * 0.06f), Size(w * 0.52f, w * 0.40f))
    }
    fun smirk() {
        val w = r * 0.22f
        drawArc(ink, 10f, 110f, false, Offset(cx + r * 0.02f - w / 2, mouthY - w * 0.55f), Size(w, w * 0.8f), style = line)
    }
    fun catMouth() {
        // ω — two little bumps.
        val w = r * 0.13f
        for (side in listOf(-1f, 1f)) {
            drawArc(ink, 0f, 180f, false, Offset(cx + side * w / 2 - w / 2, mouthY - w * 0.6f), Size(w, w), style = line)
        }
    }
    fun oMouth(radius: Float, dx: Float = 0f, ring: Boolean = false) {
        val c = Offset(cx + dx, mouthY)
        if (ring) drawCircle(ink, radius = radius, center = c, style = line)
        else drawCircle(ink, radius = radius, center = c)
    }
    fun flat(width: Float = 0.16f, tilt: Float = 0f) {
        val w = r * width
        drawLine(ink, Offset(cx - w / 2, mouthY - tilt), Offset(cx + w / 2, mouthY + tilt), line.width, StrokeCap.Round)
    }
    fun tongueOut() {
        val w = r * 0.24f
        drawArc(Tongue, 0f, 180f, true, Offset(cx + r * 0.03f - w * 0.22f, mouthY - w * 0.18f), Size(w * 0.44f, w * 0.52f))
        drawLine(ink, Offset(cx - w / 2, mouthY - w * 0.05f), Offset(cx + w / 2, mouthY - w * 0.05f), line.width, StrokeCap.Round)
    }
    fun wavy() {
        val w = r * 0.30f
        drawPath(
            Path().apply {
                moveTo(cx - w / 2, mouthY)
                quadraticTo(cx - w / 4, mouthY - r * 0.07f, cx, mouthY)
                quadraticTo(cx + w / 4, mouthY + r * 0.07f, cx + w / 2, mouthY)
            },
            ink,
            style = line,
        )
    }

    when (face) {
        MascotFace.Open -> { pills(); smile() }
        MascotFace.Happy -> { arcs(up = true); grin() }
        MascotFace.Wink -> {
            pill(Offset(cx - gap, eyeY), eyeOpen)
            arc(Offset(cx + gap, eyeY), up = true)
            smirk()
        }
        MascotFace.Focus -> {
            val look = Offset(r * 0.07f, r * 0.05f)
            pills(look = look)
            oMouth(r * 0.06f, dx = look.x)
        }
        MascotFace.Thinking -> { pills(look = Offset(-r * 0.06f, -r * 0.08f)); flat(width = 0.18f, tilt = r * 0.04f) }
        MascotFace.Curious -> { pills(); catMouth() }
        MascotFace.Surprised -> { pills(scale = 1.18f); oMouth(r * 0.09f, ring = true) }
        MascotFace.Reading -> { lidded(Offset(0f, r * 0.03f)); flat(width = 0.12f) }
        MascotFace.Writing -> { pills(look = Offset(r * 0.06f, r * 0.06f)); tongueOut() }
        MascotFace.Sleepy -> { arcs(up = false); oMouth(r * 0.045f, dx = r * 0.02f) }
        MascotFace.Error -> { squeezed(); wavy() }
    }
}

/** Face a working mascot makes for each tool while the call runs. */
fun workingFaceForTool(name: String): MascotFace = when (name) {
    "web_search", "web_fetch" -> MascotFace.Curious
    "file_read", "file_list", "shared_files", "skill_load", "notifications", "calendar", "contacts" -> MascotFace.Reading
    "file_write" -> MascotFace.Writing
    "screen_capture" -> MascotFace.Surprised
    else -> MascotFace.Focus
}

/** Mascot prop for each agent tool, so every action reads at a glance. */
@DrawableRes
fun propForTool(name: String): Int = when (name) {
    "file_read" -> Lucide.FileText
    "file_write" -> Lucide.FilePen
    "file_list", "shared_files" -> Lucide.FolderOpen
    "file_delete" -> Lucide.Trash
    "file_move" -> Lucide.FileSymlink
    "web_search" -> Lucide.Search
    "web_fetch" -> Lucide.Globe
    "shell_execute" -> Lucide.Terminal
    "device_control", "device_capabilities" -> Lucide.Smartphone
    "screen_control" -> Lucide.Pointer
    "screen_capture" -> Lucide.ScanEye
    "intent_launch" -> Lucide.Rocket
    "app_list" -> Lucide.LayoutGrid
    "skill_load" -> Lucide.BookOpen
    "calendar" -> Lucide.Calendar
    "contacts" -> Lucide.Contact
    "location" -> Lucide.MapPin
    "media" -> Lucide.Image
    "notifications" -> Lucide.Bell
    "phone_actions" -> Lucide.Phone
    "advanced_device" -> Lucide.Shield
    else -> Lucide.Wrench
}
