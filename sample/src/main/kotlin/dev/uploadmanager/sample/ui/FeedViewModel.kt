package dev.uploadmanager.sample.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.uploadmanager.sample.data.VideoItem
import dev.uploadmanager.sample.data.VideoRepository
import dev.uploadmanager.UploadManager
import dev.uploadmanager.api.UploadEvent
import dev.uploadmanager.api.UploadRequest
import dev.uploadmanager.api.UploadPriority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UploadUiState {
    object Idle : UploadUiState()
    data class Uploading(val progress: Int) : UploadUiState()
    data class Success(val downloadUrl: String) : UploadUiState()
    data class Error(val message: String) : UploadUiState()
}

class FeedViewModel : ViewModel() {
    private val repository = VideoRepository()
    val videos = repository.videos

    private val _uploadState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val uploadState = _uploadState.asStateFlow()

    private var activeTaskId: String? = null

    fun startUpload(uri: Uri, fileName: String, mimeType: String) {
        viewModelScope.launch {
            try {
                val request = UploadRequest(
                    localUri = uri,
                    mimeType = mimeType,
                    fileName = fileName,
                    priority = UploadPriority.P0 // High priority for user-initiated upload
                )
                val taskId = UploadManager.enqueue(request)
                activeTaskId = taskId
                observeUpload(taskId)
            } catch (e: Exception) {
                _uploadState.value = UploadUiState.Error(e.message ?: "Failed to start upload")
            }
        }
    }

    private fun observeUpload(taskId: String) {
        viewModelScope.launch {
            UploadManager.observe(taskId).collect { event ->
                when (event) {
                    is UploadEvent.Progress -> {
                        _uploadState.value = UploadUiState.Uploading(event.pct)
                    }
                    is UploadEvent.Completed -> {
                        onUploadSuccess(event.downloadUrl)
                    }
                    is UploadEvent.DedupHit -> {
                        onUploadSuccess(event.downloadUrl)
                    }
                    is UploadEvent.Failed -> {
                        _uploadState.value = UploadUiState.Error(event.reason)
                    }
                    is UploadEvent.Cancelled -> {
                        _uploadState.value = UploadUiState.Idle
                    }
                    else -> {}
                }
            }
        }
    }

    private fun onUploadSuccess(downloadUrl: String) {
        _uploadState.value = UploadUiState.Success(downloadUrl)
        // Inject into feed
        val newVideo = VideoItem(
            id = activeTaskId ?: System.currentTimeMillis().toString(),
            url = downloadUrl
        )
        repository.addVideoAtTop(newVideo)
    }

    fun resetUploadState() {
        _uploadState.value = UploadUiState.Idle
        activeTaskId = null
    }
}
