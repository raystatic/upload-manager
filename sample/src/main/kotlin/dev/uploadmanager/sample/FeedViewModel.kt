package dev.uploadmanager.sample

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.uploadmanager.UploadManager
import dev.uploadmanager.api.UploadEvent
import dev.uploadmanager.api.UploadPriority
import dev.uploadmanager.api.UploadRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** [id] is unique per feed entry so the pager keeps stable keys even if the same
 *  video URL is uploaded twice (e.g. a dedup hit returns an existing object's URL). */
data class VideoItem(val url: String, val id: String = UUID.randomUUID().toString())

sealed class PillUiState {
    object Hidden : PillUiState()
    data class Uploading(val pct: Int) : PillUiState()
    object SuccessAutoHide : PillUiState()
    data class TapToView(val url: String) : PillUiState()
    object Error : PillUiState()
}

class FeedViewModel(application: Application) : AndroidViewModel(application) {

    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos.asStateFlow()

    private val _pill = MutableStateFlow<PillUiState>(PillUiState.Hidden)
    val pill: StateFlow<PillUiState> = _pill.asStateFlow()

    // Emits download URL when an upload finishes; UI handles feed injection and scroll.
    private val _uploadComplete = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val uploadComplete = _uploadComplete.asSharedFlow()

    fun enqueueVideo(uri: Uri, mimeType: String, fileName: String) {
        viewModelScope.launch {
            runCatching {
                val taskId = UploadManager.enqueue(
                    UploadRequest(
                        localUri = uri,
                        mimeType = mimeType,
                        fileName = fileName,
                        priority = UploadPriority.P0,
                    )
                )
                _pill.value = PillUiState.Uploading(0)
                // collectUntil: stop observing once this upload reaches a terminal
                // state, so collectors don't accumulate across many uploads.
                UploadManager.observe(taskId).takeWhile { event ->
                    when (event) {
                        is UploadEvent.Progress -> {
                            _pill.value = PillUiState.Uploading(event.pct); true
                        }
                        is UploadEvent.Completed -> {
                            _uploadComplete.emit(event.downloadUrl); false
                        }
                        is UploadEvent.DedupHit -> {
                            _uploadComplete.emit(event.downloadUrl); false
                        }
                        is UploadEvent.Failed -> {
                            _pill.value = PillUiState.Error; false
                        }
                        is UploadEvent.Cancelled -> {
                            _pill.value = PillUiState.Hidden; false
                        }
                        else -> true
                    }
                }.collect {}
            }.onFailure { _pill.value = PillUiState.Error }
        }
    }

    fun prependVideo(url: String) {
        _videos.update { list -> listOf(VideoItem(url = url)) + list }
    }

    fun setPillSuccess() {
        _pill.value = PillUiState.SuccessAutoHide
    }

    fun setPillTapToView(url: String) {
        _pill.value = PillUiState.TapToView(url)
    }

    fun onPillAutoHideComplete() {
        _pill.value = PillUiState.Hidden
    }

    fun onTapToView() {
        _pill.value = PillUiState.Hidden
    }

    fun dismissPillError() {
        _pill.value = PillUiState.Hidden
    }
}
