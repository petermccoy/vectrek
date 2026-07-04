package com.vectrek.game.ui

import com.vectrek.game.engine.AIController
import com.vectrek.game.engine.GameWorld
import com.vectrek.game.engine.Loadout
import com.vectrek.game.engine.Ship
import com.vectrek.game.engine.ShipInput
import com.vectrek.game.net.GameClient
import com.vectrek.game.net.GameServer
import com.vectrek.game.net.Protocol

/**
 * Wires one play session together: the world, the local pilot, and (depending
 * on mode) the practice drones, the host server or the client link.
 */
class GameSession(
    val mode: Mode,
    private val playerName: String,
    private val playerLoadout: Loadout,
    private val hostName: String? = null,
    private val hostPort: Int = Protocol.DEFAULT_PORT,
) {
    enum class Mode { SINGLE, HOST, CLIENT }

    private var localWorld: GameWorld? = null
    var server: GameServer? = null
        private set
    var client: GameClient? = null
        private set
    private var playerId = -1

    var gameOver = false
        private set
    val finished: Boolean
        get() = gameOver || client?.let { it.connectionLost || it.error != null } == true

    private var droneTimer = 4f
    private var droneCounter = 0
    private var clientTick = 0

    val world: GameWorld?
        get() = if (mode == Mode.CLIENT) client?.world else localWorld

    /** Caller must hold the world lock (the game loop does). */
    fun playerShip(): Ship? {
        val id = if (mode == Mode.CLIENT) client?.myId ?: -1 else playerId
        return world?.ships?.find { it.id == id }
    }

    fun start() {
        when (mode) {
            Mode.CLIENT -> {
                client = GameClient(hostName!!, hostPort, playerName, playerLoadout)
                    .also { it.start() }
            }
            else -> {
                val w = GameWorld(System.currentTimeMillis())
                w.generate()
                playerId = w.addShip(playerName, playerLoadout).id
                localWorld = w
                if (mode == Mode.HOST) {
                    server = GameServer(w).also { it.start() }
                } else {
                    repeat(2) { spawnDrone(w) }
                }
            }
        }
    }

    /** One fixed simulation step, driven by the game loop thread. */
    fun update(dt: Float, input: ShipInput) {
        if (mode == Mode.CLIENT) {
            val c = client ?: return
            if (c.dead) gameOver = true
            val w = c.world ?: return
            synchronized(w) { w.clientAdvance(dt) }
            if (++clientTick % 2 == 0) c.sendInput(input)  // 30 Hz
            return
        }

        val w = localWorld ?: return
        synchronized(w) {
            val me = w.ships.find { it.id == playerId }
            if (me == null) {
                gameOver = true
            } else {
                me.input.copyFrom(input)
            }
            w.step(dt)
            val s = server
            if (s != null) {
                s.afterStep()
            } else {
                w.boomLog.clear()
                w.deathLog.clear()
            }
            if (mode == Mode.SINGLE) updateDrones(w, dt)
        }
    }

    private fun updateDrones(w: GameWorld, dt: Float) {
        val drones = w.ships.count { it.controller != null && it.alive }
        if (drones < 3) {
            droneTimer -= dt
            if (droneTimer <= 0f) {
                spawnDrone(w)
                droneTimer = 6f
            }
        }
    }

    private fun spawnDrone(w: GameWorld) {
        droneCounter++
        val me = w.ships.find { it.id == playerId }
        val ship = w.addShip("DRONE-$droneCounter", Loadout.DRONE, w.findSpawnPoint(me?.pos))
        ship.controller = AIController(droneCounter * 7919L)
    }

    fun stop() {
        server?.stop()
        client?.stop()
    }
}
