package com.vectrek.game.engine

import kotlin.math.cos
import kotlin.math.sin

/**
 * Fixed obstacles and gravity wells that litter the board. All are static
 * except planets, which orbit their star kinematically.
 */

class Asteroid(val pos: Vec2, val radius: Float, val shape: FloatArray) {
    companion object {
        /** Irregular polygon: per-vertex radius multipliers. */
        fun makeShape(rnd: java.util.Random, verts: Int = 10): FloatArray =
            FloatArray(verts) { 0.72f + rnd.nextFloat() * 0.42f }
    }
}

/** Axis-aligned wall segment. */
class Barrier(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
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

    fun update(dt: Float) {
        orbitAngle = wrapAngle(orbitAngle + orbitSpeed * dt)
        pos = at(orbitAngle)
    }

    private fun at(a: Float) =
        Vec2(star.pos.x + cos(a) * orbitRadius, star.pos.y + sin(a) * orbitRadius)
}

class BlackHole(val pos: Vec2, val horizon: Float, val mass: Float)

/** Short-lived visual effect; purely cosmetic, safe to run client-side. */
class Explosion(val pos: Vec2, val size: Float) {
    var age = 0f
    val duration = 0.75f
    val done get() = age >= duration
}
