package dev.uploadmanager.sample

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * Single-activity TikTok-style feed. Signs in anonymously (uploads need a Firebase
 * user), requests the notifications permission for the SDK's foreground service, then
 * shows the feed.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val signedIn by produceState(initialValue = false) {
                    value = signInIfNeeded()
                }

                val requestNotifications = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                if (signedIn) {
                    VideoFeedScreen()
                }
            }
        }
    }

    private suspend fun signInIfNeeded(): Boolean {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) return true
        return runCatching { auth.signInAnonymously().await() }.isSuccess
    }
}
