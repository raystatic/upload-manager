package dev.uploadmanager.sample

import android.content.Context
import android.content.Intent
import dev.uploadmanager.api.DedupConfig
import dev.uploadmanager.api.NetworkPreference
import dev.uploadmanager.api.StagingConfig
import dev.uploadmanager.api.StagingMode
import dev.uploadmanager.api.SyncPolicy
import dev.uploadmanager.api.UploadManagerConfig

/**
 * Selectable config presets so every CUJ can be exercised from the sample. The
 * SDK is a process singleton, so changing a preset persists the choice and
 * restarts the app process.
 */
enum class Preset(val label: String, val hint: String) {
    DEFAULT("Default", "Staging (≤64 MB), dedup on, sync off, adaptive on"),
    REFERENCE_NO_STAGING("Reference (no staging)", "Nothing staged → exercises SOURCE_GONE & restart-on-change"),
    COPY_ALWAYS("Copy always", "Every file snapshotted, even large ones"),
    DEDUP_OFF("Dedup off", "No content-hash dedup / Firestore index"),
    SYNC_FULL("Firestore sync FULL", "Mirror lifecycle + file records to Firestore"),
    ADAPTIVE_OFF("Adaptive off", "Fixed concurrency, no battery/thermal throttling"),
    WIFI_ONLY("WiFi only", "All uploads wait for an unmetered network"),
    ;

    fun toConfig(): UploadManagerConfig = when (this) {
        DEFAULT -> UploadManagerConfig(enableLogging = true)
        REFERENCE_NO_STAGING -> UploadManagerConfig(
            enableLogging = true,
            staging = StagingConfig(mode = StagingMode.REFERENCE, autoCopyBelowBytes = 0),
        )
        COPY_ALWAYS -> UploadManagerConfig(
            enableLogging = true,
            staging = StagingConfig(mode = StagingMode.COPY),
        )
        DEDUP_OFF -> UploadManagerConfig(enableLogging = true, dedup = DedupConfig(enabled = false))
        SYNC_FULL -> UploadManagerConfig(enableLogging = true, syncPolicy = SyncPolicy.FULL)
        ADAPTIVE_OFF -> UploadManagerConfig(enableLogging = true, adaptiveConcurrency = false)
        WIFI_ONLY -> UploadManagerConfig(
            enableLogging = true,
            networkPreference = NetworkPreference.WIFI_ONLY,
        )
    }

    companion object {
        private const val PREFS = "sample_config"
        private const val KEY = "preset"

        fun current(context: Context): Preset {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == name } ?: DEFAULT
        }

        fun set(context: Context, preset: Preset) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, preset.name).apply()
        }

        /** Persists [preset] and restarts the process so the SDK re-initialises. */
        fun apply(context: Context, preset: Preset) {
            set(context, preset)
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            context.startActivity(intent)
            Runtime.getRuntime().exit(0)
        }
    }
}
