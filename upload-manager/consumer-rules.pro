# Consumer R8/ProGuard rules shipped to apps that depend on this SDK.
# Room, WorkManager, and Firebase ship their own rules; these cover this SDK.

# WorkManager instantiates these workers by class name via reflection.
-keep class dev.uploadmanager.worker.FirebaseUploadWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class dev.uploadmanager.scheduler.ParkedSweepWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# Public API surface — keep names stable for consumers (incl. Java callers).
-keep class dev.uploadmanager.UploadManager { *; }
-keep class dev.uploadmanager.UploadManager$* { *; }
-keep interface dev.uploadmanager.api.** { *; }
-keep class dev.uploadmanager.api.** { *; }
