// app/src/main/java/com/domain/app/place/Here.kt
package com.domain.app.place

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.resume

/**
 * Where the phone is now, for "where am I" and "this is home". A fresh
 * reading if one comes within a few seconds, else the last one the phone
 * had, if it is recent. Null without location permission.
 */
object Here {
    private val FRESH_ENOUGH: Duration = Duration.ofMinutes(5)

    @SuppressLint("MissingPermission") // checked first
    suspend fun now(context: Context): Fix? {
        if (!LocationService.allowed(context)) return null
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in providers) {
                val fresh = withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine<android.location.Location?> { cont ->
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        manager.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
                    }
                }
                if (fresh != null) return Fix(fresh.latitude, fresh.longitude, fresh.accuracy, Instant.ofEpochMilli(fresh.time))
            }
        }
        val last = providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
            ?.let { Fix(it.latitude, it.longitude, it.accuracy, Instant.ofEpochMilli(it.time)) }
        val kept = LocationState(context).lastFix
        return listOfNotNull(last, kept).maxByOrNull { it.time }
            ?.takeIf { Duration.between(it.time, Instant.now()) < FRESH_ENOUGH }
    }
}
