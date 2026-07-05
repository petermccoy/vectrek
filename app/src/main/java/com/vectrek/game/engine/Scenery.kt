package com.vectrek.game.engine

import kotlin.math.cos
import kotlin.math.sin

/**
 * Obstacles and gravity wells that litter the board. Asteroids drift slowly,
 * planets orbit their sun, suns and wormholes are fixed.
 */

class Asteroid(var pos: Vec2, val radius: Float, val shape: FloatArray, var vel: Vec2) {
    companion object {
        /** Irregular polygon: per-vertex radius multipliers. */
        fun makeShape(rnd: java.util.Random, verts: Int = 10): FloatArray =
            FloatArray(verts) { 0.72f + rnd.nextFloat() * 0.42f }
    }
}

class Star(val pos: Vec2, val radius: Float, val mass: Float)

class Planet(
    val star: Star,
    val orbitRadius: Float,
    var orbitAngle: Float,
    val orbitSpeed: Float,   // rad/s, signed
    val radius: Float,
    val mass: Float,
) {
    var pos: Vec2 = at(orbitAngle)
        private set
    /** Instantaneous velocity, so orbiting ships can inherit it. */
    var vel: Vec2 = Vec2.ZERO
        private set

    fun update(dt: Float) {
        orbitAngle = wrapAngle(orbitAngle + orbitSpeed * dt)
        val next = at(orbitAngle)
        vel = if (dt > 0f) (next - pos) * (1f / dt) else Vec2.ZERO
        pos = next
    }

    private fun at(a: Float) =
        Vec2(star.pos.x + cos(a) * orbitRadius, star.pos.y + sin(a) * orbitRadius)
}

/**
 * Paired portals. Anything crossing the horizon is flung out of another
 * wormhole on the board, velocity intact. They still pull like gravity wells.
 */
class Wormhole(val pos: Vec2, val horizon: Float, val mass: Float)

/** Short-lived visual effect; purely cosmetic, safe to run client-side. */
class Explosion(val pos: Vec2, val size: Float) {
    var age = 0f
    val duration = 0.75f
    val done get() = age >= duration
}

/** Phaser beam flash: drawn from muzzle to each locked target for an instant. */
class Beam(val from: Vec2, val to: Vec2) {
    var age = 0f
    val duration = 0.22f
    val done get() = age >= duration
}
