package dev.uploadmanager.sample

import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

/**
 * TikTok-style vertical feed. A single [ExoPlayer] is moved between pages as the
 * current page changes (one decoder, never N). Uploads run in the background via
 * the SDK; the [UploadPill] reports progress and the feed self-syncs on completion.
 */
@Composable
fun VideoFeedScreen(viewModel: FeedViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val pill by viewModel.pill.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { videos.size })

    // One player for the whole feed.
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    // Point the player at whatever video is currently on screen.
    LaunchedEffect(pagerState, videos) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val item = videos.getOrNull(page) ?: return@collect
            exoPlayer.setMediaItem(MediaItem.fromUri(item.url))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    // Feed synchronisation when an upload completes.
    LaunchedEffect(Unit) {
        viewModel.uploadComplete.collect { url ->
            val atTop = pagerState.currentPage == 0 || videos.isEmpty()
            viewModel.prependVideo(url)
            if (atTop) {
                // Animate the fresh video sliding down into view, then auto-hide the pill.
                scope.launch { pagerState.animateScrollToPage(0) }
                viewModel.setPillSuccess()
            } else {
                // Preserve the viewer's position: the prepend shifts every index by one,
                // so silently jump to keep the same video on screen.
                scope.launch { pagerState.scrollToPage(pagerState.currentPage + 1) }
                viewModel.setPillTapToView(url)
            }
        }
    }

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri) ?: "video/mp4"
        viewModel.enqueueVideo(uri, mime, fileName = "feed-video.mp4")
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (videos.isEmpty()) {
            EmptyState()
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { videos[it].id },
            ) { page ->
                VideoPage(
                    isCurrent = page == pagerState.currentPage,
                    exoPlayer = exoPlayer,
                )
            }
        }

        UploadPill(
            state = pill,
            onAutoHideComplete = viewModel::onPillAutoHideComplete,
            onTapToView = {
                viewModel.onTapToView()
                scope.launch { pagerState.animateScrollToPage(0) }
            },
            onDismissError = viewModel::dismissPillError,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp),
        )

        ExtendedFloatingActionButton(
            onClick = {
                pickVideo.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            text = { Text("Upload") },
            icon = { Text("＋") },
        )
    }
}

/**
 * Hosts the shared player when this page is current; tapping toggles play/pause. A
 * non-current page renders a black surface (its decoder lives on the current page).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPage(isCurrent: Boolean, exoPlayer: ExoPlayer) {
    var paused by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isCurrent) {
                if (isCurrent) {
                    detectTapGestures(onTap = {
                        paused = !paused
                        exoPlayer.playWhenReady = !paused
                    })
                }
            },
    ) {
        if (isCurrent) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { view -> view.player = exoPlayer },
                onReset = { view -> view.player = null },
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "No videos yet.\nBe the first to upload!",
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(32.dp),
        )
    }
}
