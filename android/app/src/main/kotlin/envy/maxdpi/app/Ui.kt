package envy.maxdpi.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens: rounded cards on a soft background, an indigo-to-violet gradient for the header
 * and active elements, tinted (never white-on-white) secondary buttons, a color per card icon,
 * and meaning colors for status. Every color has a light and a dark value; [dark] follows the
 * phone's setting.
 */
object Tokens {
    var dark by mutableStateOf(false)
    private fun c(light: Long, dark: Long) = Color(if (this.dark) dark else light)

    // Brand gradient (header band, selected options, main button, progress).
    val gradStart get() = c(0xFF6366F1, 0xFF818CF8)
    val gradEnd get() = c(0xFFA855F7, 0xFFC084FC)
    val headerStart get() = c(0xFF4F46E5, 0xFF3730A3)
    val headerEnd get() = c(0xFF9333EA, 0xFF7E22CE)
    val gradient get() = Brush.horizontalGradient(listOf(gradStart, gradEnd))
    val headerGradient get() = Brush.linearGradient(listOf(headerStart, headerEnd))

    val indigo get() = c(0xFF4F46E5, 0xFFA5B4FC)
    val violet get() = c(0xFF7C3AED, 0xFFC4B5FD)
    val indigoSoft get() = c(0xFFEEF2FF, 0xFF2A2650)
    val violetSoft get() = c(0xFFF3E8FF, 0xFF2E2650)
    val violetText get() = c(0xFF7E22CE, 0xFFD8B4FE)

    val white get() = c(0xFFFFFFFF, 0xFF1B1A2E)   // card surface
    val bg get() = c(0xFFF5F5FB, 0xFF0F0E1A)
    val g100 get() = c(0xFFEEF0FB, 0xFF26243D)
    val g200 get() = c(0xFFE5E7EB, 0xFF34324F)
    val g300 get() = c(0xFFD1D5DB, 0xFF4B4868)
    val g400 get() = c(0xFF9CA3AF, 0xFF8C89AA)
    val g500 get() = c(0xFF6B7280, 0xFFA5A3C4)
    val g700 get() = c(0xFF374151, 0xFFD6D4EE)
    val g900 get() = c(0xFF1E1B4B, 0xFFEDEBFF)

    // Status colors.
    val okBg get() = c(0xFFDCFCE7, 0xFF14352A); val okFg get() = c(0xFF15803D, 0xFF4ADE80)
    val warnBg get() = c(0xFFFEF3C7, 0xFF3A2E12); val warnFg get() = c(0xFFB45309, 0xFFFBBF24)
    val errBg get() = c(0xFFFFE4E6, 0xFF3D1A22); val errFg get() = c(0xFFBE123C, 0xFFFB7185)

    // Icon colors, one per kind of card.
    val pink get() = c(0xFFDB2777, 0xFFF472B6)
    val green get() = c(0xFF16A34A, 0xFF4ADE80)
    val amber get() = c(0xFFD97706, 0xFFFBBF24)
    val sky get() = c(0xFF0284C7, 0xFF38BDF8)

    val card = RoundedCornerShape(20.dp)
    val ctrl = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(999.dp)
}

@Composable
fun SoftTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    SideEffect { Tokens.dark = dark }
    Tokens.dark = dark
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Tokens.indigo, background = Tokens.bg, surface = Tokens.white, onSurface = Tokens.g900)
        else lightColorScheme(primary = Tokens.indigo, onPrimary = Color.White, background = Tokens.bg, surface = Tokens.white, onSurface = Tokens.g900),
        content = content,
    )
}

/** Soft resting shadow for cards (none in dark mode, where the lighter card stands out on its own). */
fun Modifier.softShadow(shape: androidx.compose.ui.graphics.Shape, elevation: Dp = 10.dp) =
    if (Tokens.dark) this
    else shadow(elevation, shape, clip = false, ambientColor = Color(0xFF4F46E5).copy(alpha = 0.06f), spotColor = Color(0xFF4F46E5).copy(alpha = 0.12f))

