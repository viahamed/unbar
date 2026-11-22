package com.breakthebarriers.unbar.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.breakthebarriers.unbar.R
import com.breakthebarriers.unbar.model.LogEntry
import com.breakthebarriers.unbar.model.LogLevel
import com.breakthebarriers.unbar.ui.theme.UnbarOrange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LogsScreen(
    logs: List<LogEntry>,
    onClear: () -> Unit,
    onExportText: () -> String,
    onBack: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    var showDetails by remember { mutableStateOf(false) }
    // Display-time filter only: the periodic 20s path/stream lines
    // (tags "SoarPath" and "Streams") are hidden by default so they
    // cannot flood the buffer view. Copy/export still uses the full
    // buffer via onExportText() (LogStore.formatForExport).
    val displayedLogs = remember(logs, showDetails) {
        if (showDetails) logs
        else logs.filter { it.tag != "SoarPath" && it.tag != "Streams" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${stringResource(R.string.view_logs)} (${logs.size}/500)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(onExportText())) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.copy))
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.clear))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (logs.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.logs_show_details),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // Same styled switch as the settings rows (SwitchRow):
                    // brand-blue track when on, light-blue track with a
                    // white thumb and blue border when off, so it never
                    // reads as a greyed-out disabled control the way the
                    // default Material switch does.
                    val blue = MaterialTheme.colorScheme.primary
                    Switch(
                        checked = showDetails,
                        onCheckedChange = { showDetails = it },
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
            if (logs.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "No log entries recorded.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(displayedLogs.asReversed(), key = { it.id }) { entry ->
                        val levelColor = when (entry.level) {
                        LogLevel.ERROR -> UnbarOrange
                        LogLevel.WARN -> UnbarOrange
                        LogLevel.INFO -> MaterialTheme.colorScheme.primary
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItemPlacement(tween(250)),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = timeFmt.format(Date(entry.timestampMs)),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = entry.level.name,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = levelColor
                                )
                                Text(
                                    text = entry.tag,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = entry.message,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }
            }
        }
    }
    }
}
