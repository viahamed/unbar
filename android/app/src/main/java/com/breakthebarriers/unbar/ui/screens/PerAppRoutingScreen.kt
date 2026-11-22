package com.breakthebarriers.unbar.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.breakthebarriers.unbar.ui.components.UnbarFilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakthebarriers.unbar.R
import com.breakthebarriers.unbar.model.InstalledAppInfo
import com.breakthebarriers.unbar.model.PerAppRoutingMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PerAppRoutingScreen(
    mode: PerAppRoutingMode,
    selectedPackages: Set<String>,
    apps: List<InstalledAppInfo>,
    isLoading: Boolean,
    onLoadApps: () -> Unit,
    onUpdate: (PerAppRoutingMode, Set<String>) -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) { onLoadApps() }

    var query by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }

    val filtered = remember(apps, query, showSystemApps) {
        apps.filter { app ->
            (showSystemApps || !app.isSystemApp) &&
                (query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.per_app_routing)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UnbarFilterChip(
                    selected = mode == PerAppRoutingMode.ALLOW_ALL_EXCEPT,
                    onClick = { onUpdate(PerAppRoutingMode.ALLOW_ALL_EXCEPT, selectedPackages) },
                    label = { Text("Bypass selected") }
                )
                UnbarFilterChip(
                    selected = mode == PerAppRoutingMode.ONLY_SELECTED,
                    onClick = { onUpdate(PerAppRoutingMode.ONLY_SELECTED, selectedPackages) },
                    label = { Text("Only selected") }
                )
                UnbarFilterChip(
                    selected = showSystemApps,
                    onClick = { showSystemApps = !showSystemApps },
                    label = { Text("System") }
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.packageName }) { app ->
                        val checked = selectedPackages.contains(app.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItemPlacement(tween(250))
                                .clickable {
                                    val next = if (checked) {
                                        selectedPackages - app.packageName
                                    } else {
                                        selectedPackages + app.packageName
                                    }
                                    onUpdate(mode, next)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    val next = if (isChecked) {
                                        selectedPackages + app.packageName
                                    } else {
                                        selectedPackages - app.packageName
                                    }
                                    onUpdate(mode, next)
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
