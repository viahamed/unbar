package com.breakthebarriers.unbar.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.breakthebarriers.unbar.R
import com.breakthebarriers.unbar.data.db.ServerEntity
import com.breakthebarriers.unbar.data.tunnel.ResolverScanStatus
import com.breakthebarriers.unbar.model.AppSettings
import com.breakthebarriers.unbar.model.ConnectionMode
import com.breakthebarriers.unbar.model.TunnelStatus
import com.breakthebarriers.unbar.model.TunnelUiSnapshot
import com.breakthebarriers.unbar.ui.components.TutorialAccordion
import com.breakthebarriers.unbar.ui.components.TutorialGroup
import com.breakthebarriers.unbar.ui.components.TutorialTopic
import com.breakthebarriers.unbar.ui.components.formatBytes
import com.breakthebarriers.unbar.ui.components.formatElapsed
import com.breakthebarriers.unbar.ui.components.formatSpeedBps
import com.breakthebarriers.unbar.ui.theme.UnbarBlue
import com.breakthebarriers.unbar.ui.theme.UnbarOrange
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@Composable
fun HomeScreen(
    snapshotFlow: StateFlow<TunnelUiSnapshot>,
    latchedErrorFlow: StateFlow<String?>,
    settings: AppSettings,
    servers: List<ServerEntity>,
    dailyUsageBytesFlow: StateFlow<Long>,
    resolverScanStatusFlow: StateFlow<ResolverScanStatus>,
    onScanResolvers: () -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onSwitchMode: (ConnectionMode) -> Unit,
    onNavigateToServers: () -> Unit
) {
    val snapshot by snapshotFlow.collectAsStateWithLifecycle()
    val latchedError by latchedErrorFlow.collectAsStateWithLifecycle()
    val scanStatus by resolverScanStatusFlow.collectAsStateWithLifecycle()
    val activeServer = remember(servers, settings.activeServerId) {
        servers.firstOrNull { it.id == settings.activeServerId } ?: servers.firstOrNull()
    }

val isConnected = snapshot.status == TunnelStatus.CONNECTED
val isBusy = snapshot.status == TunnelStatus.CONNECTING || snapshot.status == TunnelStatus.DISCONNECTING

Column(
    modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
) {
    // (1.0.53, item 45): Home tutorial — two-level accordion at
    // the top of Home, same pattern as Settings/Servers: only
    // the "Guide" header shows first; tapping it reveals the
    // 12 topics, each explaining one Home option.
    TutorialAccordion(
        titleRes = R.string.guide_title,
        groups = listOf(
            TutorialGroup(
                titleRes = null,
                topics = listOf(
                    TutorialTopic(R.string.tutorial_home_status_title, R.string.tutorial_home_status_body),
                    TutorialTopic(R.string.tutorial_home_server_ip_title, R.string.tutorial_home_server_ip_body),
                    TutorialTopic(R.string.tutorial_home_mode_title, R.string.tutorial_home_mode_body),
                    TutorialTopic(R.string.tutorial_home_download_upload_title, R.string.tutorial_home_download_upload_body),
                    TutorialTopic(R.string.tutorial_home_latency_title, R.string.tutorial_home_latency_body),
                    TutorialTopic(R.string.tutorial_home_quality_title, R.string.tutorial_home_quality_body),
                    TutorialTopic(R.string.tutorial_home_active_resolvers_title, R.string.tutorial_home_active_resolvers_body),
                    TutorialTopic(R.string.tutorial_home_scan_title, R.string.tutorial_home_scan_body),
                    TutorialTopic(R.string.tutorial_home_today_title, R.string.tutorial_home_today_body),
                    TutorialTopic(R.string.tutorial_home_session_total_title, R.string.tutorial_home_session_total_body),
                    TutorialTopic(R.string.tutorial_home_connection_time_title, R.string.tutorial_home_connection_time_body),
                    TutorialTopic(R.string.tutorial_home_streams_title, R.string.tutorial_home_streams_body)
                )
            )
        )
    )

    // Model B Status Card
    // (A2, 1.0.15): the card color, content color and elevation
    // animate (300ms) on connect/disconnect/error instead of
    // snapping; the icon and status text crossfade+scale (200ms).
    // (1.0.24): the theme surface is NOT animated. The status
    // colors (blue/orange) still animate 300ms on connect /
    // disconnect / error, but the disconnected surface is read
    // directly from the theme, so a light/dark switch flips it
    // instantly like the rest of the UI instead of flashing
    // white/black for 300ms while the animation catches up.
    // The disconnect fade (blue -> surface) snaps; the connect
    // fade (surface -> blue) still animates.
    val statusColor by animateColorAsState(
        targetValue = when (snapshot.status) {
            TunnelStatus.CONNECTED -> UnbarBlue
            TunnelStatus.ERROR -> UnbarOrange.copy(alpha = 0.14f)
            else -> MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(300),
        label = "statusCardColor"
    )
    val cardColor = if (snapshot.status == TunnelStatus.CONNECTED ||
        snapshot.status == TunnelStatus.ERROR
    ) {
        statusColor
    } else {
        MaterialTheme.colorScheme.surface
    }
    val statusContentColor by animateColorAsState(
        targetValue = when (snapshot.status) {
            TunnelStatus.CONNECTED -> Color.White
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(300),
        label = "statusCardContentColor"
    )
    val cardContentColor = if (snapshot.status == TunnelStatus.CONNECTED) {
        statusContentColor
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val cardElevation by animateDpAsState(
        targetValue = if (isConnected) 4.dp else 1.dp,
        animationSpec = tween(300),
        label = "statusCardElevation"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor, contentColor = cardContentColor),
        elevation = CardDefaults.cardElevation(defaultElevation = cardElevation)
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (isConnected) Color.White.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = isConnected,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200)))
                                    .togetherWith(fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.9f, animationSpec = tween(200)))
                            },
                            label = "statusIcon"
                        ) { connected ->
                            Icon(
                                imageVector = if (connected) Icons.Rounded.VerifiedUser else Icons.Rounded.GppBad,
                                contentDescription = null,
                                tint = if (connected) UnbarOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Column {
                        AnimatedContent(
                            targetState = snapshot.status,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200)))
                                    .togetherWith(fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.9f, animationSpec = tween(200)))
                            },
                            label = "statusText"
                        ) { status ->
                            Text(
                                text = when (status) {
                                    TunnelStatus.CONNECTED -> stringResource(R.string.status_protected)
                                    TunnelStatus.CONNECTING -> stringResource(R.string.status_connecting)
                                    TunnelStatus.DISCONNECTING -> stringResource(R.string.status_disconnecting)
                                    TunnelStatus.ERROR -> stringResource(R.string.status_error)
                                    TunnelStatus.DISCONNECTED -> stringResource(R.string.status_not_protected)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Mode switcher (1.0.52): the static badge is now a
                // compact segmented control in the same slot — VPN |
                // Proxy. Short labels only: the full "VPN Mode" /
                // "SOCKS Mode" strings do not fit here. Same height
                // as the old badge (labelSmall, 2.dp track + 3.dp
                // segment padding); the status title flexes. The
                // active segment is filled blue (VPN) / orange
                // (Proxy); the inactive one is plain text and is the
                // only tappable part — tapping the active segment
                // does nothing. While connected the tap also
                // reconnects in the new mode (MainViewModel
                // .switchMode); while disconnected it only stores
                // the mode for the next connect.
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isConnected) Color.White.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(modifier = Modifier.padding(2.dp)) {
                        ModeSegment(
                            label = stringResource(R.string.mode_short_vpn),
                            active = settings.connectionMode == ConnectionMode.VPN,
                            activeColor = UnbarBlue,
                            isConnected = isConnected,
                            onClick = { onSwitchMode(ConnectionMode.VPN) }
                        )
                        ModeSegment(
                            label = stringResource(R.string.mode_short_proxy),
                            active = settings.connectionMode == ConnectionMode.SOCKS,
                            activeColor = UnbarOrange,
                            isConnected = isConnected,
                            onClick = { onSwitchMode(ConnectionMode.SOCKS) }
                        )
                    }
                }
            }

            // Server name below the status title (bigger, no domain),
            // server IP on the RIGHT side of the card — name and IP
            // kept separate from each other.
            if (activeServer != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activeServer.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isConnected && snapshot.connectedServerIp.isNotBlank()) {
                        // Country tag (1.0.10): the Go core looks
                        // up the server country via the geoip service
                        // and reports it in Stats(). Blank when
                        // unknown — then show just the IP. The flag
                        // (bundled drawable, all ISO codes) sits
                        // right after the code, e.g. "IP: 1.1.1.1
                        // (DE)" + German flag; no asset -> text only.
                        val country = snapshot.stats.serverCountry
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (country.isNotBlank()) {
                                    "IP: ${snapshot.connectedServerIp} ($country)"
                                } else {
                                    "IP: ${snapshot.connectedServerIp}"
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = Color.White.copy(alpha = 0.92f),
                                maxLines = 1
                            )
                            val flagRes = flagDrawableRes(country)
                            if (flagRes != null) {
                                Image(
                                    painter = painterResource(flagRes),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(width = 20.dp, height = 15.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }
                }
            }

            // Connection time lives in the stats grid below (as its
            // own card), not in the status card — the card shows only
            // the status, the server name and its IP.
            // Latched connection error (before-release #2): the
            // raw snapshot error is cleared by the 1s poll, so
            // this latched value is shown instead — visible for at
            // least 5s, until a successful connect or a newer error.
            // (Local val: latchedError is a delegated property, so
            // Kotlin cannot smart-cast it after the null check.)
            val error = latchedError
            if (!error.isNullOrBlank()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        // (1.0.53, item 45, variant D): the big Connect button is
        // gone — the card footer connects/disconnects. The footer
        // follows the card's content color (item 46): white on the
        // connected blue card, orange on the light disconnected
        // card, dark on the pale-orange error card. The divider is
        // white 20% on the colored card, a hairline on light ones.
        val footerColor = when (snapshot.status) {
            TunnelStatus.CONNECTED -> Color.White
            TunnelStatus.ERROR -> MaterialTheme.colorScheme.onSurface
            else -> UnbarOrange
        }
        val footerDividerColor = when (snapshot.status) {
            TunnelStatus.CONNECTED -> Color.White.copy(alpha = 0.2f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        Divider(
            color = footerDividerColor,
            thickness = 1.dp,
            modifier = Modifier.padding(top = 10.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = servers.isNotEmpty() || isConnected || isBusy,
                    onClick = {
                        if (isConnected || isBusy) onDisconnectClick() else onConnectClick()
                    }
                )
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedContent(
                targetState = isBusy,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(200)))
                        .togetherWith(fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.9f, animationSpec = tween(200)))
                },
                label = "statusFooterContent"
            ) { busy ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = footerColor
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (snapshot.status == TunnelStatus.CONNECTING) {
                                stringResource(R.string.status_connecting)
                            } else {
                                stringResource(R.string.status_disconnecting)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = footerColor
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            tint = footerColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isConnected) stringResource(R.string.disconnect) else stringResource(R.string.connect),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = footerColor
                        )
                    }
                }
            }
        }
    }

    // Prompt if user has not configured any server yet (no default server ships with Unbar)
    if (servers.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.no_server_configured),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onNavigateToServers) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.add_server_cta))
                }
            }
        }
    }

    // Live Stats Grid (Model B Cards)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.ArrowDownward,
            iconTint = UnbarBlue,
            label = stringResource(R.string.speed_download),
            value = formatSpeedBps(snapshot.stats.speedDownBps)
        )
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.ArrowUpward,
            iconTint = UnbarOrange,
            label = stringResource(R.string.speed_upload),
            value = formatSpeedBps(snapshot.stats.speedUpBps)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            icon = ImageVector.vectorResource(R.drawable.ic_stat_speed),
            iconTint = Color.Unspecified,
            label = stringResource(R.string.latency_label),
            value = if (snapshot.stats.latencyMs > 0L) "\u200E${snapshot.stats.latencyMs} ms" else "—"
        )
        QualityStatCard(
            modifier = Modifier.weight(1f),
            isConnected = isConnected,
            qualityScore = snapshot.stats.qualityScore
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            icon = ImageVector.vectorResource(R.drawable.ic_stat_dns),
            iconTint = Color.Unspecified,
            label = stringResource(R.string.resolver_health_label),
            // (1.0.53, item 41): the LIVE traffic-spreading count —
            // Soar-verified paths that are not suspended. While
            // connecting it climbs 0 → N; disconnected shows "—".
            // The old "below 16 → re-run Scan" signal is gone.
            value = if (isConnected) "${snapshot.stats.healthyResolvers}" else "—"
        )
        ScanResolversCard(
            modifier = Modifier.weight(1f),
            scanStatus = scanStatus,
            onScanResolvers = onScanResolvers
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DailyUsageStatCard(
            modifier = Modifier.weight(1f),
            dailyUsageBytesFlow = dailyUsageBytesFlow
        )
        StatCard(
            modifier = Modifier.weight(1f),
            icon = ImageVector.vectorResource(R.drawable.ic_stat_storage),
            iconTint = Color.Unspecified,
            label = stringResource(R.string.session_total_label),
            value = if (isConnected) {
                formatBytes(snapshot.stats.bytesUpSession + snapshot.stats.bytesDownSession)
            } else {
                "—"
            }
        )
    }

    // (1.0.15, C3): session totals — total bytes this session and
    // the live stream count — next to the existing IP/country/
    // latency. After the 1.0.5–1.0.12 stall saga, "is data
    // actually flowing?" is the question every user asks; the
    // totals and stream count answer it at a glance. The totals
    // are accumulated across watchdog reconnects (TunnelManager).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Connection time replaces the monthly-usage card: how long
        // the current session has been up, isolated into its own
        // composable so the 1s timer never recomposes the rest of Home.
        ConnectionTimeStatCard(
            modifier = Modifier.weight(1f),
            isConnected = isConnected,
            connectedSinceMs = snapshot.connectedSinceMs
        )
        StatCard(
            modifier = Modifier.weight(1f),
            icon = ImageVector.vectorResource(R.drawable.ic_stat_swap_horiz),
            iconTint = Color.Unspecified,
            label = stringResource(R.string.streams_label),
            value = if (isConnected) {
                if (snapshot.stats.maxStreams > 0) {
                    "${snapshot.stats.activeStreams}/${snapshot.stats.maxStreams}"
                } else {
                    "${snapshot.stats.activeStreams}"
                }
            } else {
                "—"
            }
        )
    }

    // Reserve space at the bottom so the floating capsule menu
    // never covers the last stats row.
    Spacer(modifier = Modifier.height(68.dp))
}

}

