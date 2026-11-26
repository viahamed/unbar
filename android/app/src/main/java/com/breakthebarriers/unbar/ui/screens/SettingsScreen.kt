package com.breakthebarriers.unbar.ui.screens

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.breakthebarriers.unbar.ui.components.AnimatedDialog
import com.breakthebarriers.unbar.ui.components.UnbarFilterChip
import com.breakthebarriers.unbar.ui.components.TutorialAccordion
import com.breakthebarriers.unbar.ui.components.TutorialGroup
import com.breakthebarriers.unbar.ui.components.TutorialTopic
import com.breakthebarriers.unbar.ui.theme.UnbarBlue
import com.breakthebarriers.unbar.ui.theme.UnbarOrange
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.breakthebarriers.unbar.BuildConfig
import com.breakthebarriers.unbar.R
import com.breakthebarriers.unbar.data.datastore.ResolverHealthStore
import com.breakthebarriers.unbar.data.datastore.droppedResolverTokens
import com.breakthebarriers.unbar.data.datastore.expandedResolverCount
import com.breakthebarriers.unbar.data.datastore.normalizeResolvers
import com.breakthebarriers.unbar.data.datastore.oversizeResolverRanges
import com.breakthebarriers.unbar.data.tunnel.CoreTunnelBridge
import com.breakthebarriers.unbar.data.tunnel.ResolverScanStatus
import com.breakthebarriers.unbar.data.update.AppUpdateChecker
import com.breakthebarriers.unbar.data.update.ReleaseInfo
import com.breakthebarriers.unbar.data.update.UpdateCheckResult
import com.breakthebarriers.unbar.data.update.UpdateFailureKind
import com.breakthebarriers.unbar.model.AppLanguage
import com.breakthebarriers.unbar.model.AppSettings
import com.breakthebarriers.unbar.model.AppThemeMode
import com.breakthebarriers.unbar.model.ConnectionMode
import com.breakthebarriers.unbar.model.DiscoveryMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: AppSettings,
    socksPasswordFlow: StateFlow<String>,
    resolverScanStatusFlow: StateFlow<ResolverScanStatus>,
    notificationsAllowedFlow: StateFlow<Boolean>,
    isIgnoringBatteryFlow: StateFlow<Boolean>,
    onTestResolvers: () -> Unit,
    onStopScan: () -> Unit,
    onPauseScan: () -> Unit,
    onResumeScan: () -> Unit,
    onRefreshSystemStatuses: () -> Unit,
    onSetConnectionMode: (ConnectionMode) -> Unit,
    onSetResolverConfig: (Boolean, DiscoveryMode, List<String>, String) -> Unit,
    onImportResolverList: (List<String>, String) -> Unit,
    onClearImportedResolvers: () -> Unit,
    onSetTcpMode: (Boolean) -> Unit,
    onSetMtu: (Int) -> Unit,
    onSetMaxAnswer: (Int) -> Unit,
    onSetSocksConfig: (Int, String, String?) -> Unit,
    onSetBandwidthLimit: (Long, Long) -> Unit,
    onSetIranGeoBypass: (Boolean) -> Unit,
    onSetCustomBypassList: (List<String>) -> Unit,
    onSetLanguage: (AppLanguage) -> Unit,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onSetShowSpeedsInNotification: (Boolean) -> Unit,
    onSetDiagnosticLogsEnabled: (Boolean) -> Unit,
    onOpenPerAppRouting: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val context = LocalContext.current
    val initialSocksPassword by socksPasswordFlow.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 0. Tutorial — two-level accordion at the top of Settings.
        // Only the "Tutorial" header shows first; tapping it reveals
        // the 13 Settings topics grouped under their 5 headings, and
        // tapping a topic shows its short paragraph.
        item(key = "tutorial") {
            TutorialAccordion(
                groups = listOf(
                    TutorialGroup(
                        R.string.tutorial_settings_group_connection,
                        listOf(
                            TutorialTopic(R.string.tutorial_settings_mode_title, R.string.tutorial_settings_mode_body),
                            TutorialTopic(R.string.tutorial_settings_tcp_title, R.string.tutorial_settings_tcp_body),
                            TutorialTopic(R.string.tutorial_settings_mtu_title, R.string.tutorial_settings_mtu_body),
                            TutorialTopic(R.string.tutorial_settings_answer_title, R.string.tutorial_settings_answer_body)
                        )
                    ),
                    TutorialGroup(
                        R.string.tutorial_settings_group_resolvers,
                        listOf(
                            TutorialTopic(R.string.tutorial_settings_resolvers_title, R.string.tutorial_settings_resolvers_body),
                            TutorialTopic(R.string.tutorial_settings_test_title, R.string.tutorial_settings_test_body)
                        )
                    ),
                    TutorialGroup(
                        R.string.tutorial_settings_group_proxy,
                        listOf(
                            TutorialTopic(R.string.tutorial_settings_proxy_title, R.string.tutorial_settings_proxy_body),
                            TutorialTopic(R.string.tutorial_settings_bandwidth_title, R.string.tutorial_settings_bandwidth_body)
                        )
                    ),
                    TutorialGroup(
                        R.string.tutorial_settings_group_routing,
                        listOf(
                            TutorialTopic(R.string.tutorial_settings_bypass_title, R.string.tutorial_settings_bypass_body),
                            TutorialTopic(R.string.tutorial_settings_bypass_list_title, R.string.tutorial_settings_bypass_list_body),
                            TutorialTopic(R.string.tutorial_settings_per_app_title, R.string.tutorial_settings_per_app_body)
                        )
                    ),
                    TutorialGroup(
                        R.string.tutorial_settings_group_system,
                        listOf(
                            TutorialTopic(R.string.tutorial_settings_update_title, R.string.tutorial_settings_update_body),
                            TutorialTopic(R.string.tutorial_settings_logs_title, R.string.tutorial_settings_logs_body)
                        )
                    )
                )
            )
        }

        // 1. Connection Mode
        item(key = "connection") {
            ConnectionSection(
                connectionMode = settings.connectionMode,
                tcpMode = settings.tcpMode,
                mtu = settings.mtu,
                maxAnswer = settings.maxAnswer,
                onSetConnectionMode = onSetConnectionMode,
                onSetTcpMode = onSetTcpMode,
                onSetMtu = onSetMtu,
                onSetMaxAnswer = onSetMaxAnswer
            )
        }

        // 2. Resolvers — Built-in or Custom only. No on/off toggle,
        // no country field, no Save button: the built-in list is
        // always the internal Iranian resolvers, Custom is only the
        // field's resolvers, and every change saves immediately.
        item(key = "resolvers") {
            ResolversSection(
                discoveryMode = settings.discoveryMode,
                customResolversJoined = settings.customResolvers.joinToString(","),
                importedResolvers = settings.importedResolvers,
                importFileName = settings.importFileName,
                importedAtMs = settings.importedAtMs,
                resolverScanStatusFlow = resolverScanStatusFlow,
                onSetResolverConfig = onSetResolverConfig,
                onImportResolverList = onImportResolverList,
                onClearImportedResolvers = onClearImportedResolvers,
                onTestResolvers = onTestResolvers,
                onStopScan = onStopScan,
                onPauseScan = onPauseScan,
                onResumeScan = onResumeScan
            )
        }

        // 3. Local SOCKS Proxy Settings (Encrypted Password in Keystore)
        item(key = "proxy") {
            ProxySection(
                socksPort = settings.socksPort,
                socksUsername = settings.socksUsername,
                initialSocksPassword = initialSocksPassword,
                onSetSocksConfig = onSetSocksConfig
            )
        }

        // 4. Bandwidth Limits (KB/s in the UI, bytes/sec stored, 0 = unlimited)
        item(key = "bandwidth") {
            BandwidthSection(
                uploadLimitBps = settings.uploadLimitBps,
                downloadLimitBps = settings.downloadLimitBps,
                onSetBandwidthLimit = onSetBandwidthLimit
            )
        }

        // 5. Routing (Order: Per-app -> Iran geo bypass -> Tunnel)
        item(key = "routing") {
            RoutingSection(
                iranGeoBypass = settings.iranGeoBypass,
                customBypassListJoined = settings.customBypassList.joinToString("\n"),
                connectionMode = settings.connectionMode,
                perAppPackagesCount = settings.perAppPackages.size,
                onSetIranGeoBypass = onSetIranGeoBypass,
                onSetCustomBypassList = onSetCustomBypassList,
                onOpenPerAppRouting = onOpenPerAppRouting
            )
        }

        // 6. Language & Theme Appearance
        item(key = "appearance") {
            AppearanceSection(
                language = settings.language,
                themeMode = settings.themeMode,
                onSetLanguage = onSetLanguage,
                onSetThemeMode = onSetThemeMode
            )
        }

    // 7. System & Diagnostics
    item(key = "diagnostics") {
        SystemDiagnosticsSection(
            context = context,
            showSpeedsInNotification = settings.showSpeedsInNotification,
            diagnosticLogsEnabled = settings.diagnosticLogsEnabled,
            notificationsAllowedFlow = notificationsAllowedFlow,
            isIgnoringBatteryFlow = isIgnoringBatteryFlow,
            onRefreshSystemStatuses = onRefreshSystemStatuses,
            onSetShowSpeedsInNotification = onSetShowSpeedsInNotification,
            onSetDiagnosticLogsEnabled = onSetDiagnosticLogsEnabled,
            onOpenLogs = onOpenLogs,
            onOpenHistory = onOpenHistory
        )
    }

    item(key = "bottom_spacer") {
        Spacer(modifier = Modifier.height(68.dp))
    }
    }
}

