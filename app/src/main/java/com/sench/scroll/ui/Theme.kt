package com.sench.scroll.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sench.scroll.R
import kotlinx.coroutines.delay
import kotlin.random.Random

object C {
    val bg = Color(0xFF070607)
    val panel = Color(0xFF0D0A0B)
    val card = Color(0xFF130D0F)
    val card2 = Color(0xFF1B1114)
    val line = Color(0xFF2C1519)
    val red = Color(0xFFFF1F3D)
    val redDim = Color(0xFF7A0E1C)
    val redGlow = Color(0x33FF1F3D)
    val cyan = Color(0xFF19E6FF)
    val text = Color(0xFFF4ECEE)
    val sub = Color(0xFF9C8A8E)
    val faint = Color(0xFF5E4E52)
    val ok = Color(0xFF46E89A)
}

val Glitch = FontFamily(Font(R.font.rubik_glitch))
val Mono = FontFamily.Monospace

@Composable
fun SenchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.red, onPrimary = C.bg, background = C.bg, onBackground = C.text,
            surface = C.card, onSurface = C.text, secondary = C.cyan, outline = C.line,
            surfaceVariant = C.card2, onSurfaceVariant = C.sub, error = C.red,
        ),
        content = content,
    )
}

/** Полоски как на старом мониторе. */
fun Modifier.scanlines(alpha: Float = 0.16f) = drawWithContent {
    drawContent()
    val step = 3.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawLine(Color.Black.copy(alpha = alpha), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

/** Глитч-заголовок: красная и голубая тени дёргаются. */
@Composable
fun GlitchText(text: String, size: TextUnit, modifier: Modifier = Modifier, animate: Boolean = true) {
    var dx by remember { mutableFloatStateOf(2f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var slice by remember { mutableStateOf(false) }
    if (animate) LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(900, 2600))
            repeat(Random.nextInt(3, 7)) {
                dx = Random.nextInt(-6, 7).toFloat(); dy = Random.nextInt(-2, 3).toFloat(); slice = Random.nextBoolean()
                delay(Random.nextLong(40, 90))
            }
            dx = 2f; dy = 0f; slice = false
        }
    }
    val style = TextStyle(fontFamily = Glitch, fontSize = size, letterSpacing = 1.sp)
    Box(modifier) {
        Text(text, style = style, color = C.cyan.copy(alpha = 0.75f), modifier = Modifier.offset((-dx).dp, (-dy).dp))
        Text(text, style = style, color = C.red, modifier = Modifier.offset(dx.dp, dy.dp))
        Text(text, style = style, color = C.text, modifier = if (slice) Modifier.offset((dx / 2).dp, 0.dp) else Modifier)
    }
}

@Composable
fun BlinkCursor() {
    val t = rememberInfiniteTransition(label = "c")
    val a by t.animateFloat(1f, 0f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Text("_", color = C.red, fontFamily = Mono, modifier = Modifier.alpha(if (a > 0.5f) 1f else 0f))
}

@Composable
fun Label(text: String, color: Color = C.red) =
    Text("// " + text.uppercase(), color = color, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)

@Composable
fun GCard(label: String? = null, modifier: Modifier = Modifier, accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CutCornerShape(topEnd = 14.dp))
            .background(if (accent) C.card2 else C.card)
            .border(BorderStroke(1.dp, if (accent) C.redDim else C.line), CutCornerShape(topEnd = 14.dp))
            .padding(14.dp),
    ) {
        if (label != null) { Label(label); Spacer(Modifier.height(10.dp)) }
        content()
    }
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, outline: Boolean = false, onClick: () -> Unit) {
    val shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(if (outline) C.bg else if (enabled) C.red else C.redDim)
            .border(1.dp, if (outline) C.red else Color.Transparent, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .scanlines(if (outline) 0f else 0.12f),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (outline) C.red else C.bg, fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 17.sp, letterSpacing = 2.sp)
    }
}

@Composable
fun SmallButton(text: String, modifier: Modifier = Modifier, filled: Boolean = false, onClick: () -> Unit) {
    val shape = CutCornerShape(topStart = 7.dp, bottomEnd = 7.dp)
    Box(
        modifier.clip(shape).background(if (filled) C.red else Color.Transparent)
            .border(1.dp, C.red, shape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (filled) C.bg else C.red, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp)
    Box(
        modifier.clip(shape).background(if (selected) C.red else C.panel)
            .border(1.dp, if (selected) C.red else C.line, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) C.bg else C.text, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}

@Composable
fun Tag(text: String, color: Color = C.red) {
    Box(Modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = color, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontSize = 15.sp)
            if (sub != null) Text(sub, color = C.sub, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = C.bg, checkedTrackColor = C.red, checkedBorderColor = C.red,
                uncheckedThumbColor = C.sub, uncheckedTrackColor = C.panel, uncheckedBorderColor = C.line,
            ),
        )
    }
}

@Composable
fun StatusRow(title: String, value: String, good: Boolean, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(1.dp)).background(if (good) C.ok else C.red))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.sub, fontFamily = Mono, fontSize = 11.sp)
            Text(value, color = C.text, fontSize = 14.sp)
        }
        if (action != null) SmallButton(action, filled = !good, onClick = onAction)
    }
}

/** Шкала «имба-метр» из 10 делений. */
@Composable
fun HypeBar(v: Int, likes: Int = -1) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("ИМБА-МЕТР ", color = C.sub, fontFamily = Mono, fontSize = 10.sp)
        repeat(10) { i -> Box(Modifier.size(width = 7.dp, height = 10.dp).background(if (i < v) C.red else C.line)) }
        Text(" $v/10", color = C.text, fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (likes >= 0) Text("  ❤ " + com.sench.scroll.Hype.short(likes), color = C.sub, fontFamily = Mono, fontSize = 10.sp)
    }
}
