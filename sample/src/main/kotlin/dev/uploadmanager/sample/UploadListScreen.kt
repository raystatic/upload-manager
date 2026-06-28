package dev.uploadmanager.sample

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.uploadmanager.api.UploadState
import java.util.Locale

/**
 * Upload-manager demo: pick multiple files → each uploads in the background with its
 * own progress + pause/resume/cancel controls, and (thanks to Firestore sync) every
 * upload also shows up on any other device signed in to the same account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadListScreen(viewModel: UploadsViewModel = viewModel()) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.enqueue(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Uploads") },
                actions = {
                    viewModel.uid?.let { uid ->
                        Text(
                            "acct ${uid.take(6)}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickFiles.launch(arrayOf("*/*")) },
                text = { Text("Select files") },
                icon = { Text("＋") },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            EmptyState(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Same account on another device sees this list too.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                items(rows, key = { it.taskId }) { row -> UploadCard(row, viewModel) }
            }
        }
    }
}

@Composable
private fun UploadCard(row: UploadRow, viewModel: UploadsViewModel) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!row.isLocal) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("another device") },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }

            Spacer(Modifier.padding(2.dp))
            Text(
                buildString {
                    append(formatBytes(row.sizeBytes))
                    append("  ·  ")
                    append(row.stateLabel)
                    row.progressPct?.let { append("  ·  $it%") }
                },
                style = MaterialTheme.typography.bodySmall,
            )

            LinearProgressIndicator(
                progress = { (row.progressPct ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )

            val canControl = row.isLocal && row.state != null
            if (canControl || row.downloadUrl != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canControl) Controls(row, viewModel)
                    row.downloadUrl?.let { url ->
                        Button(onClick = { openUrl(context, url) }) { Text("Open") }
                    }
                }
            }
        }
    }
}

/** Open an uploaded file via its download URL (browser / a registered viewer). */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun Controls(row: UploadRow, viewModel: UploadsViewModel) {
    when (row.state) {
        UploadState.UPLOADING, UploadState.QUEUED, UploadState.PENDING, UploadState.RETRYING -> {
            Button(onClick = { viewModel.pause(row.taskId) }) { Text("Pause") }
            OutlinedButton(onClick = { viewModel.cancel(row.taskId) }) { Text("Cancel") }
        }
        UploadState.PAUSED -> {
            Button(onClick = { viewModel.resume(row.taskId) }) { Text("Resume") }
            OutlinedButton(onClick = { viewModel.cancel(row.taskId) }) { Text("Cancel") }
        }
        UploadState.PARKED -> {
            Button(onClick = { viewModel.retry(row.taskId) }) { Text("Retry") }
            OutlinedButton(onClick = { viewModel.cancel(row.taskId) }) { Text("Cancel") }
        }
        UploadState.FAILED, UploadState.CANCELLED -> {
            Button(onClick = { viewModel.retry(row.taskId) }) { Text("Retry") }
        }
        else -> Unit // COMPLETED / DEDUP_HIT: nothing to control
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            "No uploads yet.\nTap “Select files” to upload one or more files.",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Bytes → a compact human size, e.g. 12.4 MB. */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}
