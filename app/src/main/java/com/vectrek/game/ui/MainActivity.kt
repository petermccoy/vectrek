package com.vectrek.game.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.vectrek.game.engine.Loadout
import com.vectrek.game.engine.LoadoutStore

class MainActivity : Activity() {

    private lateinit var nameField: EditText
    private lateinit var fitSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.goFullscreen(this)

        val col = Ui.column(this).apply { gravity = Gravity.CENTER_HORIZONTAL }

        col.addView(Ui.space(this, 28f))
        col.addView(Ui.title(this, "VECTREK", 40f))
        col.addView(Ui.label(this, "vector combat in deep space", 11f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }, Ui.match())
        col.addView(Ui.space(this, 26f))

        col.addView(Ui.label(this, "CALL SIGN"), Ui.match())
        col.addView(Ui.space(this, 4f))
        nameField = Ui.field(this, "PILOT", LoadoutStore.loadPlayerName(this))
        col.addView(nameField, Ui.match())
        col.addView(Ui.space(this, 20f))

        col.addView(Ui.button(this, "PRACTICE ARENA") { launchGame(GameSession.Mode.SINGLE) }, Ui.match())
        col.addView(Ui.space(this, 10f))
        col.addView(Ui.button(this, "HOST BATTLE") { launchGame(GameSession.Mode.HOST) }, Ui.match())
        col.addView(Ui.space(this, 10f))
        col.addView(Ui.button(this, "JOIN BATTLE") {
            saveName()
            startActivity(Intent(this, JoinActivity::class.java))
        }, Ui.match())
        col.addView(Ui.space(this, 10f))
        col.addView(Ui.button(this, "SHIP OUTFITTING", Palette.SHIELD) {
            saveName()
            startActivity(Intent(this, LoadoutActivity::class.java))
        }, Ui.match())

        col.addView(Ui.space(this, 22f))
        fitSummary = Ui.label(this, "", 10f)
        fitSummary.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(fitSummary, Ui.match())

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.BG)
            addView(col, LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 340f), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        setContentView(Ui.scroller(this, wrap))
    }

    override fun onResume() {
        super.onResume()
        val lo = LoadoutStore.loadLoadout(this)
        val parts = mutableListOf<String>()
        for ((w, lvl) in lo.weapons) parts += "${w.short}${mk(lvl)}"
        for ((d, lvl) in lo.defenses) parts += "${d.short}${mk(lvl)}"
        if (lo.energyLevel > 0) parts += "ENRG+${lo.energyLevel}"
        if (lo.engineLevel > 0) parts += "ENG+${lo.engineLevel}"
        if (lo.radarLevel > 0) parts += "RDR+${lo.radarLevel}"
        fitSummary.text = "FIT: ${parts.joinToString(" · ")}  [${lo.cost()}/${Loadout.BUDGET} PTS]"
    }

    private fun mk(level: Int) = when (level) {
        0 -> ""
        1 -> "-II"
        else -> "-III"
    }

    private fun saveName() {
        val name = nameField.text.toString().trim().take(14).ifBlank { "PILOT" }
        LoadoutStore.savePlayerName(this, name)
    }

    private fun launchGame(mode: GameSession.Mode) {
        saveName()
        startActivity(
            Intent(this, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_MODE, mode.name)
        )
    }
}
