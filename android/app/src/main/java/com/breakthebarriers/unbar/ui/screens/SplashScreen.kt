package com.breakthebarriers.unbar.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import com.breakthebarriers.unbar.ui.components.UnbarLogo
import com.breakthebarriers.unbar.ui.theme.UnbarLightSplashBg
import com.breakthebarriers.unbar.ui.theme.UnbarOrange
import kotlinx.coroutines.delay

// Minimum time the splash stays visible, measured from the first
// frame in which it is actually drawn — not from ViewModel init.
// The ViewModel is created before anything is drawn, so a minimum
// measured from init had already elapsed by the time the splash
// appeared, and the logo was visible for a single frame.
private const val SPLASH_MINIMUM_MS = 2000L

/**
 * The launch splash: the Unbar logo, static and centered, with a
 * small loading circle spinning below it while the app loads. No
 * text, no other content. The logo never rotates — only the circle
 * spins.
 *
 * Dismissal: [onSplashFinished] is called only once BOTH the app
 * data is ready ([showHome] — which already includes the 5s
 * timeout safety net, so the splash can never hang if the data
 * never loads) AND 2000ms have elapsed since the splash was first
 * drawn.
 */
@Composable
fun SplashScreen(
    isDark: Boolean,
    showHome: Boolean,
    onSplashFinished: () -> Unit
) {
    // The moment the splash was first drawn, captured inside
    // drawWithContent — i.e. during the draw phase of the first
    // frame that actually paints the splash. A plain holder, not
    // state: writing state from inside the draw pass would
    // schedule another recomposition out of the draw itself.
    val firstFrameMillis = remember { longArrayOf(0L) }
    var minimumElapsed by remember { mutableStateOf(false) }

    // Wait for the first drawn frame, then wait out the remainder
    // of the 2000ms from that frame. withFrameNanos resumes on a
    // frame callback, which runs before that frame's draw, so the
    // loop only exits once a draw has really happened; the
    // remaining time is computed from the recorded draw moment, so
    // the total is exactly 2000ms from the first drawn frame.
    LaunchedEffect(Unit) {
        while (firstFrameMillis[0] == 0L) {
            withFrameNanos { }
        }
        val remaining = SPLASH_MINIMUM_MS -
            (SystemClock.uptimeMillis() - firstFrameMillis[0])
        if (remaining > 0) delay(remaining)
        minimumElapsed = true
    }

    LaunchedEffect(showHome, minimumElapsed) {
        if (showHome && minimumElapsed) onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Splash keeps the old lighter light background; only the
            // main screens use the darker UnbarLightBg. Dark mode is
            // unchanged (colorScheme.background = UnbarDarkBg).
            .background(if (isDark) MaterialTheme.colorScheme.background else UnbarLightSplashBg)
            .drawWithContent {
                drawContent()
                if (firstFrameMillis[0] == 0L) {
                    firstFrameMillis[0] = SystemClock.uptimeMillis()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            UnbarLogo(
                isDark = isDark,
                showTagline = true,
                iconSize = 64.dp
            )
            Spacer(modifier = Modifier.height(32.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = UnbarOrange,
                strokeWidth = 3.dp
            )
        }
    }
}
