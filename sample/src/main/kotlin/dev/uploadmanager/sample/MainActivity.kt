package dev.uploadmanager.sample

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.auth.FirebaseAuth
import dev.uploadmanager.sample.ui.FeedScreen
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                var signedIn by remember { mutableStateOf(false) }
                val context = LocalContext.current
                
                LaunchedEffect(Unit) {
                    signedIn = signInIfNeeded(context)
                }

                if (signedIn) {
                    FeedScreen()
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

private suspend fun signInIfNeeded(context: Context): Boolean {
    val auth = FirebaseAuth.getInstance()
    if (auth.currentUser != null) return true
    return runCatching { auth.signInAnonymously().await() }
        .onFailure { 
            Toast.makeText(
                context, 
                "Sign-in failed: ${it.message} — is the Auth emulator running?", 
                Toast.LENGTH_LONG
            ).show() 
        }
        .isSuccess
}
