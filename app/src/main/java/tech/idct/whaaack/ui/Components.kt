package tech.idct.whaaack.ui

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.idct.whaaack.game.GameAssets
import tech.idct.whaaack.ui.theme.AccentDark
import tech.idct.whaaack.ui.theme.AccentInk
import tech.idct.whaaack.ui.theme.AccentLight
import tech.idct.whaaack.ui.theme.AccentShadow
import tech.idct.whaaack.ui.theme.Cream
import tech.idct.whaaack.ui.theme.CreamDim
import tech.idct.whaaack.ui.theme.DangerSoft
import tech.idct.whaaack.ui.theme.Hairline
import tech.idct.whaaack.ui.theme.PanelNavy
import kotlin.math.ceil

/**
 * The parallax orchard behind every menu screen.
 *
 * Menus animate only a horizontal offset on three tiled bitmaps — no per-frame layout or
 * recomposition — so the cost is a handful of GPU-composited quads. The *game* screen does
 * not use this: it draws its own parallax on the render thread.
 */
@Composable
fun OrchardBackdrop(
    assets: GameAssets?,
    animate: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val sky = remember(assets) { assets?.sky?.asImageBitmap() }
    val trees = remember(assets) { assets?.trees?.asImageBitmap() }
    val hills = remember(assets) { assets?.hills?.asImageBitmap() }

    // Only run the animation when it is actually wanted — switching parallax off used to
    // leave the infinite transition running and merely ignore its output.
    val phase: State<Float>? = if (animate) {
        rememberInfiniteTransition(label = "parallax").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 42_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "drift",
        )
    } else {
        null
    }

    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF4C82D0), Color(0xFF2A1633))))) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            // Read inside the draw lambda, not the composable body: that keeps each frame of
            // the drift a redraw instead of a recomposition of this whole subtree.
            val drift = phase?.value ?: 0f
            if (sky != null) tileLayer(sky, drift, 1f, 0f, size.height)
            val groundHeight = size.height * 0.63f
            val groundTop = size.height - groundHeight
            if (trees != null) tileLayer(trees, drift, 3.2f, groundTop, groundHeight)
            if (hills != null) tileLayer(hills, drift, 7.4f, groundTop, groundHeight)

            // Scrim so the cream type stays legible over the busy artwork.
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color(0xA8091428),
                    0.45f to Color(0x8A091428),
                    1f to Color(0x66091428),
                ),
            )
        }
        content()
    }
}

private fun DrawScope.tileLayer(
    image: ImageBitmap,
    phase: Float,
    speed: Float,
    top: Float,
    layerHeight: Float,
) {
    val scale = layerHeight / image.height
    val tileWidth = image.width * scale
    if (tileWidth <= 0f) return
    val offset = -((phase * speed * tileWidth) % tileWidth)
    val count = ceil((size.width - offset) / tileWidth).toInt() + 1
    for (i in 0 until count) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset((offset + i * tileWidth).toInt(), top.toInt()),
            dstSize = IntSize(ceil(tileWidth).toInt(), ceil(layerHeight).toInt()),
            filterQuality = FilterQuality.None,
        )
    }
}

/** The chunky orange call-to-action from the design, complete with its hard drop shadow. */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    height: Int = 62,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val lift = 8
    // Two stacked slabs: a solid shadow sitting `lift` dp lower, and the gradient face on
    // top of it. That is the flat, toy-like depth the design uses instead of a soft shadow.
    Box(modifier.fillMaxWidth().height((height + lift).dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height.dp)
                .align(Alignment.BottomCenter)
                .clip(shape)
                .background(if (enabled) AccentShadow else Color(0x55A83C50)),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(height.dp)
                .align(Alignment.TopCenter)
                .clip(shape)
                .background(
                    if (enabled) Brush.linearGradient(listOf(AccentLight, AccentDark))
                    else Brush.linearGradient(listOf(Color(0x55FFC97A), Color(0x55F2704F)))
                )
                .clickableOnce(enabled, onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = AccentInk, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    height: Int = 54,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0x21FFF3E6))
            .border(1.dp, Color(0x3DFFF3E6), RoundedCornerShape(20.dp))
            .clickableOnce(true, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Cream, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    corner: Int = 22,
    background: Color = PanelNavy,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner.dp))
            .background(background)
            .border(1.dp, Hairline, RoundedCornerShape(corner.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun FieldLabel(text: String) {
    Text(
        text.uppercase(),
        color = CreamDim,
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
fun WhaaackField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    isPassword: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                1.5.dp,
                if (isError) DangerSoft else Color(0x73FFF3E6),
                RoundedCornerShape(16.dp),
            ),
        singleLine = true,
        placeholder = { Text(placeholder, color = Color(0x66FFF3E6)) },
        visualTransformation =
            if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0x8C091428),
            unfocusedContainerColor = Color(0x8C091428),
            focusedTextColor = Cream,
            unfocusedTextColor = Cream,
            cursorColor = AccentLight,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
fun ErrorBanner(title: String, body: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x6BB22A30))
            .border(1.5.dp, DangerSoft, RoundedCornerShape(16.dp))
            .padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DangerSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", color = AccentInk, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Cream, fontSize = 13.sp, fontWeight = FontWeight.Black)
            Text(body, color = Color(0xD9FFF3E6), fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
fun CircleIconButton(symbol: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x24FFF3E6))
            .clickableOnce(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            color = if (enabled) Cream else Color(0x66FFF3E6),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircleIconButton("‹", onBack)
        Text(title, color = Cream, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Box(Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun ToggleRow(
    label: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x57140A1A))
            .border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .clickableOnce(true) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = Cream, fontSize = 14.sp, fontWeight = FontWeight.Black)
            Text(hint, color = Color(0x9EFFF3E6), fontSize = 11.sp)
        }
        Box(
            Modifier
                .width(48.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (checked) Brush.linearGradient(listOf(AccentLight, AccentDark))
                    else Brush.linearGradient(listOf(Color(0x29FFF3E6), Color(0x29FFF3E6)))
                )
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Cream),
            )
        }
    }
}

