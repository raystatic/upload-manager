package dev.uploadmanager.worker

import com.google.firebase.storage.UploadTask
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-scoped registry of live Firebase [UploadTask]s (spec §6.2 critical note).
 * Survives worker restarts while the process is alive; pause/resume route here.
 * After process death, resumption goes through the persisted session URI instead.
 */
class ActiveTaskRegistry {

    private val tasks = ConcurrentHashMap<String, UploadTask>()

    fun register(taskId: String, task: UploadTask) {
        tasks[taskId] = task
    }

    fun get(taskId: String): UploadTask? = tasks[taskId]

    fun remove(taskId: String): UploadTask? = tasks.remove(taskId)

    /**
     * A previously-paused (or stopped-worker) task that can be resumed in-process
     * instead of opening a new session.
     */
    fun resumable(taskId: String): UploadTask? =
        tasks[taskId]?.takeIf { !it.isComplete }
}
