package dev.uploadmanager.events

import android.util.Log
import dev.uploadmanager.api.UploadEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter

/** In-process event bus backing UploadManager.observe() (spec §11.3, §13.1). */
internal class UploadEvents(private val logging: Boolean) {

    private val flow = MutableSharedFlow<UploadEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val all: Flow<UploadEvent> = flow.asSharedFlow()

    fun forTask(taskId: String): Flow<UploadEvent> = all.filter { it.taskId == taskId }

    fun emit(event: UploadEvent) {
        flow.tryEmit(event)
        if (logging) {
            Log.d(TAG, "sdk.upload.${event.javaClass.simpleName.lowercase()} $event")
        }
    }

    private companion object {
        const val TAG = "UploadManager"
    }
}