@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x57140A1A))
            .border(1.dp, Hairline, RoundedCornerShape(16.dp))
            .padding(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Cream else Color.Transparent)
                    .clickableOnce(true) { onSelect(index) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    color = if (selected) Color(0xFF3B1B44) else Color(0x99FFF3E6),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Google's branded sign-in button, matching their identity guidelines. */
@Composable
fun GoogleSignInButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (enabled) Color(0xFFF2F2F2) else Color(0xFF8C8C8C))
            .clickableOnce(enabled, onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GoogleGlyph()
            Text(
                label,
                color = Color(0xFF1F1F1F),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

/**
 * The third way in, beside email and Google.
 *
 * Deliberately *not* dressed as a Google button. It sits directly under one, and two
 * near-identical white pills would invite the player to read them as the same thing — which
 * is the mix-up this app has been careful about everywhere else: signing in to Play Games and
 * signing in to a Whaaack! account used to be unrelated, and even now they are different
 * handshakes against different OAuth clients. Same height and shape so the pair reads as one
 * group; the app's own palette and a plain glyph so it reads as a *different* provider.
 */
@Composable
fun PlayGamesSignInButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (enabled) Color(0x38FFF3E6) else Color(0x1FFFF3E6))
            .border(1.dp, if (enabled) Color(0x66FFF3E6) else Hairline, RoundedCornerShape(4.dp))
            .clickableOnce(enabled, onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🎮", fontSize = 16.sp)
            Text(
                label,
                color = if (enabled) Cream else Color(0x8AFFF3E6),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun GoogleGlyph() {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val s = size.minDimension
        // A compact four-colour "G" mark: quadrant arcs in Google's palette.
        val stroke = s * 0.22f
        val inset = stroke / 2
        val arcSize = Size(s - stroke, s - stroke)
        val topLeft = Offset(inset, inset)
        drawArc(Color(0xFFEA4335), 135f, 90f, false, topLeft, arcSize, style = Stroke(stroke))
        drawArc(Color(0xFFFBBC05), 180f, 90f, false, topLeft, arcSize, style = Stroke(stroke))
        drawArc(Color(0xFF34A853), 45f, 90f, false, topLeft, arcSize, style = Stroke(stroke))
        drawArc(Color(0xFF4285F4), -45f, 90f, false, topLeft, arcSize, style = Stroke(stroke))
        drawRect(
            Color(0xFF4285F4),
            topLeft = Offset(s * 0.5f, s * 0.42f),
            size = Size(s * 0.5f - inset, stroke),
        )
    }
}

/**
 * Click modifier that ignores the ripple, stays quiet when disabled, and — as the name
 * says — fires once. The debounce matters: these handlers start ads, submit forms and
 * delete accounts, and a double tap used to run them twice.
 */
@Composable
fun Modifier.clickableOnce(enabled: Boolean, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val lastClick = remember { longArrayOf(0L) }
    return this.clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastClick[0] >= CLICK_DEBOUNCE_MS) {
            lastClick[0] = now
            onClick()
        }
    }
}

/** Long enough to swallow a fumbled double tap, short enough not to feel unresponsive. */
private const val CLICK_DEBOUNCE_MS = 500L

/**
 * The gold crown badge that marks the paid unlock, carried over from Chromis so the two
 * apps flag a purchase the same way: a flat gold crown on a 16%-gold fill inside a
 * 55%-gold border. Deliberately not the game's own amber accent — this has to read as a
 * badge, not as another button.
 */
@Composable
fun RemoveAdsButton(
    price: String,
    modifier: Modifier = Modifier,
    /**
     * Overridable because the ad-break dialog is a good deal narrower than the screens this
     * was drawn for, and the default wraps onto two lines in it — where the sentence above
     * the button has already said "one-time" anyway.
     */
    subtitle: String = "One-time purchase, no subscription",
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CrownGold.copy(alpha = 0.16f))
            .border(1.dp, CrownGold.copy(alpha = 0.55f), shape)
            .clickableOnce(true, onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CrownGold.copy(alpha = 0.16f))
                .border(1.dp, CrownGold.copy(alpha = 0.55f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            CrownIcon(color = CrownGold, size = 21.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Remove ads", color = Cream, fontSize = 14.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = Color(0x9EFFF3E6), fontSize = 11.sp)
        }
        Text(price, color = CrownGold, fontSize = 14.sp, fontWeight = FontWeight.Black)
    }
}
