package de.powerizzle.powerlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Classic Bluetooth (RFCOMM) with the platform API only. Insecure sockets skip the pairing prompt.
 * The host's phone must be discoverable for unpaired guests to find it.
 */
@SuppressLint("MissingPermission") // PowerLink checks the permissions before calling in here.
internal class Bluetooth(private val context: Context, private val serviceName: String, private val uuid: UUID) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    val supported get() = adapter != null
    val enabled get() = adapter?.isEnabled == true
    val deviceName: String get() = runCatching { adapter?.name }.getOrNull() ?: android.os.Build.MODEL
    val discoverable get() = runCatching { adapter?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE }.getOrDefault(false)

    /** Accepts guests until cancelled. */
    suspend fun serve(guests: SendChannel<Link>, onReady: () -> Unit) {
        val server = try {
            adapter!!.listenUsingInsecureRfcommWithServiceRecord(serviceName, uuid)
        } catch (e: IOException) {
            throw LinkException("Bluetooth didn't start. Turn it off and on again.", e)
        }
        onReady()
        try {
            while (true) {
                val socket = cancellableBlocking({ server.close() }) { server.accept() }
                val name = runCatching { socket.remoteDevice.name }.getOrNull() ?: socket.remoteDevice.address
                guests.send(Link(socket.inputStream, socket.outputStream, socket, Medium.BLUETOOTH, name))
            }
        } finally {
            server.close()
        }
    }

    suspend fun connect(device: BluetoothDevice): Link {
        adapter?.cancelDiscovery() // discovery slows connecting down a lot
        val socket = device.createInsecureRfcommSocketToServiceRecord(uuid)
        try {
            cancellableBlocking({ socket.close() }) { socket.connect() }
        } catch (e: IOException) {
            socket.close()
            throw LinkException("${nameOf(device)} isn't hosting a game, or is out of range.", e)
        }
        return Link(socket.inputStream, socket.outputStream, socket, Medium.BLUETOOTH, nameOf(device))
    }

    fun nameOf(device: BluetoothDevice): String = runCatching { device.name }.getOrNull() ?: device.address

    fun paired(): List<BluetoothDevice> = runCatching { adapter?.bondedDevices.orEmpty().toList() }.getOrDefault(emptyList())

    /** A nearby device, and whether it answered that it hosts our game ([hosting] is null until it answered). */
    data class Seen(val device: BluetoothDevice, val hosting: Boolean?)

    /**
     * Phones nearby (paired ones first), emitted as they show up or as we learn whether they host our game.
     * Headphones, watches and nameless gadgets are left out.
     *
     * The radio can't scan and query services at the same time (a query silently kills a running scan),
     * so this takes turns: scan for a while, then ask each phone found whether it offers our game, repeat.
     */
    fun discover(): Flow<Seen> = callbackFlow {
        val phones = LinkedHashMap<String, BluetoothDevice>()
        val scanDone = Channel<Unit>(Channel.CONFLATED)
        val answers = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)
        fun consider(device: BluetoothDevice) {
            if (!looksLikePhone(device)) return
            if (phones.put(device.address, device) == null) log("BT phone ${nameOf(device)} ${device.address}")
            trySend(Seen(device, offers(device.uuids)))
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> device?.let(::consider)
                    BluetoothDevice.ACTION_UUID -> if (device != null) {
                        @Suppress("DEPRECATION")
                        val uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)?.filterIsInstance<ParcelUuid>()?.toTypedArray()
                        // No list at all means the query was cut short, which says nothing either way.
                        val hosting = offers(uuids)
                        log("BT ${nameOf(device)} hosts: $hosting (${uuids?.joinToString()})")
                        answers.trySend(device.address to (hosting == true))
                        if (looksLikePhone(device) && hosting != null) trySend(Seen(device, hosting))
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanDone.trySend(Unit)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_UUID)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val loop = launch {
            paired().forEach(::consider)
            while (true) {
                while (scanDone.tryReceive().isSuccess) { /* drop stale */ }
                val started = runCatching { adapter?.startDiscovery() == true }.getOrDefault(false)
                log("BT scanning: $started")
                withTimeoutOrNull(SCAN_MS) { scanDone.receive() }
                runCatching { adapter?.cancelDiscovery() }
                for (device in phones.values.toList()) {
                    while (answers.tryReceive().isSuccess) { /* drop stale */ }
                    if (runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)) {
                        withTimeoutOrNull(QUERY_MS) {
                            while (answers.receive().first != device.address) { /* someone else's answer */ }
                        }
                    }
                }
                if (!started) delay(2_000)
            }
        }
        awaitClose {
            loop.cancel()
            runCatching { adapter?.cancelDiscovery() }
            context.unregisterReceiver(receiver)
        }
    }

    /** True/false if [uuids] is known, null if the device hasn't told us yet. */
    private fun offers(uuids: Array<ParcelUuid>?): Boolean? {
        if (uuids == null) return null
        // Some Android versions report SDP UUIDs byte-reversed; accept both.
        return uuids.any { it.uuid == uuid || it.uuid == reversed }
    }

    private val reversed: UUID = run {
        val b = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array().reversedArray()
        val bb = ByteBuffer.wrap(b)
        UUID(bb.long, bb.long)
    }

    private fun looksLikePhone(d: BluetoothDevice): Boolean {
        if (runCatching { d.name }.getOrNull().isNullOrBlank()) return false
        val major = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()
        return major == BluetoothClass.Device.Major.PHONE || major == BluetoothClass.Device.Major.COMPUTER
    }

    private companion object {
        const val SCAN_MS = 12_000L
        const val QUERY_MS = 15_000L
    }
}
