package de.powerizzle.powerlink

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress

class LanTest {
    private val port = 47901
    private val discovery = 47902

    @Test
    fun findConnectAndTalk() = runBlocking {
        val guests = Channel<Link>(Channel.UNLIMITED)
        val server = launch { Lan.serve(port, guests) }
        val adverts = launch { Lan.advertise(discovery) { Advert("test-app", "Alice", "1 player") } }
        try {
            val found = withTimeout(10_000) {
                Lan.scan("test-app", discovery, listOf(InetAddress.getLoopbackAddress())).first { it.isNotEmpty() }
            }
            assertEquals("Alice", found.single().advert.name)
            assertEquals("1 player", found.single().advert.info)

            val guest = Lan.connect(found.single().address, port)
            val host = withTimeout(5_000) { guests.receive() }
            guest.sendLine("hello")
            assertEquals("hello", host.receive())
            host.sendLine("welcome")
            assertEquals("welcome", guest.receive())

            guest.close()
            assertNull(withTimeout(5_000) { host.receive() })
        } finally {
            server.cancelAndJoin()
            adverts.cancelAndJoin()
        }
    }

    @Test
    fun otherAppsAreIgnored() = runBlocking {
        val adverts = launch { Lan.advertise(discovery) { Advert("other-app", "Bob") } }
        try {
            val seen = async {
                withTimeout(4_000) { Lan.scan("test-app", discovery, listOf(InetAddress.getLoopbackAddress())).first { it.isNotEmpty() } }
            }
            assertTrue(runCatching { seen.await() }.isFailure)
        } finally {
            adverts.cancelAndJoin()
        }
    }

    @Test
    fun closedPortSaysNoGame() = runBlocking {
        val e = runCatching { Lan.connect("127.0.0.1", port) }.exceptionOrNull()
        assertTrue(e is LinkException)
        assertTrue(e!!.message!!, "no game" in e.message!!)
    }

    @Test
    fun heartbeatsAreInvisibleAndSilenceClosesTheLink() = runBlocking {
        // Two links over pipes; b never sends anything after the first line, so its pipe goes quiet.
        val aIn = PipedInputStream()
        val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream()
        val aOut = PipedOutputStream(bIn)
        val t = Link.Timeouts(heartbeatMs = 100, silenceMs = 600)
        val a = Link(aIn, aOut, { aIn.close(); aOut.close() }, Medium.WIFI, "b", t)
        val b = Link(bIn, bOut, { bIn.close(); bOut.close() }, Medium.WIFI, "a", t)
        a.sendLine("ping")
        // b's reads see only real lines even though heartbeats keep arriving.
        assertEquals("ping", withTimeout(2_000) { b.receive() })
        // Nobody reads or sends anything now, but the heartbeats keep both alive.
        Thread.sleep(1_200)
        assertTrue(!a.isClosed && !b.isClosed)
        // A peer that goes silent without closing (phone walked away) is dropped after the timeout.
        val quiet = Link(PipedInputStream(PipedOutputStream()), java.io.OutputStream.nullOutputStream(), {}, Medium.WIFI, "quiet", t)
        assertNull(withTimeout(3_000) { quiet.receive() })
        assertTrue(quiet.isClosed)
        // A peer that closes is noticed right away.
        b.close()
        assertNull(withTimeout(3_000) { a.receive() })
    }
}
