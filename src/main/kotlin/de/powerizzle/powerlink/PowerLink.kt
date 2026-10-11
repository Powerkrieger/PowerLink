package de.powerizzle.powerlink

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.UUID

/**
 * Identifies one game so different apps never see each other's hosts.
 * Pick fixed ports and a random UUID per app and never change them (old and new versions must agree).
 */
data class LinkConfig(
    val app: String,
    val tcpPort: Int,
    val discoveryPort: Int,
    val bluetoothUuid: UUID,
)

/** Whether a [Medium] can be used right now, and if not, what the player has to do. */
sealed interface Readiness {
    data object Ready : Readiness
    data class NeedsPermission(val permissions: List<String>) : Readiness
    data class TurnedOff(val fix: Fix, val message: String) : Readiness
    data class Unsupported(val message: String) : Readiness
}

enum class Fix { BLUETOOTH, WIFI, LOCATION }

/** A game found nearby. */
data class Game(
    val medium: Medium,
    val name: String,
    val info: String,
    val open: Boolean,
    /** Where it is, for display: an address, or "paired"/"nearby" for Bluetooth phones. */
    val where: String,
    internal val target: Any,
) {
    val key: String get() = "${medium.name}:$where:$name"
}

/** How hosting is going on one medium. */
sealed interface HostStatus {
    data class Waiting(val readiness: Readiness) : HostStatus
    data object Starting : HostStatus
    /** Guests can join; [detail] tells them how to find us (addresses, device name). */
    data class Live(val detail: String) : HostStatus
    data class Failed(val message: String) : HostStatus
}

/**
 * Entry point for local multiplayer. One host, any number of guests, over Wi-Fi/hotspot, Bluetooth
 * and Wi-Fi Direct at the same time. Apps only see [Link]s carrying lines of text.
 */
class PowerLink(context: Context, val config: LinkConfig) {
    private val context = context.applicationContext
    private val bluetooth = Bluetooth(this.context, config.app, config.bluetoothUuid)
    private val wifiDirect = WifiDirect(this.context, config.app)

    private companion object {
        const val HOSTING = "hosting a game"
    }

