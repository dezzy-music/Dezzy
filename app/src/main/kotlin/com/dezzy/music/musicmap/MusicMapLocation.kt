/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.musicmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.dezzy.music.playback.OmegaMathIntegration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.round

/**
 * Ubicación aproximada para el Mapa musical.
 *
 * - Se redondea a 2 decimales (~1 km): suficiente para el mapa y no revela la dirección exacta.
 * - Se guarda en el dispositivo para que, tras reiniciar la app, la presencia siga saliendo en el
 *   mapa sin tener que volver a abrirlo (antes solo vivía en memoria y se perdía).
 */
object MusicMapLocation {
    private const val PREFS = "music_map_location"
    private const val FIX_TIMEOUT_MS = 10_000L

    @Volatile private var restored = false

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Restaura la última ubicación guardada en [OmegaMathIntegration] (una vez por proceso). */
    fun ensureRestored(context: Context) {
        if (restored) return
        restored = true
        if (OmegaMathIntegration.latitude != null && OmegaMathIntegration.longitude != null) return
        stored(context)?.let { (lat, lng) ->
            OmegaMathIntegration.latitude = lat
            OmegaMathIntegration.longitude = lng
        }
    }

    /** Última ubicación guardada, o null. */
    fun stored(context: Context): Pair<Double, Double>? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("lat") || !prefs.contains("lng")) return null
        val lat = prefs.getFloat("lat", 0f).toDouble()
        val lng = prefs.getFloat("lng", 0f).toDouble()
        return lat to lng
    }

    /** Borra la ubicación guardada (al dejar de compartir). */
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        OmegaMathIntegration.latitude = null
        OmegaMathIntegration.longitude = null
    }

    /**
     * Pide una ubicación nueva (red primero, GPS de respaldo) con tiempo límite; si no llega,
     * usa la última conocida o la guardada. La guarda y la publica en [OmegaMathIntegration].
     */
    suspend fun refresh(context: Context): Pair<Double, Double>? {
        if (!hasPermission(context)) return stored(context)
        val fix = freshFix(context) ?: lastKnown(context)
        val point = fix?.let { coarse(it.latitude) to coarse(it.longitude) } ?: return stored(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("lat", point.first.toFloat())
            .putFloat("lng", point.second.toFloat())
            .putLong("at", System.currentTimeMillis())
            .apply()
        OmegaMathIntegration.latitude = point.first
        OmegaMathIntegration.longitude = point.second
        return point
    }

    private fun coarse(value: Double): Double = round(value * 100.0) / 100.0

    @SuppressLint("MissingPermission")
    private suspend fun freshFix(context: Context): Location? = withContext(Dispatchers.Main) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@withContext null
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return@withContext null
        withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val signal = android.os.CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                            if (cont.isActive) cont.resume(location)
                        }
                    } else {
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                if (cont.isActive) cont.resume(location)
                            }
                        }
                        cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    }
                } catch (_: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider ->
                runCatching { if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null }
                    .getOrNull()
            }
            .maxByOrNull { it.time }
    }
}
