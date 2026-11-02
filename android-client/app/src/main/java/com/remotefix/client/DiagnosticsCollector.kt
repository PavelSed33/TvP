package com.remotefix.client

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs

object DiagnosticsCollector {
    fun collect(
        context: Context,
        serverConnected: Boolean,
        screenPermissionGranted: Boolean,
        sessionServiceActive: Boolean,
    ): DeviceDiagnostics {
        val app = context.applicationContext
        val batteryIntent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val batteryScale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (batteryLevel >= 0 && batteryScale > 0) {
            (batteryLevel * 100 / batteryScale).coerceIn(0, 100)
        } else {
            null
        }

        val memory = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val freeRamMb = memory.availMem / (1024 * 1024)
        val freeStorageMb = StatFs(Environment.getDataDirectory().absolutePath).availableBytes / (1024 * 1024)
        val network = activeNetwork(app)
        val appVersion = runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        }.getOrDefault("unknown")

        val checks = listOf(
            DiagnosticCheck("internet", when (network.hasInternet) {
                true -> "ok"
                false -> "warning"
                null -> "unknown"
            }),
            DiagnosticCheck("server_connection", if (serverConnected) "ok" else "warning"),
            DiagnosticCheck("screen_capture_permission", if (screenPermissionGranted) "ok" else "unknown"),
            DiagnosticCheck("accessibility_permission", "unsupported"),
            DiagnosticCheck("storage", if (freeStorageMb > 100) "ok" else "warning"),
            DiagnosticCheck("wifi", if (network.type == "wifi") "ok" else "warning"),
            DiagnosticCheck("foreground_service", if (sessionServiceActive) "ok" else "warning"),
        )

        return DeviceDiagnostics(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            appVersion = appVersion,
            batteryPercent = batteryPercent,
            freeRamMb = freeRamMb,
            freeStorageMb = freeStorageMb,
            networkType = network.type,
            wifiEnabled = network.type == "wifi",
            checks = checks,
        )
    }

    private data class NetworkSnapshot(val type: String, val hasInternet: Boolean?)

    private fun activeNetwork(context: Context): NetworkSnapshot {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return NetworkSnapshot("none", false)
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return NetworkSnapshot("unknown", null)
        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
        return NetworkSnapshot(type, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
    }
}
