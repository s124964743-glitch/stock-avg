package app.stockavg

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

val Accent = Color(0xFF8FB5FF)
val Good = Color(0xFF3ECF8E)
val Bad = Color(0xFFFF6B64)
val Up = Color(0xFFFF5A64)   // 台股：漲 = 紅
val Down = Color(0xFF2ED47A) // 台股：跌 = 綠

/** 黑底 + 幾團柔和的彩色光暈，讓玻璃卡片有東西可以折射。 */
@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            fun orb(c: Color, x: Float, y: Float, r: Float) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(c.copy(alpha = 0.38f), Color.Transparent),
                        center = Offset(x, y),
                        radius = r,
                    ),
                    radius = r,
                    center = Offset(x, y),
                )
            }
            orb(Color(0xFF6E56FF), size.width * 0.05f, size.height * 0.06f, size.width * 0.95f)
            orb(Color(0xFF00C2FF), size.width * 1.0f, size.height * 0.38f, size.width * 0.85f)
            orb(Color(0xFFFF4FD8), size.width * 0.15f, size.height * 0.85f, size.width * 0.95f)
        }
        content()
    }
}

@Composable
fun Glass(
    modifier: Modifier = Modifier,
    radius: Dp = 26.dp,
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val a = if (strong) 1.4f else 1f
    var m = modifier
        .clip(shape)
        .background(
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.16f * a),
                    Color.White.copy(alpha = 0.05f * a),
                    Color.White.copy(alpha = 0.10f * a),
                )
            )
        )
        .border(
            1.dp,
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.60f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.28f))
            ),
            shape,
        )
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(16.dp), content = content)
}

@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val fill = if (primary)
        Brush.linearGradient(listOf(Color(0xCC6E56FF), Color(0xCC00A6E8)))
    else
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.06f)))
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .border(
                1.dp,
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.10f))),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    kind: KeyboardType = KeyboardType.Decimal,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = kind),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Accent,
            unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
            focusedLabelColor = Accent,
            unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
            cursorColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.06f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
        ),
    )
}

@Composable
fun GlassDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xF0080B14))
        ) {
            Glass(Modifier.fillMaxWidth(), strong = true, content = content)
        }
    }
}
