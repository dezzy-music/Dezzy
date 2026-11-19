/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cola acotada para lo que se hace al terminar una descarga (letra, traducción,
 * portada grande, canvas, exportar). Antes cada descarga completada lanzaba su
 * propia corrutina sin límite: con una precarga de miles de canciones eran miles
 * de peticiones de red a la vez peleando con las propias descargas.
 *
 * - Como mucho [maxConcurrent] trabajos a la vez; el resto espera en orden (el
 *   semáforo de kotlinx es FIFO) sin ocupar hilos.
 * - Una canción ya en cola o en curso no se repite.
 * - Un fallo en una canción no para a las demás ni deja permisos sin liberar.
 */
class DownloadPostProcessor(
    private val scope: CoroutineScope,
    maxConcurrent: Int,
    private val process: suspend (String) -> Unit,
) {
    private val permits = Semaphore(maxConcurrent)
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val running = AtomicInteger(0)

    /** Canciones en cola o en curso. */
    val pending: Int get() = queued.size

    /** Trabajos ejecutándose ahora mismo. */
    val active: Int get() = running.get()

    /** Encola [songId]. Devuelve false si ya estaba en cola o en curso. */
    fun submit(songId: String): Boolean {
        if (!queued.add(songId)) return false
        scope.launch {
            try {
                permits.withPermit {
                    running.incrementAndGet()
                    try {
                        process(songId)
                    } finally {
                        running.decrementAndGet()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // El que llama ya registra sus errores; aquí sólo se evita que uno tumbe la cola.
            } finally {
                queued.remove(songId)
            }
        }
        return true
    }
}
