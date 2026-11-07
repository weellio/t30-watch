package com.t30watch.ble

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class CommandTimeoutException(val command: String, timeoutMs: Long) :
    Exception("$command: no answer from the watch within ${timeoutMs / 1000} s")

/**
 * The one door every watch command goes through.
 *
 * The watch can't handle two operations at once (data gets corrupted), so commands run strictly
 * one after another: each waits for its callback, or its timeout, before the next one starts.
 * SDK calls run on the main thread, which is what the Veepoo demo does.
 */
class CommandQueue(private val log: RawLog) {
    private val mutex = Mutex()

    suspend fun <T> run(name: String, timeoutMs: Long, block: suspend () -> T): T = mutex.withLock {
        log.app("queue start", name)
        val started = System.currentTimeMillis()
        try {
            withContext(Dispatchers.Main) {
                withTimeout(timeoutMs) { block() }
            }
        } catch (e: TimeoutCancellationException) {
            log.app("queue timeout", "$name after ${timeoutMs}ms")
            throw CommandTimeoutException(name, timeoutMs)
        } finally {
            log.app("queue end", "$name (${System.currentTimeMillis() - started}ms)")
        }
    }
}
