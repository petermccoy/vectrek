package com.vectrek.game.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.vectrek.game.engine.DefenseType
import com.vectrek.game.engine.Loadout
import com.vectrek.game.engine.LoadoutStore
import com.vectrek.game.engine.WeaponType
import com.vectrek.game.engine.cloakDrain
import com.vectrek.game.engine.shieldAbsorb
import com.vectrek.game.engine.shieldDrain
import com.vectrek.game.engine.weaponSpec

/**
 * The outfitting bay. A fixed budget of points buys weapons, defenses and
 * core-system upgrades; every choice is a trade-off. Over-budget fits can't
 * be saved.
 */
class LoadoutActivity : Activity() {

    private val weapons = mutableMapOf<WeaponType, Int>()
    private val defenses = mutableMapOf<DefenseType, Int>()
    private var energyLevel = 0
    private var engineLevel = 0
    private var radarLevel = 0
    private var hullStyle = 0

    private lateinit var pointsLabel: TextView
    private lateinit var saveButton: Button
    private val refreshers = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.goFullscreen(this)

        val lo = LoadoutStore.loadLoadout(this)
        weapons.putAll(lo.weapons)
        defenses.putAll(lo.defenses)
        energyLevel = lo.energyLevel
        engineLevel = lo.engineLevel
        radarLevel = lo.radarLevel
        hullStyle = lo.hullStyle

        val col = Ui.column(this)
        col.addView(Ui.title(this, "OUTFITTING", 24f), Ui.match())
        col.addView(Ui.space(this, 6f))
        pointsLabel = Ui.label(this, "", 14f)
        pointsLabel.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(pointsLabel, Ui.match())
        col.addView(Ui.space(this, 14f))

        col.addView(Ui.label(this, "— WEAPONS —", 11f), Ui.match())
        for (w in WeaponType.entries) {
            col.addView(itemRow(
                name = "${w.label}  [${w.cost}pt, +${w.upgradeCost}/mk]",
                equipped = { weapons.containsKey(w) },
                level = { weapons[w] ?: 0 },
                maxLevel = w.maxLevel,
                toggle = { if (weapons.containsKey(w)) weapons.remove(w) else weapons[w] = 0 },
                setLevel = { weapons[w] = it },
                describe = {
                    val s = weaponSpec(w, weapons[w] ?: 0)
                    if (s.ammo >= 0) {
                        "dmg ${s.damage.toInt()} · ammo ${s.ammo}"
                    } else {
                        "beam: locks all ships in ${s.range.toInt()} range · " +
                            "dmg ${s.damage.toInt()} split across targets, half hull half energy · " +
                            "${s.energyCost.toInt()} energy/shot"
                    }
                },
            ), Ui.match())
        }

        col.addView(Ui.space(this, 12f))
        col.addView(Ui.label(this, "— DEFENSES —", 11f), Ui.match())
        for (d in DefenseType.entries) {
            col.addView(itemRow(
                name = "${d.label}  [${d.cost}pt, +${d.upgradeCost}/mk]",
                equipped = { defenses.containsKey(d) },
                level = { defenses[d] ?: 0 },
                maxLevel = d.maxLevel,
                toggle = { if (defenses.containsKey(d)) defenses.remove(d) else defenses[d] = 0 },
                setLevel = { defenses[d] = it },
                describe = {
                    val lvl = defenses[d] ?: 0
                    when (d) {
                        DefenseType.SHIELD -> "soaks ${(shieldAbsorb(lvl) * 100).toInt()}% · ${fmt(shieldDrain(lvl))} energy/s"
                        DefenseType.CLOAK -> "invisible to ships & missiles · ${fmt(cloakDrain(lvl))} energy/s"
                    }
                },
            ), Ui.match())
        }

        col.addView(Ui.space(this, 12f))
        col.addView(Ui.label(this, "— CORE SYSTEMS —", 11f), Ui.match())
        col.addView(coreRow("ENERGY CELLS [2pt/lvl]", 3, { energyLevel }, { energyLevel = it }) {
            "max ${100 + 25 * energyLevel} · regen ${fmt(6f + 1.5f * energyLevel)}/s"
        }, Ui.match())
        col.addView(coreRow("ENGINES [1pt/lvl]", 2, { engineLevel }, { engineLevel = it }) {
            "+${engineLevel * 15}% thrust & turn"
        }, Ui.match())
        col.addView(coreRow("RADAR [1pt/lvl]", 3, { radarLevel }, { radarLevel = it }) {
            "range ${(900 + 700 * radarLevel)}"
        }, Ui.match())

        col.addView(Ui.space(this, 12f))
        col.addView(Ui.label(this, "— HULL DESIGN (free) —", 11f), Ui.match())
        col.addView(hullRow(), Ui.match())

        col.addView(Ui.space(this, 18f))
        saveButton = Ui.button(this, "SAVE FIT") {
            if (current().cost() <= Loadout.BUDGET) {
                LoadoutStore.saveLoadout(this, current())
                finish()
            }
        }
        col.addView(saveButton, Ui.match())
        col.addView(Ui.space(this, 8f))
        col.addView(Ui.button(this, "CANCEL", Ui.DIM) { finish() }, Ui.match())
        col.addView(Ui.space(this, 24f))