@Composable
fun SoftCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .softShadow(Tokens.card)
            .clip(Tokens.card)
            .background(Tokens.white)
            .padding(20.dp),
    ) { Column { content() } }
}

object Type {
    val title get() = TextStyle(fontSize = 24.sp, fontWeight = FontWeight(750), color = Tokens.g900, letterSpacing = (-0.6).sp)
    val stat get() = TextStyle(fontSize = 27.sp, fontWeight = FontWeight(750), color = Tokens.g900, letterSpacing = (-1).sp)
    val heading get() = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Tokens.g900)
    val body get() = TextStyle(fontSize = 14.sp, color = Tokens.g500)
    val strong get() = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Tokens.g900)
    val label get() = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Tokens.g400)
    val chip = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text, style = Type.label, modifier = modifier.padding(bottom = 8.dp))

enum class Tone { OK, BUSY, WARN, ERROR, NEUTRAL }

/** Status pill in a meaning color. */
@Composable
fun StatusPill(text: String, tone: Tone) {
    val (bg, fg) = when (tone) {
        Tone.OK -> Tokens.okBg to Tokens.okFg
        Tone.BUSY -> Tokens.indigoSoft to Tokens.indigo
        Tone.WARN -> Tokens.warnBg to Tokens.warnFg
        Tone.ERROR -> Tokens.errBg to Tokens.errFg
        Tone.NEUTRAL -> Tokens.g100 to Tokens.g500
    }
    Text(
        text, style = Type.chip, color = fg,
        modifier = Modifier.clip(Tokens.pill).background(bg).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** A row of pill choices; the selected one is filled with the brand gradient. */
@Composable
fun <T> PillChoice(
    options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit,
    enabled: Boolean = true, modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().clip(Tokens.pill).background(Tokens.g100).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, text) ->
            val isSel = value == selected && enabled
            Box(
                Modifier
                    .weight(1f)
                    .clip(Tokens.pill)
                    .then(if (isSel) Modifier.background(Tokens.gradient) else Modifier)
                    .clickable(enabled = enabled) { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text, fontSize = 13.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                    color = when { !enabled -> Tokens.g300; isSel -> Color.White; else -> Tokens.g500 },
                )
            }
        }
    }
}

/** The main action: gradient pill with a violet glow. */
@Composable
fun PrimaryButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(if (enabled && !Tokens.dark) Modifier.shadow(16.dp, Tokens.pill, ambientColor = Tokens.violet.copy(alpha = 0.3f), spotColor = Tokens.violet.copy(alpha = 0.45f)) else Modifier)
            .clip(Tokens.pill)
            .then(if (enabled) Modifier.background(Tokens.gradient) else Modifier.background(Tokens.g200))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.White else Tokens.g400, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** Secondary button: tinted violet fill, so it stays visible on white cards. */
@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .clip(Tokens.pill)
            .background(if (enabled) Tokens.violetSoft else Tokens.g100)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Tokens.violetText else Tokens.g300, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
    }
}

/** Squircle icon bubble; [accent] colors its tint (defaults to indigo, or gray when [tint] is false). */
@Composable
fun Bubble(
    size: Dp = 40.dp, tint: Boolean = true, accent: Color? = null,
    onClick: (() -> Unit)? = null, content: @Composable () -> Unit,
) {
    val bg = when {
        accent != null -> accent.copy(alpha = if (Tokens.dark) 0.22f else 0.14f)
        tint -> Tokens.indigoSoft
        else -> Tokens.g100
    }
    Box(
        Modifier.size(size).clip(Tokens.ctrl).background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Card title row with a colored icon bubble. */
@Composable
fun CardTitle(icon: Painter, accent: Color, title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Bubble(34.dp, accent = accent) { Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp)) }
        Text(title, style = Type.heading, modifier = Modifier.weight(1f).padding(start = 10.dp))
        trailing?.invoke()
    }
}

/** Pill progress bar with the brand gradient. */
@Composable
fun SoftProgress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(Tokens.pill).background(Tokens.g100)) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(height).clip(Tokens.pill).background(Tokens.gradient))
        }
    }
}

