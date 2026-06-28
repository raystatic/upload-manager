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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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

/** One screen per Critical User Journey. HOME lists them. */
private enum class Cuj(val title: String) {
    HOME("Upload Manager — CUJs"),
    BASIC("1 · Basic upload"),
    RESUME("2 · Resume after app kill"),
    CONTROL("3 · Pause / Resume / Cancel"),
    RETRY("4 · Retry & Park (network loss)"),
    STAGING("5 · Staging & source safety"),
    DEDUP("6 · Deduplication"),
    ADAPTIVE("7 · Adaptive concurrency"),
    REBOOT("8 · Reboot resilience"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { CujRunner(context) }

    var screen by remember { mutableStateOf(Cuj.HOME) }
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
                if (event is UploadEvent.Progress) return@collect
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
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showPresets) {
        PresetDialog(onDismiss = { showPresets = false }, onSelect = { Preset.apply(context, it) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = {
                    if (screen != Cuj.HOME) {
                        TextButton(onClick = { screen = Cuj.HOME }) { Text("‹ Home") }
                    }
                },
            )
        },
    ) { padding ->
        if (screen == Cuj.HOME) {
            HomeScreen(padding, Preset.current(context), onChangePreset = { showPresets = true }, onOpen = { screen = it })
        } else {
            CujScreen(
                screen = screen,
                modifier = Modifier.padding(padding),
                runner = runner,
                scope = scope,
                status = status,
                setStatus = { status = it },
                tasks = tasks,
                events = events,
                onPickFile = { pickFile.launch(arrayOf("*/*")) },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    padding: androidx.compose.foundation.layout.PaddingValues,
    preset: Preset,
    onChangePreset: () -> Unit,
    onOpen: (Cuj) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Pick a use case to see how the SDK behaves. Each screen explains what " +
                    "it demonstrates, how to trigger it, and what to watch.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Config preset: ${preset.label}", style = MaterialTheme.typography.titleSmall)
                    Text(preset.hint, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.padding(2.dp))
                    OutlinedButton(onClick = onChangePreset) { Text("Change preset (restarts app)") }
                }
            }
        }
        items(Cuj.entries.filter { it != Cuj.HOME }) { cuj ->
            Button(onClick = { onOpen(cuj) }, modifier = Modifier.fillMaxWidth()) { Text(cuj.title) }
        }
    }
}

@Composable
private fun CujScreen(
    screen: Cuj,
    modifier: Modifier,
    runner: CujRunner,
    scope: CoroutineScope,
    status: String,
    setStatus: (String) -> Unit,
    tasks: List<UploadTaskState>,
    events: List<String>,
    onPickFile: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Text(explanation(screen), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { CujActions(screen, runner, scope, setStatus, onPickFile) }
        item { Text(status, style = MaterialTheme.typography.bodySmall) }
        item { EventsCard(events) }
        item { Text("Tasks", style = MaterialTheme.typography.titleMedium) }
        if (tasks.isEmpty()) {
            item { Text("No uploads yet — run the action above.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(tasks, key = { it.taskId }) { task -> TaskCard(task) }
        }
    }
}

@Composable
private fun CujActions(
    screen: Cuj,
    runner: CujRunner,
    scope: CoroutineScope,
    setStatus: (String) -> Unit,
    onPickFile: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (screen) {
            Cuj.BASIC -> {
                FullButton("Upload a small file") { launchCuj(scope, setStatus) { runner.uploadSmall() } }
                OutlinedFullButton("Pick a file & upload") { onPickFile() }
            }
            Cuj.RESUME, Cuj.CONTROL, Cuj.RETRY, Cuj.REBOOT ->
                FullButton("Upload a large file (~70 MB)") { launchCuj(scope, setStatus) { runner.uploadLarge() } }
            Cuj.STAGING ->
                FullButton("Enqueue, then delete the source") { launchCuj(scope, setStatus) { runner.uploadThenDeleteSource() } }
            Cuj.DEDUP ->
                FullButton("Upload the same content (tap twice)") { launchCuj(scope, setStatus) { runner.uploadDuplicate() } }
            Cuj.ADAPTIVE ->
                FullButton("Queue 3 small uploads") {
                    scope.launch {
                        repeat(3) { runCatching { runner.uploadSmall() } }
                        setStatus("queued 3 uploads")
                    }
                }
            Cuj.HOME -> Unit
        }
        OutlinedFullButton("Clear completed") {
            scope.launch { val n = UploadManager.clearCompleted(); setStatus("cleared $n") }
        }
    }
}

private fun explanation(screen: Cuj): String = when (screen) {
    Cuj.BASIC ->
        "Enqueue a file and watch it go PENDING → QUEUED → UPLOADING → COMPLETED. " +
            "The row is written to Room before any network call; the object lands at " +
            "users/{uid}/files/… in your bucket."
    Cuj.RESUME ->
        "THE headline feature. Tap upload, then while it's mid-transfer kill the app:\n\n" +
            "  adb shell am force-stop dev.uploadmanager.sample\n\n" +
            "Reopen the app — the upload continues from where it stopped (not 0%). " +
            "Logcat (UploadManager) shows started(resuming=true). The resume token was " +
            "persisted to Room the instant Firebase issued it."
    Cuj.CONTROL ->
        "Start a large upload, then use each row's Pause / Resume / Cancel buttons. " +
            "Pause halts at PAUSED; Resume continues from the same offset; Cancel stops it."
    Cuj.RETRY ->
        "Start a large upload, then drop the network:\n\n" +
            "  adb shell svc wifi disable   (wait)   adb shell svc wifi enable\n\n" +
            "A short blip → RETRYING then recovers. A long outage → PARKED (no progress); " +
            "reconnect and it completes on its own, no user action."
    Cuj.STAGING ->
        "This button enqueues a file and immediately deletes the original.\n\n" +
            "• Default preset: it still COMPLETES — the SDK uploaded a snapshot.\n" +
            "• 'Reference (no staging)' preset: it FAILS with SOURCE_GONE.\n\n" +
            "So editing/deleting the source after enqueue can't corrupt the upload."
    Cuj.DEDUP ->
        "Tap once and wait for COMPLETED, then tap again — the same content uploads " +
            "ZERO bytes (DEDUP_HIT) and reuses the existing object. Needs the dedup " +
            "preset/config + Firestore. (Stale index entries are re-uploaded, not faked.)"
    Cuj.ADAPTIVE ->
        "Queue a few uploads, then simulate a low, unplugged battery:\n\n" +
            "  adb shell dumpsys battery set level 15 && adb shell dumpsys battery unplug\n\n" +
            "Fewer run at once and a large upload parks as DEVICE_BUSY. Restore with " +
            "adb shell dumpsys battery reset."
    Cuj.REBOOT ->
        "Start an upload (or get one to QUEUED/PARKED), then reboot:\n\n" +
            "  adb reboot\n\n" +
            "Reopen the app — the task is picked back up and finishes. WorkManager + Room " +
            "both survive reboot; the SDK reconciles on startup."
    Cuj.HOME -> ""
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
private fun EventsCard(events: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Recent events", style = MaterialTheme.typography.titleSmall)
            if (events.isEmpty()) {
                Text("—", style = MaterialTheme.typography.bodySmall)
            } else {
                events.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
