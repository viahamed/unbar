package com.breakthebarriers.unbar.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.breakthebarriers.unbar.R
import com.breakthebarriers.unbar.data.config.ConfigShareCodec

@Composable
fun ExportConfigDialog(
    serverName: String,
    exportUri: String,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val qrBitmap = remember(exportUri) {
        runCatching { ConfigShareCodec.generateQrBitmap(exportUri, 560) }.getOrNull()
    }
    var copied by remember { mutableStateOf(false) }

    // (A4, 1.0.15): scale+fade enter/exit instead of popping in/out.
    AnimatedDialog(onDismiss = onDismiss) { dismiss ->
        AlertDialog(
            onDismissRequest = dismiss,
            title = {
                Text(text = "${stringResource(R.string.export_config)}: $serverName")
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .size(210.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Config QR Code",
                                modifier = Modifier.size(190.dp)
                            )
                        }
                    }
                    Text(
                        text = exportUri,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.export_security_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(exportUri))
                        copied = true
                    }
                ) {
                    Text(if (copied) "✓ ${stringResource(R.string.copy)}" else stringResource(R.string.copy))
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

@Composable
fun ImportConfigForm(
    initialText: String = "",
    onImport: (String) -> String?,
    onScanQr: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var rawText by remember(initialText) { mutableStateOf(initialText) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Paste an unbar:// link or exported JSON config (from QR or clipboard).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = rawText,
            onValueChange = {
                rawText = it
                errorText = null
            },
            label = { Text("unbar://... or JSON") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 5
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onScanQr) {
                Icon(
                    Icons.Rounded.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.scan_qr_code))
            }
            OutlinedButton(
                onClick = {
                    clipboard.getText()?.text?.let {
                        rawText = it
                        errorText = null
                    }
                }
            ) {
                Text("Paste")
            }
        }
        if (errorText != null) {
            Spacer(modifier = Modifier.height(2.dp))
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
                    val err = onImport(rawText)
                    if (err == null) onDismiss() else errorText = err
                },
                enabled = rawText.isNotBlank()
            ) {
                // This button imports the pasted/scanned config — it is
                // an action, not a settings Save (the app has no Save
                // buttons: every setting auto-saves).
                Text(stringResource(R.string.import_config))
            }
        }
    }
}