// All ten Home stat cards share one fixed compact height —
// the simple cards' natural height — so every row stays even.
// The richer Quality and Test cards tighten their inner spacing
// (12dp vertical padding, 4dp gaps, 4dp bar, compact pill) to
// fit the same height with nothing clipped; simpler cards keep
// their content top-aligned in it.
private val StatCardHeight = 78.dp

// Bundled flag drawable for a 2-letter country code
// ("flag_de"), or null when the code has no asset — the caller
// then shows just the code text, never a broken image.
@Composable
private fun flagDrawableRes(countryCode: String): Int? {
    if (countryCode.length != 2) return null
    val context = LocalContext.current
    val resId = remember(countryCode) {
        context.resources.getIdentifier(
            "flag_" + countryCode.lowercase(),
            "drawable",
            context.packageName
        )
    }
    return resId.takeIf { it != 0 }
}

@Composable
private fun DailyUsageStatCard(
    modifier: Modifier = Modifier,
    dailyUsageBytesFlow: StateFlow<Long>
) {
    val dailyUsageBytes by dailyUsageBytesFlow.collectAsStateWithLifecycle()
    StatCard(
        modifier = modifier,
        icon = ImageVector.vectorResource(R.drawable.ic_stat_data_usage),
        iconTint = Color.Unspecified,
        label = stringResource(R.string.usage_daily),
        value = formatBytes(dailyUsageBytes)
    )
}

