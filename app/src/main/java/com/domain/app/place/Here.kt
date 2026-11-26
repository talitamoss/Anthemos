// app/src/main/java/com/domain/app/place/Here.kt
package com.domain.app.place

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
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
    private val NEAR_ENOUGH: Duration = Duration.ofDays(3)

    suspend fun now(context: Context): Fix? =
        if (LocationService.allowed(context)) find(context, FRESH_ENOUGH) else null

    /**
     * Roughly where the phone is, for the weather: approximate location is
     * enough, and a reading from the last few days will do.
     */
    suspend fun near(context: Context): Fix? = if (roughlyAllowed(context)) find(context, NEAR_ENOUGH) else null

    fun roughlyAllowed(context: Context) = LocationService.allowed(context) ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by the callers
    private suspend fun find(context: Context, within: Duration): Fix? {
        val manager = context.getSystemService(LocationManager::class.java)
        // The fused provider first where there is one (Android 12+): many phones place
        // themselves only through it, with the network and GPS providers quiet.
        val providers = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(LocationManager.FUSED_PROVIDER) else emptyList()) +
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        val enabled = providers.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in enabled) {
                val fresh = withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine<android.location.Location?> { cont ->
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        // Approximate-only permission refuses GPS; that one is skipped.
                        runCatching { manager.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) } }
                            .onFailure { cont.resume(null) }
                    }
                }
                if (fresh != null) return Fix(fresh.latitude, fresh.longitude, fresh.accuracy, Instant.ofEpochMilli(fresh.time))
            }
        }
        val last = (providers + LocationManager.PASSIVE_PROVIDER).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            ?.let { Fix(it.latitude, it.longitude, it.accuracy, Instant.ofEpochMilli(it.time)) }
        val kept = LocationState(context).lastFix
        return listOfNotNull(last, kept).maxByOrNull { it.time }
            ?.takeIf { Duration.between(it.time, Instant.now()) < within }
    }
}
