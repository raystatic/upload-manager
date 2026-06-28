package dev.uploadmanager.sample.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun UploadProgressPill(
    state: UploadUiState,
    isDeepInFeed: Boolean,
    onTapToView: () -> Unit,
    onAnimationFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0) }
    var isSuccess by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        when (state) {
            is UploadUiState.Idle -> {
                visible = false
            }
            is UploadUiState.Uploading -> {
                visible = true
                isSuccess = false
                isError = false
                progress = state.progress
                text = "Uploading... $progress%"
            }
            is UploadUiState.Success -> {
                visible = true
                isSuccess = true
                text = if (isDeepInFeed) "Upload complete. Tap to view" else "Upload complete!"
                if (!isDeepInFeed) {
                    delay(2000)
                    visible = false
                    onAnimationFinished()
                }
            }
            is UploadUiState.Error -> {
                visible = true
                isError = true
                text = "Upload failed"
                delay(3000)
                visible = false
                onAnimationFinished()
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
    ) {
        val backgroundColor = when {
            isError -> Color(0xFFD32F2F).copy(alpha = 0.9f)
            isSuccess && !isDeepInFeed -> Color(0xFF388E3C).copy(alpha = 0.9f)
            else -> Color.Black.copy(alpha = 0.7f)
        }

        Box(
            modifier = Modifier
                .padding(top = 48.dp) // Below status bar
                .clip(RoundedCornerShape(24.dp))
                .background(backgroundColor)
                .clickable(enabled = isSuccess && isDeepInFeed) {
                    onTapToView()
                    visible = false
                    onAnimationFinished()
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state is UploadUiState.Uploading) {
                    CircularProgressIndicator(
                        progress = progress / 100f,
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else if (isSuccess && !isDeepInFeed) {
                    Text("✓", color = Color.White, fontSize = 14.sp)
                }
                Text(text, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}