        setContentView(Ui.scroller(this, col))
        refresh()
    }

    private fun current() =
        Loadout(weapons.toMap(), defenses.toMap(), energyLevel, engineLevel, radarLevel, hullStyle)

    /** Cosmetic hull picker: cycle through the designs with < and >. */
    private fun hullRow(): LinearLayout {
        val row = Ui.row(this).apply {
            setPadding(0, Ui.dp(this@LoadoutActivity, 6f), 0, Ui.dp(this@LoadoutActivity, 6f))
        }
        val nameText = Ui.label(this, "", 13f, Ui.ACCENT).apply { gravity = Gravity.CENTER }
        val prev = Ui.smallButton(this, "<") {
            hullStyle = (hullStyle + Loadout.HULL_NAMES.size - 1) % Loadout.HULL_NAMES.size
            refresh()
        }
        val next = Ui.smallButton(this, ">") {
            hullStyle = (hullStyle + 1) % Loadout.HULL_NAMES.size
            refresh()
        }
        row.addView(prev)
        row.addView(nameText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(next)
        refreshers += {
            nameText.text = Loadout.HULL_NAMES[hullStyle]
        }
        return row
    }

    private fun fmt(v: Float): String {
        val tenths = (v * 10).toInt()
        return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    }

    private fun refresh() {
        val cost = current().cost()
        val over = cost > Loadout.BUDGET
        pointsLabel.text = "POINTS  $cost / ${Loadout.BUDGET}" + if (over) "  — OVER BUDGET" else ""
        pointsLabel.setTextColor(if (over) Palette.ENEMY else Ui.ACCENT)
        saveButton.isEnabled = !over
        saveButton.alpha = if (over) 0.4f else 1f
        for (r in refreshers) r()
    }

    /** Row with an equip toggle plus -/+ mark-level controls. */
    private fun itemRow(
        name: String,
        equipped: () -> Boolean,
        level: () -> Int,
        maxLevel: Int,
        toggle: () -> Unit,
        setLevel: (Int) -> Unit,
        describe: () -> String,
    ): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(this@LoadoutActivity, 6f), 0, Ui.dp(this@LoadoutActivity, 6f))
        }
        val row = Ui.row(this)
        val equipBtn = Ui.smallButton(this, "", onClick = { toggle(); refresh() })
        val levelText = Ui.label(this, "", 13f, Ui.ACCENT).apply {
            gravity = Gravity.CENTER
            minWidth = Ui.dp(this@LoadoutActivity, 52f)
        }
        val minus = Ui.smallButton(this, "-") {
            if (equipped() && level() > 0) { setLevel(level() - 1); refresh() }
        }
        val plus = Ui.smallButton(this, "+") {
            if (equipped() && level() < maxLevel) { setLevel(level() + 1); refresh() }
        }
        row.addView(equipBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.space(this, 1f).apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(this@LoadoutActivity, 8f), 1) })
        row.addView(minus)
        row.addView(levelText)
        row.addView(plus)
        val desc = Ui.label(this, "", 10f)
        outer.addView(row, Ui.match())
        outer.addView(desc, Ui.match())

        refreshers += {
            val on = equipped()
            equipBtn.text = (if (on) "[x] " else "[ ] ") + name
            equipBtn.setTextColor(if (on) Ui.ACCENT else Ui.DIM)
            equipBtn.background = Ui.outline(this, if (on) Ui.ACCENT else Ui.DIM)
            levelText.text = if (on) "MK " + "I".repeat(level() + 1) else "—"
            minus.alpha = if (on && level() > 0) 1f else 0.3f
            plus.alpha = if (on && level() < maxLevel) 1f else 0.3f
            desc.text = if (on) describe() else ""
        }
        return outer
    }

    /** Row for always-installed core systems with 0..max levels. */
    private fun coreRow(
        name: String,
        maxLevel: Int,
        level: () -> Int,
        setLevel: (Int) -> Unit,
        describe: () -> String,
    ): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(this@LoadoutActivity, 6f), 0, Ui.dp(this@LoadoutActivity, 6f))
        }
        val row = Ui.row(this)
        val label = Ui.label(this, name, 12f, Ui.ACCENT)
        val levelText = Ui.label(this, "", 13f, Ui.ACCENT).apply {
            gravity = Gravity.CENTER
            minWidth = Ui.dp(this@LoadoutActivity, 52f)
        }
        val minus = Ui.smallButton(this, "-") { if (level() > 0) { setLevel(level() - 1); refresh() } }
        val plus = Ui.smallButton(this, "+") { if (level() < maxLevel) { setLevel(level() + 1); refresh() } }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(minus)
        row.addView(levelText)
        row.addView(plus)
        val desc = Ui.label(this, "", 10f)
        outer.addView(row, Ui.match())
        outer.addView(desc, Ui.match())

        refreshers += {
            levelText.text = "LVL ${level()}"
            minus.alpha = if (level() > 0) 1f else 0.3f
            plus.alpha = if (level() < maxLevel) 1f else 0.3f
            desc.text = describe()
        }
        return outer
    }
}
