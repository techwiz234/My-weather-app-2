package com.techwiz.myweather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/** One-shot "where am I" using the platform LocationManager (no Google Play services needed). */
object LocationHelper {

    @SuppressLint("MissingPermission")
    fun locate(context: Context, onResult: (Location?) -> Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            onResult(null)
            return
        }
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val last = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

        // A fix from the last few minutes is plenty for weather.
        if (last != null && System.currentTimeMillis() - last.time < 5 * 60_000L) {
            onResult(last)
            return
        }
        // Network location is fast and works with "approximate" permission; GPS is the backup.
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in providers }
        if (provider == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(last)
            return
        }
        try {
            lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { loc ->
                onResult(loc ?: last)
            }
        } catch (e: SecurityException) {
            onResult(last)
        }
    }
}
