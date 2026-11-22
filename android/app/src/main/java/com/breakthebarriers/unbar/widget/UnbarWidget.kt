package com.breakthebarriers.unbar.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.VpnService
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.breakthebarriers.unbar.MainActivity
import com.breakthebarriers.unbar.R
import com.breakthebarriers.unbar.UnbarApplication
import com.breakthebarriers.unbar.model.TunnelStatus
import com.breakthebarriers.unbar.ui.components.renderWordmarkBitmap
import com.breakthebarriers.unbar.ui.theme.UnbarBlue
import com.breakthebarriers.unbar.ui.theme.UnbarOrange
import kotlin.math.roundToInt

// Widget state keys (Glance Preferences state). Written by UnbarWidgetUpdater whenever the tunnel state or active server changes; read by provideGlance to draw the Model A 4x1 slim bar.
val WIDGET_STATUS_KEY = stringPreferencesKey("status")
val WIDGET_SERVER_NAME_KEY = stringPreferencesKey("server_name")
val WIDGET_SERVER_IP_KEY = stringPreferencesKey("server_ip")
val WIDGET_COUNTRY_KEY = stringPreferencesKey("country")

class UnbarWidget : GlanceAppWidget() {

override suspend fun provideGlance(context: Context, id: GlanceId) {
    val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val wordmarkHeightPx = (20f * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(24)
    val wordmarkBitmap = renderWordmarkBitmap(context, isDark = isDark, heightPx = wordmarkHeightPx)

    provideContent {
        val prefs = currentState<Preferences>()
        val status = prefs[WIDGET_STATUS_KEY] ?: "disconnected"
        val serverName = prefs[WIDGET_SERVER_NAME_KEY] ?: ""
        val isConnected = status == "connected"
        val isBusy = status == "connecting" || status == "disconnecting"

        val bgColor = ColorProvider(R.color.widget_bg)
        val bodyColor = ColorProvider(R.color.widget_body)
        val actionBgColor = ColorProvider(if (isConnected) UnbarBlue else UnbarOrange)

        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgColor)
                .cornerRadius(16.dp)
                .clickable(actionStartActivity<MainActivity>())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(wordmarkBitmap),
                contentDescription = context.getString(R.string.widget_label),
                modifier = GlanceModifier
                    .width(72.dp)
                    .height(20.dp),
                contentScale = ContentScale.Fit
            )

            if (serverName.isNotBlank()) {
                Text(
                    text = " - $serverName",
                    style = TextStyle(
                        color = bodyColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier
                        .defaultWeight()
                        .padding(end = 8.dp)
                )
            } else {
                Spacer(modifier = GlanceModifier.defaultWeight())
            }

            Box(
                modifier = GlanceModifier
                    .background(actionBgColor)
                    .cornerRadius(16.dp)
                    .clickable(actionRunCallback<ToggleWidgetAction>())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isConnected || isBusy) {
                        context.getString(R.string.disconnect)
                    } else {
                        context.getString(R.string.connect)
                    },
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

}

// The button action: toggles the tunnel from the widget. Reads the LIVE tunnel state (not the widget's stored state, which can lag), so a tap always does the right thing. Same guards as the tile: no server or no VPN permission yet -> open the app.
class ToggleWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val app = context.applicationContext as? UnbarApplication ?: return
        val container = app.container
        val status = container.tunnelManager.snapshot.value.status
        if (status == TunnelStatus.CONNECTED ||
            status == TunnelStatus.CONNECTING ||
            status == TunnelStatus.DISCONNECTING
        ) {
            container.tunnelManager.requestDisconnect()
            return
        }
        val servers = runCatching { container.dao.getAllServers() }.getOrDefault(emptyList())
        if (servers.isEmpty() || VpnService.prepare(context) != null) {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }
        container.tunnelManager.requestConnect()
    }
}
