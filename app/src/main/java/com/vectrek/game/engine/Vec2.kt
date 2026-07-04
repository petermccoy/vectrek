package com.vectrek.game.engine

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable 2D vector used for positions, velocities and directions. */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    operator fun div(s: Float) = Vec2(x / s, y / s)

    fun length(): Float = sqrt(x * x + y * y)
    fun lengthSq(): Float = x * x + y * y
    fun dist(o: Vec2): Float = (this - o).length()
    fun distSq(o: Vec2): Float = (this - o).lengthSq()
    fun dot(o: Vec2): Float = x * o.x + y * o.y
    fun angle(): Float = atan2(y, x)

    fun normalized(): Vec2 {
        val l = length()
        return if (l < 1e-6f) ZERO else Vec2(x / l, y / l)
    }

    companion object {
        val ZERO = Vec2(0f, 0f)
        fun fromAngle(a: Float, len: Float = 1f) = Vec2(cos(a) * len, sin(a) * len)
    }
}

const val PIF = PI.toFloat()
const val TWO_PI = 2f * PIF

/** Smallest signed angle taking you from [b] to [a], in (-pi, pi]. */
fun angleDiff(a: Float, b: Float): Float {
    var d = a - b
    while (d > PIF) d -= TWO_PI
    while (d < -PIF) d += TWO_PI
    return d
}

fun wrapAngle(a: Float): Float {
    var r = a
    while (r > PIF) r -= TWO_PI
    while (r < -PIF) r += TWO_PI
    return r
}
