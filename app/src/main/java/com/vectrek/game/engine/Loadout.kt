package com.vectrek.game.engine

import android.content.Context
import org.json.JSONObject

/**
 * Offensive systems. Each has a base point cost plus a per-level upgrade cost.
 * Upgrades raise damage and (for ammo weapons) magazine size, or lower the
 * energy cost of the energy weapon.
 */
enum class WeaponType(val label: String, val short: String, val cost: Int, val maxLevel: Int, val upgradeCost: Int) {
    PROJECTILE("Cannon", "GUN", 3, 2, 1),
    ENERGY("Phaser", "PHSR", 4, 2, 1),
    GUIDED("Missiles", "MSSL", 5, 2, 2),
    MINE("Mines", "MINE", 4, 2, 1);
}

/**
 * Defensive systems. Upgrades reduce the energy drain (and for shields raise
 * how much of an incoming hit they can soak).
 */
enum class DefenseType(val label: String, val short: String, val cost: Int, val maxLevel: Int, val upgradeCost: Int) {
    SHIELD("Shields", "SHLD", 5, 2, 1),
    CLOAK("Cloak", "CLK", 4, 2, 1);
}

data class WeaponSpec(
    val damage: Float,      // phaser: total damage, split among all locked targets
    val ammo: Int,          // -1 = unlimited (energy weapon)
    val cooldown: Float,    // seconds between shots
    val energyCost: Float,  // per shot
    val speed: Float,       // muzzle speed added to ship velocity
    val life: Float,        // seconds before the shot expires
    val range: Float = 0f,  // phaser beam lock range
)

fun weaponSpec(type: WeaponType, level: Int): WeaponSpec = when (type) {
    WeaponType.PROJECTILE -> WeaponSpec(8f + 3f * level, 40 + 15 * level, 0.24f, 0f, 575f, 1.8f)
    WeaponType.ENERGY -> WeaponSpec(16f + 5f * level, -1, 0.55f, 12f - 1.5f * level, 0f, 0f, 620f + 60f * level)
    WeaponType.GUIDED -> WeaponSpec(26f + 8f * level, 6 + 3 * level, 1.2f, 0f, 370f, 6.5f)
    WeaponType.MINE -> WeaponSpec(34f + 10f * level, 5 + 3 * level, 0.8f, 0f, 0f, 90f)
}

/** Shield: fraction of a hit absorbed while raised; energy pays for what it soaks. */
fun shieldAbsorb(level: Int): Float = 0.70f + 0.08f * level
fun shieldDrain(level: Int): Float = 7f - 1.5f * level     // per second while raised
fun cloakDrain(level: Int): Float = 6.5f - 1.5f * level    // per second while cloaked

/**
 * A ship configuration bought with a fixed budget of outfit points.
 * Weapons/defenses map to their upgrade level (0..maxLevel).
 */
data class Loadout(
    val weapons: Map<WeaponType, Int> = emptyMap(),
    val defenses: Map<DefenseType, Int> = emptyMap(),
    val energyLevel: Int = 0,  // 0..3: +25 max energy and +1.5 regen per level (2 pts each)
    val engineLevel: Int = 0,  // 0..2: +15% thrust/turn/speed per level (1 pt each)
    val radarLevel: Int = 0,   // 0..3: +700 radar range per level (1 pt each)
    val hullStyle: Int = 0,    // cosmetic, free: index into HULL_NAMES
) {
    fun cost(): Int {
        var c = 0
        for ((w, lvl) in weapons) c += w.cost + w.upgradeCost * lvl
        for ((d, lvl) in defenses) c += d.cost + d.upgradeCost * lvl
        c += 2 * energyLevel + engineLevel + radarLevel
        return c
    }

    fun has(w: WeaponType) = weapons.containsKey(w)
    fun has(d: DefenseType) = defenses.containsKey(d)
    fun level(w: WeaponType) = weapons[w] ?: 0
    fun level(d: DefenseType) = defenses[d] ?: 0

    fun toJson(): JSONObject {
        val o = JSONObject()
        val wj = JSONObject()
        for ((w, lvl) in weapons) wj.put(w.name, lvl)
        val dj = JSONObject()
        for ((d, lvl) in defenses) dj.put(d.name, lvl)
        o.put("w", wj); o.put("d", dj)
        o.put("en", energyLevel); o.put("eng", engineLevel); o.put("rad", radarLevel)
        o.put("hs", hullStyle)
        return o
    }

    companion object {
        const val BUDGET = 20
        val HULL_NAMES = arrayOf("SABER", "CRUISER", "TALON")

        /** Cannon + missiles + shield + cloak + energy I + radar I = 20 pts. */
        val DEFAULT = Loadout(
            weapons = mapOf(WeaponType.PROJECTILE to 0, WeaponType.GUIDED to 0),
            defenses = mapOf(DefenseType.SHIELD to 0, DefenseType.CLOAK to 0),
            energyLevel = 1, engineLevel = 0, radarLevel = 1,
        )

        /** Cheap fit used by practice drones. */
        val DRONE = Loadout(weapons = mapOf(WeaponType.PROJECTILE to 0), hullStyle = 2)

        fun fromJson(o: JSONObject): Loadout {
            val weapons = mutableMapOf<WeaponType, Int>()
            val defenses = mutableMapOf<DefenseType, Int>()
            val wj = o.optJSONObject("w") ?: JSONObject()
            for (w in WeaponType.entries) if (wj.has(w.name)) {
                weapons[w] = wj.getInt(w.name).coerceIn(0, w.maxLevel)
            }
            val dj = o.optJSONObject("d") ?: JSONObject()
            for (d in DefenseType.entries) if (dj.has(d.name)) {
                defenses[d] = dj.getInt(d.name).coerceIn(0, d.maxLevel)
            }
            return Loadout(
                weapons, defenses,
                o.optInt("en").coerceIn(0, 3),
                o.optInt("eng").coerceIn(0, 2),
                o.optInt("rad").coerceIn(0, 3),
                o.optInt("hs").coerceIn(0, HULL_NAMES.size - 1),
            )
        }
    }
}

/** Concrete ship performance numbers derived from a loadout. */
class ShipStats(loadout: Loadout) {
    val maxHull = 100f
    val maxEnergy = 100f + 25f * loadout.energyLevel
    val energyRegen = 6f + 1.5f * loadout.energyLevel
    // ~80% of the original pacing: slower, more deliberate flying.
    val thrustAccel = 208f * (1f + 0.15f * loadout.engineLevel)
    val turnRate = 3.1f * (1f + 0.15f * loadout.engineLevel)
    val maxSpeed = 336f * (1f + 0.10f * loadout.engineLevel)
    val radarRange = 900f + 700f * loadout.radarLevel
    val thrustDrain = 5f  // energy per second while burning
}

/** Persists the player's name and chosen loadout. */
object LoadoutStore {
    private const val PREFS = "vectrek"

    fun saveLoadout(context: Context, loadout: Loadout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("loadout", loadout.toJson().toString()).apply()
    }

    fun loadLoadout(context: Context): Loadout {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("loadout", null) ?: return Loadout.DEFAULT
        return try {
            Loadout.fromJson(JSONObject(s))
        } catch (_: Exception) {
            Loadout.DEFAULT
        }
    }

    fun savePlayerName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("name", name).apply()
    }

    fun loadPlayerName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("name", null) ?: "PILOT"
}
