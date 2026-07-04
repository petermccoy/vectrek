package com.vectrek.game.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import com.vectrek.game.net.NsdHelper
import com.vectrek.game.net.Protocol

/**
 * Scans the local Wi-Fi network for hosted battles (mDNS) and lists them.
 * A manual IP entry is provided as a fallback for networks where multicast
 * discovery is filtered.
 */
class JoinActivity : Activity() {

    private var nsd: NsdHelper? = null
    private lateinit var gamesList: LinearLayout
    private lateinit var status: TextView
    private val known = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.goFullscreen(this)

        val col = Ui.column(this)
        col.addView(Ui.title(this, "JOIN BATTLE", 24f), Ui.match())
        col.addView(Ui.space(this, 10f))
        status = Ui.label(this, "scanning local network for battles…", 11f)
        col.addView(status, Ui.match())
        col.addView(Ui.space(this, 10f))

        gamesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(gamesList, Ui.match())

        col.addView(Ui.space(this, 24f))
        col.addView(Ui.label(this, "MANUAL LINK (host's IP is shown on their screen)", 10f), Ui.match())
        col.addView(Ui.space(this, 6f))
        val ipField = Ui.field(this, "192.168.x.x", "")
        col.addView(ipField, Ui.match())
        col.addView(Ui.space(this, 8f))
        col.addView(Ui.button(this, "CONNECT") {
            val host = ipField.text.toString().trim()
            if (host.isNotEmpty()) join(host, Protocol.DEFAULT_PORT)
        }, Ui.match())
        col.addView(Ui.space(this, 8f))
        col.addView(Ui.button(this, "BACK", Ui.DIM) { finish() }, Ui.match())

        setContentView(Ui.scroller(this, col))
    }

    override fun onStart() {
        super.onStart()
        known.clear()
        gamesList.removeAllViews()
        nsd = NsdHelper(this).also { helper ->
            helper.startDiscovery { game ->
                val key = "${game.host.hostAddress}:${game.port}"
                if (!known.add(key)) return@startDiscovery
                status.text = "battles found — tap to join"
                gamesList.addView(
                    Ui.button(this, "${game.name}  ($key)") {
                        join(game.host.hostAddress ?: return@button, game.port)
                    },
                    Ui.match(),
                )
                gamesList.addView(Ui.space(this, 8f))
            }
        }
    }

    override fun onStop() {
        super.onStop()
        nsd?.stopDiscovery()
        nsd = null
    }

    private fun join(host: String, port: Int) {
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_MODE, GameSession.Mode.CLIENT.name)
                .putExtra(GameActivity.EXTRA_HOST, host)
                .putExtra(GameActivity.EXTRA_PORT, port)
        )
    }
}