/** Text input with an indigo border and soft ring when focused. */
@Composable
fun SoftField(
    value: String, onValueChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, keyboardType: KeyboardType = KeyboardType.Text,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = Tokens.g900),
        cursorBrush = SolidColor(Tokens.indigo),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier
                    .then(if (focused) Modifier.border(3.dp, Tokens.indigoSoft, Tokens.ctrl) else Modifier)
                    .border(1.5.dp, if (focused) Tokens.indigo else Tokens.g200, Tokens.ctrl)
                    .clip(Tokens.ctrl)
                    .background(Tokens.white)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, style = TextStyle(fontSize = 14.sp, color = Tokens.g400))
                inner()
            }
        },
    )
}

/** − value + stepper with squircle buttons. */
@Composable
fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Bubble(36.dp, tint = false, onClick = { if (value > range.first) onChange(value - 1) }) {
            Text("−", fontSize = 18.sp, color = if (value > range.first) Tokens.g700 else Tokens.g300, fontWeight = FontWeight.Bold)
        }
        Text("$value", style = Type.heading, modifier = Modifier.padding(horizontal = 4.dp))
        Bubble(36.dp, accent = Tokens.violet, onClick = { if (value < range.last) onChange(value + 1) }) {
            Text("+", fontSize = 18.sp, color = Tokens.violet, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun RowScope.Spacer1() = androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(Tokens.g100))

// ---- Gradient header, glass elements, tab bar --------------------------------------------

/**
 * The gradient band at the top of every tab, drawn under the status bar. [tiles] go below the
 * title as glass tiles. Content below should overlap the band's bottom edge by [OVERLAP].
 */
@Composable
fun GradientHeader(
    title: String, subtitle: String, icon: Painter,
    trailing: (@Composable () -> Unit)? = null,
    tiles: (@Composable RowScope.() -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().background(Tokens.headerGradient)) {
        // Soft decorative circles.
        Box(Modifier.size(170.dp).offset(x = 250.dp, y = (-60).dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)))
        Box(Modifier.size(110.dp).offset(x = (-40).dp, y = 130.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f)))
        Column(Modifier.statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = OVERLAP + 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(Tokens.ctrl).background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(title, style = Type.title.copy(color = Color.White), maxLines = 1)
                    Text(subtitle, fontSize = 12.5.sp, color = Color.White.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                trailing?.invoke()
            }
            if (tiles != null) {
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { tiles() }
            }
        }
    }
}

val OVERLAP = 26.dp

/** Gradient strip behind the status bar, so scrolled content never runs under the (light) status icons. */
@Composable
fun StatusBarScrim() {
    Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Tokens.headerGradient))
}

/** Semi-transparent tile on the gradient: a value and a caption. */
@Composable
fun RowScope.GlassTile(value: String, caption: String, dot: Color? = null) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.16f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(dot))
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(caption, fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Semi-transparent pill button on the gradient. */
@Composable
fun GlassButton(text: String, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(Tokens.pill).background(Color.White.copy(alpha = 0.2f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), Tokens.pill).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Compact bottom tab bar. */
@Composable
fun <T> TabBar(tabs: List<Triple<T, String, Painter>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Tokens.bg).navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp)
            .softShadow(RoundedCornerShape(24.dp), 8.dp).clip(RoundedCornerShape(24.dp)).background(Tokens.white)
            .padding(6.dp),
    ) {
        tabs.forEach { (value, label, icon) ->
            val on = value == selected
            val color by animateColorAsState(if (on) Tokens.violet else Tokens.g400, label = "tab")
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                    // No ripple/focus box: the gradient pill already shows the selected tab.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(value) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(width = 44.dp, height = 28.dp).clip(Tokens.pill)
                        .then(if (on) Modifier.background(Tokens.gradient) else Modifier),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, label, tint = if (on) Color.White else Tokens.g400, modifier = Modifier.size(18.dp)) }
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}