// (1.0.21): each Settings section is its own composable with narrow,
// stable params, so a toggle re-executes only its own section —
// Compose skips every section whose params did not change. The
// top-level SettingsScreen stays thin: it only collects the flows
// and passes one field at a time down.

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionSection(
    connectionMode: ConnectionMode,
    tcpMode: Boolean,
    mtu: Int,
    maxAnswer: Int,
    onSetConnectionMode: (ConnectionMode) -> Unit,
    onSetTcpMode: (Boolean) -> Unit,
    onSetMtu: (Int) -> Unit,
    onSetMaxAnswer: (Int) -> Unit
) {
    SettingsCard(title = stringResource(R.string.section_connection)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnbarFilterChip(
                selected = connectionMode == ConnectionMode.VPN,
                onClick = { onSetConnectionMode(ConnectionMode.VPN) },
                label = { Text(stringResource(R.string.mode_vpn)) }
            )
            UnbarFilterChip(
                selected = connectionMode == ConnectionMode.SOCKS,
                onClick = { onSetConnectionMode(ConnectionMode.SOCKS) },
                label = { Text(stringResource(R.string.mode_socks)) }
            )
        }
        SwitchRow(
            label = "TCP Mode",
            subtitle = stringResource(R.string.tcp_mode_explanation),
            checked = tcpMode,
            onCheckedChange = onSetTcpMode
        )
        // MTU (VPN mode only): the packet size for the tunnel. Auto
        // (1.0.34, default) measures the network interface MTU and
        // subtracts the tunnel overhead, so it fits the real path;
        // 1280 helps when pages stall, 1500 can be faster on a
        // stable link. Saves immediately on tap (no Save button),
        // applies on next connect.
        Text(
            text = stringResource(R.string.mtu_label),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnbarFilterChip(
                selected = mtu == 0,
                onClick = { onSetMtu(0) },
                label = { Text(stringResource(R.string.mtu_auto)) }
            )
            listOf(1280, 1400, 1500).forEach { value ->
                UnbarFilterChip(
                    selected = mtu == value,
                    onClick = { onSetMtu(value) },
                    label = { Text(value.toString()) }
                )
            }
        }
        Text(
            text = stringResource(R.string.mtu_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // (1.0.38): MTU is not a live setting — it only takes
        // effect on the next connect (it is deliberately absent
        // from TunnelManager.applyLiveSettings). Say so with the
        // existing "applies on reconnect" string, the same
        // wording as the DNS answer-size section below.
        Text(
            text = stringResource(R.string.applies_on_reconnect),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // DNS answer size (1.0.28): the cap on how much downstream
        // data one DNS answer may carry. Auto lets the client probe
        // up to 1232 bytes; a manual value caps it. Smaller answers
        // are more likely to arrive on a lossy path, larger ones are
        // faster on a healthy path. Saves immediately on tap (no
        // Save button), applies on next connect.
        Text(
            text = stringResource(R.string.dns_answer_size_label),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        // (Before-release #1): FlowRow, not Row — on narrow
        // screens (Mi 9) the 5 chips did not fit and the last one
        // ("1232") was squeezed until its text broke over two
        // lines. FlowRow wraps the overflow chip cleanly onto a
        // second line instead.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            UnbarFilterChip(
                selected = maxAnswer == 0,
                onClick = { onSetMaxAnswer(0) },
                label = { Text(stringResource(R.string.dns_answer_size_auto)) }
            )
            listOf(512, 768, 1024, 1232).forEach { value ->
                UnbarFilterChip(
                    selected = maxAnswer == value,
                    onClick = { onSetMaxAnswer(value) },
                    label = { Text(value.toString()) }
                )
            }
        }
        Text(
            text = stringResource(R.string.dns_answer_size_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // (Hygiene, 1.0.15): mode + TCP also apply on reconnect.
        Text(
            text = stringResource(R.string.applies_on_reconnect),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ResolversSection(
    discoveryMode: DiscoveryMode,
    customResolversJoined: String,
    importedResolvers: List<String>,
    importFileName: String,
    importedAtMs: Long,
    resolverScanStatusFlow: StateFlow<ResolverScanStatus>,
    onSetResolverConfig: (Boolean, DiscoveryMode, List<String>, String) -> Unit,
    onImportResolverList: (List<String>, String) -> Unit,
    onClearImportedResolvers: () -> Unit,
    onTestResolvers: () -> Unit,
    onStopScan: () -> Unit,
    onPauseScan: () -> Unit,
    onResumeScan: () -> Unit
) {
    // Custom resolvers field: the text the user types. Normalized
    // (comma-separated, :53 added, stray symbols stripped) and saved
    // automatically 3s after the user stops typing — there is no Save
    // button in the Resolvers section. The 3s window (not 700ms) gives
    // room to type a whole resolver; and while the field is focused the
    // text is NEVER rewritten — the old code re-set the text on every
    // debounce, which moved the caret and made typing impossible.
    // Normalization is applied to the saved value immediately, and to
    // the visible text only once focus is lost.
    var customText by remember { mutableStateOf(customResolversJoined) }
    var customFocused by remember { mutableStateOf(false) }

    // Keep the field in sync if the saved list changes elsewhere
    // (e.g. a config import), without clobbering an in-progress edit.
    LaunchedEffect(customResolversJoined) {
        val saved = customResolversJoined
        if (!customFocused && normalizeResolvers(customText).joinToString(",") != saved) {
            customText = saved
        }
    }

    // Debounced auto-normalize + auto-save for the Custom field.
    // 3s after the last keystroke (typing resets the clock); the
    // field is never rewritten while the user is actively typing.
    // Discovery is always on and the country is always IR now, so
    // those two arguments are fixed (1.0.16).
    LaunchedEffect(customText) {
        delay(3000)
        val normalized = normalizeResolvers(customText)
        val joined = normalized.joinToString(",")
        if (!customFocused && joined != customText) customText = joined
        if (joined != customResolversJoined) {
            onSetResolverConfig(
                true,
                discoveryMode,
                normalized,
                "IR"
            )
        }
    }

    // When the field loses focus, show the normalized form of what was
    // typed (adds :53, strips symbols) without waiting for the debounce.
    // This only formats the typed text — it never reverts to the saved
    // value, so it cannot clobber an edit whose save is still pending.
    LaunchedEffect(customFocused) {
        if (!customFocused) {
            val joined = normalizeResolvers(customText).joinToString(",")
            if (joined != customText) customText = joined
        }
    }

    SettingsCard(title = stringResource(R.string.section_resolvers)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnbarFilterChip(
                selected = discoveryMode == DiscoveryMode.BUILTIN,
                onClick = {
                    onSetResolverConfig(
                        true,
                        DiscoveryMode.BUILTIN,
                        normalizeResolvers(customText),
                        "IR"
                    )
                },
                label = { Text(stringResource(R.string.discovery_builtin)) }
            )
            UnbarFilterChip(
                selected = discoveryMode == DiscoveryMode.CUSTOM,
                onClick = {
                    onSetResolverConfig(
                        true,
                        DiscoveryMode.CUSTOM,
                        normalizeResolvers(customText),
                        "IR"
                    )
                },
                label = { Text(stringResource(R.string.discovery_custom)) }
            )
            // (1.0.2): Import — a resolver list loaded from a .txt
            // file, kept separately from the Custom field.
            UnbarFilterChip(
                selected = discoveryMode == DiscoveryMode.IMPORT,
                onClick = {
                    onSetResolverConfig(
                        true,
                        DiscoveryMode.IMPORT,
                        normalizeResolvers(customText),
                        "IR"
                    )
                },
                label = { Text(stringResource(R.string.discovery_import)) }
            )
        }

        if (discoveryMode == DiscoveryMode.CUSTOM) {
            OutlinedTextField(
                value = customText,
                onValueChange = { customText = it },
                label = { Text(stringResource(R.string.custom_resolvers_label)) },
                supportingText = {
                    // (1.0.33): the count is the EXPANDED number of
                    // addresses the scan will probe — a /24 shows
                    // 256, not 1 — so a range never reads as a
                    // single IP.
                    val count = expandedResolverCount(customText)
                    Text(stringResource(R.string.custom_resolvers_count, count))
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { customFocused = it.isFocused }
            )
            // (B5, 1.0.15): warn about dropped entries (IPv6 /
            // hostnames) instead of silently ignoring them.
            val dropped = droppedResolverTokens(customText)
            if (dropped.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.custom_resolvers_ignored,
                        dropped.joinToString(", ")
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            // (1.0.33): warn about ranges too large to scan
            // (>4096 addresses, prefix < /20) — the core skips
            // them, so say so instead of letting the scan run
            // on nothing.
            val oversize = oversizeResolverRanges(customText)
            if (oversize.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.custom_resolvers_range_too_large,
                        oversize.joinToString(", ")
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                text = stringResource(R.string.custom_resolvers_format_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // CIDR ranges are supported too — the field accepts
            // them (1.0.32) and the core expands them when
            // scanning/connecting.
            Text(
                text = stringResource(R.string.custom_resolvers_cidr_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (discoveryMode == DiscoveryMode.IMPORT) {
            ResolverImportBlock(
                importedResolvers = importedResolvers,
                importFileName = importFileName,
                importedAtMs = importedAtMs,
                onImport = onImportResolverList,
                onClear = onClearImportedResolvers
            )
        } else {
            Text(
                text = stringResource(R.string.discovery_builtin_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ResolversHealthSection(
            discoveryMode = discoveryMode,
            resolverScanStatusFlow = resolverScanStatusFlow,
            onTestResolvers = onTestResolvers,
            onStopScan = onStopScan,
            onPauseScan = onPauseScan,
            onResumeScan = onResumeScan
        )

        // (Hygiene, 1.0.15): resolver changes apply on reconnect.
        Text(
            text = stringResource(R.string.applies_on_reconnect),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProxySection(
    socksPort: Int,
    socksUsername: String,
    initialSocksPassword: String,
    onSetSocksConfig: (Int, String, String?) -> Unit
) {
    var socksPortText by remember(socksPort) { mutableStateOf(socksPort.toString()) }
    var socksUserText by remember(socksUsername) { mutableStateOf(socksUsername) }
    var socksPassText by remember(initialSocksPassword) { mutableStateOf(initialSocksPassword) }
    var socksFocused by remember { mutableStateOf(false) }

    // Local SOCKS5 proxy: NO Save button — each field saves
    // automatically 5s after the user stops typing, exactly like the
    // Resolvers section. The save itself never touches the text
    // field, so it cannot move the caret; only the port
    // normalization (on focus loss) rewrites the visible text, and
    // only while the field is not focused.
    LaunchedEffect(socksPortText, socksUserText, socksPassText) {
        delay(5000)
        val port = socksPortText.toIntOrNull()?.coerceIn(1024, 65535) ?: 1080
        if (port != socksPort ||
            socksUserText != socksUsername ||
            socksPassText != initialSocksPassword
        ) {
            onSetSocksConfig(port, socksUserText, socksPassText)
        }
    }
    // Normalize the port when the field loses focus ("80" -> "1080").
    LaunchedEffect(socksFocused) {
        if (!socksFocused) {
            val port = socksPortText.toIntOrNull()?.coerceIn(1024, 65535) ?: 1080
            if (port.toString() != socksPortText) socksPortText = port.toString()
        }
    }

    SettingsCard(title = stringResource(R.string.section_proxy)) {
        OutlinedTextField(
            value = socksPortText,
            onValueChange = { socksPortText = it.filter(Char::isDigit) },
            label = { Text("Port (default 1080)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { socksFocused = it.isFocused }
        )
        OutlinedTextField(
            value = socksUserText,
            onValueChange = { socksUserText = it },
            label = { Text("Username (optional)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { socksFocused = it.isFocused }
        )
        OutlinedTextField(
            value = socksPassText,
            onValueChange = { socksPassText = it },
            label = { Text("Password (optional, stored in Android Keystore)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { socksFocused = it.isFocused }
        )
    }
}

@Composable
private fun BandwidthSection(
    uploadLimitBps: Long,
    downloadLimitBps: Long,
    onSetBandwidthLimit: (Long, Long) -> Unit
) {
    // Bandwidth limits are entered in KB/s (1.0.38, matching iOS),
    // while the stored/applied value stays bytes/sec (x1024, same
    // factor iOS uses). A stored byte value that is not a multiple
    // of 1024 displays rounded down and re-saves as an exact
    // multiple — self-correcting.
    var upLimitText by remember(uploadLimitBps) { mutableStateOf((uploadLimitBps / 1024L).toString()) }
    var downLimitText by remember(downloadLimitBps) { mutableStateOf((downloadLimitBps / 1024L).toString()) }

    // Bandwidth limits: NO Save button — each saves automatically
    // 5s after the user stops typing, exactly like the other fields.
    LaunchedEffect(upLimitText, downLimitText) {
        delay(5000)
        val up = (upLimitText.toLongOrNull() ?: 0L) * 1024L
        val down = (downLimitText.toLongOrNull() ?: 0L) * 1024L
        if (up != uploadLimitBps || down != downloadLimitBps) {
            onSetBandwidthLimit(up, down)
        }
    }

    SettingsCard(title = stringResource(R.string.section_bandwidth)) {
        OutlinedTextField(
            value = upLimitText,
            onValueChange = { upLimitText = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.bandwidth_upload_kbps)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = downLimitText,
            onValueChange = { downLimitText = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.bandwidth_download_kbps)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RoutingSection(
    iranGeoBypass: Boolean,
    customBypassListJoined: String,
    connectionMode: ConnectionMode,
    perAppPackagesCount: Int,
    onSetIranGeoBypass: (Boolean) -> Unit,
    onSetCustomBypassList: (List<String>) -> Unit,
    onOpenPerAppRouting: () -> Unit
) {
    var customBypassText by remember(customBypassListJoined) {
        mutableStateOf(customBypassListJoined)
    }
    var customBypassFocused by remember { mutableStateOf(false) }

    // Custom bypass list: NO Save button — saves automatically 5s
    // after the user stops typing, exactly like the other fields.
    LaunchedEffect(customBypassText) {
        delay(5000)
        val parsed = parseLines(customBypassText)
        if (parsed.joinToString("\n") != customBypassListJoined) {
            onSetCustomBypassList(parsed)
        }
    }
    // Clean up the visible list when the field loses focus (drop empty
    // lines, trim, de-duplicate) — same as the Custom resolvers field.
    LaunchedEffect(customBypassFocused) {
        if (!customBypassFocused) {
            val joined = parseLines(customBypassText).joinToString("\n")
            if (joined != customBypassText) customBypassText = joined
        }
    }

    SettingsCard(title = stringResource(R.string.section_routing)) {
        SwitchRow(
            label = stringResource(R.string.iran_geo_bypass),
            checked = iranGeoBypass,
            onCheckedChange = onSetIranGeoBypass
        )

        OutlinedTextField(
            value = customBypassText,
            onValueChange = { customBypassText = it },
            label = { Text("${stringResource(R.string.custom_bypass_list)} (one domain/IP per line)") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { customBypassFocused = it.isFocused }
        )

        // VPN-mode-only controls: Per-app routing. (Kill Switch
        // and Always-on VPN were removed in 1.0.15 — they are
        // not effective for a DNS-tunnel app. Always-on is
        // delegated to Android's own system setting.)
        if (connectionMode == ConnectionMode.VPN) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            NavigationRow(
                label = "${stringResource(R.string.per_app_routing)} ($perAppPackagesCount)",
                onClick = onOpenPerAppRouting
            )
        }
    }
}

@Composable
private fun AppearanceSection(
    language: AppLanguage,
    themeMode: AppThemeMode,
    onSetLanguage: (AppLanguage) -> Unit,
    onSetThemeMode: (AppThemeMode) -> Unit
) {
    SettingsCard(title = stringResource(R.string.section_appearance)) {
        Text("Language / زبان", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnbarFilterChip(
                selected = language == AppLanguage.SYSTEM,
                onClick = { onSetLanguage(AppLanguage.SYSTEM) },
                label = { Text("System") }
            )
            UnbarFilterChip(
                selected = language == AppLanguage.ENGLISH,
                onClick = { onSetLanguage(AppLanguage.ENGLISH) },
                label = { Text("English") }
            )
            UnbarFilterChip(
                selected = language == AppLanguage.PERSIAN,
                onClick = { onSetLanguage(AppLanguage.PERSIAN) },
                label = { Text("فارسی") }
            )
        }

        Text("Theme", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnbarFilterChip(
                selected = themeMode == AppThemeMode.SYSTEM,
                onClick = { onSetThemeMode(AppThemeMode.SYSTEM) },
                label = { Text("System") }
            )
            UnbarFilterChip(
                selected = themeMode == AppThemeMode.LIGHT,
                onClick = { onSetThemeMode(AppThemeMode.LIGHT) },
                label = { Text("Light") }
            )
            UnbarFilterChip(
                selected = themeMode == AppThemeMode.DARK,
                onClick = { onSetThemeMode(AppThemeMode.DARK) },
                label = { Text("Dark") }
            )
        }
    }
}

@Composable
private fun ResolversHealthSection(
    discoveryMode: DiscoveryMode,
    resolverScanStatusFlow: StateFlow<ResolverScanStatus>,
    onTestResolvers: () -> Unit,
    onStopScan: () -> Unit,
    onPauseScan: () -> Unit,
    onResumeScan: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val healthStore = remember(context.applicationContext) {
        ResolverHealthStore(context.applicationContext)
    }
    val scanStatus by resolverScanStatusFlow.collectAsStateWithLifecycle()
    val remembered by healthStore.entriesFlow.collectAsState(initial = emptyList())
    val sortedRemembered = remember(remembered) { remembered.sortedBy { it.rttMs } }

    var isTesting by remember { mutableStateOf(false) }
    LaunchedEffect(scanStatus.scanRunning) {
        if (!scanStatus.scanRunning) isTesting = false
    }

    val testing = isTesting || scanStatus.scanRunning
    // A scan has completed (in this process) once any scan
    // counter is non-zero and no scan is running. The stepper
    // appears when a scan starts and stays visible after it
    // completes, showing all 3 stages done.
    val scanCompleted = !testing && (
        scanStatus.testedResolvers > 0 ||
            scanStatus.respondingResolvers > 0 ||
            scanStatus.verifiedCount > 0 ||
            scanStatus.scanVerifiedCount > 0
        )
    val showStepper = testing || scanCompleted

    // (1.0.32): Stop / Pause / Resume sit next to Test while a scan
    // runs. Test is disabled while testing; Stop cancels the scan
    // (partial results kept); Pause holds it and Resume continues
    // it — the button flips between Pause and Resume from the
    // scanPaused flag, so only one of the two is ever shown.
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = {
                isTesting = true
                onTestResolvers()
            },
            enabled = !testing
        ) {
            Text(stringResource(R.string.test_resolvers))
        }
        if (testing) {
            OutlinedButton(
                onClick = onStopScan
            ) {
                Text(stringResource(R.string.stop_scan))
            }
            if (scanStatus.scanPaused) {
                OutlinedButton(
                    onClick = onResumeScan
                ) {
                    Text(stringResource(R.string.resume_scan))
                }
            } else {
                OutlinedButton(
                    onClick = onPauseScan
                ) {
                    Text(stringResource(R.string.pause_scan))
                }
            }
        }
    }

    // B1 scan stepper (replaces the old spinner + "testing"
    // text and the responding/verified count rows — the stepper
    // IS the counts display now).
    if (showStepper) {
        ResolverScanStepper(
            testing = testing,
            scanCompleted = scanCompleted,
            testedResolvers = scanStatus.testedResolvers,
            totalResolvers = scanStatus.totalResolvers,
            respondingResolvers = scanStatus.respondingResolvers,
            // (1.0.33): stage 2 shows the SCAN's own vetted
            // count — what the scan itself verified — not the
            // live client's paths (which are 0 when
            // disconnected and never reflect the scan).
            verifiedCount = scanStatus.scanVerifiedCount,
            savedCount = sortedRemembered.size
        )
    }

if (discoveryMode == DiscoveryMode.BUILTIN && sortedRemembered.isNotEmpty()) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.remembered_resolvers_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        OutlinedButton(
            onClick = {
                clipboard.setText(
                    AnnotatedString(sortedRemembered.joinToString(", ") { it.addr })
                )
            }
        ) {
            Text(stringResource(R.string.copy_all_resolvers, sortedRemembered.size))
        }
    }
    val verifiedN = if (scanStatus.verifiedCount > 0) scanStatus.verifiedCount else sortedRemembered.size
    Text(
        text = stringResource(R.string.resolvers_spread_note, verifiedN),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        // (1.0.53): shows the 16 fastest remembered resolvers —
        // the pool can hold ~600 entries, so the full list is never
        // composed eagerly here; the rest is counted below and the
        // whole pool is still copied by "Copy all".
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            sortedRemembered.take(16).forEach { entry ->
                RememberedResolverRow(addr = entry.addr, rttMs = entry.rttMs)
            }
        }
    }
    if (sortedRemembered.size > 16) {
        Text(
            text = stringResource(R.string.remembered_resolvers_more, sortedRemembered.size - 16),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

}

// B1 scan stepper, minimal style (approved 2026-11-20): the 3 real
// scan stages as a delicate vertical timeline — a 1dp line, tiny
// markers (9dp hollow blue dot pending, 13dp orange check done,
// 12dp orange spinner active), no card behind the timeline, compact
// text and airy spacing. Stage 1 is the long live part (tested /
// total + responding); stage 2 vets next (up to 30s) with a LIVE
// verified count, then stage 3 lands when the scan completes. A
// completed stage gets an ORANGE checkmark — blue (#0B4F9E) and
// orange (#FF7A1A) only, no other success colour anywhere in this
// UI. (1.0.36): stages follow real progress — stage 1 goes DONE
// as soon as tested reaches total, stage 2 is ACTIVE during
// vetting with its live count.
private enum class ScanStageState { PENDING, ACTIVE, DONE }

@Composable
private fun ResolverScanStepper(
    testing: Boolean,
    scanCompleted: Boolean,
    testedResolvers: Int,
    totalResolvers: Int,
    respondingResolvers: Int,
    verifiedCount: Int,
    savedCount: Int
) {
    // (1.0.36): per-stage states follow REAL progress, not just
    // "scan running / scan done" — the 1.0.35 field report was
    // that the scan looked stuck on stage 1 (spinner) and then
    // suddenly jumped to all-done with the verified count.
    // Stage 1 is DONE as soon as tested reaches total (stage 1
    // finished), even while the scan still runs (stage 2
    // vetting). Stage 2 is ACTIVE while stage 1 is done and the
    // scan still runs — its count is live now (the Go core
    // publishes scanVerified on every vet poll). Stages 2 and 3
    // are DONE once the scan completes. All PENDING before any
    // scan.
    val stage1Done = scanCompleted ||
        (totalResolvers > 0 && testedResolvers >= totalResolvers)
    val stage1State = when {
        stage1Done -> ScanStageState.DONE
        testing -> ScanStageState.ACTIVE
        else -> ScanStageState.PENDING
    }
    val stage2State = when {
        scanCompleted -> ScanStageState.DONE
        stage1Done && testing -> ScanStageState.ACTIVE
        else -> ScanStageState.PENDING
    }
    val stage3State = if (scanCompleted) ScanStageState.DONE else ScanStageState.PENDING
    Column(modifier = Modifier.fillMaxWidth()) {
        ScanStageRow(
            state = stage1State,
            name = stringResource(R.string.scan_stage_screening),
            stats = listOf(
                stringResource(R.string.scan_tested, testedResolvers, totalResolvers),
                stringResource(R.string.resolvers_responding, respondingResolvers)
            ),
            isLast = false
        )
        ScanStageRow(
            state = stage2State,
            name = stringResource(R.string.scan_stage_handshake),
            stats = listOf(
                stringResource(R.string.resolvers_verified, verifiedCount)
            ),
            isLast = false
        )
        ScanStageRow(
            state = stage3State,
            name = stringResource(R.string.scan_stage_ranking),
            // The saved count is only meaningful once the scan
            // has landed; before that the remembered list is
            // still the previous scan's.
            stats = if (scanCompleted) {
                listOf(stringResource(R.string.scan_saved, savedCount))
            } else {
                emptyList()
            },
            isLast = true
        )
    }
}

@Composable
private fun ScanStageRow(
    state: ScanStageState,
    name: String,
    stats: List<String>,
    isLast: Boolean
) {
    // Minimal timeline: a 1dp line and tiny markers centred in a
    // 13dp column (the largest marker, the check, is 13dp). No
    // card behind the stepper — it floats on the section surface.
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(13.dp)
        ) {
            Box(
                modifier = Modifier.size(13.dp),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    ScanStageState.DONE -> Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = UnbarOrange,
                        modifier = Modifier.size(13.dp)
                    )
                    ScanStageState.ACTIVE -> CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = UnbarOrange
                    )
                    ScanStageState.PENDING -> Box(
                        modifier = Modifier
                            .size(9.dp)
                            .border(1.dp, UnbarBlue.copy(alpha = 0.5f), CircleShape)
                    )
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(UnbarBlue.copy(alpha = 0.25f))
                )
            }
        }
        Column(
            modifier = Modifier.padding(start = 10.dp, bottom = 16.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (state == ScanStageState.PENDING) FontWeight.Normal else FontWeight.Medium,
                color = if (state == ScanStageState.PENDING) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            stats.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SystemDiagnosticsSection(
    context: Context,
    showSpeedsInNotification: Boolean,
    diagnosticLogsEnabled: Boolean,
    notificationsAllowedFlow: StateFlow<Boolean>,
    isIgnoringBatteryFlow: StateFlow<Boolean>,
    onRefreshSystemStatuses: () -> Unit,
    onSetShowSpeedsInNotification: (Boolean) -> Unit,
    onSetDiagnosticLogsEnabled: (Boolean) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val notificationsAllowed by notificationsAllowedFlow.collectAsStateWithLifecycle()
    val isIgnoringBattery by isIgnoringBatteryFlow.collectAsStateWithLifecycle()
    var pendingStatusRefresh by remember { mutableStateOf(false) }

    // Check-for-update state: whether a check is running, the release
    // an available update offers (shown in the dialog), a release whose
    // download waits for the install permission, and the DownloadManager
    // id + APK name of a download in progress.
    var updateChecking by remember { mutableStateOf(false) }
    var updateDialog by remember { mutableStateOf<ReleaseInfo?>(null) }
    var pendingUpdateDownload by remember { mutableStateOf<ReleaseInfo?>(null) }
    var pendingDownloadId by remember { mutableStateOf(-1L) }
    var pendingApkName by remember { mutableStateOf<String?>(null) }
    val updateScope = rememberCoroutineScope()

    fun showUpdateToast(resId: Int, long: Boolean = false) {
        Toast.makeText(
            context,
            context.getString(resId),
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    // One check per tap, no retries: the result is a toast, or the
    // update dialog when a newer release exists.
    fun startUpdateCheck() {
        if (updateChecking) return
        updateChecking = true
        updateScope.launch {
            val result = AppUpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
            updateChecking = false
            when (result) {
                is UpdateCheckResult.UpToDate -> showUpdateToast(R.string.update_latest)
                is UpdateCheckResult.Failed -> showUpdateToast(
                    when (result.kind) {
                        UpdateFailureKind.NO_APK -> R.string.update_no_apk
                        else -> R.string.update_check_failed
                    },
                    long = true
                )
                is UpdateCheckResult.Available -> updateDialog = result.release
            }
        }
    }

    // Download (or install, when the APK is already on disk). The
    // install permission comes first: without it the installer cannot
    // open, so the release is remembered and the download starts when
    // the user comes back from system settings (see the ON_RESUME
    // observer below).
    fun startUpdateDownload(release: ReleaseInfo) {
        if (!AppUpdateChecker.canInstallUnknownApps(context)) {
            pendingUpdateDownload = release
            showUpdateToast(R.string.update_allow_install, long = true)
            AppUpdateChecker.openInstallUnknownAppsSettings(context)
            return
        }
        if (AppUpdateChecker.installDownloadedApk(context, release.apkName)) return
        val id = AppUpdateChecker.enqueueDownload(context, release)
        if (id == -1L) {
            showUpdateToast(R.string.update_download_failed, long = true)
            return
        }
        pendingDownloadId = id
        pendingApkName = release.apkName
        showUpdateToast(R.string.update_downloading)
    }

val lifecycleOwner = LocalLifecycleOwner.current
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            if (pendingStatusRefresh) {
                pendingStatusRefresh = false
                onRefreshSystemStatuses()
            }
            val pending = pendingUpdateDownload
            if (pending != null && AppUpdateChecker.canInstallUnknownApps(context)) {
                pendingUpdateDownload = null
                startUpdateDownload(pending)
            }
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
}

    // When a download started here finishes, open the installer for
    // it — or say so when the download itself failed. (If the user
    // left Settings meanwhile, the section is gone and so is this
    // receiver; the finished APK stays on disk and the next Download
    // tap installs it instead of downloading again.)
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == -1L || id != pendingDownloadId) return
                pendingDownloadId = -1L
                val apkName = pendingApkName
                pendingApkName = null
                if (apkName != null && AppUpdateChecker.isDownloadSuccessful(c, id)) {
                    AppUpdateChecker.installDownloadedApk(c, apkName)
                } else {
                    Toast.makeText(
                        c,
                        c.getString(R.string.update_download_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

SettingsCard(title = stringResource(R.string.section_diagnostics)) {
    SwitchRow(
        label = stringResource(R.string.speeds_in_notification),
        checked = showSpeedsInNotification,
        onCheckedChange = onSetShowSpeedsInNotification
    )
    HorizontalDivider()
    NavigationRow(
        label = if (notificationsAllowed) {
            "${stringResource(R.string.notification_permission)} (${stringResource(R.string.status_allowed)})"
        } else {
            "${stringResource(R.string.notification_permission)} (${stringResource(R.string.status_not_allowed)})"
        },
        onClick = {
            pendingStatusRefresh = true
            openNotificationSettings(context)
        }
    )
    HorizontalDivider()
    NavigationRow(
        label = if (isIgnoringBattery) {
            "${stringResource(R.string.battery_optimization)} (${stringResource(R.string.status_exempted)})"
        } else {
            stringResource(R.string.battery_optimization)
        },
        onClick = {
            pendingStatusRefresh = true
            openBatteryOptimizationSettings(context)
        }
    )
    HorizontalDivider()
    SwitchRow(
        label = stringResource(R.string.diagnostic_logs),
        subtitle = stringResource(R.string.diagnostic_logs_note),
        checked = diagnosticLogsEnabled,
        onCheckedChange = onSetDiagnosticLogsEnabled
    )
    HorizontalDivider()
    NavigationRow(
        label = stringResource(R.string.view_logs),
        onClick = onOpenLogs
    )
    HorizontalDivider()
    // (1.0.15, C8): connection history — last ~30 sessions.
    NavigationRow(
        label = stringResource(R.string.history_title),
        onClick = onOpenHistory
    )
    // Check for update: one tap checks GitHub Releases for a newer
    // build. While a check runs the row is disabled and shows a
    // spinner instead of the chevron. Hidden in F-Droid builds
    // (-Pfdroid), where F-Droid itself delivers updates.
    if (BuildConfig.UPDATE_CHECK) {
    HorizontalDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !updateChecking) { startUpdateCheck() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.update_check),
            style = MaterialTheme.typography.bodyMedium
        )
        if (updateChecking) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
    }
}

    // A newer release is available: its version + notes, with
    // Download / Later. The notes scroll when they are long.
    if (updateDialog != null) {
        val release = updateDialog!!
        AnimatedDialog(onDismiss = { updateDialog = null }) { dismiss ->
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text(text = stringResource(R.string.update_available_title)) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.update_available_body, release.version),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (release.notes.isNotBlank()) {
                            Text(
                                text = release.notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            dismiss()
                            startUpdateDownload(release)
                        }
                    ) {
                        Text(stringResource(R.string.update_download))
                    }
                },
                dismissButton = {
                    TextButton(onClick = dismiss) {
                        Text(stringResource(R.string.update_later))
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable private fun SwitchRow(
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // The OFF state must still read as an interactive switch, not a
        // greyed-out disabled control: a light-blue track and a white
        // thumb with a blue border. The ON state is the brand blue.
        val blue = MaterialTheme.colorScheme.primary
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = blue,
                checkedThumbColor = Color.White,
                checkedBorderColor = blue,
                uncheckedTrackColor = blue.copy(alpha = 0.22f),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = blue
            )
        )
    }
}

@Composable
private fun NavigationRow(
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
    }
}

// (1.0.16, item 4, Model A): one remembered healthy resolver. The IP is monospace with the ":53" dimmed beside it; the copy icon copies the full "IP:53" (same pattern as AboutScreen's CopyableAddressRow). The ping is shown small, after the address.
// The row is numbers + an IP (mathematical, left-to-right): forced LTR so the
// Persian RTL locale neither mirrors the row nor reverses the IP octets.
@Composable private fun RememberedResolverRow(addr: String, rttMs: Int) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val ip = addr.substringBeforeLast(':')
    val port = addr.substringAfterLast(':', "")
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ip,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
                if (port.isNotEmpty()) {
                    Text(
                        text = ":$port",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "  $rttMs ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.copy),
                tint = if (copied) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable {
                        clipboard.setText(AnnotatedString(addr))
                        copied = true
                    }
                    .padding(6.dp)
                    .size(20.dp)
            )
        }
    }
}

private fun parseLines(raw: String): List<String> = raw.split('\n', ',').map { it.trim()
}.filter { it.isNotEmpty() }.distinct()

private fun openBatteryOptimizationSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        runCatching { context.startActivity(intent) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

private fun openNotificationSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            openAppDetailsSettings(context)
        }
    } else {
        openAppDetailsSettings(context)
    }
}

private fun openAppDetailsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}
