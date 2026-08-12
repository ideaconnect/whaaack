package tech.idct.whaaack.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette carried over from the Claude Design prototype.
val Cream = Color(0xFFFFF3E6)
val CreamDim = Color(0xB3FFF3E6)
val CreamFaint = Color(0x8AFFF3E6)
val OrchardNight = Color(0xFF2A1633)
val PanelNavy = Color(0x8C091428)
val PanelNavyStrong = Color(0xD6091428)
val Hairline = Color(0x24FFF3E6)
val AccentLight = Color(0xFFFFC97A)
val AccentDark = Color(0xFFF2704F)
val AccentShadow = Color(0xFFA83C50)
val AccentInk = Color(0xFF43162F)
val Danger = Color(0xFFE2574C)
val DangerSoft = Color(0xFFFF8A7A)
val Success = Color(0xFF8FD24E)

private val scheme = darkColorScheme(
    primary = AccentLight,
    onPrimary = AccentInk,
    secondary = AccentDark,
    onSecondary = AccentInk,
    background = OrchardNight,
    onBackground = Cream,
    surface = OrchardNight,
    onSurface = Cream,
    error = DangerSoft,
    onError = AccentInk,
)

private val typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 56.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
    ),
)

@Composable
fun WhaaackTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The orchard art is dark either way, so the app commits to a single dark scheme.
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
