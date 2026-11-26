package com.breakthebarriers.unbar.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.breakthebarriers.unbar.R
import com.breakthebarriers.unbar.model.AppThemeMode
import com.breakthebarriers.unbar.ui.components.UnbarLogo
import com.breakthebarriers.unbar.ui.screens.AboutScreen
import com.breakthebarriers.unbar.ui.screens.HistoryScreen
import com.breakthebarriers.unbar.ui.screens.HomeScreen
import com.breakthebarriers.unbar.ui.screens.LogsScreen
import com.breakthebarriers.unbar.ui.screens.PerAppRoutingScreen
import com.breakthebarriers.unbar.ui.screens.QrScannerScreen
import com.breakthebarriers.unbar.ui.screens.ServersScreen
import com.breakthebarriers.unbar.ui.screens.SettingsScreen
import com.breakthebarriers.unbar.ui.screens.SplashScreen
import com.breakthebarriers.unbar.ui.theme.UnbarCapsuleBlue
import com.breakthebarriers.unbar.ui.theme.UnbarOrange
import com.breakthebarriers.unbar.ui.theme.UnbarTheme

private object Routes {
    const val HOME = "home"
    const val SERVERS = "servers"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val PER_APP = "per_app"
    const val LOGS = "logs"
    const val HISTORY = "history"
    const val QR_SCAN = "qr_scan"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnbarApp(
    viewModel: MainViewModel,
    onRequestConnect: () -> Unit
) {
    // (1.0.21, lag fix): the root subscribes ONLY to the theme. The
    // full settings used to be collected here, so EVERY settings change
    // (one switch toggle) recomposed the whole Scaffold + NavHost +
    // TopAppBar + capsule. Each destination now collects the settings
    // (or the single field) it needs inside itself, so a toggle only
    // recomposes the screen that shows it.
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    // (1.0.16): the Compose splash (SplashScreen) is shown until the app data is ready (showHome) AND 1000ms have elapsed since the splash was first drawn — the minimum is measured from the first drawn frame, inside SplashScreen. The 5s timeout inside showHome is the safety net. splashFinished is one-way: once the main UI appears the splash never comes back (e.g. on a later recomposition).
    val showHome by viewModel.showHome.collectAsStateWithLifecycle()
    var splashFinished by remember { mutableStateOf(false) }
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

UnbarTheme(themeMode = themeMode) {
    if (!splashFinished) {
        SplashScreen(
            isDark = isDark,
            showHome = showHome,
            onSplashFinished = { splashFinished = true }
        )
        return@UnbarTheme
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.HOME

    val topLevelRoutes = setOf(Routes.HOME, Routes.SERVERS, Routes.SETTINGS, Routes.ABOUT)
    val showChrome = currentRoute in topLevelRoutes

    Scaffold(
        topBar = {
            if (showChrome) {
                // (1.0.25): the TopAppBar's own container color animates
                // (~300ms) on a theme switch, so the bar sat as a stark
                // white/black band while the rest of the UI had already
                // flipped (the content reads colorScheme directly and
                // flips instantly). The background is now drawn by this
                // Box, which reads colorScheme.surface directly and
                // flips instantly too; the TopAppBar itself is
                // transparent, so its internal animation has nothing
                // visible to animate.
                Box(
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    // (1.0.31): the Home top bar (logo + moon) stays LTR
                    // in every language — in Persian the bar must not
                    // mirror, so the logo stays left and the theme
                    // toggle stays right. Other routes keep the ambient
                    // direction, so their Persian titles stay right-aligned.
                    val topBarDirection = if (currentRoute == Routes.HOME) LayoutDirection.Ltr else LocalLayoutDirection.current
                    CompositionLocalProvider(LocalLayoutDirection provides topBarDirection) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent
                        ),
                        title = {
                        AnimatedContent(
                            targetState = currentRoute,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(200)) + scaleIn(
                                    initialScale = 0.98f,
                                    animationSpec = tween(200)
                                )).togetherWith(fadeOut(animationSpec = tween(150)))
                            },
                            label = "topBarTitle"
                        ) { route ->
                            when (route) {
                                // (1.0.15): the brand tagline sits next
                                // to the wordmark on Home — smaller
                                // than the name but readable (12sp,
                                // muted gray, matching the logo
                                // tagline), always in English in both
                                // languages (it is the brand tagline,
                                // same as the logo).
                                Routes.HOME -> Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    UnbarLogo(
                                        isDark = isDark,
                                        showTagline = false,
                                        iconSize = 28.dp,
                                        compact = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.brand_tagline),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                Routes.SERVERS -> Text(stringResource(R.string.servers_title))
                                Routes.SETTINGS -> Text(stringResource(R.string.settings_title))
                                Routes.ABOUT -> Text(stringResource(R.string.about_title))
                            }
                        }
                    },
                    actions = {
                        AnimatedContent(
                            targetState = currentRoute == Routes.HOME,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(200))
                                    .togetherWith(fadeOut(animationSpec = tween(150)))
                            },
                            label = "topBarActions"
                        ) { onHome ->
                            if (onHome) {
                                // Debug shortcut (1.0.38): opens the logs
                                // screen, next to the theme toggle.
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            navController.navigate(Routes.LOGS)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.BugReport,
                                            contentDescription = stringResource(R.string.view_logs),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            viewModel.setThemeMode(
                                                if (isDark) AppThemeMode.LIGHT else AppThemeMode.DARK
                                            )
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
                } // CompositionLocalProvider (Home top bar LTR)
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // (A1, 1.0.15): Material motion for navigation.
            // Top-level destinations fade through; drill-downs
            // slide+fade from the END side (RTL-aware: in Persian
            // the end side is the left). Pop is the reverse.
            val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            val fadeThroughEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
                fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.98f, animationSpec = tween(200))
            }
            val fadeThroughExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
                fadeOut(animationSpec = tween(150))
            }
            val drillEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
                slideInHorizontally(initialOffsetX = { if (isRtl) -it / 3 else it / 3 }) + fadeIn(animationSpec = tween(250))
            }
            val drillExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
                slideOutHorizontally(targetOffsetX = { if (isRtl) it / 3 else -it / 3 }) + fadeOut(animationSpec = tween(250))
            }
            val drillPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
                slideInHorizontally(initialOffsetX = { if (isRtl) it / 3 else -it / 3 }) + fadeIn(animationSpec = tween(250))
            }
            val drillPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
                slideOutHorizontally(targetOffsetX = { if (isRtl) -it / 3 else it / 3 }) + fadeOut(animationSpec = tween(250))
            }
            NavHost(
                navController = navController,
                startDestination = Routes.HOME
            ) {
                composable(
                    route = Routes.HOME,
                    enterTransition = fadeThroughEnter,
                    exitTransition = fadeThroughExit,
                    popEnterTransition = fadeThroughEnter,
                    popExitTransition = fadeThroughExit
                ) {
                    val servers by viewModel.servers.collectAsStateWithLifecycle()
                    // (1.0.21, lag fix): Home collects the settings
                    // itself, inside its destination — a settings
                    // change recomposes Home only, not the root.
                    val settings by viewModel.settings.collectAsStateWithLifecycle()
                    HomeScreen(
                        snapshotFlow = viewModel.tunnelSnapshot,
                        latchedErrorFlow = viewModel.latchedError,
                        settings = settings,
                        servers = servers,
                        dailyUsageBytesFlow = viewModel.dailyUsageBytes,
                        resolverScanStatusFlow = viewModel.resolverScanStatus,
                        onScanResolvers = viewModel::testResolvers,
                        onConnectClick = onRequestConnect,
                        onDisconnectClick = { viewModel.disconnect() },
                        // (1.0.52): the status-card mode switcher. The
                        // reconnect goes through onRequestConnect (the
                        // Activity's startConnectionFlow) so the VPN
                        // -permission gate still applies when switching
                        // into VPN mode.
                        onSwitchMode = { mode -> viewModel.switchMode(mode, onRequestConnect) },
                        onNavigateToServers = {
                            navController.navigate(Routes.SERVERS) {
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(
                    route = Routes.SERVERS,
                    enterTransition = fadeThroughEnter,
                    exitTransition = fadeThroughExit,
                    popEnterTransition = fadeThroughEnter,
                    popExitTransition = fadeThroughExit
                ) {
                    val servers by viewModel.servers.collectAsStateWithLifecycle()
                    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
                    val subscriptionStatus by viewModel.subscriptionStatus.collectAsStateWithLifecycle()
                    val updatingSubscriptionIds by viewModel.updatingSubscriptionIds.collectAsStateWithLifecycle()
                    val autoUpdateSubscriptions by viewModel.autoUpdateSubscriptions.collectAsStateWithLifecycle()
                    val clearSubscriptionUrl by viewModel.clearSubscriptionUrl.collectAsStateWithLifecycle()
                    val testingIds by viewModel.testingServerIds.collectAsStateWithLifecycle()
                    val isAutoSelecting by viewModel.isAutoSelecting.collectAsStateWithLifecycle()
                    val scannedImportText by viewModel.scannedImportText.collectAsStateWithLifecycle()
                    // (1.0.21, lag fix): Servers only needs the active
                    // server id — a narrow derived flow, not the whole
                    // settings object.
                    val activeServerId by viewModel.activeServerId.collectAsStateWithLifecycle()
                    ServersScreen(
                        servers = servers,
                        activeServerId = activeServerId,
                        testingServerIds = testingIds,
                        isAutoSelecting = isAutoSelecting,
                        scannedImportText = scannedImportText,
                        onConsumeScannedImportText = viewModel::consumeScannedImportText,
                        onOpenQrScanner = { navController.navigate(Routes.QR_SCAN) },
                        onSelectServer = viewModel::selectServer,
                        onSaveServer = viewModel::saveServer,
                        onDeleteServer = viewModel::deleteServer,
                        onTestServer = viewModel::testServer,
                        onAutoSelectFastest = viewModel::autoSelectFastestServer,
                        onExportUri = viewModel::exportServerUri,
                        onImportConfig = viewModel::importConfigString,
                        subscriptions = subscriptions,
                        subscriptionStatus = subscriptionStatus,
                        updatingSubscriptionIds = updatingSubscriptionIds,
                        autoUpdateSubscriptions = autoUpdateSubscriptions,
                        clearSubscriptionUrl = clearSubscriptionUrl,
                        onConsumeClearSubscriptionUrl = viewModel::consumeClearSubscriptionUrl,
                        onAddOrUpdateSubscription = viewModel::addOrUpdateSubscription,
                        onUpdateSubscriptionUrl = viewModel::updateSubscriptionUrl,
                        onRefreshSubscription = viewModel::refreshSubscription,
                        onDeleteSubscription = viewModel::deleteSubscription,
                        onSetAutoUpdateSubscriptions = viewModel::setAutoUpdateSubscriptions,
                        onClearSubscriptionError = viewModel::clearSubscriptionError
                    )
                }

                composable(
                    route = Routes.SETTINGS,
                    enterTransition = fadeThroughEnter,
                    exitTransition = fadeThroughExit,
                    popEnterTransition = fadeThroughEnter,
                    popExitTransition = fadeThroughExit
                ) {
                    // (1.0.21, lag fix): Settings collects the settings
                    // itself, inside its destination — a toggle
                    // recomposes Settings only, not the root.
                    val settings by viewModel.settings.collectAsStateWithLifecycle()
                    SettingsScreen(
                        settings = settings,
                        socksPasswordFlow = viewModel.socksPassword,
                        resolverScanStatusFlow = viewModel.resolverScanStatus,
                        notificationsAllowedFlow = viewModel.notificationsAllowed,
                        isIgnoringBatteryFlow = viewModel.isIgnoringBattery,
                        onTestResolvers = viewModel::testResolvers,
                        onStopScan = viewModel::stopScan,
                        onPauseScan = viewModel::pauseScan,
                        onResumeScan = viewModel::resumeScan,
                        onRefreshSystemStatuses = viewModel::refreshSystemStatuses,
                        onSetConnectionMode = viewModel::setConnectionMode,
                        onSetResolverConfig = viewModel::setResolverConfig,
                        onImportResolverList = viewModel::importResolverList,
                        onClearImportedResolvers = viewModel::clearImportedResolvers,
                        onSetTcpMode = viewModel::setTcpMode,
                        onSetMtu = viewModel::setMtu,
                        onSetMaxAnswer = viewModel::setMaxAnswer,
                        onSetSocksConfig = viewModel::setSocksConfig,
                        onSetBandwidthLimit = viewModel::setBandwidthLimit,
                        onSetIranGeoBypass = viewModel::setIranGeoBypass,
                        onSetCustomBypassList = viewModel::setCustomBypassList,
                        onSetLanguage = viewModel::setLanguage,
                        onSetThemeMode = viewModel::setThemeMode,
                        onSetShowSpeedsInNotification = viewModel::setShowSpeedsInNotification,
                        onSetDiagnosticLogsEnabled = viewModel::setDiagnosticLogsEnabled,
                        onOpenPerAppRouting = { navController.navigate(Routes.PER_APP) },
                        onOpenLogs = { navController.navigate(Routes.LOGS) },
                        onOpenHistory = { navController.navigate(Routes.HISTORY) }
                    )
                }

                composable(
                    route = Routes.ABOUT,
                    enterTransition = fadeThroughEnter,
                    exitTransition = fadeThroughExit,
                    popEnterTransition = fadeThroughEnter,
                    popExitTransition = fadeThroughExit
                ) {
                    AboutScreen(themeMode = themeMode)
                }

                composable(
                    route = Routes.PER_APP,
                    enterTransition = drillEnter,
                    exitTransition = drillExit,
                    popEnterTransition = drillPopEnter,
                    popExitTransition = drillPopExit
                ) {
                    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
                    val isLoadingApps by viewModel.isLoadingApps.collectAsStateWithLifecycle()
                    // (1.0.21, lag fix): Per-app routing collects the
                    // settings itself, inside its destination.
                    val settings by viewModel.settings.collectAsStateWithLifecycle()
                    PerAppRoutingScreen(
                        mode = settings.perAppMode,
                        selectedPackages = settings.perAppPackages,
                        apps = installedApps,
                        isLoading = isLoadingApps,
                        onLoadApps = viewModel::loadInstalledApps,
                        onUpdate = viewModel::setPerAppRouting,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Routes.LOGS,
                    enterTransition = drillEnter,
                    exitTransition = drillExit,
                    popEnterTransition = drillPopEnter,
                    popExitTransition = drillPopExit
                ) {
                    val logs by viewModel.logs.collectAsStateWithLifecycle()
                    LogsScreen(
                        logs = logs,
                        onClear = viewModel::clearLogs,
                        onExportText = viewModel::exportLogsText,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Routes.HISTORY,
                    enterTransition = drillEnter,
                    exitTransition = drillExit,
                    popEnterTransition = drillPopEnter,
                    popExitTransition = drillPopExit
                ) {
                    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
                    val monthlyUsage by viewModel.monthlyUsageBytes.collectAsStateWithLifecycle()
                    HistoryScreen(
                        sessions = sessions,
                        monthlyUsageBytes = monthlyUsage,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Routes.QR_SCAN,
                    enterTransition = drillEnter,
                    exitTransition = drillExit,
                    popEnterTransition = drillPopEnter,
                    popExitTransition = drillPopExit
                ) {
                    QrScannerScreen(
                        onScanned = { text ->
                            viewModel.setScannedImportText(text)
                            navController.popBackStack()
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
            }

            if (showChrome) {
                FloatingCapsuleMenu(
                    currentRoute = currentRoute,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                    onNavigate = { route ->
                        if (route == Routes.HOME) {
                            // Hardened Home navigation: pop everything above
                            // Home first so tapping Home always lands on a
                            // single Home instance.
                            navController.popBackStack(Routes.HOME, inclusive = false)
                            navController.navigate(Routes.HOME) {
                                launchSingleTop = true
                            }
                        } else {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                )
            }
        }
    }
}

}

// Capsule colour: UnbarCapsuleBlue (ui/theme/Theme.kt), per theme.

@Composable
private fun CapsuleMenuIcon(expanded: Boolean) {
    // White hamburger (3 lines; middle line shorter + shifted left),
    // turning into an X when expanded — the approved Model C icon.
    Canvas(modifier = Modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.6.dp.toPx()
        val white = Color.White
        if (!expanded) {
            drawLine(white, Offset(w * 0.12f, h * 0.28f), Offset(w * 0.88f, h * 0.28f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(white, Offset(w * 0.12f, h * 0.50f), Offset(w * 0.62f, h * 0.50f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(white, Offset(w * 0.12f, h * 0.72f), Offset(w * 0.88f, h * 0.72f), strokeWidth = stroke, cap = StrokeCap.Round)
        } else {
            val m = w * 0.20f
            drawLine(white, Offset(m, m), Offset(w - m, h - m), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(white, Offset(w - m, m), Offset(m, h - m), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

// (1.0.2): every item, selected or not, has the same box (icon,
// 2dp gap, one trimmed label line, the pill's insets), so the icons
// sit on one line, the block is centred in the capsule with equal
// space above and below, and nothing moves when the selection moves.
@Composable
private fun CapsuleMenuItem(icon: ImageVector, labelRes: Int, selected: Boolean) {
    Column(
        modifier = Modifier
            .background(
                if (selected) UnbarOrange else Color.Transparent,
                // Nested radius: capsule 16dp - 5dp inset = 11dp.
                RoundedCornerShape(11.dp)
            )
            .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(labelRes),
            fontSize = 12.sp,
            lineHeight = 14.sp,
            // Glyphs centred in the line box and the extra leading
            // trimmed, so the label adds no uneven space below.
            style = LocalTextStyle.current.copy(
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both
                )
            ),
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = Color.White
        )
    }
}

@Composable
private fun FloatingCapsuleMenu(
    currentRoute: String,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // (1.0.2): 316dp (was 340dp) — 12sp labels and 5dp pill insets
    // let 4 items of 63dp (+8dp start inset = 260dp) hold the longest
    // label ("Settings" / "تنظیمات") without ellipsis. The 56dp close
    // button is unchanged, so items = 316-56.
    val capsuleWidth by animateDpAsState(
        targetValue = if (expanded) 316.dp else 56.dp,
        animationSpec = tween(220),
        label = "capsuleMenuWidth"
    )
    val items = remember {
        listOf(
            Triple(Routes.HOME, R.string.nav_home, Icons.Rounded.Home),
            Triple(Routes.SERVERS, R.string.nav_servers, Icons.Rounded.Dns),
            Triple(Routes.SETTINGS, R.string.nav_settings, Icons.Rounded.Settings),
            Triple(Routes.ABOUT, R.string.nav_about, Icons.Rounded.Info)
        )
    }

    Surface(
        modifier = modifier
            .width(capsuleWidth)
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        color = UnbarCapsuleBlue,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(120)),
                modifier = Modifier
                    .weight(1f)
                    .clipToBounds()
            ) {
                Row(
                    modifier = Modifier
                        .requiredWidth(260.dp)
                        .fillMaxHeight()
                        .padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEach { (route, labelRes, icon) ->
                        val selected = currentRoute == route
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable {
                                    expanded = false
                                    onNavigate(route)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            CapsuleMenuItem(icon, labelRes, selected)
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { expanded = !expanded },
                contentAlignment = Alignment.Center
            ) {
                CapsuleMenuIcon(expanded = expanded)
            }
        }
    }
}
