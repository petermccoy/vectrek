package com.vectrek.game.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.vectrek.game.engine.GameWorld
import com.vectrek.game.engine.Ship
import com.vectrek.game.engine.Shot
import com.vectrek.game.engine.ShotKind
import com.vectrek.game.engine.Vec2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

object Palette {
    const val SELF = 0xFF48FFDE.toInt()
    const val ENEMY = 0xFFFF5A54.toInt()
    const val ASTEROID = 0xFF8A97A8.toInt()
    const val BARRIER = 0xFF7686A0.toInt()
    const val STAR = 0xFFFFD24A.toInt()
    const val PLANET = 0xFF57A8FF.toInt()
    const val HOLE = 0xFFB86BFF.toInt()
    const val SHIELD = 0xFF4BB8FF.toInt()
    const val BOLT = 0xFF6CFF7A.toInt()
    const val SLUG = 0xFFFFFFFF.toInt()
    const val MISSILE = 0xFFFFA23E.toInt()
    const val MINE = 0xFFFF4A6A.toInt()
    const val BOOM = 0xFFFFB050.toInt()
    const val BOUNDS = 0xFFFF3A66.toInt()
    const val TEXT_DIM = 0xFF8FA3B8.toInt()
}

/**
 * Old-school vector look: bright strokes on black, camera locked to the
 * player's ship. Also owns the screen<->world transform used for steering.
 */
class Renderer {

    var viewW = 1f; private set
    var viewH = 1f; private set
    private var scale = 1f
    private var camX = 4000f
    private var camY = 3000f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val orbitDash = DashPathEffect(floatArrayOf(26f, 22f), 0f)

    fun screenToWorld(s: Vec2) =
        Vec2((s.x - viewW / 2f) / scale + camX, (s.y - viewH / 2f) / scale + camY)

    fun drawWorld(canvas: Canvas, world: GameWorld, me: Ship?, time: Float) {
        viewW = canvas.width.toFloat()
        viewH = canvas.height.toFloat()
        canvas.drawColor(Color.BLACK)

        if (me != null) {
            camX = me.pos.x.coerceIn(0f, world.width)
            camY = me.pos.y.coerceIn(0f, world.height)
        }
        scale = viewW / VIEW_SPAN

        canvas.save()
        canvas.translate(viewW / 2f, viewH / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-camX, -camY)

        val halfW = viewW / 2f / scale
        val halfH = viewH / 2f / scale
        val left = camX - halfW
        val right = camX + halfW
        val top = camY - halfH
        val bottom = camY + halfH

        fun visible(p: Vec2, r: Float) =
            p.x + r > left && p.x - r < right && p.y + r > top && p.y - r < bottom

        drawStarfield(canvas, left, top, right, bottom)
        drawBounds(canvas, world)

        for (star in world.stars) {
            // Orbit guides
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.pathEffect = orbitDash
            paint.color = Color.WHITE
            paint.alpha = 26
            for (p in world.planets) {
                if (p.star === star) canvas.drawCircle(star.pos.x, star.pos.y, p.orbitRadius, paint)
            }
            paint.pathEffect = null
            if (visible(star.pos, star.radius * 2.4f)) drawStar(canvas, star.pos, star.radius, time)
        }
        for (p in world.planets) if (visible(p.pos, p.radius * 1.5f)) {
            stroke(Palette.PLANET, 3f)
            canvas.drawCircle(p.pos.x, p.pos.y, p.radius, paint)
            paint.alpha = 70
            canvas.drawCircle(p.pos.x, p.pos.y, p.radius * 0.55f, paint)
        }
        for (b in world.blackHoles) if (visible(b.pos, b.horizon * 3f)) drawBlackHole(canvas, b.pos, b.horizon, time)
        for (a in world.asteroids) if (visible(a.pos, a.radius * 1.3f)) drawAsteroid(canvas, a.pos, a.radius, a.shape)
        for (b in world.barriers) {
            if (b.x + b.w > left && b.x < right && b.y + b.h > top && b.y < bottom) {
                stroke(Palette.BARRIER, 3f)
                canvas.drawRect(b.x, b.y, b.x + b.w, b.y + b.h, paint)
                paint.alpha = 60
                canvas.drawRect(b.x + 8f, b.y + 8f, b.x + b.w - 8f, b.y + b.h - 8f, paint)
            }
        }

        for (s in world.shots) if (visible(s.pos, 60f)) drawShot(canvas, s, time)

        for (ship in world.ships) {
            if (!ship.alive) continue
            // Cloaked hostiles are invisible. (The host filters them out of
            // network snapshots too, so clients can't peek at the packets.)
            if (ship.cloakOn && ship !== me) continue
            if (visible(ship.pos, 90f)) drawShip(canvas, ship, ship === me, time)
        }

        for (e in world.explosions) drawExplosion(canvas, e.pos, e.size, e.age / e.duration)

        canvas.restore()
    }

