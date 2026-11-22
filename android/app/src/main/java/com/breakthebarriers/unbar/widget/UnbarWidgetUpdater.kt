package com.breakthebarriers.unbar.widget

import android.content.Context
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.breakthebarriers.unbar.UnbarApplication
import com.breakthebarriers.unbar.model.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Pushes the live tunnel state and active server name into every placed home-screen widget instance whenever tunnel state or server selection changes. A no-op when no widget is placed.
object UnbarWidgetUpdater {

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

fun update(context: Context) {
    val appContext = context.applicationContext
    val container = (appContext as? UnbarApplication)?.container ?: return
    val snapshot = container.tunnelManager.snapshot.value
    val status = when (snapshot.status) {
        TunnelStatus.CONNECTED -> "connected"
        TunnelStatus.CONNECTING -> "connecting"
        TunnelStatus.DISCONNECTING -> "disconnecting"
        TunnelStatus.ERROR -> "error"
        TunnelStatus.DISCONNECTED -> "disconnected"
    }
    val connectedServerName = snapshot.connectedServerName
    val serverIp = snapshot.connectedServerIp
    val country = snapshot.stats.serverCountry
    scope.launch {
        runCatching {
            val serverName = connectedServerName.ifBlank {
                val activeId = container.settingsRepository.current().activeServerId
                val servers = container.dao.getAllServers()
                (servers.firstOrNull { it.id == activeId } ?: servers.firstOrNull())?.name.orEmpty()
            }
            val manager = GlanceAppWidgetManager(appContext)
            val ids = manager.getGlanceIds(UnbarWidget::class.java)
            for (id in ids) {
                updateAppWidgetState(
                    appContext,
                    PreferencesGlanceStateDefinition,
                    id
                ) {
                    mutablePreferencesOf(
                        WIDGET_STATUS_KEY to status,
                        WIDGET_SERVER_NAME_KEY to serverName,
                        WIDGET_SERVER_IP_KEY to serverIp,
                        WIDGET_COUNTRY_KEY to country
                    )
                }
                UnbarWidget().update(appContext, id)
            }
        }
    }
}

}
