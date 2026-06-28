package dev.uploadmanager.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Email/password sign-in. The cross-device demo needs both devices to be the *same*
 * Firebase user — anonymous auth gives each device its own uid, so it can't share a
 * list. Enter identical credentials on each device. Requires the Email/Password
 * provider to be enabled in the Firebase console.
 */
@Composable
fun SignInScreen(onSignedIn: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            val result = signInOrCreate(email.trim(), password)
            busy = false
            result.onSuccess { onSignedIn() }.onFailure { error = it.message ?: "Sign-in failed" }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Upload Manager", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Sign in with the same email + password on every device to share one upload list.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (min 6 chars)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp))
        }
        Button(
            onClick = { submit() },
            enabled = !busy && email.isNotBlank() && password.length >= 6,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
            }
            Text("Sign in / Create account")
        }
    }
}

/** Sign in; if the account doesn't exist yet, create it (so first run on a device works). */
private suspend fun signInOrCreate(email: String, password: String): Result<Unit> {
    val auth = FirebaseAuth.getInstance()
    return runCatching { auth.signInWithEmailAndPassword(email, password).await() }
        .map { }
        .recoverCatching { signInError ->
            // No such user yet → create it. Any other error (wrong password) rethrows.
            if (!signInError.isNoSuchUser()) throw signInError
            auth.createUserWithEmailAndPassword(email, password).await()
            Unit
        }
}

private fun Throwable.isNoSuchUser(): Boolean {
    val msg = message?.lowercase().orEmpty()
    return "no user record" in msg || "there is no user" in msg
}
