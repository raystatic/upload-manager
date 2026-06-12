package dev.uploadmanager.sample

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.uploadmanager.api.UploadState
import dev.uploadmanager.api.UploadTaskState

class TaskAdapter(
    private val onPause: (String) -> Unit,
    private val onResume: (String) -> Unit,
    private val onCancel: (String) -> Unit,
    private val onRetry: (String) -> Unit,
) : ListAdapter<UploadTaskState, TaskAdapter.Holder>(Diff) {

    object Diff : DiffUtil.ItemCallback<UploadTaskState>() {
        override fun areItemsTheSame(a: UploadTaskState, b: UploadTaskState) = a.taskId == b.taskId
        override fun areContentsTheSame(a: UploadTaskState, b: UploadTaskState) = a == b
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.task_name)
        val status: TextView = view.findViewById(R.id.task_status)
        val progress: ProgressBar = view.findViewById(R.id.task_progress)
        val primary: Button = view.findViewById(R.id.task_primary_button)
        val cancel: Button = view.findViewById(R.id.task_cancel_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_task, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val task = getItem(position)
        holder.name.text = task.fileName
        holder.status.text = buildString {
            append(task.state.name)
            append("  ${task.progressPct}%")
            task.errorCode?.let { append("  [$it]") }
        }
        holder.progress.progress = task.progressPct

        when (task.state) {
            UploadState.UPLOADING, UploadState.QUEUED, UploadState.PENDING, UploadState.RETRYING -> {
                holder.primary.text = "Pause"
                holder.primary.isEnabled = true
                holder.primary.setOnClickListener { onPause(task.taskId) }
            }
            UploadState.PAUSED -> {
                holder.primary.text = "Resume"
                holder.primary.isEnabled = true
                holder.primary.setOnClickListener { onResume(task.taskId) }
            }
            UploadState.FAILED, UploadState.CANCELLED -> {
                holder.primary.text = "Retry"
                holder.primary.isEnabled = true
                holder.primary.setOnClickListener { onRetry(task.taskId) }
            }
            else -> {
                holder.primary.text = "—"
                holder.primary.isEnabled = false
                holder.primary.setOnClickListener(null)
            }
        }

        holder.cancel.isEnabled = !task.state.isTerminal
        holder.cancel.setOnClickListener { onCancel(task.taskId) }
    }
}
