package de.powerizzle.powerlink

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * Wi-Fi Direct with the platform API (android.net.wifi.p2p), no Google services.
 *
 * The host creates its own group (a small Wi-Fi network run by the phone) and advertises the game with
 * a DNS-SD record that includes the group's name and password. Guests join that group with the password,
 * so the host doesn't get a "accept connection?" prompt for every player, then talk plain TCP to the host
 * at the group owner address. The phones can stay on their normal Wi-Fi at the same time.
 */
@SuppressLint("MissingPermission") // PowerLink checks the permissions before calling in here.
internal class WifiDirect(private val context: Context, private val app: String) {
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    val supported get() = manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    val wifiOn get() = wifi?.isWifiEnabled == true

    data class Target(val deviceAddress: String, val network: String?, val passphrase: String?)

    private fun channel(): WifiP2pManager.Channel =
        manager!!.initialize(context, Looper.getMainLooper(), null) ?: throw LinkException("Wi-Fi Direct isn't available right now.")

    private suspend fun WifiP2pManager.Channel.call(what: String, action: (WifiP2pManager.ActionListener) -> Unit) {
        val reason = suspendCancellableCoroutine { cont ->
            action(object : WifiP2pManager.ActionListener {
                override fun onSuccess() = cont.resume(null)
                override fun onFailure(reason: Int) = cont.resume(reason)
            })
        }
        log("P2P $what: ${if (reason == null) "ok" else failure(reason)}")
        if (reason != null) throw LinkException("Wi-Fi Direct: $what failed (${failure(reason)}).")
    }

    private fun failure(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "not supported"
        WifiP2pManager.BUSY -> "busy, try again"
        else -> "error $reason"
    }

    private suspend fun group(ch: WifiP2pManager.Channel): WifiP2pGroup? = suspendCancellableCoroutine { cont ->
        manager!!.requestGroupInfo(ch) { cont.resume(it) }
    }

    private suspend fun connectionInfo(ch: WifiP2pManager.Channel): WifiP2pInfo? = suspendCancellableCoroutine { cont ->
        manager!!.requestConnectionInfo(ch) { cont.resume(it) }
    }

    /**
     * Host side: creates the group and advertises [advert] until cancelled. Guests then reach the
     * TCP server that [Lan.serve] runs on all interfaces. Calls [onReady] with the group's name.
     */
    suspend fun host(advert: () -> Advert, onReady: (String) -> Unit) {
        val m = manager!!
        val ch = channel()
        var service: WifiP2pDnsSdServiceInfo? = null
        try {
            // A group left over from earlier (or from another app) would keep us from creating ours.
            runCatching { ch.call("removing the old group") { m.removeGroup(ch, it) } }
            createGroup(m, ch)
            var g: WifiP2pGroup? = null
            for (i in 0 until 40) {
                g = group(ch)
                if (g?.networkName != null && g.passphrase != null) break
                delay(250)
            }
            if (g?.passphrase == null) throw LinkException("Wi-Fi Direct: the group didn't come up.")
            onReady(g.networkName)
            val network = g.networkName
            val passphrase = g.passphrase
            var published: Advert? = null
            while (true) {
                val ad = advert()
                if (ad != published) {
                    service?.let { old -> runCatching { ch.call("updating") { m.removeLocalService(ch, old, it) } } }
                    val instance = Instance.encode(ad, network, passphrase)
                    log("P2P advertising as $instance")
                    val info = WifiP2pDnsSdServiceInfo.newInstance(instance, SERVICE_TYPE, emptyMap())
                    ch.call("advertising") { m.addLocalService(ch, info, it) }
                    service = info
                    published = ad
                }
                // No discoverPeers() here: searching takes the radio off the group's channel, and guests that are
                // just joining then miss their DHCP answers and get dropped. A group on channel 1 is found anyway.
                delay(2_000)
            }
        } finally {
            service?.let { m.removeLocalService(ch, it, null) }
            m.stopPeerDiscovery(ch, null)
            m.removeGroup(ch, null)
            if (Build.VERSION.SDK_INT >= 27) ch.close()
        }
    }

    /**
     * Guests search on the 2.4 GHz "social" channels, so a group on 5 GHz (where Android likes to put it
     * when the phone's own Wi-Fi is on 5 GHz) is never found. Ask for 2.4 GHz where Android allows it.
     */
    private suspend fun createGroup(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (Build.VERSION.SDK_INT >= 29) {
            val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val code = (1..2).map { chars.random() }.joinToString("")
            val config = WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-$code")
                .setPassphrase((1..8).map { chars.random() }.joinToString(""))
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .build()
            if (runCatching { ch.call("creating a 2.4 GHz group") { m.createGroup(ch, config, it) } }.isSuccess) return
        }
        ch.call("creating the group") { m.createGroup(ch, it) }
    }

