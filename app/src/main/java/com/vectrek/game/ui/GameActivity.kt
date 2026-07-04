package com.vectrek.game.ui

import android.app.Activity
import android.os.Bundle
import com.vectrek.game.engine.LoadoutStore
import com.vectrek.game.net.NsdHelper
import com.vectrek.game.net.Protocol

class GameActivity : Activity() {

    private var session: GameSession? = null
    private var nsd: NsdHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.goFullscreen(this)

        val mode = GameSession.Mode.valueOf(
            intent.getStringExtra(EXTRA_MODE) ?: GameSession.Mode.SINGLE.name
        )
        val name = LoadoutStore.loadPlayerName(this)
        val loadout = LoadoutStore.loadLoadout(this)

        val s = GameSession(
            mode = mode,
            playerName = name,
            playerLoadout = loadout,
            hostName = intent.getStringExtra(EXTRA_HOST),
            hostPort = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT),
        )
        s.start()
        session = s

        if (mode == GameSession.Mode.HOST) {
            s.server?.let { server ->
                nsd = NsdHelper(this).also { it.register(name, server.port) }
            }
        }

        setContentView(GameView(this, s) { finish() })
    }

    override fun onDestroy() {
        super.onDestroy()
        nsd?.unregister()
        session?.stop()
        session = null
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
    }
}