    fun permissions(medium: Medium): List<String> = when (medium) {
        Medium.WIFI -> emptyList()
        Medium.BLUETOOTH -> if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        Medium.WIFI_DIRECT -> if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun locationOn(): Boolean =
        context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

    fun readiness(medium: Medium): Readiness {
        when (medium) {
            Medium.WIFI -> return Readiness.Ready
            Medium.BLUETOOTH -> if (!bluetooth.supported) return Readiness.Unsupported("This phone has no Bluetooth.")
            Medium.WIFI_DIRECT -> if (!wifiDirect.supported) return Readiness.Unsupported("This phone doesn't support Wi-Fi Direct.")
        }
        val missing = permissions(medium).filterNot(::granted)
        if (missing.isNotEmpty()) return Readiness.NeedsPermission(missing)
        return when (medium) {
            Medium.BLUETOOTH -> when {
                !bluetooth.enabled -> Readiness.TurnedOff(Fix.BLUETOOTH, "Bluetooth is off.")
                Build.VERSION.SDK_INT < 31 && !locationOn() -> Readiness.TurnedOff(Fix.LOCATION, "This Android needs location turned on to find Bluetooth phones.")
                else -> Readiness.Ready
            }
            Medium.WIFI_DIRECT -> when {
                !wifiDirect.wifiOn -> Readiness.TurnedOff(Fix.WIFI, "Wi-Fi is off. It doesn't need a network, just switched on.")
                Build.VERSION.SDK_INT < 33 && !locationOn() -> Readiness.TurnedOff(Fix.LOCATION, "This Android needs location turned on for Wi-Fi Direct.")
                else -> Readiness.Ready
            }
            Medium.WIFI -> Readiness.Ready
        }
    }

    /** This phone's Bluetooth name, as guests will see it. */
    val bluetoothName: String get() = bluetooth.deviceName
    val bluetoothDiscoverable: Boolean get() = bluetooth.discoverable

    /** Many phones drop broadcast packets unless someone holds this lock; discovery needs them. */
    private fun multicastLock(): WifiManager.MulticastLock? = runCatching {
        context.getSystemService(WifiManager::class.java)?.createMulticastLock("powerlink-${config.app}")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    /**
     * Starts hosting on [media]. Media that aren't ready yet (permission, switched off) start by
     * themselves as soon as they are. Close the returned host when the lobby is done or left.
     */
    fun host(name: String, media: Set<Medium> = Medium.entries.toSet()): LinkHost = LinkHost(name, media)

    inner class LinkHost internal constructor(name: String, private val media: Set<Medium>) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val incoming = Channel<Link>(Channel.UNLIMITED)
        private val jobs = HashMap<Medium, Job>()
        @Volatile private var advert = Advert(config.app, name, port = config.tcpPort)

        /** New guests, in the order they connected. Collect it once. */
        val guests: Flow<Link> = incoming.receiveAsFlow()

        val status = MutableStateFlow<Map<Medium, HostStatus>>(media.associateWith { HostStatus.Starting })

        private fun set(m: Medium, s: HostStatus) = status.update { it + (m to s) }

        /** What finders see next to the host's name, and whether they can still join. */
        fun update(info: String, open: Boolean = true) {
            advert = advert.copy(info = info, open = open)
        }

        init {
            // The TCP server serves Wi-Fi, hotspot and Wi-Fi Direct guests alike.
            if (Medium.WIFI in media || Medium.WIFI_DIRECT in media) {
                scope.launch {
                    try {
                        Lan.serve(config.tcpPort, incoming) {
                            if (Medium.WIFI in media) set(Medium.WIFI, HostStatus.Live(lanDetail()))
                        }
                    } catch (e: Exception) {
                        if (Medium.WIFI in media) set(Medium.WIFI, HostStatus.Failed(e.message ?: "Wi-Fi hosting failed"))
                    }
                }
            }
            if (Medium.WIFI in media) {
                scope.launch {
                    val lock = multicastLock()
                    try {
                        Lan.advertise(config.discoveryPort, bind = ::bindToLocalNetwork) { advert }
                    } catch (_: Exception) {
                        // Finding by name is broken, but typing the address still works.
                    } finally {
                        lock?.release()
                    }
                }
                scope.launch {
                    // Addresses change when a hotspot or Wi-Fi Direct group comes up.
                    while (isActive) {
                        delay(2_000)
                        if (status.value[Medium.WIFI] is HostStatus.Live) set(Medium.WIFI, HostStatus.Live(lanDetail()))
                    }
                }
            }
            scope.launch {
                while (isActive) {
                    for (m in media) if (m != Medium.WIFI) startWhenReady(m)
                    delay(1_500)
                }
            }
        }

        private fun lanDetail(): String = Lan.localAddresses().joinToString().ifEmpty { "Not on any Wi-Fi. Turn on Wi-Fi or a hotspot." }

        private fun startWhenReady(m: Medium) {
            if (jobs[m]?.isActive == true) return
            val r = readiness(m)
            if (r != Readiness.Ready) {
                set(m, HostStatus.Waiting(r))
                return
            }
            set(m, HostStatus.Starting)
            jobs[m] = scope.launch {
                try {
                    when (m) {
                        Medium.BLUETOOTH -> bluetooth.serve(incoming) {
                            set(m, HostStatus.Live("Visible as “${bluetooth.deviceName}”"))
                        }
                        Medium.WIFI_DIRECT -> wifiDirect.host({ advert }) { group ->
                            set(m, HostStatus.Live("Group $group"))
                        }
                        Medium.WIFI -> {}
                    }
                } catch (e: LinkException) {
                    set(m, HostStatus.Failed(e.message!!))
                    delay(5_000) // then the loop above retries
                } catch (e: Exception) {
                    if (isActive) {
                        set(m, HostStatus.Failed(e.message ?: e.javaClass.simpleName))
                        delay(5_000)
                    }
                }
            }
        }

        fun close() {
            scope.cancel()
            incoming.close()
        }
    }

    /**
     * Games nearby on [media], refreshed continuously while collected. Media that aren't ready are
     * skipped until they are. Bluetooth can't tell which phones host a game, so it lists nearby phones.
     */
    fun scan(media: Set<Medium> = Medium.entries.toSet()): Flow<List<Game>> = channelFlow {
        val lan = MutableStateFlow<List<Game>>(emptyList())
        val direct = MutableStateFlow<Map<String, Pair<Game, Long>>>(emptyMap())
        val bt = MutableStateFlow<List<Game>>(emptyList())
        val jobs = HashMap<Medium, Job>()
        val lock = if (Medium.WIFI in media) multicastLock() else null
        if (Medium.WIFI in media) launch {
            Lan.scan(config.app, config.discoveryPort).collect { found ->
                lan.value = found.map { Game(Medium.WIFI, it.advert.name, it.advert.info, it.advert.open, it.address, it.address) }
            }
        }
        launch {
            while (isActive) {
                for (m in media - Medium.WIFI) {
                    if (jobs[m]?.isActive == true || readiness(m) != Readiness.Ready) continue
                    jobs[m] = launch {
                        runCatching {
                            when (m) {
                                Medium.WIFI_DIRECT -> wifiDirect.scan().collect { f ->
                                    val g = Game(Medium.WIFI_DIRECT, f.advert.name, f.advert.info, f.advert.open, f.target.deviceAddress, f.target)
                                    direct.update { it + (g.where to (g to System.currentTimeMillis())) }
                                }
                                Medium.BLUETOOTH -> {
                                    val seen = LinkedHashMap<String, Game>()
                                    bluetooth.discover().collect { (d, hosting) ->
                                        val known = seen[d.address]
                                        // Keep a "yes, hosting" answer even if a later, partial answer lacks it.
                                        val isHost = hosting == true || (hosting == null && known?.info == HOSTING)
                                        val paired = d.bondState == BluetoothDevice.BOND_BONDED
                                        val info = if (isHost) HOSTING else if (paired) "paired phone" else "phone nearby"
                                        seen[d.address] = Game(Medium.BLUETOOTH, bluetooth.nameOf(d), info, true, d.address, d)
                                        bt.value = seen.values.sortedByDescending { it.info == HOSTING }
                                    }
                                }
                                Medium.WIFI -> {}
                            }
                        }
                    }
                }
                val now = System.currentTimeMillis()
                direct.update { m -> m.filterValues { now - it.second < 40_000 } }
                send(lan.value + direct.value.values.map { it.first } + bt.value)
                delay(700)
            }
        }
        awaitClose { lock?.release() }
    }

    /**
     * The network whose local subnet contains [target]. Sockets must be tied to it: Android otherwise uses the
     * default network, which is mobile data whenever the Wi-Fi or hotspot has no internet.
     */
    private fun localNetworkFor(target: InetAddress): Pair<Network, String>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val props = cm.getLinkProperties(network) ?: continue
            if (props.routes.any { !it.isDefaultRoute && it.matches(target) }) return network to props.interfaceName.orEmpty()
        }
        return null
    }

    private fun bindToLocalNetwork(socket: Socket, target: InetAddress) {
        val (network, name) = localNetworkFor(target) ?: return
        runCatching { network.bindSocket(socket) }
            .onSuccess { log("LAN: connecting to $target via $name") }
            .onFailure { log("LAN: couldn't bind to $name: ${it.message}") }
    }

    private fun bindToLocalNetwork(socket: DatagramSocket, target: InetAddress) {
        val (network, _) = localNetworkFor(target) ?: return
        runCatching { network.bindSocket(socket) }
    }

    /** Connects to a game from [scan]. Throws [LinkException] with a message for the player. */
    suspend fun join(game: Game): Link = when (game.medium) {
        Medium.WIFI -> Lan.connect(game.target as String, config.tcpPort, bind = ::bindToLocalNetwork)
        Medium.BLUETOOTH -> bluetooth.connect(game.target as BluetoothDevice)
        Medium.WIFI_DIRECT -> wifiDirect.connect(game.target as WifiDirect.Target, game.name, config.tcpPort)
    }

    /** Connects to a host by typing its address, for when discovery doesn't find it. */
    suspend fun joinAddress(address: String): Link = Lan.connect(address, config.tcpPort, bind = ::bindToLocalNetwork)
}
