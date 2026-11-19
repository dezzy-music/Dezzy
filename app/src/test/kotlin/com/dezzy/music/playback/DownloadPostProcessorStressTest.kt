package com.dezzy.music.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pruebas de esfuerzo de la cola de post-procesado de descargas: miles de
 * descargas que terminan casi a la vez, desde muchos hilos, con fallos y
 * duplicados, como en una precarga grande de Spotify o un modo offline completo.
 */
class DownloadPostProcessorStressTest {

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun awaitDrained(processor: DownloadPostProcessor, timeoutMs: Long) {
        withTimeout(timeoutMs) {
            while (processor.pending > 0) delay(5)
        }
    }

    @Test
    fun dosMilDescargas_nuncaSuperanElLimiteYSeProcesanUnaVez() = runBlocking {
        val scope = newScope()
        val current = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val processor = DownloadPostProcessor(scope, maxConcurrent = 2) { id ->
            val now = current.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            delay(1) // simula red
            counts.getOrPut(id) { AtomicInteger() }.incrementAndGet()
            current.decrementAndGet()
        }

        repeat(2_000) { processor.submit("song$it") }
        awaitDrained(processor, timeoutMs = 30_000)

        assertTrue("pico de concurrencia ${peak.get()} > 2", peak.get() <= 2)
        assertEquals(2_000, counts.size)
        assertTrue(counts.values.all { it.get() == 1 })
        assertEquals(0, processor.active)
        scope.cancel()
    }

    @Test
    fun agotamiento_diezMilDesdeDieciseisHilos() = runBlocking {
        val scope = newScope()
        val done = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val current = AtomicInteger(0)
        val processor = DownloadPostProcessor(scope, maxConcurrent = 3) {
            peak.accumulateAndGet(current.incrementAndGet()) { a, b -> maxOf(a, b) }
            done.incrementAndGet()
            current.decrementAndGet()
        }

        val pool = Executors.newFixedThreadPool(16)
        val latch = CountDownLatch(16)
        repeat(16) { t ->
            pool.execute {
                for (i in 0 until 625) processor.submit("t$t-$i")
                latch.countDown()
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        awaitDrained(processor, timeoutMs = 30_000)
        pool.shutdown()

        assertEquals(10_000, done.get())
        assertTrue("pico ${peak.get()} > 3", peak.get() <= 3)
        scope.cancel()
    }

    @Test
    fun duplicadosEnCurso_noSeRepiten() = runBlocking {
        val scope = newScope()
        val runs = AtomicInteger(0)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val processor = DownloadPostProcessor(scope, maxConcurrent = 2) {
            runs.incrementAndGet()
            gate.await()
        }

        assertTrue(processor.submit("misma"))
        val pool = Executors.newFixedThreadPool(8)
        val accepted = AtomicInteger(0)
        val latch = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                repeat(50) { if (processor.submit("misma")) accepted.incrementAndGet() }
                latch.countDown()
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, accepted.get())

        gate.complete(Unit)
        awaitDrained(processor, timeoutMs = 5_000)
        assertEquals(1, runs.get())
        // Terminada, se puede volver a encolar (p. ej. una descarga repetida).
        assertTrue(processor.submit("misma"))
        awaitDrained(processor, timeoutMs = 5_000)
        assertEquals(2, runs.get())
        scope.cancel()
    }

    @Test
    fun fallos_noBloqueanLaCola() = runBlocking {
        val scope = newScope()
        val ok = AtomicInteger(0)
        val processor = DownloadPostProcessor(scope, maxConcurrent = 2) { id ->
            val n = id.removePrefix("s").toInt()
            if (n % 3 == 0) error("fallo simulado en $id")
            if (n % 7 == 0) throw OutOfMemoryError("simulado")
            ok.incrementAndGet()
        }

        repeat(900) { processor.submit("s$it") }
        awaitDrained(processor, timeoutMs = 20_000)

        val expected = (0 until 900).count { it % 3 != 0 && it % 7 != 0 }
        assertEquals(expected, ok.get())
        assertEquals(0, processor.active)
        // Los permisos se liberaron: entra y termina otra más.
        assertTrue(processor.submit("s1000"))
        awaitDrained(processor, timeoutMs = 5_000)
        assertFalse(processor.pending > 0)
        scope.cancel()
    }

    @Test
    fun trabajosLentos_noRetienenHilos() = runBlocking {
        // Mil trabajos esperando su turno no deben ocupar mil hilos: esperan suspendidos.
        val scope = newScope()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val processor = DownloadPostProcessor(scope, maxConcurrent = 2) { gate.await() }
        val threadsBefore = Thread.activeCount()
        repeat(1_000) { processor.submit("w$it") }
        delay(300)
        val threadsWhileWaiting = Thread.activeCount()
        assertTrue(
            "hilos: antes $threadsBefore, esperando $threadsWhileWaiting",
            threadsWhileWaiting - threadsBefore < 80,
        )
        assertEquals(2, processor.active)
        gate.complete(Unit)
        awaitDrained(processor, timeoutMs = 10_000)
        scope.cancel()
    }
}
