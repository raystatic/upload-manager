package dev.uploadmanager.sample

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import dev.uploadmanager.UploadManager
import dev.uploadmanager.api.UploadPriority
import dev.uploadmanager.api.UploadRequest
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : AppCompatActivity() {

    private val adapter = TaskAdapter(
        onPause = { UploadManager.pause(it) },
        onResume = { UploadManager.resume(it) },
        onCancel = { UploadManager.cancel(it) },
        onRetry = { UploadManager.retry(it) },
    )

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::enqueue) }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* foreground uploads still run without the notification being visible */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<RecyclerView>(R.id.task_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<Button>(R.id.pick_button).setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.clear_button).setOnClickListener {
            lifecycleScope.launch { UploadManager.clearCompleted() }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            signInIfNeeded()
            UploadManager.observeAll().collectLatest { adapter.submitList(it) }
        }
    }

    private suspend fun signInIfNeeded() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            runCatching { auth.signInAnonymously().await() }
                .onFailure { toast("Sign-in failed: ${it.message} — is the Auth emulator running?") }
        }
    }

    private fun enqueue(uri: Uri) {
        lifecycleScope.launch {
            val (name, mime) = describe(uri)
            runCatching {
                UploadManager.enqueue(
                    UploadRequest(
                        localUri = uri,
                        mimeType = mime,
                        fileName = name,
                        priority = UploadPriority.P0,
                    )
                )
            }.onFailure { toast("Enqueue failed: ${it.message}") }
        }
    }

    private fun describe(uri: Uri): Pair<String, String> {
        var name = "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx)
        }
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        return name to mime
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
