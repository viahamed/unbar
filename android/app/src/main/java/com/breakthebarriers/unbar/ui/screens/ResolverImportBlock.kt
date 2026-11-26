package com.breakthebarriers.unbar.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.breakthebarriers.unbar.R
import com.breakthebarriers.unbar.ui.theme.InnerPanelRadius
import com.breakthebarriers.unbar.data.datastore.RESOLVER_IMPORT_MAX_BYTES
import com.breakthebarriers.unbar.data.datastore.RESOLVER_SCAN_CAP
import com.breakthebarriers.unbar.data.datastore.ResolverImportResult
import com.breakthebarriers.unbar.data.datastore.cleanResolverFile
import com.breakthebarriers.unbar.data.datastore.decodeResolverFile
import com.breakthebarriers.unbar.data.datastore.oversizeResolverRanges
import com.breakthebarriers.unbar.data.datastore.resolverAddressCount
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// (1.0.2) Import mode of the Resolvers card: pick a .txt file with the
// system file picker (no storage permission), clean it with the same
// rules as the Custom field plus de-duplication and sorting, store the
// result, and show what was fixed. Scanning is the unchanged shared
// scan section below it (the same 3-stage stepper as Custom).

private sealed interface ImportReadResult {
    data class Ok(val fileName: String, val result: ResolverImportResult) : ImportReadResult
    data object TooLarge : ImportReadResult
    data object Unreadable : ImportReadResult
}

private fun readResolverFile(context: Context, uri: Uri): ImportReadResult {
    return runCatching {
        val resolver = context.contentResolver
        var name = ""
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0).orEmpty()
        }
        if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "resolvers.txt" }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(RESOLVER_IMPORT_MAX_BYTES + 1)
            var total = 0
            while (total < buf.size) {
                val n = input.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            if (total > RESOLVER_IMPORT_MAX_BYTES) return ImportReadResult.TooLarge
            buf.copyOf(total)
        } ?: return ImportReadResult.Unreadable
        ImportReadResult.Ok(name, cleanResolverFile(decodeResolverFile(bytes)))
    }.getOrElse { ImportReadResult.Unreadable }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResolverImportBlock(
    importedResolvers: List<String>,
    importFileName: String,
    importedAtMs: Long,
    onImport: (List<String>, String) -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ResolverImportResult?>(null) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var showList by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        errorRes = null
        scope.launch {
            val read = withContext(Dispatchers.IO) { readResolverFile(context, uri) }
            reading = false
            when (read) {
                ImportReadResult.TooLarge -> errorRes = R.string.import_error_too_large
                ImportReadResult.Unreadable -> errorRes = R.string.import_error_unreadable
                is ImportReadResult.Ok -> {
                    if (read.result.entries.isEmpty()) {
                        // Nothing usable: keep the previous list.
                        errorRes = R.string.import_error_empty
                        report = read.result
                    } else {
                        report = read.result
                        showList = false
                        onImport(read.result.entries, read.fileName)
                    }
                }
            }
        }
    }
    val pick = { launcher.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) }

    val hasList = importedResolvers.isNotEmpty()
    if (!hasList) {
        Text(
            text = stringResource(R.string.import_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.import_note_custom_kept),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = pick, enabled = !reading) {
            Text(stringResource(R.string.import_choose_file))
        }
    } else {
        val addresses = resolverAddressCount(importedResolvers)
        Card(
            shape = RoundedCornerShape(InnerPanelRadius),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Column {
                    Text(
                        text = importFileName.ifBlank { "resolvers.txt" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    val date = if (importedAtMs > 0L) {
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(importedAtMs))
                    } else ""
                    Text(
                        text = stringResource(R.string.import_file_meta, importedResolvers.size, addresses) +
                            if (date.isNotEmpty()) " · $date" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = pick, enabled = !reading) {
                Text(stringResource(R.string.import_replace_file))
            }
            OutlinedButton(onClick = { showList = !showList }) {
                Text(stringResource(if (showList) R.string.import_hide_list else R.string.import_view_list))
            }
            OutlinedButton(onClick = {
                report = null
                showList = false
                onClear()
            }) {
                Text(stringResource(R.string.import_clear), color = MaterialTheme.colorScheme.error)
            }
        }
        if (showList) {
            Card(
                shape = RoundedCornerShape(InnerPanelRadius),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                // Only the first 200 entries are composed; the count
                // of the rest is shown below them.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    importedResolvers.take(200).forEach {
                        Text(text = it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    if (importedResolvers.size > 200) {
                        Text(
                            text = stringResource(R.string.import_list_more, importedResolvers.size - 200),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (addresses > RESOLVER_SCAN_CAP) {
            Text(
                text = stringResource(R.string.import_over_cap, addresses, RESOLVER_SCAN_CAP),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        val oversize = oversizeResolverRanges(importedResolvers.joinToString(","))
        if (oversize.isNotEmpty()) {
            Text(
                text = stringResource(R.string.custom_resolvers_range_too_large, oversize.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    if (reading) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.import_reading), style = MaterialTheme.typography.bodySmall)
        }
    }
    errorRes?.let {
        Text(text = stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    // What the last import fixed (this session only).
    report?.let { r ->
        val lines = buildList {
            if (r.cleaned > 0) add(stringResource(R.string.import_fix_cleaned, r.cleaned))
            if (r.duplicates > 0) add(stringResource(R.string.import_fix_duplicates, r.duplicates))
            if (r.insideRange > 0) add(stringResource(R.string.import_fix_inside_range, r.insideRange))
            if (r.rangesCorrected > 0) add(stringResource(R.string.import_fix_ranges, r.rangesCorrected))
            if (r.dashConverted > 0) add(stringResource(R.string.import_fix_dash, r.dashConverted))
        }
        if (lines.isNotEmpty()) {
            Text(
                text = stringResource(R.string.import_fixed_title, r.fixedCount),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            lines.forEach {
                Text(text = "• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (r.ignored.isNotEmpty()) {
            Text(
                text = stringResource(R.string.import_ignored, r.ignored.size, r.ignored.take(5).joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
