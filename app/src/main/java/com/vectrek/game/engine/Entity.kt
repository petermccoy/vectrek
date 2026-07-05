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
    var leaveOrbit = false               // held briefly after the LEAVE ORBIT tap

    fun copyFrom(o: ShipInput) {
        steer = o.steer; thrust = o.thrust
        fireHeld.clear(); fireHeld.addAll(o.fireHeld)
        shield = o.shield; cloak = o.cloak
        leaveOrbit = o.leaveOrbit
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
    var hullStyle = loadout.hullStyle

    val ammo = mutableMapOf<WeaponType, Int>()
    private val cooldowns = FloatArray(WeaponType.entries.size)
    var shieldOn = false
    var cloakOn = false
    var thrusting = false
    var kills = 0

    // Planet orbit: while captured, the ship rides a circular parking orbit
    // and slowly repairs its hull. Client mirrors only get [netOrbiting].
    var orbitPlanet: Planet? = null
    var orbitAngle = 0f
    var orbitR = 0f
    var orbitAngVel = 0f
    var orbitCooldown = 0f     // grace period after leaving before re-capture
    var netOrbiting = false
    val inOrbit get() = orbitPlanet != null || netOrbiting

    /** Grace period after a wormhole transit before another can grab us. */
    var wormholeCooldown = 0f

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
        orbitCooldown = max(0f, orbitCooldown - dt)
        wormholeCooldown = max(0f, wormholeCooldown - dt)

        // Defenses stay up only while there is energy to feed them.
        shieldOn = input.shield && loadout.has(DefenseType.SHIELD) && energy > 1f
        cloakOn = input.cloak && loadout.has(DefenseType.CLOAK) && energy > 1f

        val planet = orbitPlanet
        thrusting = false
        if (planet != null) {
            // Parked: ride the orbit, mend the hull. Weapons stay live.
            orbitAngle = wrapAngle(orbitAngle + orbitAngVel * dt)
            val radial = Vec2.fromAngle(orbitAngle)
            pos = planet.pos + radial * orbitR
            val tangent = Vec2(-radial.y, radial.x) * (if (orbitAngVel >= 0f) 1f else -1f)
            vel = tangent * (abs(orbitAngVel) * orbitR) + planet.vel
            heading = tangent.angle()
            hull = (hull + ORBIT_REPAIR_RATE * dt).coerceAtMost(maxHull)
            if (input.leaveOrbit) leaveOrbit()
        } else {
            // Free flight: turn toward the touched point; burn once lined up.
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
        }

        var drain = 0f
        if (shieldOn) drain += shieldDrain(loadout.level(DefenseType.SHIELD))
        if (cloakOn) drain += cloakDrain(loadout.level(DefenseType.CLOAK))
        energy = (energy + (stats.energyRegen - drain) * dt).coerceIn(0f, maxEnergy)

        for (w in WeaponType.entries) if (w in input.fireHeld) tryFire(world, w)

        if (planet == null) pos += vel * dt
    }

    /** Captured by a planet: set up the parking orbit continuing our swing. */
    fun enterOrbit(planet: Planet) {
        orbitPlanet = planet
        orbitR = planet.radius + radius + 12f
        val radial = (pos - planet.pos).normalized()
        orbitAngle = radial.angle()
        pos = planet.pos + radial * orbitR
        // Keep circling the way we were already moving around the planet.
        val tangent = Vec2(-radial.y, radial.x)
        val side = if ((vel - planet.vel).dot(tangent) >= 0f) 1f else -1f
        orbitAngVel = side * (ORBIT_LINEAR_SPEED / orbitR)
    }

    private fun leaveOrbit() {
        val planet = orbitPlanet ?: return
        val radial = Vec2.fromAngle(orbitAngle)
        val tangent = Vec2(-radial.y, radial.x) * (if (orbitAngVel >= 0f) 1f else -1f)
        vel = tangent * (ORBIT_LINEAR_SPEED * 1.4f) + radial * 70f + planet.vel
        heading = vel.angle()
        orbitPlanet = null
        orbitCooldown = 1.5f
    }

    private fun tryFire(world: GameWorld, w: WeaponType) {
        val lvl = loadout.weapons[w] ?: return
        if (cooldowns[w.ordinal] > 0f) return
        val spec = weaponSpec(w, lvl)
        if (spec.ammo >= 0 && (ammo[w] ?: 0) <= 0) return
        if (energy < spec.energyCost) return

        if (w == WeaponType.ENERGY) {
            // Beam weapon: only fires if something is locked in range.
            if (!world.fireBeam(this, spec)) return
        } else {
            world.spawnShot(this, w, spec)
        }
        energy -= spec.energyCost
        if (spec.ammo >= 0) ammo[w] = (ammo[w] ?: 0) - 1
        cooldowns[w.ordinal] = spec.cooldown
        // Firing while cloaked decloaks you (drops the toggle).
        if (cloakOn && w != WeaponType.MINE) {
            input.cloak = false
            cloakOn = false
        }
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

    companion object {
        const val ORBIT_REPAIR_RATE = 5f      // hull per second while parked
        const val ORBIT_LINEAR_SPEED = 85f    // parking-orbit tangential speed
    }
}

enum class ShotKind { SLUG, MISSILE, MINE }

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
