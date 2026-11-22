package com.breakthebarriers.unbar.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakthebarriers.unbar.R
import com.breakthebarriers.unbar.data.config.ServerValidation
import com.breakthebarriers.unbar.data.db.ServerEntity
import com.breakthebarriers.unbar.data.db.SubscriptionEntity
import com.breakthebarriers.unbar.data.subscription.SubscriptionErrorKind
import com.breakthebarriers.unbar.data.subscription.SubscriptionStatus
import com.breakthebarriers.unbar.ui.components.TutorialAccordion
import com.breakthebarriers.unbar.ui.components.TutorialGroup
import com.breakthebarriers.unbar.ui.components.TutorialTopic
import com.breakthebarriers.unbar.ui.components.AnimatedDialog
import com.breakthebarriers.unbar.ui.components.ExportConfigDialog
import com.breakthebarriers.unbar.ui.components.ImportConfigForm
import com.breakthebarriers.unbar.ui.theme.UnbarBlue
import com.breakthebarriers.unbar.ui.theme.UnbarOrange
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServersScreen(
    servers: List<ServerEntity>,
    activeServerId: Long?,
    testingServerIds: Set<Long>,
    isAutoSelecting: Boolean,
    scannedImportText: String?,
    onConsumeScannedImportText: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onSelectServer: (Long) -> Unit,
    onSaveServer: (ServerEntity?, String, String, String) -> Unit,
    onDeleteServer: (ServerEntity) -> Unit,
    onTestServer: (ServerEntity) -> Unit,
    onAutoSelectFastest: () -> Unit,
    onExportUri: (ServerEntity) -> String,
    onImportConfig: (String) -> String?,
    subscriptions: List<SubscriptionEntity>,
    subscriptionStatus: SubscriptionStatus?,
    updatingSubscriptionIds: Set<Long>,
    autoUpdateSubscriptions: Boolean,
    clearSubscriptionUrl: Boolean,
    onConsumeClearSubscriptionUrl: () -> Unit,
    onAddOrUpdateSubscription: (String) -> Unit,
    onUpdateSubscriptionUrl: (Long, String) -> Unit,
    onRefreshSubscription: (SubscriptionEntity) -> Unit,
    onDeleteSubscription: (SubscriptionEntity) -> Unit,
    onSetAutoUpdateSubscriptions: (Boolean) -> Unit,
    onClearSubscriptionError: () -> Unit
) {
    var editingServer by remember { mutableStateOf<ServerEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var addSheetPage by remember { mutableStateOf(AddSheetPage.Cards) }
    var exportingServer by remember { mutableStateOf<ServerEntity?>(null) }
    // Text pre-filled into the Import page of the Add sheet (from a
    // scanned QR code).
    var importPrefill by remember { mutableStateOf("") }
    // URL typed into the Subscription page of the Add sheet.
    var subscriptionUrl by remember { mutableStateOf("") }
    // Id of the subscription being edited in the Add sheet, or null
    // when the sheet is adding a new one. Set by the pencil on a
    // subscription row; cleared when the sheet dismisses.
    var editingSubscriptionId by remember { mutableStateOf<Long?>(null) }

    // When the QR scanner returns a value, feed it into the Import
    // page of the Add sheet and open it there, then clear the shared
    // value.
    LaunchedEffect(scannedImportText) {
        if (scannedImportText != null) {
            importPrefill = scannedImportText
            addSheetPage = AddSheetPage.Import
            showAddSheet = true
            onConsumeScannedImportText()
        }
    }

    LaunchedEffect(clearSubscriptionUrl) {
        if (clearSubscriptionUrl) {
            subscriptionUrl = ""
            onConsumeClearSubscriptionUrl()
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    addSheetPage = AddSheetPage.Cards
                    showAddSheet = true
                },
                containerColor = UnbarOrange,
                contentColor = Color.White
            ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.add_sheet_title))
            }
        }
    ) { _ ->
        // (Before-release #4): no .padding(innerPadding) here —
        // the parent Scaffold in UnbarApp already places this
        // content below the TopAppBar; applying the inner
        // Scaffold's padding again double-counted the status bar
        // and pushed the first card ~24dp too low. The LazyColumn's
        // own contentPadding (12dp) is the top gap.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Tutorial — two-level accordion at the top of the Servers
            // screen. Only the "Tutorial" header shows first; tapping it
            // reveals the 5 Servers topics, and tapping a topic shows
            // its short paragraph.
            item(key = "tutorial") {
                TutorialAccordion(
                    groups = listOf(
                        TutorialGroup(
                            titleRes = null,
                            topics = listOf(
                                TutorialTopic(R.string.tutorial_servers_add_title, R.string.tutorial_servers_add_body),
                                TutorialTopic(R.string.tutorial_servers_import_title, R.string.tutorial_servers_import_body),
                                TutorialTopic(R.string.tutorial_servers_subscription_title, R.string.tutorial_servers_subscription_body),
                                TutorialTopic(R.string.tutorial_servers_latency_title, R.string.tutorial_servers_latency_body),
                                TutorialTopic(R.string.tutorial_servers_edit_title, R.string.tutorial_servers_edit_body)
                            )
                        )
                    )
                )
            }

            // The subscription section only exists once at least one
            // subscription has been added; with an empty list nothing
            // is rendered for subscriptions at all.
            if (subscriptions.isNotEmpty()) {
                item(key = "subscription_card") {
                    SubscriptionCard(
                        subscriptions = subscriptions,
                        subscriptionStatus = subscriptionStatus,
                        isUpdating = updatingSubscriptionIds.isNotEmpty() ||
                            subscriptionStatus is SubscriptionStatus.Updating,
                        autoUpdateSubscriptions = autoUpdateSubscriptions,
                        onSetAutoUpdate = onSetAutoUpdateSubscriptions
                    )
                }

                items(subscriptions, key = { "sub_${it.id}" }) { subscription ->
                    SubscriptionRowCard(
                        subscription = subscription,
                        isUpdating = updatingSubscriptionIds.contains(subscription.id),
                        onRefresh = { onRefreshSubscription(subscription) },
                        onEdit = {
                            subscriptionUrl = subscription.url
                            editingSubscriptionId = subscription.id
                            addSheetPage = AddSheetPage.Subscription
                            showAddSheet = true
                        },
                        onDelete = { onDeleteSubscription(subscription) },
                        modifier = Modifier.animateItemPlacement(tween(250))
                    )
                }
            }

            item(key = "server_actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Auto-select (V1, button 2): solid blue pill,
                    // white wand + white text. Same onClick, enabled
                    // state and spinner state as before — the spinner
                    // is white here, or it would vanish on the blue.
                    Button(
                        onClick = onAutoSelectFastest,
                        enabled = servers.isNotEmpty() && !isAutoSelecting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = UnbarBlue,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isAutoSelecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.auto_select_fastest),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (servers.isEmpty()) {
                item(key = "servers_empty") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.servers_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.servers_empty_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(servers, key = { "srv_${it.id}" }) { server ->
                    val isSelected = server.id == activeServerId
                    val isTesting = testingServerIds.contains(server.id)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItemPlacement(tween(250))
                            .clickable { onSelectServer(server.id) },
                        shape = RoundedCornerShape(16.dp),
                        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // Selection dot (V1): filled blue
                                        // when selected, hollow grey
                                        // otherwise — replaces the
                                        // checkmark icon.
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(if (isSelected) UnbarBlue else Color.Transparent)
                                                .border(
                                                    1.5.dp,
                                                    if (isSelected) UnbarBlue else MaterialTheme.colorScheme.outline,
                                                    CircleShape
                                                )
                                        )
                                        Text(
                                            text = server.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (server.subscriptionId != null) {
                                            Surface(
                                                color = UnbarOrange.copy(alpha = 0.14f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.subscription_badge),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = UnbarOrange,
                                                    maxLines = 1,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = server.zone,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 16.dp)
                                    )
                                }

                                // Health / latency (1.0.15, C6): the ms is
                                // the generate_204 probe — the same probe
                                // the Home latency shows — so the two are
                                // comparable. A dial-based fallback is
                                // labelled "(dial)" so it is never
                                // mistaken for a real RTT. Healthy +
                                // measured shows as a light-blue pill (V1).
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    when {
                                        isTesting -> CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        server.lastHealthy == true && server.lastLatencyMs != null -> {
                                            Surface(
                                                color = UnbarBlue.copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text(
                                                    // LRM prefix: "145 ms" must not flip to "ms 145" in the Persian RTL locale.
                                                    text = "\u200E${server.lastLatencyMs} ms",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = UnbarBlue,
                                                    maxLines = 1,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }
                                            if (server.lastLatencyProbe == "dial") {
                                                Text(
                                                    text = stringResource(R.string.latency_probe_dial),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        server.lastHealthy == false -> Text(
                                            text = "Unreachable",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = UnbarOrange
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                IconButton(onClick = { onTestServer(server) }, enabled = !isTesting) {
                                    Icon(Icons.Rounded.Speed, contentDescription = stringResource(R.string.test))
                                }
                                IconButton(onClick = { exportingServer = server }) {
                                    Icon(Icons.Rounded.QrCode2, contentDescription = stringResource(R.string.export_config))
                                }
                                IconButton(onClick = {
                                    editingServer = server
                                    showAddDialog = true
                                }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.edit))
                                }
                                IconButton(onClick = { onDeleteServer(server) }) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.delete),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }

    if (showAddDialog) {
        ServerEditorDialog(
            initial = editingServer,
            existingServers = servers,
            onSave = { name, zone, key ->
                onSaveServer(editingServer, name, zone, key)
                // (A4): do NOT set showAddDialog = false here — the
                // dialog's animated dismiss calls onDismiss after
                // the exit animation plays.
            },
            onDismiss = { showAddDialog = false }
        )
    }

    if (showAddSheet) {
        AddSheet(
            page = addSheetPage,
            onPageChange = { addSheetPage = it },
            importPrefill = importPrefill,
            onImportConfig = onImportConfig,
            onOpenQrScanner = onOpenQrScanner,
            servers = servers,
            onSaveServer = onSaveServer,
            subscriptionUrl = subscriptionUrl,
            onSubscriptionUrlChange = {
                subscriptionUrl = it
                onClearSubscriptionError()
            },
            subscriptions = subscriptions,
            subscriptionStatus = subscriptionStatus,
            isUpdatingSubscription = updatingSubscriptionIds.isNotEmpty() ||
                subscriptionStatus is SubscriptionStatus.Updating,
            onAddOrUpdateSubscription = { onAddOrUpdateSubscription(subscriptionUrl) },
            editingSubscriptionId = editingSubscriptionId,
            onUpdateSubscriptionUrl = { id -> onUpdateSubscriptionUrl(id, subscriptionUrl) },
            onDismiss = {
                showAddSheet = false
                importPrefill = ""
                editingSubscriptionId = null
            }
        )
    }

    exportingServer?.let { server ->
        ExportConfigDialog(
            serverName = server.name,
            exportUri = onExportUri(server),
            onDismiss = { exportingServer = null }
        )
    }
}

private enum class AddSheetPage { Cards, Import, Manual, Subscription }

@Composable
private fun AddSheet(
    page: AddSheetPage,
    onPageChange: (AddSheetPage) -> Unit,
    importPrefill: String,
    onImportConfig: (String) -> String?,
    onOpenQrScanner: () -> Unit,
    servers: List<ServerEntity>,
    onSaveServer: (ServerEntity?, String, String, String) -> Unit,
    subscriptionUrl: String,
    onSubscriptionUrlChange: (String) -> Unit,
    subscriptions: List<SubscriptionEntity>,
    subscriptionStatus: SubscriptionStatus?,
    isUpdatingSubscription: Boolean,
    onAddOrUpdateSubscription: () -> Unit,
    editingSubscriptionId: Long?,
    onUpdateSubscriptionUrl: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    // (A4, 1.0.15): scale+fade enter/exit instead of popping in/out.
    AnimatedDialog(onDismiss = onDismiss) { dismiss ->
        Dialog(
            onDismissRequest = dismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.add_sheet_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    when (page) {
                        AddSheetPage.Cards -> {
                            AddSheetCard(
                                icon = Icons.Rounded.QrCodeScanner,
                                titleRes = R.string.import_config,
                                subtitleRes = R.string.add_card_import_subtitle,
                                onClick = { onPageChange(AddSheetPage.Import) }
                            )
                            AddSheetCard(
                                icon = Icons.Rounded.Edit,
                                titleRes = R.string.add_server_cta,
                                subtitleRes = R.string.add_card_server_subtitle,
                                onClick = { onPageChange(AddSheetPage.Manual) }
                            )
                            AddSheetCard(
                                icon = Icons.Rounded.Link,
                                titleRes = R.string.subscription_title,
                                subtitleRes = R.string.add_card_subscription_subtitle,
                                onClick = { onPageChange(AddSheetPage.Subscription) }
                            )
                        }
                        AddSheetPage.Import -> {
                            AddSheetBackButton(onClick = { onPageChange(AddSheetPage.Cards) })
                            ImportConfigForm(
                                initialText = importPrefill,
                                onImport = onImportConfig,
                                onScanQr = {
                                    // Close the sheet first so the
                                    // scanner opens cleanly; the
                                    // scanned value re-opens the sheet
                                    // on the Import page pre-filled.
                                    dismiss()
                                    onOpenQrScanner()
                                },
                                onDismiss = dismiss
                            )
                        }
                        AddSheetPage.Manual -> {
                            AddSheetBackButton(onClick = { onPageChange(AddSheetPage.Cards) })
                            ServerEditorForm(
                                initial = null,
                                existingServers = servers,
                                onSave = { name, zone, key ->
                                    onSaveServer(null, name, zone, key)
                                },
                                onDismiss = dismiss
                            )
                        }
                        AddSheetPage.Subscription -> {
                            AddSheetBackButton(onClick = { onPageChange(AddSheetPage.Cards) })
                            SubscriptionAddForm(
                                url = subscriptionUrl,
                                onUrlChange = onSubscriptionUrlChange,
                                subscriptions = subscriptions,
                                subscriptionStatus = subscriptionStatus,
                                isUpdating = isUpdatingSubscription,
                                onAddOrUpdate = onAddOrUpdateSubscription,
                                editingSubscriptionId = editingSubscriptionId,
                                onUpdateSubscription = onUpdateSubscriptionUrl,
                                onDismiss = dismiss
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddSheetBackButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text = "‹ " + stringResource(R.string.back))
    }
}

@Composable
private fun AddSheetCard(
    icon: ImageVector,
    @StringRes titleRes: Int,
    @StringRes subtitleRes: Int,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = UnbarBlue,
                modifier = Modifier.size(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SubscriptionAddForm(
    url: String,
    onUrlChange: (String) -> Unit,
    subscriptions: List<SubscriptionEntity>,
    subscriptionStatus: SubscriptionStatus?,
    isUpdating: Boolean,
    onAddOrUpdate: () -> Unit,
    editingSubscriptionId: Long?,
    onUpdateSubscription: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // In edit mode the action updates the existing subscription by id
    // (its servers stay linked); otherwise it adds a new subscription.
    val submit: () -> Unit = {
        val id = editingSubscriptionId
        if (id != null) {
            onUpdateSubscription(id)
        } else {
            onAddOrUpdate()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.subscription_url_label)) },
            placeholder = { Text("https://...") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    keyboardController?.hide()
                    submit()
                }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val pasted = clipboardManager.getText()?.text?.trim().orEmpty()
                    if (pasted.isNotEmpty()) {
                        onUrlChange(pasted)
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.subscription_paste),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = {
                    keyboardController?.hide()
                    submit()
                },
                enabled = url.isNotBlank() && !isUpdating,
                colors = ButtonDefaults.buttonColors(
                    containerColor = UnbarOrange,
                    contentColor = Color.White
                ),
                modifier = Modifier.weight(1f)
            ) {
                if (isUpdating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = stringResource(
                        if (editingSubscriptionId != null) R.string.save else R.string.add_action
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        SubscriptionStatusLine(
            status = effectiveSubscriptionStatus(subscriptionStatus, subscriptions)
        )

        Text(
            text = stringResource(R.string.subscription_https_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

private fun effectiveSubscriptionStatus(
    subscriptionStatus: SubscriptionStatus?,
    subscriptions: List<SubscriptionEntity>
): SubscriptionStatus {
    if (subscriptionStatus != null) {
        return subscriptionStatus
    }
    val latest = subscriptions
        .filter { it.lastUpdatedMs != null }
        .maxByOrNull { it.lastUpdatedMs ?: 0L }
    return if (latest != null) {
        SubscriptionStatus.Updated(serverCount = latest.serverCount, skippedCount = 0)
    } else {
        SubscriptionStatus.NeverUpdated
    }
}

@Composable
private fun SubscriptionCard(
    subscriptions: List<SubscriptionEntity>,
    subscriptionStatus: SubscriptionStatus?,
    isUpdating: Boolean,
    autoUpdateSubscriptions: Boolean,
    onSetAutoUpdate: (Boolean) -> Unit
) {
    val status = effectiveSubscriptionStatus(subscriptionStatus, subscriptions)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, UnbarOrange),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header (V1 compact banner): book icon + "Subscription"
            // + the status on the right. Updated shows as an orange
            // pill; Updating a spinner + text; NeverUpdated muted
            // text. Error is long, so it gets its own row below.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Rounded.MenuBook,
                    contentDescription = null,
                    tint = UnbarOrange,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = stringResource(R.string.subscription_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                SubscriptionStatusTrailing(status = status)
            }

            if (status is SubscriptionStatus.Error) {
                Text(
                    text = subscriptionErrorMessage(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.subscription_auto_update),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                )
                // The OFF state must still read as an interactive switch,
                // not a greyed-out disabled control: a light-blue track
                // and a white thumb with a blue border, like the TCP
                // Mode switch. The ON state is the brand blue.
                val blue = MaterialTheme.colorScheme.primary
                Switch(
                    checked = autoUpdateSubscriptions,
                    onCheckedChange = onSetAutoUpdate,
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

            Text(
                text = stringResource(R.string.subscription_https_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// The status at the right of the subscription banner header (V1):
// Updated is an orange pill with the server count; Updating a small
// spinner + text; NeverUpdated muted text. Error is rendered as its
// own row below the header (too long for the header slot).
@Composable
private fun SubscriptionStatusTrailing(status: SubscriptionStatus) {
    when (status) {
        SubscriptionStatus.NeverUpdated -> {
            Text(
                text = stringResource(R.string.subscription_never_updated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        SubscriptionStatus.Updating -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = UnbarOrange
                )
                Text(
                    text = stringResource(R.string.subscription_updating),
                    style = MaterialTheme.typography.bodySmall,
                    color = UnbarOrange,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
        is SubscriptionStatus.Updated -> {
            Surface(
                color = UnbarOrange,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.subscription_status_pill, status.serverCount),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        is SubscriptionStatus.Error -> {
            // Rendered below the header; nothing in the trailing slot.
        }
    }
}

// The error message for a subscription status, shared by the banner
// card and the add-form status line.
@Composable
private fun subscriptionErrorMessage(status: SubscriptionStatus.Error): String {
    return when (status.kind) {
        SubscriptionErrorKind.EMPTY_URL -> stringResource(R.string.subscription_error_empty_url)
        SubscriptionErrorKind.HTTP_SCHEME -> stringResource(R.string.subscription_error_http_scheme)
        SubscriptionErrorKind.NON_HTTPS_SCHEME -> stringResource(R.string.subscription_error_https_only)
        SubscriptionErrorKind.INVALID_URL -> stringResource(R.string.subscription_error_invalid_url)
        SubscriptionErrorKind.HTTP_STATUS -> stringResource(
            R.string.subscription_error_http_status,
            status.detail.orEmpty()
        )
        SubscriptionErrorKind.INVALID_FORMAT -> stringResource(R.string.subscription_error_invalid_format)
        SubscriptionErrorKind.NETWORK -> stringResource(
            R.string.subscription_error_network,
            status.detail.orEmpty()
        )
    }
}

@Composable
private fun SubscriptionStatusLine(status: SubscriptionStatus) {
    when (status) {
        SubscriptionStatus.NeverUpdated -> {
            Text(
                text = stringResource(R.string.subscription_never_updated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SubscriptionStatus.Updating -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = UnbarOrange
                )
                Text(
                    text = stringResource(R.string.subscription_updating),
                    style = MaterialTheme.typography.bodySmall,
                    color = UnbarOrange,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        is SubscriptionStatus.Updated -> {
            val message = if (status.skippedCount > 0) {
                stringResource(
                    R.string.subscription_status_updated_skipped,
                    status.serverCount,
                    status.skippedCount
                )
            } else {
                stringResource(R.string.subscription_status_updated, status.serverCount)
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = UnbarBlue,
                fontWeight = FontWeight.Medium
            )
        }
        is SubscriptionStatus.Error -> {
            Text(
                text = subscriptionErrorMessage(status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SubscriptionRowCard(
    subscription: SubscriptionEntity,
    isUpdating: Boolean,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val updatedText = if (subscription.lastUpdatedMs != null) {
        val formatted = remember(subscription.lastUpdatedMs) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
                .format(Date(subscription.lastUpdatedMs))
        }
        stringResource(R.string.subscription_last_updated, formatted)
    } else {
        stringResource(R.string.subscription_never_updated)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = subscription.url,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = updatedText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = stringResource(R.string.subscription_server_count, subscription.serverCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = UnbarBlue,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRefresh, enabled = !isUpdating) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = UnbarOrange
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.subscription_refresh)
                        )
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.edit)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerEditorDialog(
    initial: ServerEntity?,
    existingServers: List<ServerEntity>,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    // (A4, 1.0.15): scale+fade enter/exit instead of popping in/out.
    AnimatedDialog(onDismiss = onDismiss) { dismiss ->
        AlertDialog(
            onDismissRequest = dismiss,
            title = {
                Text(if (initial == null) stringResource(R.string.add_server_cta) else stringResource(R.string.edit))
            },
            text = {
                ServerEditorForm(
                    initial = initial,
                    existingServers = existingServers,
                    onSave = onSave,
                    onDismiss = dismiss
                )
            },
            confirmButton = {},
            dismissButton = {}
        )
    }
}

@Composable
private fun ServerEditorForm(
    initial: ServerEntity?,
    existingServers: List<ServerEntity>,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var zone by remember(initial) { mutableStateOf(initial?.zone ?: "") }
    var pubKey by remember(initial) { mutableStateOf(initial?.publicKeyB64 ?: "") }
    // (B5, 1.0.15): entry validation — the key must be a 32-byte
    // Ed25519 base64 key, the zone a hostname, and the pair must not
    // duplicate another server. Invalid entries stay open with the
    // error shown instead of saving a server that can never connect.
    var errorText by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.server_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = zone,
            onValueChange = { zone = it; errorText = null },
            label = { Text(stringResource(R.string.server_zone)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pubKey,
            onValueChange = { pubKey = it; errorText = null },
            label = { Text(stringResource(R.string.server_pubkey)) },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        if (errorText != null) {
            Text(
                text = errorText!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val err = ServerValidation.validateEntry(
                        existingServers, initial?.id, zone, pubKey
                    )
                    if (err != null) {
                        errorText = err
                    } else {
                        onSave(name, zone, pubKey)
                        onDismiss()
                    }
                },
                enabled = zone.isNotBlank() && pubKey.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}
