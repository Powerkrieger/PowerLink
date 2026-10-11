package de.powerizzle.powerlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** How two phones are connected. */
enum class Medium(val label: String, val icon: String) {
    WIFI("Wi-Fi / hotspot", "📶"),
    BLUETOOTH("Bluetooth", "🔵"),
    WIFI_DIRECT("Wi-Fi Direct", "📡"),
}

/**
 * A connection to one other phone carrying lines of text, the same over every [Medium].
 *
 * Both ends send a small heartbeat every few seconds and drop the connection when the other side
 * has been silent for [Timeouts.silenceMs], so a phone that walked away or went to sleep is noticed
 * even in a turn-based game where nothing else is sent for minutes.
 */
class Link(
    input: InputStream,
    output: OutputStream,
    private val closeable: Closeable,
    val medium: Medium,
    /** Who is on the other end, for display: an IP address or a device name. */
    val remote: String,
    private val timeouts: Timeouts = Timeouts(),
    private val onClose: () -> Unit = {},
) {
    data class Timeouts(val heartbeatMs: Long = 3_000, val silenceMs: Long = 15_000)

    private val reader = input.bufferedReader()
    private val writer = output.bufferedWriter()
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastHeard = System.currentTimeMillis()

    /** Lines read so far. A dedicated reader keeps the heartbeat check working even while the app isn't reading. */
    private val inbox = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    lastHeard = System.currentTimeMillis()
                    if (line != HEARTBEAT) inbox.send(line)
                }
            } catch (_: Exception) {
                // connection broke
            } finally {
                inbox.close()
                close()
            }
        }
        scope.launch {
            while (isActive) {
                delay(timeouts.heartbeatMs)
                if (System.currentTimeMillis() - lastHeard > timeouts.silenceMs) {
                    close()
                    break
                }
                runCatching { write(HEARTBEAT) }.onFailure { close() }
            }
        }
    }

    val isClosed get() = closed.get()

    private fun write(line: String) {
        synchronized(writer) {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }

    /** Sends one line (must not contain line breaks). Blocking; throws when the connection is gone. */
    fun sendLine(line: String) {
        require('\n' !in line && '\r' !in line) { "Lines must not contain line breaks" }
        write(line)
    }

    /** Sends one line on the IO dispatcher. Returns false when the connection is gone. */
    suspend fun send(line: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { sendLine(line) }.onFailure { close() }.isSuccess
    }

    /** Next line, or null once the connection is closed and everything sent before was read. Blocking. */
    fun readLine(): String? = runBlocking { receive() }

    /** Next line, or null once the connection is closed and everything sent before was read. */
    suspend fun receive(): String? = inbox.receiveCatching().getOrNull()

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        inbox.close() // lines that already arrived can still be read
        runCatching { closeable.close() }
        scope.cancel()
        runCatching { onClose() }
    }

    override fun toString() = "Link(${medium.name}, $remote)"

    private companion object {
        /** Never produced by apps: real lines can't be a lone NUL character by convention. */
        const val HEARTBEAT = "\u0000"
    }
}

/**
 * Runs a blocking call (accept, connect, read) on the IO dispatcher. If the coroutine is cancelled
 * while the call is still running, [abort] is called to unblock it, typically by closing the socket.
 */
suspend fun <T> cancellableBlocking(abort: () -> Unit, block: () -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val watcher = launch {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) abort()
        }
    }
    try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: Exception) {
        // Aborting makes the call throw (e.g. "Socket closed"); report that as the cancellation it is.
        ensureActive()
        throw e
    } finally {
        finished.set(true)
        watcher.cancel()
    }
}

/** Something went wrong connecting; [message] is meant for players, not developers. */
class LinkException(message: String, cause: Throwable? = null) : Exception(message, cause)
