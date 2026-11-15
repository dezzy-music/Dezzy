/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.dezzy.music.security

import android.content.Context
import android.util.AtomicFile
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.dezzy.music.constants.AccountChannelHandleKey
import com.dezzy.music.constants.AccountEmailKey
import com.dezzy.music.constants.AccountNameKey
import com.dezzy.music.constants.DataSyncIdKey
import com.dezzy.music.constants.InnerTubeCookieKey
import com.dezzy.music.constants.PoTokenKey
import com.dezzy.music.constants.SpotifySpDcKey
import com.dezzy.music.constants.SpotifySpKeyKey
import com.dezzy.music.constants.VisitorDataKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

/**
 * Copia de las sesiones (YouTube y Spotify) fuera del archivo de ajustes.
 *
 * Si la app se cierra de golpe mientras DataStore escribe (falta de memoria, un servicio que
 * revienta…), el archivo `settings` puede quedar dañado y DataStore lo recrea vacío: eso era un
 * cierre de sesión silencioso. Con esta copia, el manejador de corrupción de DataStore recupera
 * las sesiones en lugar de perderlas.
 *
 * - Se guarda en `noBackupFilesDir`: nunca viaja en la copia de seguridad de Google.
 * - Los valores se copian tal cual están en DataStore (la cookie y sp_dc ya van cifrados con
 *   [SessionCrypto]); no se descifra nada aquí.
 * - Se escribe con [AtomicFile], así que la copia nunca queda a medias.
 */
object SessionBackup {
    private const val FILE_NAME = "session_backup.properties"

    private val KEYS: List<Preferences.Key<String>> = listOf(
        InnerTubeCookieKey,
        VisitorDataKey,
        DataSyncIdKey,
        PoTokenKey,
        AccountNameKey,
        AccountEmailKey,
        AccountChannelHandleKey,
        SpotifySpDcKey,
        SpotifySpKeyKey,
    )

    @Volatile
    private var file: AtomicFile? = null

    /** Llamar al principio de `Application.onCreate`, antes de leer DataStore. */
    fun init(context: Context) {
        if (file != null) return
        synchronized(this) {
            if (file == null) {
                file = AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
            }
        }
    }

    /** Las sesiones guardadas en la copia, listas para sembrar un DataStore recién recreado. */
    fun restore(): MutablePreferences {
        val prefs = mutablePreferencesOf()
        val f = file ?: return prefs
        runCatching {
            val props = Properties()
            f.openRead().use { props.load(it) }
            KEYS.forEach { key ->
                props.getProperty(key.name)?.takeIf { it.isNotEmpty() }?.let { prefs[key] = it }
            }
            Timber.w("SessionBackup: sesiones recuperadas (%d valores)", prefs.asMap().size)
        }.onFailure { Timber.w(it, "SessionBackup: no hay copia que recuperar") }
        return prefs
    }

    /** Mantiene la copia al día cada vez que cambia una sesión (entrar, salir, renovar). */
    fun startMirroring(scope: CoroutineScope, dataStore: DataStore<Preferences>) {
        scope.launch(Dispatchers.IO) {
            dataStore.data
                .map { prefs -> KEYS.associate { it.name to prefs[it].orEmpty() } }
                .distinctUntilChanged()
                .catch { Timber.w(it, "SessionBackup: no se pudo leer DataStore") }
                .collect { write(it) }
        }
    }

    private fun write(values: Map<String, String>) {
        val f = file ?: return
        var out: FileOutputStream? = null
        try {
            out = f.startWrite()
            val props = Properties()
            values.forEach { (k, v) -> if (v.isNotEmpty()) props.setProperty(k, v) }
            props.store(out, null)
            f.finishWrite(out)
        } catch (e: Exception) {
            out?.let { f.failWrite(it) }
            Timber.w(e, "SessionBackup: no se pudo guardar la copia")
        }
    }
}
