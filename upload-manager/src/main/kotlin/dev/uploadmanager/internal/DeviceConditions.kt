package dev.uploadmanager.internal

/** Thermal severity, ordered so `>=` comparisons work (revision spec §10.3). */
internal enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL }

/** A snapshot of the device state that governs upload concurrency (spec §10.2). */
internal data class DeviceConditions(
    val batteryPct: Int,
    val charging: Boolean,
    val thermal: ThermalLevel,
    val unmetered: Boolean,
)

/**
 * Pure mapping from device conditions to upload concurrency (spec §10.2/§10.3).
 * Kept side-effect free so it is fully unit-testable; the framework listeners
 * live in [DeviceConditionsMonitor].
 */
internal object ConcurrencyPolicy {

    /** Total concurrent transfers allowed under [conditions], capped at [configMax]. */
    fun maxConcurrent(conditions: DeviceConditions, configMax: Int): Int {
        val raw = when {
            conditions.thermal >= ThermalLevel.MODERATE -> 0        // all paused
            !conditions.unmetered -> 1                              // cellular: throttle to 1
            conditions.charging -> 3                                // charging behaves like healthy
            conditions.batteryPct < 20 -> 1
            conditions.batteryPct < 50 -> 2
            else -> 3
        }
        return raw.coerceIn(0, configMax)
    }

    /**
     * Whether a large (e.g. video) upload of [sizeBytes] may proceed now. Large
     * uploads are held off cellular, in the heat, and on a low non-charging battery.
     */
    fun allowsLargeUpload(conditions: DeviceConditions, sizeBytes: Long, largeThresholdBytes: Long): Boolean {
        if (sizeBytes < largeThresholdBytes) return true
        return when {
            conditions.thermal >= ThermalLevel.MODERATE -> false
            !conditions.unmetered -> false
            !conditions.charging && conditions.batteryPct < 20 -> false
            else -> true
        }
    }
}
