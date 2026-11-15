/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.dezzy.music.p2p.crypto.toHex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Radar «Cerca»: descubre a otras personas con Dezzy a unos metros usando Bluetooth LE.
 * Sin internet, sin GPS y sin servidores: cada teléfono anuncia su clave pública
 * (32 bytes repartidos en el anuncio y en la respuesta al escaneo) y escucha las de los demás.
 */
object P2PNearby {

    /** UUID de 16 bits propios de Dezzy (primera y segunda mitad de la clave). */
    private val UUID_A: ParcelUuid = ParcelUuid.fromString("0000d22e-0000-1000-8000-00805f9b34fb")
    private val UUID_B: ParcelUuid = ParcelUuid.fromString("0000d22f-0000-1000-8000-00805f9b34fb")

    private const val STALE_MS = 45_000L

    enum class Status { IDLE, SCANNING, NO_PERMISSION, BLUETOOTH_OFF, UNSUPPORTED }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _people = MutableStateFlow<List<P2PNearbyPerson>>(emptyList())
    val people: StateFlow<List<P2PNearbyPerson>> = _people.asStateFlow()

    private val found = ConcurrentHashMap<String, P2PNearbyPerson>()
    private var pruneJob: Job? = null
    private var advertising = false
    private var scanning = false

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            advertising = false
            Timber.d("BLE anuncio falló: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onResult(it) }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Timber.d("BLE escaneo falló: $errorCode")
        }
    }

    private fun onResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val a = record.getServiceData(UUID_A) ?: return
        val b = record.getServiceData(UUID_B) ?: return
        if (a.size != 16 || b.size != 16) return
        val pubkey = (a + b).toHex()
        if (pubkey == P2PSocial.me) return
        val isNew = !found.containsKey(pubkey)
        found[pubkey] = P2PNearbyPerson(pubkey, result.rssi, System.currentTimeMillis())
        publish()
        if (isNew) P2PSocial.scope.launch { runCatching { P2PSocial.loadProfiles(listOf(pubkey)) } }
    }

    private fun publish() {
        val cutoff = System.currentTimeMillis() - STALE_MS
        found.entries.removeAll { it.value.lastSeen < cutoff }
        _people.value = found.values.sortedByDescending { it.rssi }
    }

    /** Empieza a anunciarme (si soy visible) y a escuchar a los de alrededor. */
    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (!hasPermissions(context)) {
            _status.value = Status.NO_PERMISSION
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            _status.value = Status.UNSUPPORTED
            return
        }
        if (!adapter.isEnabled) {
            _status.value = Status.BLUETOOTH_OFF
            return
        }
        runCatching {
            val myKey = P2PSocial.me
            if (!advertising && myKey != null && P2PSocial.nearbyVisible) {
                val bytes = myKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                adapter.bluetoothLeAdvertiser?.let { advertiser ->
                    val settings = AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                        .setConnectable(false)
                        .build()
                    val data = AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .addServiceData(UUID_A, bytes.copyOfRange(0, 16))
                        .build()
                    val response = AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .addServiceData(UUID_B, bytes.copyOfRange(16, 32))
                        .build()
                    advertiser.startAdvertising(settings, data, response, advertiseCallback)
                    advertising = true
                }
            }
            if (!scanning) {
                val filters = listOf(ScanFilter.Builder().setServiceData(UUID_A, ByteArray(0), ByteArray(0)).build())
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                adapter.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
                scanning = true
            }
            _status.value = Status.SCANNING
        }.onFailure {
            Timber.w(it, "No se pudo iniciar el radar")
            _status.value = Status.NO_PERMISSION
        }
        if (pruneJob?.isActive != true) {
            pruneJob = P2PSocial.scope.launch {
                while (isActive) {
                    delay(5_000)
                    publish()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop(context: Context) {
        pruneJob?.cancel()
        pruneJob = null
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        runCatching { if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { if (advertising) adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        scanning = false
        advertising = false
        if (_status.value == Status.SCANNING) _status.value = Status.IDLE
    }
}
