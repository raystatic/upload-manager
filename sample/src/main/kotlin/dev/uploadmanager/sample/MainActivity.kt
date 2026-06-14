package dev.uploadmanager.sample

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.firebase.auth.FirebaseAuth
import dev.uploadmanager.UploadManager
import dev.uploadmanager.api.UploadEvent
import dev.uploadmanager.api.UploadState
import dev.uploadmanager.api.UploadTaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { SampleScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { CujRunner(context) }
    val preset = remember { Preset.current(context) }

    var status by remember { mutableStateOf("Sign-in pending…") }
    var showPresets by remember { mutableStateOf(false) }

    val signedIn by produceState(initialValue = false) {
        value = signInIfNeeded(context).also { status = if (it) "Ready." else "Sign-in failed." }
    }
    val tasksFlow = remember(signedIn) {
        if (signedIn) UploadManager.observeAll() else flowOf(emptyList<UploadTaskState>())
    }
    val tasks by tasksFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val events = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        runCatching {
            UploadManager.observeEvents().collect { event ->
                if (event is UploadEvent.Progress) return@collect // too chatty for the log
                events.add(0, eventLabel(event))
                while (events.size > 8) events.removeAt(events.size - 1)
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        launchCuj(scope, { status = it }) {
            val (name, mime) = describe(context, uri)
            runner.uploadPicked(uri, name, mime)
        }
    }
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* uploads still run if denied */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showPresets) {
        PresetDialog(
            onDismiss = { showPresets = false },
            onSelect = { Preset.apply(context, it) }, // persists + restarts the process
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Upload Manager — CUJ runner") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { PresetBar(preset) { showPresets = true } }
            item { CujButtons(runner, scope, { status = it }) { pickFile.launch(it) } }
            item { StatusLine(status) }
            item { EventsCard(events) }
            item {
                Text("Tasks", style = MaterialTheme.typography.titleMedium)
            }
            if (tasks.isEmpty()) {
                item { Text("No uploads yet — run a CUJ above.", style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(tasks, key = { it.taskId }) { task -> TaskCard(task) }
            }
        }
    }
}

@Composable
private fun PresetBar(preset: Preset, onChange: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Config preset: ${preset.label}", style = MaterialTheme.typography.titleSmall)
            Text(preset.hint, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.padding(2.dp))
            OutlinedButton(onClick = onChange) { Text("Change preset (restarts app)") }
        }
    }
}

@Composable
private fun CujButtons(
    runner: CujRunner,
    scope: CoroutineScope,
    setStatus: (String) -> Unit,
    pickFile: (Array<String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FullButton("Pick a file & upload") { pickFile(arrayOf("*/*")) }
        FullButton("CUJ: upload small file (staged)") { launchCuj(scope, setStatus) { runner.uploadSmall() } }
        FullButton("CUJ: upload large file (~70 MB, reference)") { launchCuj(scope, setStatus) { runner.uploadLarge() } }
        FullButton("CUJ: dedup (tap twice, same content)") { launchCuj(scope, setStatus) { runner.uploadDuplicate() } }
        FullButton("CUJ: enqueue then delete source") { launchCuj(scope, setStatus) { runner.uploadThenDeleteSource() } }
        OutlinedFullButton("Clear completed") {
            scope.launch { val n = UploadManager.clearCompleted(); setStatus("cleared $n completed") }
        }
    }
}

@Composable
private fun FullButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun OutlinedFullButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun StatusLine(status: String) {
    Text(status, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun EventsCard(events: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Recent events", style = MaterialTheme.typography.titleSmall)
            if (events.isEmpty()) {
                Text("—", style = MaterialTheme.typography.bodySmall)
            } else {
                events.forEach { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun PresetDialog(onDismiss: () -> Unit, onSelect: (Preset) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Choose a config preset") },
        text = {
            Column {
                Preset.entries.forEach { p ->
                    TextButton(onClick = { onSelect(p) }, modifier = Modifier.fillMaxWidth()) {
                        Text(p.label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
    )
}

@Composable
private fun TaskCard(task: UploadTaskState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(task.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(task.state.name)
                    append("  ${task.progressPct}%")
                    task.errorCode?.let { append("  [$it]") }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { task.progressPct / 100f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
            Row {
                PrimaryAction(task)
                Spacer(Modifier.width(8.dp))
                if (!task.state.isTerminal) {
                    OutlinedButton(onClick = { UploadManager.cancel(task.taskId) }) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun PrimaryAction(task: UploadTaskState) {
    when (task.state) {
        UploadState.UPLOADING, UploadState.QUEUED, UploadState.PENDING, UploadState.RETRYING ->
            Button(onClick = { UploadManager.pause(task.taskId) }) { Text("Pause") }
        UploadState.PAUSED ->
            Button(onClick = { UploadManager.resume(task.taskId) }) { Text("Resume") }
        UploadState.FAILED, UploadState.CANCELLED ->
            Button(onClick = { UploadManager.retry(task.taskId) }) { Text("Retry") }
        else -> Unit
    }
}

private fun launchCuj(scope: CoroutineScope, setStatus: (String) -> Unit, block: suspend () -> String) {
    scope.launch { setStatus(runCatching { block() }.getOrElse { "⚠ ${it.message}" }) }
}

private fun eventLabel(event: UploadEvent): String {
    val id = event.taskId.take(8)
    return when (event) {
        is UploadEvent.Enqueued -> "enqueued $id"
        is UploadEvent.Started -> "started $id (resuming=${event.resuming})"
        is UploadEvent.Progress -> "progress $id ${event.pct}%"
        is UploadEvent.Paused -> "paused $id"
        is UploadEvent.Resumed -> "resumed $id"
        is UploadEvent.Retrying -> "retry $id #${event.retryCount} ${event.errorCode ?: ""}"
        is UploadEvent.Parked -> "PARKED $id"
        is UploadEvent.Completed -> "completed $id"
        is UploadEvent.DedupHit -> "DEDUP_HIT $id"
        is UploadEvent.Failed -> "FAILED $id ${event.reason}"
        is UploadEvent.Cancelled -> "cancelled $id"
    }
}

private suspend fun signInIfNeeded(context: Context): Boolean {
    val auth = FirebaseAuth.getInstance()
    if (auth.currentUser != null) return true
    return runCatching { auth.signInAnonymously().await() }
        .onFailure { toast(context, "Sign-in failed: ${it.message} — is the Auth emulator running?") }
        .isSuccess
}

private fun describe(context: Context, uri: Uri): Pair<String, String> {
    var name = "unknown"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx)
    }
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    return name to mime
}

private fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
