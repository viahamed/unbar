package com.breakthebarriers.unbar.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.breakthebarriers.unbar.R
import kotlin.math.roundToInt

/**
 * The approved Unbar wordmark lockup, as the approved PNGs — never
 * redrawn in code. The old version drew a fake logo (blue square +
 * horizontal stripes + plain "Un"/"bar" text), which did not match
 * the approved wordmark at all.
 *
 * - Full lockup (wordmark + "by Break The Barriers" tagline) when
 *   showTagline is true (Splash, About).
 * - Wordmark only when showTagline is false (top-app-bar header).
 * - Light PNG on light theme, dark PNG on dark theme.
 * iconSize is the logo height; the width follows the PNG aspect.
 */
@Composable
fun UnbarLogo(
    modifier: Modifier = Modifier,
    isDark: Boolean = false,
    showTagline: Boolean = true,
    iconSize: Dp = 44.dp,
    compact: Boolean = false
) {
    val resId = when {
        showTagline && isDark -> R.drawable.unbar_logo_dark
        showTagline && !isDark -> R.drawable.unbar_logo_light
        !showTagline && isDark -> R.drawable.unbar_wordmark_dark
        else -> R.drawable.unbar_wordmark_light
    }
    // Full lockup 1730x673 (aspect 2.57); wordmark 1730x481 (aspect 3.60).
    val aspect = if (showTagline) 2.57f else 3.60f
    Image(
        painter = painterResource(resId),
        contentDescription = stringResource(R.string.app_name),
        modifier = modifier
            .height(iconSize)
            .aspectRatio(aspect),
        contentScale = ContentScale.Fit
    )
}

/**
 * Renders the approved wordmark drawable into a compact Bitmap for the
 * Glance home-screen widget so the widget uses the exact app wordmark
 * in both light and dark themes.
 */
fun renderWordmarkBitmap(context: Context, isDark: Boolean, heightPx: Int): Bitmap {
    val resId = if (isDark) R.drawable.unbar_wordmark_dark else R.drawable.unbar_wordmark_light
    val clampedHeight = heightPx.coerceAtLeast(1)
    val widthPx = (clampedHeight * 3.60f).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(widthPx, clampedHeight, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val drawable = ContextCompat.getDrawable(context, resId)
    if (drawable != null) {
        drawable.setBounds(0, 0, widthPx, clampedHeight)
        drawable.draw(canvas)
    }
    return bitmap
}
