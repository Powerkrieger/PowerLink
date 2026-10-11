# PowerLink

Local multiplayer for the Powerizzle games: one host, any number of guests, over

- **Wi-Fi / hotspot**: plain TCP on the same network, found by UDP broadcast (or by typing the address),
- **Bluetooth**: classic RFCOMM, no pairing prompt,
- **Wi-Fi Direct**: the host opens its own group, guests join it with the advertised password.

The host is reachable on all of them at once and guests see one list. Apps only ever handle a `Link`:
a connection carrying lines of text, with a built-in heartbeat that drops silent peers.

**No Google services.** Everything uses platform APIs (`java.net`, `android.bluetooth`, `android.net.wifi.p2p`).
Keep it that way: no Play Services, Nearby or Firebase dependencies.

## Adding it to an app

PowerLink is a git submodule plus a Gradle module, so fixes happen here once.

```sh
git submodule add https://github.com/Powerkrieger/PowerLink.git powerlink
```

`settings.gradle.kts`:

```kotlin
include(":powerlink")
```

The root `build.gradle.kts` must provide the plugin versions (`com.android.library`,
`org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`, all `apply false`).
Then in the app: `implementation(project(":powerlink"))`. CI checkouts need `submodules: true`.

Update an app to the newest PowerLink: `git submodule update --remote powerlink`, then commit.

## Using it

```kotlin
val link = PowerLink(context, LinkConfig(
    app = "mygame",                 // games with different ids never see each other
    tcpPort = 47410, discoveryPort = 47411,
    bluetoothUuid = UUID.fromString("…"),  // fixed per app, never change
))

// Host
val host = link.host("Alice")
host.update(info = "2/6 players", open = true)   // what finders see
host.guests.collect { guest: Link -> … }          // each guest that connects
HostPanel(link, host)                             // Compose: status per medium, fixes permissions/switches
host.close()

// Guest
JoinPanel(link, onJoined = { l: Link, game -> … })   // Compose: finds games, joins, address field
// or by hand: link.scan().collect { games -> … };  link.join(game);  link.joinAddress("192.168.1.5")

// Talking
l.send("hello")         // suspend, false when gone
val line = l.receive()  // suspend, null when closed
l.close()
```

## Things learned the hard way

- **Guest Wi-Fis isolate clients.** Phones on the same guest/public network often can't reach each other at all.
  `Lan.connect` says so in its error; Bluetooth, Wi-Fi Direct or a hotspot work around it.
- **Offline hotspots aren't Android's default network.** With mobile data on, unbound sockets to a hotspot
  (or any Wi-Fi without internet) go out over mobile data and time out. `Lan.connect` and the discovery replies
  therefore bind their sockets to the network whose subnet contains the other phone.
- **Broadcast discovery needs a `MulticastLock`**, or many phones silently drop the packets.
- **Bluetooth can't scan and query services at the same time:** an SDP query kills a running scan. Discovery
  takes turns (scan ~12 s, then ask each phone found whether it offers our UUID). Only named phones/computers are listed.
- **Wi-Fi Direct groups must be on 2.4 GHz** to be found (guests search on the social channels); Android puts them
  on 5 GHz when the phone's own Wi-Fi is there. The details travel in the DNS-SD *instance name*, because TXT
  records frequently don't arrive. The host must not run `discoverPeers()`, or joining guests miss their DHCP answer.
  Joining can still fail when the host's radio is busy on its other channel, so it retries.

Debug log: `adb logcat -s PowerLink`.

## Tests

`LanTest` runs real sockets on localhost (discovery, connecting, heartbeats, error messages). Bluetooth and
Wi-Fi Direct need two phones.
