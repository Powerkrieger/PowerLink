package de.powerizzle.powerlink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

internal val linkJson = Json { ignoreUnknownKeys = true }

/** What a host tells the world about its game. [info] is free text from the app, e.g. "3 players". */
@Serializable
data class Advert(val app: String, val name: String, val info: String = "", val open: Boolean = true, val port: Int = 0)

/**
 * Plain TCP on the local network: the same Wi-Fi, a phone's hotspot, or a Wi-Fi Direct group.
 * Pure java.net, so it also runs in JVM tests.
 */
object Lan {
    private const val ASK = "POWERLINK?"
    private const val ANSWER = "POWERLINK!"
    private const val CONNECT_TIMEOUT_MS = 6_000

    /** Accepts guests on [port] until cancelled, handing each connection to [guests]. */
    suspend fun serve(port: Int, guests: SendChannel<Link>, onReady: () -> Unit = {}) {
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            throw LinkException("Can't open port $port. Is another game still hosting?", e)
        }
        onReady()
        try {
            while (true) {
                val socket = cancellableBlocking({ server.close() }) { server.accept() }
                socket.tcpNoDelay = true
                guests.send(Link(socket.getInputStream(), socket.getOutputStream(), socket, Medium.WIFI, socket.inetAddress.hostAddress.orEmpty()))
            }
        } finally {
            server.close()
        }
    }

    /**
     * Connects to a host. [bind] gets the unconnected socket first; on Android it ties the socket to the network
     * the host is on, since an offline hotspot or Wi-Fi isn't the default network while mobile data is on.
     */
    suspend fun connect(
        address: String,
        port: Int,
        medium: Medium = Medium.WIFI,
        bind: (Socket, InetAddress) -> Unit = { _, _ -> },
        onClose: () -> Unit = {},
    ): Link {
        val socket = Socket()
        try {
            cancellableBlocking({ socket.close() }) {
                val target = InetAddress.getByName(address.trim())
                bind(socket, target)
                socket.connect(InetSocketAddress(target, port), CONNECT_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            socket.close()
            throw when (e) {
                // The usual suspect: guest and public Wi-Fis keep devices from seeing each other.
                is NoRouteToHostException, is SocketTimeoutException -> LinkException(
                    "Can't reach $address. This Wi-Fi probably keeps devices apart (common on guest networks). " +
                        "Use a phone's hotspot, Bluetooth or Wi-Fi Direct instead.", e,
                )
                is ConnectException -> LinkException("$address is reachable, but no game is open there.", e)
                else -> LinkException("Couldn't connect to $address: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
        socket.tcpNoDelay = true
        return Link(socket.getInputStream(), socket.getOutputStream(), socket, medium, address.trim(), onClose = onClose)
    }

    /**
     * Host side: answers discovery questions for [app] on [discoveryPort] until cancelled.
     * [bind] ties each reply socket to the network the question came from (see [connect]); unbound,
     * Android would send replies to an offline hotspot out over mobile data.
     */
    suspend fun advertise(discoveryPort: Int, bind: (DatagramSocket, InetAddress) -> Unit = { _, _ -> }, advert: () -> Advert) {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(discoveryPort))
        }
        val buf = ByteArray(512)
        try {
            while (true) {
                val packet = DatagramPacket(buf, buf.size)
                cancellableBlocking({ socket.close() }) { socket.receive(packet) }
                val ad = advert()
                if (String(packet.data, 0, packet.length) != ASK + ad.app) continue
                val reply = (ANSWER + linkJson.encodeToString(Advert.serializer(), ad)).toByteArray()
                runCatching {
                    DatagramSocket().use { out ->
                        bind(out, packet.address)
                        out.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                }
            }
        } finally {
            socket.close()
        }
    }

    data class Found(val address: String, val advert: Advert, val seenAt: Long)

    /**
     * Joiner side: keeps asking every network this phone is on and emits the hosts that answered
     * within the last few seconds. [extraTargets] lets tests ask localhost directly.
     */
    fun scan(app: String, discoveryPort: Int, extraTargets: List<InetAddress> = emptyList()): Flow<List<Found>> = channelFlow {
        val socket = DatagramSocket().apply {
            broadcast = true
            soTimeout = 400
        }
        val found = LinkedHashMap<String, Found>()
        launch(Dispatchers.IO) {
            val ask = (ASK + app).toByteArray()
            while (isActive) {
                for (target in broadcastAddresses() + extraTargets) {
                    runCatching { socket.send(DatagramPacket(ask, ask.size, target, discoveryPort)) }
                }
                delay(1_500)
            }
        }
        val buf = ByteArray(1024)
        try {
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                val got = try {
                    socket.receive(packet)
                    true
                } catch (_: SocketTimeoutException) {
                    false
                }
                val now = System.currentTimeMillis()
                if (got) {
                    val text = String(packet.data, 0, packet.length)
                    val ad = if (text.startsWith(ANSWER)) runCatching { linkJson.decodeFromString(Advert.serializer(), text.removePrefix(ANSWER)) }.getOrNull() else null
                    val address = packet.address.hostAddress
                    if (ad != null && ad.app == app && address != null) found[address] = Found(address, ad, now)
                }
                found.values.removeAll { now - it.seenAt > 5_000 }
                send(found.values.toList())
            }
        } finally {
            socket.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun broadcastAddresses(): List<InetAddress> {
        val perInterface = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
        }.getOrDefault(emptyList())
        return (perInterface + InetAddress.getByName("255.255.255.255")).distinct()
    }

    /** This phone's IPv4 addresses on Wi-Fi, hotspot and Wi-Fi Direct (not mobile data), for typing in by hand. */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("dummy") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
}
