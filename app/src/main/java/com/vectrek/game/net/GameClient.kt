package com.vectrek.game.net

import com.vectrek.game.engine.GameWorld
import com.vectrek.game.engine.Loadout
import com.vectrek.game.engine.ShipInput
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Multiplayer joiner. Sends the pilot's inputs to the host and maintains a
 * mirror [world] rebuilt from the host's seed (scenery) plus snapshots
 * (everything that moves).
 */
class GameClient(
    private val hostName: String,
    private val hostPort: Int,
    private val playerName: String,
    private val loadout: Loadout,
) {
    @Volatile var world: GameWorld? = null
        private set
    @Volatile var myId = -1
        private set
    @Volatile var dead = false
        private set
    @Volatile var connectionLost = false
        private set
    @Volatile var error: String? = null
        private set

    private val socket = DatagramSocket()
    @Volatile private var running = true
    @Volatile private var hostAddr: InetAddress? = null
    @Volatile private var lastSnapshotAt = 0L

    fun start() {
        Thread({ receiveLoop() }, "vectrek-client-rx").start()
        Thread({ helloLoop() }, "vectrek-client-hello").start()
    }

    private fun helloLoop() {
        try {
            hostAddr = InetAddress.getByName(hostName)
        } catch (e: Exception) {
            error = "Can't resolve host: $hostName"
            return
        }
        val msg = Protocol.hello(playerName, loadout)
        while (running && world == null) {
            send(msg)
            try {
                Thread.sleep(600)
            } catch (_: InterruptedException) {
                return
            }
        }
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
                handle(JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8)))
            } catch (_: Exception) {
            }
        }
    }

    private fun handle(msg: JSONObject) {
        when (msg.optString("t")) {
            "wel" -> {
                if (world == null) {
                    myId = msg.getInt("id")
                    val w = GameWorld(
                        msg.getLong("seed"),
                        msg.getDouble("w").toFloat(),
                        msg.getDouble("h").toFloat(),
                    )
                    w.generate()
                    lastSnapshotAt = System.currentTimeMillis()
                    world = w
                }
            }

            "snap" -> {
                val w = world ?: return
                lastSnapshotAt = System.currentTimeMillis()
                if (!msg.optBoolean("you", true)) dead = true
                synchronized(w) {
                    Protocol.applySnapshot(msg, w, myId)
                }
            }
        }
    }

    /** Called from the game loop (~30/s). Also watches connection health. */
    fun sendInput(input: ShipInput) {
        if (world != null && System.currentTimeMillis() - lastSnapshotAt > 5000) {
            connectionLost = true
        }
        send(Protocol.input(input))
    }

    private fun send(msg: JSONObject) {
        val addr = hostAddr ?: return
        val bytes = msg.toString().toByteArray(Charsets.UTF_8)
        try {
            socket.send(DatagramPacket(bytes, bytes.size, addr, hostPort))
        } catch (_: Exception) {
        }
    }

    fun stop() {
        send(Protocol.bye())
        running = false
        socket.close()
    }
}
