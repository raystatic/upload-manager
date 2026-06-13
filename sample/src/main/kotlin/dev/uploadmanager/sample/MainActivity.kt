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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.firebase.auth.FirebaseAuth
import dev.uploadmanager.UploadManager
import dev.uploadmanager.api.UploadPriority
import dev.uploadmanager.api.UploadRequest
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
        setContent {
            MaterialTheme {
                SampleScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Sign in anonymously, then observe this user's queue once signed in.
    val signedIn by produceState(initialValue = false) {
        value = signInIfNeeded(context)
    }
    val tasksFlow = remember(signedIn) {
        if (signedIn) UploadManager.observeAll() else flowOf(emptyList<UploadTaskState>())
    }
    val tasks by tasksFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { enqueue(context, scope, it) } }

    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* uploads still run if denied; only the progress notification is hidden */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Upload Manager") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { pickFile.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                ) { Text("Pick & upload") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = { scope.launch { UploadManager.clearCompleted() } },
                    modifier = Modifier.weight(1f),
                ) { Text("Clear completed") }
            }

            Spacer(Modifier.padding(8.dp))

            if (tasks.isEmpty()) {
                Text(
                    "No uploads yet. Tap \"Pick & upload\" to enqueue a file.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tasks, key = { it.taskId }) { task -> TaskCard(task) }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(task: UploadTaskState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                task.fileName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            )
            Row {
                PrimaryAction(task)
                Spacer(Modifier.width(8.dp))
                if (!task.state.isTerminal) {
                    OutlinedButton(onClick = { UploadManager.cancel(task.taskId) }) {
                        Text("Cancel")
                    }
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

private fun enqueue(context: Context, scope: CoroutineScope, uri: Uri) {
    scope.launch {
        val (name, mime) = describe(context, uri)
        runCatching {
            UploadManager.enqueue(
                UploadRequest(
                    localUri = uri,
                    mimeType = mime,
                    fileName = name,
                    priority = UploadPriority.P0,
                )
            )
        }.onFailure { toast(context, "Enqueue failed: ${it.message}") }
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

private fun toast(context: Context, msg: String) =
    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
