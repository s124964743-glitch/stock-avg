package app.stockavg

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.cos
import kotlin.math.sin

val Accent = Color(0xFFA8C6FF)
val Good = Color(0xFF2BE38C)
val Bad = Color(0xFFFF4D4D)
val Up = Color(0xFFFF3B47)   // 台股：漲 = 紅
val Down = Color(0xFF1FE07F) // 台股：跌 = 綠

/**
 * 仿 iOS「Liquid Glass」：
 *  - 背後的畫面先輕微模糊（折射前的毛玻璃）
 *  - 邊緣用 AGSL 著色器做「透鏡折射」：越靠近邊緣，背景被彎曲、拉伸，並帶有色散
 *  - 提高飽和與亮度，疊上極薄的白色染色
 *  - 光源在左上：邊緣有鏡面高光（亮邊），頂部有一層柔光
 * 需要 Android 13（API 33）以上；更舊的系統自動退回一般半透明玻璃。
 */
private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bevel;
uniform float strength;
uniform float dpx;
uniform float4 tint;

float sdRound(float2 p, float2 hb, float r) {
    float2 q = abs(p) - hb + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 fc) {
    float r = min(radius, min(size.x, size.y) * 0.5);
    float bv = min(bevel, min(size.x, size.y) * 0.45);
    float2 hb = size * 0.5;
    float2 p = fc - hb;
    float sd = sdRound(p, hb, r);
    float2 n = float2(
        sdRound(p + float2(1.0, 0.0), hb, r) - sdRound(p - float2(1.0, 0.0), hb, r),
        sdRound(p + float2(0.0, 1.0), hb, r) - sdRound(p - float2(0.0, 1.0), hb, r));
    float nl = length(n);
    n = nl > 0.0001 ? n / nl : float2(0.0, -1.0);

    float depth = clamp(-sd / bv, 0.0, 1.0);
    float edge = 1.0 - depth;
    float k = edge * edge;
    float2 off = -n * k * strength;

    half4 c0 = content.eval(fc + off);
    half4 c1 = content.eval(fc + off * 1.22);
    half4 c2 = content.eval(fc + off * 1.44);
    float3 col = float3(c0.r, c1.g, c2.b);

    float lum = dot(col, float3(0.299, 0.587, 0.114));
    col = mix(float3(lum), col, 1.0);
    col = col * 0.70 + tint.rgb * tint.a;

    float2 L = normalize(float2(-0.75, -0.65));
    float facing = dot(n, L);
    float rim = pow(edge, 3.0);
    float spec = rim * (0.40 * max(facing, 0.0) + 0.16 * max(-facing, 0.0));
    float line = 1.0 - smoothstep(0.0, 1.8 * dpx, abs(sd + 1.1 * dpx));
    spec += line * (0.14 + 0.26 * max(facing, 0.0));
    float sheen = 0.02 * (1.0 - smoothstep(0.0, 0.55, fc.y / size.y));
    col += float3(spec + sheen);

    col = clamp(col, float3(0.0), float3(1.0));
    float a = clamp(0.5 - sd, 0.0, 1.0);
    return half4(half3(col * a), half(a));
}
"""

class Backdrop(val w: Float, val h: Float, val phase: State<Float>)

val LocalBackdrop = compositionLocalOf<Backdrop?> { null }

/** 畫面最底層：黑底、緩慢飄移的彩色光暈、極淡的網格（讓玻璃折射看得出來）。 */
fun DrawScope.drawField(w: Float, h: Float, t: Float) {
    drawRect(Color.Black, size = Size(w, h))
    val a = (t * 2.0 * Math.PI).toFloat()
    fun orb(c: Color, cx: Float, cy: Float, r: Float, alpha: Float) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(c.copy(alpha = alpha), Color.Transparent),
                center = Offset(cx, cy),
                radius = r,
            ),
            radius = r,
            center = Offset(cx, cy),
        )
    }
    orb(Color(0xFF6E56FF), w * (0.12f + 0.12f * sin(a)), h * (0.10f + 0.04f * cos(a)), w * 0.95f, 0.36f)
    orb(Color(0xFF00C2FF), w * (0.92f + 0.07f * cos(a + 1f)), h * (0.34f + 0.05f * sin(a + 2f)), w * 0.85f, 0.30f)
    orb(Color(0xFFFF4FD8), w * (0.18f + 0.10f * sin(a + 3f)), h * (0.62f + 0.04f * cos(a + 1f)), w * 0.90f, 0.30f)
    orb(Color(0xFF3B6BFF), w * (0.85f + 0.07f * sin(a + 4f)), h * (0.92f + 0.03f * cos(a)), w * 0.95f, 0.34f)
    val step = 56.dp.toPx()
    val line = Color.White.copy(alpha = 0.03f)
    var x = 0f
    while (x < w) {
        drawLine(line, Offset(x, 0f), Offset(x, h), 1.5f)
        x += step
    }
    var y = 0f
    while (y < h) {
        drawLine(line, Offset(0f, y), Offset(w, y), 1.5f)
        y += step
    }
}

@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    val tr = rememberInfiniteTransition(label = "bg")
    val phase = tr.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(60000, easing = LinearEasing)),
        label = "phase",
    )
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val bd = remember(w, h) { Backdrop(w, h, phase) }
        CompositionLocalProvider(LocalBackdrop provides bd) {
            Canvas(Modifier.fillMaxSize()) { drawField(w, h, phase.value) }
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

/** 一塊液態玻璃。content 疊在折射層上面，文字保持清晰不變形。 */
@Composable
fun GlassSurface(
    modifier: Modifier,
    radius: Dp,
    tint: Color,
    content: @Composable BoxScope.() -> Unit,
) {
    val bd = LocalBackdrop.current
    val clipShape = RoundedCornerShape(radius)
    var pos by remember { mutableStateOf(Offset.Zero) }
    val lens = Build.VERSION.SDK_INT >= 33 && bd != null

    Box(
        modifier
            .onGloballyPositioned { pos = it.positionInRoot() }
            .clip(clipShape)
    ) {
        if (lens && bd != null) {
            val shader = remember { RuntimeShader(LENS) }
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        shader.setFloatUniform("size", size.width, size.height)
                        shader.setFloatUniform("radius", radius.toPx())
                        shader.setFloatUniform("bevel", 22.dp.toPx())
                        shader.setFloatUniform("strength", 16.dp.toPx())
                        shader.setFloatUniform("dpx", 1.dp.toPx())
                        shader.setFloatUniform("tint", tint.red, tint.green, tint.blue, tint.alpha)
                        val blur = RenderEffect.createBlurEffect(9.dp.toPx(), 9.dp.toPx(), Shader.TileMode.CLAMP)
                        val len = RenderEffect.createRuntimeShaderEffect(shader, "content")
                        renderEffect = RenderEffect.createChainEffect(len, blur).asComposeRenderEffect()
                        clip = true
                        this.shape = clipShape
                    }
                    .drawBehind {
                        translate(-pos.x, -pos.y) { drawField(bd.w, bd.h, bd.phase.value) }
                    }
            )
        } else {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.16f),
                                Color.White.copy(alpha = 0.05f),
                                Color.White.copy(alpha = 0.10f),
                            )
                        )
                    )
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.28f))
                        ),
                        clipShape,
                    )
            )
        }
        content()
    }
}

@Composable
fun Glass(
    modifier: Modifier = Modifier,
    radius: Dp = 32.dp,
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    GlassSurface(m, radius, Color.White.copy(alpha = if (strong) 0.04f else 0.02f)) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, shimmer: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "press",
    )
    val tint = if (primary) Color(0xFF4F6FF0).copy(alpha = 0.36f) else Color.White.copy(alpha = if (pressed) 0.10f else 0.03f)
    GlassSurface(
        modifier
            .graphicsLayer {
                scaleX = sc
                scaleY = sc
            }
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        100.dp,
        tint,
    ) {
        Box(
            Modifier.padding(horizontal = 22.dp, vertical = 14.dp).align(Alignment.Center),
            contentAlignment = Alignment.Center,
        ) {
            if (shimmer) ShimmerText(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            else Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 彩色流光文字（shimmer）：一條彩虹漸層從左掃到右，不斷循環。 */
@Composable
fun ShimmerText(text: String, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit, fontWeight: FontWeight) {
    val tr = rememberInfiniteTransition(label = "shimmer")
    val x by tr.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing)),
        label = "shimmerX",
    )
    val band = 420f
    val brush = Brush.linearGradient(
        colors = listOf(
            Color(0xFFFFFFFF), Color(0xFFFF7AD9), Color(0xFFFFE066),
            Color(0xFF6EF3FF), Color(0xFF9D8CFF), Color(0xFFFFFFFF),
        ),
        start = Offset(x * band, 0f),
        end = Offset(x * band + band, 0f),
        tileMode = TileMode.Repeated,
    )
    Text(text, modifier, fontSize = fontSize, fontWeight = fontWeight, style = TextStyle(brush = brush))
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
        shape = RoundedCornerShape(22.dp),
        keyboardOptions = KeyboardOptions(keyboardType = kind),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color.White.copy(alpha = 0.75f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.34f),
            focusedLabelColor = Color.White,
            unfocusedLabelColor = Color.White.copy(alpha = 0.78f),
            cursorColor = Color.White,
            focusedContainerColor = Color.Black.copy(alpha = 0.25f),
            unfocusedContainerColor = Color.Black.copy(alpha = 0.20f),
        ),
    )
}

@Composable
fun GlassDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0xF0080B14))
        ) {
            Glass(Modifier.fillMaxWidth(), strong = true, content = content)
        }
    }
}