    // ------------------------------------------------------------------

    private fun stroke(color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        paint.alpha = 255
    }

    private fun drawStarfield(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        paint.style = Paint.Style.FILL
        val cell = 420f
        var ix = floor(left / cell).toInt() - 1
        val ixEnd = floor(right / cell).toInt() + 1
        val iyStart = floor(top / cell).toInt() - 1
        val iyEnd = floor(bottom / cell).toInt() + 1
        while (ix <= ixEnd) {
            var iy = iyStart
            while (iy <= iyEnd) {
                var h = ix * 73856093 xor iy * 19349663
                repeat(2) {
                    h = h * 1103515245 + 12345
                    val fx = ((h ushr 8) and 0x3FF) / 1023f
                    val fy = ((h ushr 18) and 0x3FF) / 1023f
                    paint.color = Color.WHITE
                    paint.alpha = 60 + ((h ushr 4) and 0x7F)
                    canvas.drawCircle((ix + fx) * cell, (iy + fy) * cell, 2.2f, paint)
                }
                iy++
            }
            ix++
        }
    }

    private fun drawBounds(canvas: Canvas, world: GameWorld) {
        stroke(Palette.BOUNDS, 4f)
        paint.alpha = 160
        canvas.drawRect(0f, 0f, world.width, world.height, paint)
        paint.alpha = 60
        canvas.drawRect(-14f, -14f, world.width + 14f, world.height + 14f, paint)
    }

    private fun drawStar(canvas: Canvas, pos: Vec2, radius: Float, time: Float) {
        val pulse = radius * (1f + 0.03f * sin(time * 2.4f))
        stroke(Palette.STAR, 4f)
        canvas.drawCircle(pos.x, pos.y, pulse, paint)
        paint.alpha = 90
        canvas.drawCircle(pos.x, pos.y, pulse * 1.28f, paint)
        paint.alpha = 200
        paint.strokeWidth = 2.5f
        val rays = 9
        for (i in 0 until rays) {
            val a = time * 0.15f + i * TWO_PI_F / rays
            val c = cos(a); val s = sin(a)
            canvas.drawLine(
                pos.x + c * pulse * 1.4f, pos.y + s * pulse * 1.4f,
                pos.x + c * pulse * (1.75f + 0.1f * sin(time * 3f + i)), pos.y + s * pulse * 1.75f, paint,
            )
        }
    }

    private fun drawBlackHole(canvas: Canvas, pos: Vec2, horizon: Float, time: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawCircle(pos.x, pos.y, horizon, paint)
        stroke(Palette.HOLE, 3.5f)
        canvas.drawCircle(pos.x, pos.y, horizon, paint)
        paint.strokeWidth = 2.5f
        for (ring in 1..2) {
            val r = horizon * (1f + ring * 0.65f)
            paint.alpha = 150 / ring
            val startDeg = -time * 80f * ring + ring * 120f
            canvas.drawArc(pos.x - r, pos.y - r, pos.x + r, pos.y + r, startDeg, 250f, false, paint)
        }
    }

