package dev.uploadmanager.sample.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class VideoRepository {
    // Some public sample videos for initial feed
    private val initialVideos = listOf(
        VideoItem("1", "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"),
        VideoItem("2", "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4"),
        VideoItem("3", "https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4")
    )

    private val _videos = MutableStateFlow(initialVideos)
    val videos: Flow<List<VideoItem>> = _videos.asStateFlow()

    fun addVideoAtTop(video: VideoItem) {
        val current = _videos.value.toMutableList()
        current.add(0, video)
        _videos.value = current
    }
}
