package tech.idct.whaaack.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The crown from Chromis, ported across so the two apps mark a paid unlock the same way.
 *
 * Material has no crown — `WorkspacePremium` is a rosette and `EmojiEvents` is a trophy —
 * and the crown emoji renders in whatever colour and shape the platform font decides, which
 * is useless next to tinted iconography. So it is drawn by hand, on the same 24x24 grid
 * Material icons use.
 *
 * Two details are load-bearing and were deliberate in the original:
 *
 *  - the 1.4-unit gap between the band (which ends at y=16.8) and the base (which starts at
 *    y=18.2). Closing it is the main way this shape stops reading as a crown and starts
 *    reading as a jagged blob at 20px;
 *  - no jewels. At icon size they turn into noise, and the three-peak silhouette is already
 *    unambiguous.
 *
 * Flat single colour, no gradient — also as in the original, where the gold is a palette
 * token rather than a ramp.
 */
@Composable
fun CrownIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f

        // Band: a closed seven-point polygon — left shoulder, dip, centre peak, dip,
        // right shoulder, then straight back along the bottom edge.
        val band = Path().apply {
            moveTo(2.4f * k, 6.6f * k)
            lineTo(7.7f * k, 11.2f * k)
            lineTo(12f * k, 4.2f * k)
            lineTo(16.3f * k, 11.2f * k)
            lineTo(21.6f * k, 6.6f * k)
            lineTo(20.1f * k, 16.8f * k)
            lineTo(3.9f * k, 16.8f * k)
            close()
        }
        drawPath(band, color)

        drawRoundRect(
            color = color,
            topLeft = Offset(3.4f * k, 18.2f * k),
            size = Size(17.2f * k, 2.9f * k),
            cornerRadius = CornerRadius(1.1f * k, 1.1f * k),
        )
    }
}

/** Chromis' gold, for dark surfaces. Not a gradient, and not the game's amber accent. */
val CrownGold = Color(0xFFF3C33C)
