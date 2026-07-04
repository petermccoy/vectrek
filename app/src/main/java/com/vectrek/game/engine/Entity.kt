package com.vectrek.game.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/** Anything that moves and collides in the world. */
abstract class Entity(var pos: Vec2, var vel: Vec2, val radius: Float) {
    var alive = true
    open fun update(world: GameWorld, dt: Float) {
        pos += vel * dt
    }
}

/**
 * Per-tick pilot intent. Filled by the touch controller (local player),
 * an [AIController] (drones), or the network layer (remote players).
 */
class ShipInput {
    var steer: Vec2? = null              // world-space point the pilot is touching
    var thrust = false                   // burn while turning toward the steer point
    val fireHeld = mutableSetOf<WeaponType>()
    var shield = false                   // toggle states, not momentary
    var cloak = false

    fun copyFrom(o: ShipInput) {
        steer = o.steer; thrust = o.thrust
        fireHeld.clear(); fireHeld.addAll(o.fireHeld)
        shield = o.shield; cloak = o.cloak
    }
}

fun interface ShipController {
    fun control(ship: Ship, world: GameWorld, dt: Float)
}

class Ship(
    val id: Int,
    var name: String,
    val loadout: Loadout,
    pos: Vec2,
) : Entity(pos, Vec2.ZERO, 18f) {

    val stats = ShipStats(loadout)
    var heading = 0f
    var hull = stats.maxHull
    var energy = stats.maxEnergy
    // Display maxima. Client mirrors overwrite these from snapshots since they
    // don't know remote ships' loadouts.
    var maxHull = stats.maxHull
    var maxEnergy = stats.maxEnergy

    val ammo = mutableMapOf<WeaponType, Int>()
    private val cooldowns = FloatArray(WeaponType.entries.size)
    var shieldOn = false
    var cloakOn = false
    var thrusting = false
    var kills = 0

    val input = ShipInput()
    var controller: ShipController? = null

    init {
        for ((w, lvl) in loadout.weapons) {
            val spec = weaponSpec(w, lvl)
            if (spec.ammo >= 0) ammo[w] = spec.ammo
        }
    }

    fun cooldown(w: WeaponType) = cooldowns[w.ordinal]

    override fun update(world: GameWorld, dt: Float) {
        controller?.control(this, world, dt)

        for (i in cooldowns.indices) cooldowns[i] = max(0f, cooldowns[i] - dt)

        // Defenses stay up only while there is energy to feed them.
        shieldOn = input.shield && loadout.has(DefenseType.SHIELD) && energy > 1f
        cloakOn = input.cloak && loadout.has(DefenseType.CLOAK) && energy > 1f

        // Turn toward the touched point; burn once roughly lined up.
        thrusting = false
        val target = input.steer
        if (target != null) {
            val desired = atan2(target.y - pos.y, target.x - pos.x)
            val diff = angleDiff(desired, heading)
            val maxTurn = stats.turnRate * dt
            heading = wrapAngle(heading + diff.coerceIn(-maxTurn, maxTurn))
            if (input.thrust && abs(diff) < 1.2f && energy > 0.5f) {
                vel += Vec2.fromAngle(heading, stats.thrustAccel * dt)
                energy -= stats.thrustDrain * dt
                thrusting = true
            }
        }

        val sp = vel.length()
        if (sp > stats.maxSpeed) vel = vel * (stats.maxSpeed / sp)

        var drain = 0f
        if (shieldOn) drain += shieldDrain(loadout.level(DefenseType.SHIELD))
        if (cloakOn) drain += cloakDrain(loadout.level(DefenseType.CLOAK))
        energy = (energy + (stats.energyRegen - drain) * dt).coerceIn(0f, maxEnergy)

        for (w in WeaponType.entries) if (w in input.fireHeld) tryFire(world, w)

        pos += vel * dt
    }

    private fun tryFire(world: GameWorld, w: WeaponType) {
        val lvl = loadout.weapons[w] ?: return
        if (cooldowns[w.ordinal] > 0f) return
        val spec = weaponSpec(w, lvl)
        if (spec.ammo >= 0 && (ammo[w] ?: 0) <= 0) return
        if (energy < spec.energyCost) return

        energy -= spec.energyCost
        if (spec.ammo >= 0) ammo[w] = (ammo[w] ?: 0) - 1
        cooldowns[w.ordinal] = spec.cooldown
        // Firing while cloaked decloaks you for a moment (drops the toggle).
        if (cloakOn && w != WeaponType.MINE) {
            input.cloak = false
            cloakOn = false
        }
        world.spawnShot(this, w, spec)
    }

    /** Apply damage; raised shields soak a fraction, paid for with energy. */
    fun takeDamage(world: GameWorld, amount: Float, attackerId: Int = -1) {
        if (!alive) return
        var dmg = amount
        if (shieldOn) {
            val wanted = dmg * shieldAbsorb(loadout.level(DefenseType.SHIELD))
            val soaked = minOf(wanted, energy / 0.6f)
            energy = max(0f, energy - soaked * 0.6f)
            dmg -= soaked
        }
        hull -= dmg
        if (hull <= 0f) {
            hull = 0f
            alive = false
            world.onShipDestroyed(this, attackerId)
        }
    }
}

enum class ShotKind { SLUG, BOLT, MISSILE, MINE }

class Shot(
    val id: Int,
    val kind: ShotKind,
    val ownerId: Int,
    pos: Vec2,
    vel: Vec2,
    val damage: Float,
    var life: Float,
) : Entity(pos, vel, if (kind == ShotKind.MINE) 10f else 5f) {

    var age = 0f
    val armed get() = kind != ShotKind.MINE || age > MINE_ARM_TIME
    var heading = vel.angle()

    override fun update(world: GameWorld, dt: Float) {
        age += dt
        life -= dt
        if (kind == ShotKind.MINE) {
            // Dropped mines shed their initial drift and sit still.
            vel = vel * (1f - (2.5f * dt).coerceAtMost(1f))
        } else if (vel.lengthSq() > 1f) {
            heading = vel.angle()
        }
        pos += vel * dt
    }

    companion object {
        const val MINE_ARM_TIME = 1.2f
        const val MINE_TRIGGER_RADIUS = 95f
        const val MINE_BLAST_RADIUS = 150f
    }
}
