package com.vectrek.game.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.vectrek.game.engine.DefenseType
import com.vectrek.game.engine.Loadout
import com.vectrek.game.engine.Ship
import com.vectrek.game.engine.ShipInput
import com.vectrek.game.engine.ShotKind
import com.vectrek.game.engine.WeaponType
import com.vectrek.game.engine.weaponSpec
import kotlin.math.sin

/**
 * All on-screen instrumentation: hull/energy bars, radar, weapon and defense
 * buttons (along the top edge, clear of steering thumbs), the LEAVE ORBIT
 * button, status line and overlays. Buttons are drawn on the canvas and
 * hit-tested by [hitTest] from the touch handler.
 */
class Hud(private val density: Float) {

    class Btn(
        val label: String,
        val weapon: WeaponType? = null,
        val defense: DefenseType? = null,
        val leaveOrbit: Boolean = false,
        var cx: Float = 0f,
        var cy: Float = 0f,
        var r: Float = 0f,
    )

    val buttons = mutableListOf<Btn>()
    private val leaveBtn = Btn("LEAVE\nORBIT", leaveOrbit = true)
    /** Updated during draw; read by the touch thread. */
    @Volatile private var orbitBtnVisible = false

    private var laidOutW = -1
    private var laidOutH = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }

    private fun dp(v: Float) = v * density

    fun layout(w: Int, h: Int, loadout: Loadout) {
        if (w == laidOutW && h == laidOutH && buttons.isNotEmpty()) return
        laidOutW = w; laidOutH = h
        buttons.clear()
        val r = dp(28f)
        val gap = dp(70f)
        val rowY = dp(104f)

        // Defenses: row leading in from the left edge (below the bars).
        val defenses = DefenseType.entries.filter { loadout.has(it) }
        for ((i, dt) in defenses.withIndex()) {
            buttons += Btn(dt.short, defense = dt).apply {
                cx = dp(50f) + i * gap
                cy = rowY
                this.r = r
            }
        }
        // Weapons: row leading in from the right edge (left of the radar).
        val weapons = WeaponType.entries.filter { loadout.has(it) }
        for ((i, wt) in weapons.withIndex()) {
            buttons += Btn(wt.short, weapon = wt).apply {
                cx = w - dp(180f) - i * gap
                cy = rowY
                this.r = r
            }
        }
        leaveBtn.cx = w / 2f
        leaveBtn.cy = h - dp(64f)
        leaveBtn.r = dp(34f)
    }

    fun hitTest(x: Float, y: Float): Btn? {
        if (orbitBtnVisible) {
            val dx = x - leaveBtn.cx; val dy = y - leaveBtn.cy
            if (dx * dx + dy * dy < leaveBtn.r * leaveBtn.r * 1.6f) return leaveBtn
        }
        return buttons.firstOrNull {
            val dx = x - it.cx; val dy = y - it.cy
            dx * dx + dy * dy < it.r * it.r * 1.4f
        }
    }

    // ------------------------------------------------------------------

    fun draw(canvas: Canvas, session: GameSession, me: Ship?, input: ShipInput, time: Float) {
        val w = canvas.width.toFloat()

        orbitBtnVisible = me != null && me.inOrbit
        if (me != null) {
            layout(canvas.width, canvas.height, me.loadout)
            drawBars(canvas, me)
            drawRadar(canvas, session, me, time)
            drawButtons(canvas, session, me, input, time)
            if (me.inOrbit) drawLeaveOrbit(canvas, time)
        }
        drawStatusLine(canvas, session, me, w)
        drawOverlays(canvas, session, w, canvas.height.toFloat())
    }

    private fun drawBars(canvas: Canvas, me: Ship) {
        val x = dp(18f)
        var y = dp(20f)
        val bw = dp(150f)
        val bh = dp(9f)

        fun bar(label: String, frac: Float, color: Int) {
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = dp(9f)
            paint.color = Palette.TEXT_DIM
            canvas.drawText(label, x, y + bh - dp(1f), paint)
            val bx = x + dp(34f)
            paint.color = color
            paint.alpha = 255
            canvas.drawRect(bx, y, bx + bw * frac.coerceIn(0f, 1f), y + bh, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.2f)
            paint.alpha = 160
            canvas.drawRect(bx, y, bx + bw, y + bh, paint)
            y += bh + dp(8f)
        }

        val hullFrac = me.hull / me.maxHull
        val hullColor = Color.rgb(
            (255 * (1f - hullFrac * 0.7f)).toInt().coerceIn(0, 255),
            (220 * hullFrac + 40).toInt().coerceIn(0, 255),
            70,
        )
        bar("HULL", hullFrac, hullColor)
        bar("ENRG", me.energy / me.maxEnergy, Palette.SELF)
    }

    private fun drawRadar(canvas: Canvas, session: GameSession, me: Ship, time: Float) {
        val world = session.world ?: return
        val r = dp(64f)
        val cx = canvas.width - r - dp(18f)
        val cy = r + dp(18f)
        val range = me.stats.radarRange
        val k = r / range

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.4f)
        paint.color = Palette.SELF
        paint.alpha = 120
        canvas.drawCircle(cx, cy, r, paint)
        paint.alpha = 45
        canvas.drawCircle(cx, cy, r * 0.5f, paint)
        // Sweep
        val a = time * 1.5f
        canvas.drawLine(cx, cy, cx + kotlin.math.cos(a) * r, cy + sin(a) * r, paint)

        paint.style = Paint.Style.FILL
        fun blip(px: Float, py: Float, color: Int, size: Float) {
            val dx = (px - me.pos.x) * k
            val dy = (py - me.pos.y) * k
            if (dx * dx + dy * dy > r * r) return
            paint.color = color
            paint.alpha = 230
            canvas.drawCircle(cx + dx, cy + dy, size, paint)
        }

        for (aRock in world.asteroids) blip(aRock.pos.x, aRock.pos.y, Palette.ASTEROID, dp(1.5f))
        for (s in world.stars) blip(s.pos.x, s.pos.y, Palette.STAR, dp(3f))
        for (p in world.planets) blip(p.pos.x, p.pos.y, Palette.PLANET, dp(2f))
        for (h in world.wormholes) blip(h.pos.x, h.pos.y, Palette.HOLE, dp(3f))
        for (shot in world.shots) {
            when (shot.kind) {
                ShotKind.MINE -> if (shot.ownerId == me.id) blip(shot.pos.x, shot.pos.y, Palette.MINE, dp(1.5f))
                ShotKind.MISSILE -> blip(shot.pos.x, shot.pos.y, Palette.MISSILE, dp(1.5f))
                else -> {}
            }
        }
        for (ship in world.ships) {
            if (ship.id == me.id || !ship.alive || ship.cloakOn) continue
            blip(ship.pos.x, ship.pos.y, Palette.ENEMY, dp(2.5f))
        }
        paint.color = Palette.SELF
        canvas.drawCircle(cx, cy, dp(2f), paint)
    }

    private fun drawButtons(canvas: Canvas, session: GameSession, me: Ship, input: ShipInput, time: Float) {
        val world = session.world
        for (b in buttons) {
            var color = Palette.SELF
            var active = false
            var sub = ""
            var usable = true
            var locked = false

            val wt = b.weapon
            if (wt != null) {
                val lvl = me.loadout.level(wt)
                val spec = weaponSpec(wt, lvl)
                if (spec.ammo >= 0) {
                    val left = me.ammo[wt] ?: 0
                    sub = "x$left"
                    if (left <= 0) {
                        color = Palette.ENEMY; usable = false
                    }
                } else {
                    sub = "${spec.energyCost.toInt()}e"
                    if (me.energy < spec.energyCost) usable = false
                    // Phaser lock indicator: something visible is in beam range.
                    locked = world != null && world.ships.any {
                        it.alive && it.id != me.id && !it.cloakOn &&
                            it.pos.dist(me.pos) <= spec.range
                    }
                    if (!locked) usable = false
                }
                active = wt in input.fireHeld
            }
            val dt = b.defense
            if (dt != null) {
                color = Palette.SHIELD
                active = when (dt) {
                    DefenseType.SHIELD -> input.shield
                    DefenseType.CLOAK -> input.cloak
                }
                if (me.energy < 2f) usable = false
            }

            if (active || locked) {
                paint.style = Paint.Style.FILL
                paint.color = if (locked && !active) Palette.BOLT else color
                paint.alpha = if (locked && !active) (40 + 25 * sin(time * 8f)).toInt().coerceIn(20, 70) else 60
                canvas.drawCircle(b.cx, b.cy, b.r, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.8f)
            paint.color = if (locked) Palette.BOLT else color
            paint.alpha = if (usable) 220 else 80
            canvas.drawCircle(b.cx, b.cy, b.r, paint)

            // Cooldown arc
            if (wt != null) {
                val lvl = me.loadout.level(wt)
                val spec = weaponSpec(wt, lvl)
                val cd = me.cooldown(wt)
                if (cd > 0f && spec.cooldown > 0f) {
                    paint.alpha = 255
                    paint.strokeWidth = dp(2.5f)
                    canvas.drawArc(
                        b.cx - b.r, b.cy - b.r, b.cx + b.r, b.cy + b.r,
                        -90f, 360f * (cd / spec.cooldown), false, paint,
                    )
                }
            }

            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(11f)
            paint.alpha = if (usable) 255 else 110
            canvas.drawText(b.label, b.cx, b.cy + dp(1f), paint)
            if (sub.isNotEmpty()) {
                paint.textSize = dp(8.5f)
                paint.alpha = 180
                canvas.drawText(sub, b.cx, b.cy + dp(12f), paint)
            }
        }
    }

    private fun drawLeaveOrbit(canvas: Canvas, time: Float) {
        val b = leaveBtn
        paint.style = Paint.Style.FILL
        paint.color = Palette.PLANET
        paint.alpha = (30 + 20 * sin(time * 4f)).toInt().coerceIn(15, 55)
        canvas.drawCircle(b.cx, b.cy, b.r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.alpha = 230
        canvas.drawCircle(b.cx, b.cy, b.r, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(10f)
        paint.alpha = 255
        canvas.drawText("LEAVE", b.cx, b.cy - dp(2f), paint)
        canvas.drawText("ORBIT", b.cx, b.cy + dp(9f), paint)
    }

    private fun drawStatusLine(canvas: Canvas, session: GameSession, me: Ship?, w: Float) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(10f)
        paint.color = Palette.TEXT_DIM
        paint.alpha = 220
        val orbiting = if (me?.inOrbit == true) "  ·  IN ORBIT: REPAIRING" else ""
        val status = when (session.mode) {
            GameSession.Mode.SINGLE -> "PRACTICE ARENA  ·  KILLS ${me?.kills ?: 0}$orbiting"
            GameSession.Mode.HOST -> {
                val s = session.server
                "HOSTING ${s?.localAddress}:${s?.port}  ·  SHIPS ${session.world?.ships?.size ?: 0}  ·  KILLS ${me?.kills ?: 0}$orbiting"
            }
            GameSession.Mode.CLIENT -> "BATTLE LINK  ·  SHIPS ${session.world?.ships?.size ?: 0}  ·  KILLS ${me?.kills ?: 0}$orbiting"
        }
        canvas.drawText(status, w / 2f, dp(18f), paint)
    }

    private fun drawOverlays(canvas: Canvas, session: GameSession, w: Float, h: Float) {
        val client = session.client
        val text = when {
            client?.error != null -> "LINK FAILED" to client.error!!
            client?.connectionLost == true -> "CONNECTION LOST" to "tap to return to base"
            session.gameOver -> "SHIP DESTROYED" to "hull integrity lost — tap to return to base"
            session.world == null -> "SEARCHING FOR HOST…" to "same wi-fi network required"
            else -> null
        } ?: return

        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.alpha = 150
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.color = if (session.gameOver) Palette.ENEMY else Palette.SELF
        paint.alpha = 255
        paint.textSize = dp(26f)
        canvas.drawText(text.first, w / 2f, h / 2f - dp(6f), paint)
        paint.color = Palette.TEXT_DIM
        paint.textSize = dp(11f)
        canvas.drawText(text.second, w / 2f, h / 2f + dp(16f), paint)
    }
}
