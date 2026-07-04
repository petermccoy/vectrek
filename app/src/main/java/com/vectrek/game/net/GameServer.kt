package com.vectrek.game.net

import com.vectrek.game.engine.GameWorld
import com.vectrek.game.engine.Loadout
import com.vectrek.game.engine.Ship
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Authoritative multiplayer host. Runs on the phone that started the battle:
 * the host's own ship is simulated like single player, remote ships are
 * simulated from the inputs clients send, and everyone gets personalized
 * snapshots back (~20/s).
 *
 * All world mutation happens under `synchronized(world)`, shared with the
 * game loop thread.
 */
class GameServer(private val world: GameWorld) {

    private class RemoteClient(
        val addr: InetAddress,
        val port: Int,
        var shipId: Int,
        var name: String,
        var lastSeen: Long = System.currentTimeMillis(),
        var dead: Boolean = false,
    ) {
        val key get() = key(addr, port)

        companion object {
            fun key(addr: InetAddress, port: Int) = "${addr.hostAddress}:$port"
        }
    }

    private val socket: DatagramSocket = try {
        DatagramSocket(Protocol.DEFAULT_PORT)
    } catch (_: SocketException) {
        DatagramSocket()  // port busy; NSD advertises whatever we got
    }
    val port: Int get() = socket.localPort

    @Volatile var localAddress: String = "?"
        private set

    private val clients = HashMap<String, RemoteClient>()
    @Volatile private var running = true
    private var stepCounter = 0
    private val pendingBooms = mutableListOf<Triple<Float, Float, Float>>()

    fun start() {
        Thread({ receiveLoop() }, "vectrek-server").start()
        Thread({ localAddress = findLocalAddress() }, "vectrek-ip").start()
    }

    private fun receiveLoop() {
        val buf = ByteArray(Protocol.MAX_PACKET)
        while (running) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                if (!running) return
                continue
            }
            try {
                val msg = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                handle(msg, packet.address, packet.port)
            } catch (_: Exception) {
                // Malformed datagram; ignore.
            }
        }
    }

    private fun handle(msg: JSONObject, addr: InetAddress, fromPort: Int) {
        val key = RemoteClient.key(addr, fromPort)
        when (msg.optString("t")) {
            "hello" -> synchronized(world) {
                var client = clients[key]
                if (client == null) {
                    val name = msg.optString("name", "PILOT").take(14).ifBlank { "PILOT" }
                    val loadout = msg.optJSONObject("lo")
                        ?.let { Loadout.fromJson(it) } ?: Loadout.DEFAULT
                    val ship = world.addShip(name, loadout)
                    client = RemoteClient(addr, fromPort, ship.id, name)
                    clients[key] = client
                }
                client.lastSeen = System.currentTimeMillis()
                send(Protocol.welcome(client.shipId, world.seed, world.width, world.height), client)
            }

            "in" -> synchronized(world) {
                val client = clients[key] ?: return
                client.lastSeen = System.currentTimeMillis()
                val ship = world.ships.find { it.id == client.shipId } ?: return
                Protocol.readInput(msg, ship.input)
            }

            "bye" -> synchronized(world) {
                val client = clients.remove(key) ?: return
                world.ships.removeAll { it.id == client.shipId }
            }
        }
    }

    /**
     * Called from the game loop after every fixed simulation step (under the
     * world lock). Drains event logs and broadcasts snapshots every few steps.
     */
    fun afterStep() {
        for (e in world.boomLog) pendingBooms += Triple(e.pos.x, e.pos.y, e.size)
        world.boomLog.clear()
        for ((deadId, _) in world.deathLog) {
            for (c in clients.values) if (c.shipId == deadId) c.dead = true
        }
        world.deathLog.clear()

        stepCounter++
        if (stepCounter % 3 != 0) return  // 60 Hz sim -> 20 Hz snapshots

        val now = System.currentTimeMillis()
        val timedOut = clients.values.filter { now - it.lastSeen > 8000 }
        for (c in timedOut) {
            clients.remove(c.key)
            world.ships.removeAll { it.id == c.shipId }
        }

        val booms = pendingBooms.toList()
        pendingBooms.clear()
        for (c in clients.values) {
            send(Protocol.snapshot(world, c.shipId, !c.dead, booms), c)
        }
    }

    private fun send(msg: JSONObject, client: RemoteClient) {
        val bytes = msg.toString().toByteArray(Charsets.UTF_8)
        try {
            socket.send(DatagramPacket(bytes, bytes.size, client.addr, client.port))
        } catch (_: Exception) {
        }
    }

    fun clientCount(): Int = synchronized(world) { clients.size }

    fun stop() {
        running = false
        socket.close()
    }

    private fun findLocalAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }
}
