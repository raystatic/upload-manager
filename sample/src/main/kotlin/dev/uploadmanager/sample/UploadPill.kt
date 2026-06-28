package dev.uploadmanager.sample

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Top-center floating pill that reports the background upload's progress. It floats
 * over the feed (never blocks it) and slides in/out from the top.
 */
@Composable
fun UploadPill(
    state: PillUiState,
    onAutoHideComplete: () -> Unit,
    onTapToView: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state !is PillUiState.Hidden

    // "Upload complete!" persists 2s, then slides up. Errors clear after 3s.
    LaunchedEffect(state) {
        when (state) {
            is PillUiState.SuccessAutoHide -> {
                delay(2_000)
                onAutoHideComplete()
            }
            is PillUiState.Error -> {
                delay(3_000)
                onDismissError()
            }
            else -> Unit
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        // Keep showing the last non-hidden content while sliding out.
        AnimatedContent(targetState = state, label = "pill-content") { s ->
            when (s) {
                is PillUiState.Uploading -> PillSurface {
                    Spinner()
                    Spacer(Modifier.width(10.dp))
                    PillText("Uploading… ${s.pct}%")
                }
                is PillUiState.SuccessAutoHide -> PillSurface {
                    Glyph("✓", Color(0xFF4CAF50))
                    Spacer(Modifier.width(10.dp))
                    PillText("Upload complete!")
                }
                is PillUiState.TapToView -> PillSurface(
                    modifier = Modifier.clickable { onTapToView() },
                ) {
                    Glyph("✓", Color(0xFF4CAF50))
                    Spacer(Modifier.width(10.dp))
                    PillText("Upload complete. Tap to view.")
                }
                is PillUiState.Error -> PillSurface(background = Color(0xFF7F1D1D)) {
                    Glyph("!", Color(0xFFFCA5A5))
                    Spacer(Modifier.width(10.dp))
                    PillText("Upload failed")
                }
                is PillUiState.Hidden -> Spacer(Modifier.size(0.dp))
            }
        }
    }
}

@Composable
private fun PillSurface(
    modifier: Modifier = Modifier,
    background: Color = Color(0xE61C1C1E),
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

@Composable
private fun PillText(text: String) {
    Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun Glyph(symbol: String, tint: Color) {
    Text(symbol, color = tint, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun Spinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(16.dp),
        color = Color.White,
        strokeWidth = 2.dp,
    )
}
