package com.vectrek.game.engine

import java.util.Random
import kotlin.math.abs

/**
 * Simple practice-drone brain: wander the arena, and when a non-cloaked ship
 * comes into sensor range, chase it and fire with a bit of target lead.
 */
class AIController(seed: Long) : ShipController {

    private val rnd = Random(seed)
    private var wanderTarget: Vec2? = null
    private var wanderTimer = 0f

    override fun control(ship: Ship, world: GameWorld, dt: Float) {
        val input = ship.input
        input.fireHeld.clear()

        if (ship.orbitPlanet != null) {
            // Parked for repairs: cast off once the hull is mostly patched.
            input.leaveOrbit = ship.hull > ship.maxHull * 0.85f
            return
        }
        input.leaveOrbit = false

        val enemy = world.ships
            .filter { it.alive && it.id != ship.id && !it.cloakOn }
            .minByOrNull { it.pos.distSq(ship.pos) }

        if (enemy != null && enemy.pos.dist(ship.pos) < 1500f) {
            val dist = enemy.pos.dist(ship.pos)
            // Lead the target by projectile flight time.
            val lead = enemy.pos + enemy.vel * (dist / 575f)
            input.steer = lead
            input.thrust = dist > 380f
            val aimError = abs(angleDiff((lead - ship.pos).angle(), ship.heading))
            if (dist < 780f && aimError < 0.22f) {
                input.fireHeld += WeaponType.PROJECTILE
            }
            input.shield = ship.loadout.has(DefenseType.SHIELD) &&
                ship.hull < ship.maxHull * 0.6f && dist < 700f
        } else {
            wanderTimer -= dt
            val t = wanderTarget
            if (t == null || wanderTimer <= 0f || ship.pos.dist(t) < 260f) {
                wanderTarget = Vec2(
                    300f + rnd.nextFloat() * (world.width - 600f),
                    300f + rnd.nextFloat() * (world.height - 600f),
                )
                wanderTimer = 4f + rnd.nextFloat() * 4f
            }
            input.steer = wanderTarget
            input.thrust = true
            input.shield = false
        }
    }
}
