package dev.uploadmanager.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Drives the [ConcurrencyGovernor] from live battery, thermal, and network state
 * (spec §10.2/§10.3). Thermal MODERATE+ pauses all transfers; recovery resumes
 * them. Updates are debounced so a value flapping on a boundary doesn't thrash.
 */
class DeviceConditionsMonitor(
    context: Context,
    private val configMax: Int,
    private val largeThresholdBytes: Long,
    private val governor: ConcurrencyGovernor,
    private val scope: CoroutineScope,
    private val onThermalPause: () -> Unit,
    private val onThermalResume: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val thermalExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var current = DeviceConditions(100, false, ThermalLevel.NONE, unmetered = true)
    private var thermalPaused = false
    private var debounceJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = requestRecompute()
    }

    // Created only on API 29+ so the listener class is never loaded on older devices.
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    fun start() {
        appContext.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val listener = PowerManager.OnThermalStatusChangedListener { requestRecompute() }
            thermalListener = listener
            runCatching { powerManager.addThermalStatusListener(thermalExecutor, listener) }
        }
        recompute()
    }

    fun stop() {
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { listener ->
                runCatching { powerManager.removeThermalStatusListener(listener) }
            }
        }
        debounceJob?.cancel()
        thermalExecutor.shutdown()
    }

    fun conditions(): DeviceConditions = current

    private fun requestRecompute() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            recompute()
        }
    }

    private fun recompute() {
        val conditions = readConditions()
        current = conditions
        governor.setLimit(ConcurrencyPolicy.maxConcurrent(conditions, configMax))

        val shouldPause = conditions.thermal >= ThermalLevel.MODERATE
        if (shouldPause && !thermalPaused) {
            thermalPaused = true
            onThermalPause()
        } else if (!shouldPause && thermalPaused) {
            thermalPaused = false
            onThermalResume()
        }
    }

    private fun readConditions(): DeviceConditions {
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalLevel(runCatching { powerManager.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE))
        } else {
            ThermalLevel.NONE
        }

        val caps = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull()
        val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ?: true

        return DeviceConditions(pct, charging, thermal, unmetered)
    }

    private fun thermalLevel(status: Int): ThermalLevel = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NONE
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
        else -> ThermalLevel.CRITICAL
    }

    private companion object {
        const val DEBOUNCE_MS = 3_000L
    }
}