@Composable
private fun ConnectionTimeStatCard(
    modifier: Modifier = Modifier,
    isConnected: Boolean,
    connectedSinceMs: Long?
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isConnected, connectedSinceMs) {
        if (!isConnected) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }
    StatCard(
        modifier = modifier,
        icon = ImageVector.vectorResource(R.drawable.ic_stat_timer),
        iconTint = Color.Unspecified,
        label = stringResource(R.string.connection_time_label),
        value = if (isConnected) formatElapsed(connectedSinceMs, nowMs) else "—"
    )
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String
) {
    Card(
        modifier = modifier.height(StatCardHeight),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// Connection quality card: same card shell as StatCard, but the
// value is the quality WORD (large, bold, orange) + the score
// ("92 / 100"). No progress bar — the word + score are the
// whole content. Compact inside: 12dp vertical padding, 10dp gap,
// titleMedium word (slightly smaller than a headline) — the card
// fits the shared 78dp height. Disconnected (or no live verified
// path yet): "—" like the other cards.
@Composable
private fun QualityStatCard(
    modifier: Modifier = Modifier,
    isConnected: Boolean,
    qualityScore: Int
) {
    Card(
        modifier = modifier.height(StatCardHeight),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_stat_shield_outline),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.connection_quality_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isConnected || qualityScore < 0) {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            } else {
                // Five-segment quality bar (design 3): lit segments =
                // quality level (5 Excellent, 4 Good, 3 Fair, 2/1 Poor).
                // No word, no number — the bar alone shows the quality.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val lit = qualityBars(qualityScore)
                    repeat(5) { index ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(16.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (index < lit) UnbarOrange
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun qualityBars(score: Int): Int = when {
    score >= 80 -> 5
    score >= 60 -> 4
    score >= 40 -> 3
    score >= 20 -> 2
    else -> 1
}

// Scan-resolvers card (1.0.53, item 39): same card shell as
// StatCard. Top row: magnifier icon + label + the Scan pill
// (greyed/disabled while a scan runs). Below it a horizontal
// 3-stage stepper (variant C): blue hollow circle = pending,
// orange spinning ring = active, orange filled circle with a
// white check = done (checks always sit inside the circle).
// NO text under the bar — just the button and the stepper.
// Compact inside to fit the shared 78dp height.
private enum class ScanStepState { PENDING, ACTIVE, DONE }

@Composable
private fun ScanResolversCard(
    modifier: Modifier = Modifier,
    scanStatus: ResolverScanStatus,
    onScanResolvers: () -> Unit
) {
    val running = scanStatus.scanRunning
    // Stage states mirror the Settings stepper (1.0.36): stage 1
    // is DONE as soon as tested reaches total, stage 2 is ACTIVE
    // while stage 1 is done and the scan still runs, stages 2 and
    // 3 are DONE once the scan completes. All PENDING before any
    // scan.
    val scanCompleted = !running && (
        scanStatus.testedResolvers > 0 ||
            scanStatus.respondingResolvers > 0 ||
            scanStatus.verifiedCount > 0 ||
            scanStatus.scanVerifiedCount > 0
        )
    val stage1Done = scanCompleted ||
        (scanStatus.totalResolvers > 0 && scanStatus.testedResolvers >= scanStatus.totalResolvers)
    val stage1State = when {
        stage1Done -> ScanStepState.DONE
        running -> ScanStepState.ACTIVE
        else -> ScanStepState.PENDING
    }
    val stage2State = when {
        scanCompleted -> ScanStepState.DONE
        stage1Done && running -> ScanStepState.ACTIVE
        else -> ScanStepState.PENDING
    }
    val stage3State = if (scanCompleted) ScanStepState.DONE else ScanStepState.PENDING
    Card(
        modifier = modifier.height(StatCardHeight),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_stat_magnifier_check),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.test_resolvers),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (running) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        UnbarOrange
                    },
                    modifier = Modifier.clickable(
                        enabled = !running,
                        onClick = onScanResolvers
                    )
                ) {
                    Text(
                        text = stringResource(R.string.test_resolvers_button),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (running) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            Color.White
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScanStepNode(stage1State)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                ScanStepNode(stage2State)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                ScanStepNode(stage3State)
            }
        }
    }
}

@Composable
private fun ScanStepNode(state: ScanStepState) {
    Box(
        modifier = Modifier.size(18.dp),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            ScanStepState.DONE -> Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(UnbarOrange),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(11.dp)
                )
            }
            ScanStepState.ACTIVE -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = UnbarOrange
            )
            ScanStepState.PENDING -> Box(
                modifier = Modifier
                    .size(18.dp)
                    .border(2.dp, UnbarBlue, CircleShape)
            )
        }
    }
}

// (1.0.52): one half of the status-card mode switcher. The active
// segment is filled with the mode color (blue for VPN, orange for
// Proxy) and is not clickable; the inactive segment is plain text
// and is the tap target. Inactive text is dimmed when connected
// (white 70% on the translucent track) so the two states read
// apart at a glance.
@Composable
private fun ModeSegment(
    label: String,
    active: Boolean,
    activeColor: Color,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) activeColor else Color.Transparent)
            .then(if (active) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            color = if (active) {
                Color.White
            } else if (isConnected) {
                Color.White.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
