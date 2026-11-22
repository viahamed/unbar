package com.breakthebarriers.unbar.service

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.breakthebarriers.unbar.MainActivity
import com.breakthebarriers.unbar.R
import com.breakthebarriers.unbar.UnbarApplication
import com.breakthebarriers.unbar.model.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// (1.0.15, C1): Quick Settings tile — connect/disconnect from the
// notification shade. In a blackout the single most-used action is
// connect/disconnect, and the shade is one swipe away, so the tile
// is the highest-frequency interaction in the app. Tap toggles;
// long-press opens the app (QS_TILE_PREFERENCES intent filter on
// MainActivity). Builds on the existing VpnService start/stop path —
// no new permissions beyond the tile itself.
//
// The tile reads the LIVE tunnel state from the Application container
// (same process), so it never shows a stale state. The app asks the
// tile to refresh (requestUpdate) whenever the tunnel state changes
// (TunnelManager.markConnected/markDisconnected/markError).
class UnbarTileService : TileService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)

    companion object {
        // Ask the system to re-run onStartListening on this tile so
        // it picks up the new tunnel state. No-op below API 24
        // (Quick Settings tiles do not exist there) and when the
        // tile has not been added by the user.
        fun requestUpdate(context: android.content.Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, UnbarTileService::class.java)
                )
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        // The server check is a Room query — do it off the main thread.
        serviceScope.launch {
            val container = (application as UnbarApplication).container
            val status = container.tunnelManager.snapshot.value.status
            if (status == TunnelStatus.CONNECTED ||
                status == TunnelStatus.CONNECTING ||
                status == TunnelStatus.DISCONNECTING
            ) {
                container.tunnelManager.requestDisconnect()
                return@launch
            }
            // Disconnected / error: connect, but only if a server is
            // configured and the VPN permission is already granted.
            // Otherwise open the app so the user can add a server or
            // grant the VPN permission through the normal flow.
            val servers = runCatching { container.dao.getAllServers() }.getOrDefault(emptyList())
            if (servers.isEmpty()) {
                openApp()
                return@launch
            }
            if (VpnService.prepare(this@UnbarTileService) != null) {
                openApp()
                return@launch
            }
            container.tunnelManager.requestConnect()
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val container = (application as UnbarApplication).container
        val snapshot = container.tunnelManager.snapshot.value
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification_ub)
        tile.label = getString(R.string.tile_label)
        when (snapshot.status) {
            TunnelStatus.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                // Subtitle (API 29+): the connected server, so the
                // shade shows at a glance which server is in use.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = snapshot.connectedServerName.ifBlank { null }
                }
            }
            TunnelStatus.CONNECTING, TunnelStatus.DISCONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = null
                }
            }
            TunnelStatus.DISCONNECTED, TunnelStatus.ERROR -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = null
                }
            }
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
