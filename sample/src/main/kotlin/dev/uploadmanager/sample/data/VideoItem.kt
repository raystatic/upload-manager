package dev.uploadmanager.sample.data

data class VideoItem(
    val id: String,
    val url: String,
    val isLocal: Boolean = false
)
