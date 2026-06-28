package dev.uploadmanager.sample.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@Composable
fun FeedScreen(viewModel: FeedViewModel = viewModel()) {
    val context = LocalContext.current
    val videos by viewModel.videos.collectAsState(initial = emptyList())
    val uploadState by viewModel.uploadState.collectAsState()
    
    val pagerState = rememberPagerState(pageCount = { videos.size })
    val coroutineScope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let {
                val (name, mime) = describe(context, it)
                viewModel.startUpload(it, name, mime)
            }
        }
    )

    // Handle auto-scroll to top on success if user is at page 0
    LaunchedEffect(videos) {
        if (pagerState.currentPage == 0 && videos.isNotEmpty() && uploadState is UploadUiState.Success) {
            pagerState.animateScrollToPage(0)
            viewModel.resetUploadState()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (videos.isEmpty()) {
            EmptyState(onUploadClick = {
                launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            })
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { videos[it].id }
            ) { page ->
                VideoPlayer(
                    videoUrl = videos[page].url,
                    isActive = page == pagerState.currentPage
                )
            }

            // Floating Upload Button
            FloatingActionButton(
                onClick = {
                    launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Upload Video")
            }
        }

        // Floating Progress Pill
        UploadProgressPill(
            state = uploadState,
            isDeepInFeed = pagerState.currentPage > 0,
            onTapToView = {
                coroutineScope.launch {
                    pagerState.animateScrollToPage(0)
                }
            },
            onAnimationFinished = {
                viewModel.resetUploadState()
            },
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
fun EmptyState(onUploadClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Button(onClick = onUploadClick) {
            Text("No videos yet. Be the first to upload!")
        }
    }
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