    data class Found(val target: Target, val advert: Advert)

    /** Joiner side: games advertised nearby, emitted as they are seen. */
    fun scan(): Flow<Found> = callbackFlow {
        val m = manager!!
        val ch = channel()
        // Only the service's instance name reliably reaches guests (TXT records often don't), so it carries everything.
        m.setDnsSdResponseListeners(ch, { instance, _, device ->
            val found = Instance.decode(instance, app, device.deviceAddress, device.deviceName)
            log("P2P service from ${device.deviceName}: ${if (found == null) "not ours" else found.advert.name}")
            if (found != null) trySend(found)
        }, { _, _, _ -> })
        val job = launch {
            while (true) {
                // Service discovery stops by itself after a while and Android forgets requests; re-arm regularly.
                runCatching { ch.call("clearing") { m.clearServiceRequests(ch, it) } }
                runCatching { ch.call("searching") { m.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(), it) } }
                runCatching { ch.call("searching") { m.discoverServices(ch, it) } }
                delay(12_000)
            }
        }
        awaitClose {
            job.cancel()
            m.clearServiceRequests(ch, null)
            m.stopPeerDiscovery(ch, null)
            if (Build.VERSION.SDK_INT >= 27) ch.close()
        }
    }

    /**
     * Joins the host's group and opens a TCP link to it. Leaving the group happens when the link closes.
     * Joining fails now and then (the host's radio may be busy on its other Wi-Fi channel), so it retries.
     */
    suspend fun connect(target: Target, name: String, port: Int): Link {
        var last: LinkException? = null
        repeat(3) { attempt ->
            try {
                return connectOnce(target, name, port)
            } catch (e: LinkException) {
                log("P2P join attempt ${attempt + 1} failed: ${e.message}")
                last = e
                delay(1_500)
            }
        }
        throw last!!
    }

    private suspend fun connectOnce(target: Target, name: String, port: Int): Link {
        val m = manager!!
        val ch = channel()
        val config = if (Build.VERSION.SDK_INT >= 29 && target.network != null && target.passphrase != null) {
            WifiP2pConfig.Builder()
                .setNetworkName(target.network)
                .setPassphrase(target.passphrase)
                .enablePersistentMode(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            WifiP2pConfig().apply {
                deviceAddress = target.deviceAddress
                groupOwnerIntent = 0
                wps.setup = WpsInfo.PBC
            }
        }
        fun leave() {
            m.removeGroup(ch, null)
            if (Build.VERSION.SDK_INT >= 27) ch.close()
        }
        try {
            m.stopPeerDiscovery(ch, null)
            ch.call("connecting") { m.connect(ch, config, it) }
            val owner = withTimeoutOrNull(15_000) {
                var address: InetAddress? = null
                while (address == null) {
                    val info = connectionInfo(ch)
                    if (info?.groupFormed == true && !info.isGroupOwner) address = info.groupOwnerAddress
                    if (address == null) delay(400)
                }
                address
            } ?: throw LinkException("Couldn't join $name over Wi-Fi Direct. Stand closer and try again.")
            // The group's addresses take a moment to settle; retry the TCP connect a few times.
            var last: Exception? = null
            repeat(5) {
                try {
                    return Lan.connect(owner.hostAddress!!, port, Medium.WIFI_DIRECT, onClose = ::leave)
                } catch (e: LinkException) {
                    last = e
                    delay(800)
                }
            }
            throw LinkException("Joined $name's Wi-Fi Direct group, but the game didn't answer.", last)
        } catch (e: Throwable) {
            leave()
            throw e
        }
    }

    /**
     * The advert packed into a DNS-SD instance name (max 63 bytes):
     * `app|network|passphrase|open|name|info`, name and info shortened to fit.
     */
    internal object Instance {
        private const val MAX = 63

        fun encode(ad: Advert, network: String, passphrase: String): String {
            val head = listOf(ad.app, network, passphrase, if (ad.open) "1" else "0").joinToString("|") + "|"
            var name = ad.name.replace("|", "/")
            var info = ad.info.replace("|", "/")
            fun all() = "$head$name|$info"
            while (all().toByteArray().size > MAX && info.isNotEmpty()) info = info.dropLast(1)
            while (all().toByteArray().size > MAX && name.length > 1) name = name.dropLast(1)
            return all()
        }

        fun decode(instance: String, app: String, deviceAddress: String, deviceName: String): Found? {
            val p = instance.split("|")
            if (p.size < 6 || p[0] != app) return null
            return Found(
                Target(deviceAddress, p[1].takeIf { it.startsWith("DIRECT-") }, p[2].takeIf { it.length >= 8 }),
                Advert(app, p[4].ifEmpty { deviceName }, p[5], p[3] != "0"),
            )
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_powerlink._tcp"
    }
}