    private fun drawAsteroid(canvas: Canvas, pos: Vec2, radius: Float, shape: FloatArray) {
        path.reset()
        for (i in shape.indices) {
            val a = i * TWO_PI_F / shape.size
            val r = radius * shape[i]
            val x = pos.x + cos(a) * r
            val y = pos.y + sin(a) * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        stroke(Palette.ASTEROID, 3f)
        canvas.drawPath(path, paint)
    }

    private fun drawShot(canvas: Canvas, s: Shot, time: Float) {
        when (s.kind) {
            ShotKind.SLUG -> {
                stroke(Palette.SLUG, 3f)
                val d = Vec2.fromAngle(s.heading, 12f)
                canvas.drawLine(s.pos.x - d.x, s.pos.y - d.y, s.pos.x + d.x, s.pos.y + d.y, paint)
            }
            ShotKind.BOLT -> {
                stroke(Palette.BOLT, 5f)
                val d = Vec2.fromAngle(s.heading, 16f)
                canvas.drawLine(s.pos.x - d.x, s.pos.y - d.y, s.pos.x + d.x, s.pos.y + d.y, paint)
                paint.alpha = 70
                canvas.drawCircle(s.pos.x, s.pos.y, 12f, paint)
            }
            ShotKind.MISSILE -> {
                canvas.save()
                canvas.translate(s.pos.x, s.pos.y)
                canvas.rotate(s.heading * RAD_TO_DEG)
                stroke(Palette.MISSILE, 3f)
                path.reset()
                path.moveTo(12f, 0f); path.lineTo(-8f, 6f); path.lineTo(-8f, -6f); path.close()
                canvas.drawPath(path, paint)
                paint.alpha = 170
                canvas.drawLine(-8f, 0f, -16f - 6f * sin(time * 40f), 0f, paint)
                canvas.restore()
            }
            ShotKind.MINE -> {
                val blink = !s.armed || sin(time * 9f) > -0.3f
                stroke(Palette.MINE, 3f)
                paint.alpha = if (blink) 255 else 90
                canvas.drawCircle(s.pos.x, s.pos.y, 9f, paint)
                for (i in 0 until 4) {
                    val a = i * TWO_PI_F / 4 + 0.6f
                    canvas.drawLine(
                        s.pos.x + cos(a) * 9f, s.pos.y + sin(a) * 9f,
                        s.pos.x + cos(a) * 16f, s.pos.y + sin(a) * 16f, paint,
                    )
                }
            }
        }
    }

    private fun drawShip(canvas: Canvas, ship: Ship, isMe: Boolean, time: Float) {
        val color = if (isMe) Palette.SELF else Palette.ENEMY
        val alpha = if (ship.cloakOn) 70 else 255
        val r = ship.radius

        canvas.save()
        canvas.translate(ship.pos.x, ship.pos.y)
        canvas.rotate(ship.heading * RAD_TO_DEG)
        stroke(color, 3f)
        paint.alpha = alpha
        path.reset()
        path.moveTo(r * 1.15f, 0f)
        path.lineTo(-r * 0.8f, r * 0.7f)
        path.lineTo(-r * 0.45f, 0f)
        path.lineTo(-r * 0.8f, -r * 0.7f)
        path.close()
        canvas.drawPath(path, paint)

        if (ship.thrusting) {
            val flick = 0.7f + 0.3f * sin(time * 47f + ship.id * 3f)
            paint.color = Palette.MISSILE
            paint.alpha = (alpha * 0.85f).toInt()
            canvas.drawLine(-r * 0.6f, r * 0.3f, -r * (1.1f + flick), 0f, paint)
            canvas.drawLine(-r * 0.6f, -r * 0.3f, -r * (1.1f + flick), 0f, paint)
        }
        canvas.restore()

        if (ship.shieldOn) {
            stroke(Palette.SHIELD, 2.5f)
            paint.alpha = (90 + 50 * sin(time * 6f + ship.id)).toInt().coerceIn(40, 160)
            canvas.drawCircle(ship.pos.x, ship.pos.y, r * 1.7f, paint)
        }

        if (!isMe && !ship.cloakOn) {
            paint.style = Paint.Style.FILL
            paint.color = Palette.TEXT_DIM
            paint.alpha = 200
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 20f
            canvas.drawText(ship.name, ship.pos.x, ship.pos.y - r * 2.2f, paint)
        }
    }

    private fun drawExplosion(canvas: Canvas, pos: Vec2, size: Float, t: Float) {
        val alpha = ((1f - t) * 255f).toInt().coerceIn(0, 255)
        stroke(Palette.BOOM, 3f)
        paint.alpha = alpha
        canvas.drawCircle(pos.x, pos.y, size * (0.3f + t * 2.0f), paint)
        paint.strokeWidth = 2.5f
        for (i in 0 until 8) {
            val a = i * TWO_PI_F / 8 + pos.x  // pos-derived phase so booms differ
            val inner = size * t * 1.4f
            val outer = size * (0.4f + t * 2.4f)
            canvas.drawLine(
                pos.x + cos(a) * inner, pos.y + sin(a) * inner,
                pos.x + cos(a) * outer, pos.y + sin(a) * outer, paint,
            )
        }
    }

    companion object {
        /** World units visible across the screen width. */
        const val VIEW_SPAN = 1500f
        const val RAD_TO_DEG = 57.29578f
        const val TWO_PI_F = 6.2831855f
    }
}
