package com.vectrek.game.engine

import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The whole battlefield: a large bounded rectangle littered with asteroids,
 * barriers, stars (with orbiting planets) and black holes. Stars, planets and
 * black holes pull on ships and shots.
 *
 * The same class serves three roles:
 *  - single player: simulated locally via [step]
 *  - multiplayer host: simulated locally, snapshotted to clients
 *  - multiplayer client: a render mirror updated from snapshots and smoothed
 *    between them via [clientAdvance]
 */
class GameWorld(val seed: Long, val width: Float = 8000f, val height: Float = 6000f) {

    val ships = mutableListOf<Ship>()
    val shots = mutableListOf<Shot>()
    val asteroids = mutableListOf<Asteroid>()
    val barriers = mutableListOf<Barrier>()
    val stars = mutableListOf<Star>()
    val planets = mutableListOf<Planet>()
    val blackHoles = mutableListOf<BlackHole>()
    val explosions = mutableListOf<Explosion>()

    /** Explosions spawned since last drain; the host relays these to clients. */
    val boomLog = mutableListOf<Explosion>()
    /** Ships destroyed since last drain (id -> killer id). */
    val deathLog = mutableListOf<Pair<Int, Int>>()

    var tick = 0L
    private var nextShotId = 1
    private var nextShipId = 1
    private val rnd = Random(seed)

    // ------------------------------------------------------------------
    // World generation (deterministic from seed; clients rebuild scenery
    // locally so only dynamic state crosses the network)
    // ------------------------------------------------------------------

    fun generate() {
        val placed = mutableListOf<Pair<Vec2, Float>>()  // center, clearance radius

        fun fits(p: Vec2, clear: Float): Boolean {
            if (p.x < clear + 150f || p.x > width - clear - 150f) return false
            if (p.y < clear + 150f || p.y > height - clear - 150f) return false
            return placed.all { (q, c) -> p.dist(q) > clear + c + 130f }
        }

        fun place(clear: Float, tries: Int = 60): Vec2? {
            repeat(tries) {
                val p = Vec2(rand(200f, width - 200f), rand(200f, height - 200f))
                if (fits(p, clear)) {
                    placed += p to clear
                    return p
                }
            }
            return null
        }

        // Stars with orbiting planets
        repeat(2) {
            val maxOrbit = rand(320f, 480f)
            val p = place(maxOrbit + 60f) ?: return@repeat
            val star = Star(p, rand(80f, 105f), 2.4e7f)
            stars += star
            val planetCount = 1 + rnd.nextInt(2)
            for (i in 0 until planetCount) {
                val orbitR = maxOrbit * (0.55f + 0.45f * i / max(1, planetCount - 1))
                planets += Planet(
                    star, orbitR, rand(0f, TWO_PI),
                    (if (rnd.nextBoolean()) 1f else -1f) * rand(0.12f, 0.3f),
                    rand(24f, 38f), 2.0e6f,
                )
            }
        }

        // Black holes
        repeat(2) {
            val p = place(420f) ?: return@repeat
            blackHoles += BlackHole(p, 55f, 4.2e7f)
        }

        // Barriers (long thin walls)
        repeat(7) {
            val horizontal = rnd.nextBoolean()
            val len = rand(400f, 900f)
            val thick = rand(50f, 80f)
            val clear = len / 2f
            val p = place(clear) ?: return@repeat
            barriers += if (horizontal) {
                Barrier(p.x - len / 2f, p.y - thick / 2f, len, thick)
            } else {
                Barrier(p.x - thick / 2f, p.y - len / 2f, thick, len)
            }
        }

        // Asteroid field
        repeat(46) {
            val r = rand(30f, 85f)
            val p = place(r) ?: return@repeat
            asteroids += Asteroid(p, r, Asteroid.makeShape(rnd))
        }
    }

    private fun rand(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    /** A spawn location with breathing room from scenery and other ships. */
    fun findSpawnPoint(awayFrom: Vec2? = null): Vec2 {
        repeat(200) {
            val p = Vec2(rand(300f, width - 300f), rand(300f, height - 300f))
            if (!isClear(p, 200f)) return@repeat
            if (ships.any { it.alive && it.pos.dist(p) < 500f }) return@repeat
            if (awayFrom != null && p.dist(awayFrom) < 1200f) return@repeat
            return p
        }
        return Vec2(width / 2f, height / 2f)
    }

    private fun isClear(p: Vec2, clear: Float): Boolean {
        if (asteroids.any { it.pos.dist(p) < it.radius + clear }) return false
        if (stars.any { it.pos.dist(p) < it.radius + clear + 250f }) return false
        if (blackHoles.any { it.pos.dist(p) < it.horizon + clear + 350f }) return false
        if (planets.any { it.pos.dist(p) < it.radius + clear }) return false
        if (barriers.any {
                p.x > it.x - clear && p.x < it.x + it.w + clear &&
                    p.y > it.y - clear && p.y < it.y + it.h + clear
            }
        ) return false
        return true
    }

    fun addShip(name: String, loadout: Loadout, pos: Vec2? = null): Ship {
        val ship = Ship(nextShipId++, name, loadout, pos ?: findSpawnPoint())
        ships += ship
        return ship
    }

    /** Client mirrors create ships with ids handed out by the host. */
    fun addMirrorShip(id: Int, name: String): Ship {
        val ship = Ship(id, name, Loadout.DEFAULT, Vec2.ZERO)
        ships += ship
        return ship
    }

    // ------------------------------------------------------------------
    // Simulation
    // ------------------------------------------------------------------

    fun step(dt: Float) {
        tick++
        for (p in planets) p.update(dt)

        for (s in ships) {
            if (!s.alive) continue
            s.vel += gravityAt(s.pos) * dt
            s.update(this, dt)
            collideShipWithWorld(s)
        }

        val shotIter = shots.iterator()
        val newHits = mutableListOf<Shot>()  // mines detonated this step
        while (shotIter.hasNext()) {
            val shot = shotIter.next()
            if (shot.kind != ShotKind.MINE) shot.vel += gravityAt(shot.pos) * dt
            if (shot.kind == ShotKind.MISSILE) steerMissile(shot, dt)
            shot.update(this, dt)

            if (shot.life <= 0f) {
                if (shot.kind == ShotKind.MINE || shot.kind == ShotKind.MISSILE) newHits += shot
                shot.alive = false
                continue
            }
            if (collideShotWithWorld(shot)) continue

            if (shot.kind == ShotKind.MINE) {
                if (shot.armed && ships.any {
                        it.alive && it.id != shot.ownerId &&
                            it.pos.dist(shot.pos) < Shot.MINE_TRIGGER_RADIUS
                    }
                ) {
                    newHits += shot
                    shot.alive = false
                }
            } else {
                for (ship in ships) {
                    if (!ship.alive || ship.id == shot.ownerId) continue
                    if (ship.pos.dist(shot.pos) < ship.radius + shot.radius) {
                        ship.takeDamage(this, shot.damage, shot.ownerId)
                        addExplosion(shot.pos, 28f)
                        shot.alive = false
                        break
                    }
                }
            }
        }
        for (mine in newHits) detonate(mine)
        shots.removeAll { !it.alive }
        ships.removeAll { !it.alive }

        updateExplosions(dt)
    }

    /** Client-side smoothing between snapshots: dead-reckon, no game rules. */
    fun clientAdvance(dt: Float) {
        boomLog.clear()
        deathLog.clear()
        for (p in planets) p.update(dt)
        for (s in ships) if (s.alive) s.pos += s.vel * dt
        for (s in shots) s.pos += s.vel * dt
        updateExplosions(dt)
    }

    private fun updateExplosions(dt: Float) {
        for (e in explosions) e.age += dt
        explosions.removeAll { it.done }
    }

    private fun gravityAt(p: Vec2): Vec2 {
        var ax = 0f
        var ay = 0f
        fun pull(src: Vec2, mass: Float) {
            val dx = src.x - p.x
            val dy = src.y - p.y
            val d2 = max(dx * dx + dy * dy, 3600f)
            val d = sqrt(d2)
            val a = min(mass / d2, 900f)
            ax += dx / d * a
            ay += dy / d * a
        }
        for (s in stars) pull(s.pos, s.mass)
        for (pl in planets) pull(pl.pos, pl.mass)
        for (b in blackHoles) pull(b.pos, b.mass)
        return Vec2(ax, ay)
    }

    private fun steerMissile(shot: Shot, dt: Float) {
        val target = ships
            .filter { it.alive && it.id != shot.ownerId && !it.cloakOn }
            .minByOrNull { it.pos.distSq(shot.pos) }
            ?: return
        if (shot.pos.dist(target.pos) > 2200f) return
        val speed = max(shot.vel.length(), 60f)
        val desired = (target.pos - shot.pos).angle()
        val current = shot.vel.angle()
        val turn = angleDiff(desired, current).coerceIn(-2.6f * dt, 2.6f * dt)
        shot.vel = Vec2.fromAngle(current + turn, speed)
    }

    // ------------------------------------------------------------------
    // Collisions
    // ------------------------------------------------------------------

    private fun collideShipWithWorld(s: Ship) {
        // Bounded arena walls: bounce, with damage on hard impacts.
        if (s.pos.x < s.radius) bounceShip(s, Vec2(1f, 0f), Vec2(s.radius, s.pos.y))
        if (s.pos.x > width - s.radius) bounceShip(s, Vec2(-1f, 0f), Vec2(width - s.radius, s.pos.y))
        if (s.pos.y < s.radius) bounceShip(s, Vec2(0f, 1f), Vec2(s.pos.x, s.radius))
        if (s.pos.y > height - s.radius) bounceShip(s, Vec2(0f, -1f), Vec2(s.pos.x, height - s.radius))

        for (a in asteroids) collideShipWithCircle(s, a.pos, a.radius)
        for (p in planets) collideShipWithCircle(s, p.pos, p.radius)

        for (star in stars) {
            val d = s.pos.dist(star.pos)
            if (d < star.radius + s.radius) {
                // Stellar surface: continuous burn plus a hard shove out.
                val n = (s.pos - star.pos).normalized()
                s.pos = star.pos + n * (star.radius + s.radius)
                s.vel = n * max(s.vel.length() * 0.4f, 120f)
                s.takeDamage(this, 25f)
                addExplosion(s.pos, 30f)
                if (!s.alive) return
            }
        }
        for (b in blackHoles) {
            if (s.pos.dist(b.pos) < b.horizon + s.radius * 0.5f) {
                // Crossed the event horizon: nothing survives that.
                s.hull = 0f
                s.alive = false
                onShipDestroyed(s, -1)
                return
            }
        }
        for (bar in barriers) collideShipWithRect(s, bar)
    }

    private fun bounceShip(s: Ship, normal: Vec2, corrected: Vec2) {
        s.pos = corrected
        val vn = s.vel.dot(normal)
        if (vn < 0f) {
            s.vel -= normal * (1.6f * vn)  // restitution 0.6
            impactDamage(s, -vn)
        }
    }

    private fun collideShipWithCircle(s: Ship, c: Vec2, r: Float) {
        val minDist = r + s.radius
        if (s.pos.distSq(c) >= minDist * minDist) return
        val n = (s.pos - c).normalized()
        s.pos = c + n * minDist
        val vn = s.vel.dot(n)
        if (vn < 0f) {
            s.vel -= n * (1.6f * vn)
            impactDamage(s, -vn)
        }
    }

    private fun collideShipWithRect(s: Ship, b: Barrier) {
        val cx = s.pos.x.coerceIn(b.x, b.x + b.w)
        val cy = s.pos.y.coerceIn(b.y, b.y + b.h)
        val dx = s.pos.x - cx
        val dy = s.pos.y - cy
        val d2 = dx * dx + dy * dy
        if (d2 >= s.radius * s.radius) return
        val d = sqrt(max(d2, 1e-4f))
        val n = if (d > 1e-3f) Vec2(dx / d, dy / d) else Vec2(0f, -1f)
        s.pos = Vec2(cx, cy) + n * s.radius
        val vn = s.vel.dot(n)
        if (vn < 0f) {
            s.vel -= n * (1.6f * vn)
            impactDamage(s, -vn)
        }
    }

    private fun impactDamage(s: Ship, impactSpeed: Float) {
        if (impactSpeed > 140f) {
            s.takeDamage(this, (impactSpeed - 140f) * 0.06f)
            addExplosion(s.pos, 20f)
        }
    }

    /** @return true if the shot died on scenery. */
    private fun collideShotWithWorld(shot: Shot): Boolean {
        val p = shot.pos
        var hit = p.x < 0f || p.x > width || p.y < 0f || p.y > height
        if (!hit) hit = asteroids.any { it.pos.dist(p) < it.radius + shot.radius }
        if (!hit) hit = planets.any { it.pos.dist(p) < it.radius + shot.radius }
        if (!hit) hit = stars.any { it.pos.dist(p) < it.radius + shot.radius }
        if (!hit) hit = barriers.any {
            p.x > it.x - shot.radius && p.x < it.x + it.w + shot.radius &&
                p.y > it.y - shot.radius && p.y < it.y + it.h + shot.radius
        }
        if (!hit && blackHoles.any { it.pos.dist(p) < it.horizon }) {
            shot.alive = false  // swallowed silently
            return true
        }
        if (hit) {
            if (shot.kind == ShotKind.MINE) {
                detonate(shot)
            } else {
                addExplosion(shot.pos, 18f)
            }
            shot.alive = false
            return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // Weapons
    // ------------------------------------------------------------------

    fun spawnShot(owner: Ship, w: WeaponType, spec: WeaponSpec) {
        val dir = Vec2.fromAngle(owner.heading)
        when (w) {
            WeaponType.MINE -> {
                val pos = owner.pos - dir * (owner.radius + 22f)
                shots += Shot(nextShotId++, ShotKind.MINE, owner.id, pos, owner.vel * 0.25f, spec.damage, spec.life)
            }
            else -> {
                val kind = when (w) {
                    WeaponType.PROJECTILE -> ShotKind.SLUG
                    WeaponType.ENERGY -> ShotKind.BOLT
                    else -> ShotKind.MISSILE
                }
                val pos = owner.pos + dir * (owner.radius + 8f)
                shots += Shot(nextShotId++, kind, owner.id, pos, owner.vel + dir * spec.speed, spec.damage, spec.life)
            }
        }
    }

    private fun detonate(mine: Shot) {
        addExplosion(mine.pos, 60f)
        for (ship in ships) {
            if (!ship.alive) continue
            val d = ship.pos.dist(mine.pos)
            if (d < Shot.MINE_BLAST_RADIUS + ship.radius) {
                val falloff = 1f - (d / (Shot.MINE_BLAST_RADIUS + ship.radius)) * 0.6f
                ship.takeDamage(this, mine.damage * falloff, mine.ownerId)
                // Blast shove
                ship.vel += (ship.pos - mine.pos).normalized() * 180f * falloff
            }
        }
    }

    fun onShipDestroyed(ship: Ship, attackerId: Int) {
        addExplosion(ship.pos, 95f)
        deathLog += ship.id to attackerId
        if (attackerId >= 0) ships.find { it.id == attackerId }?.let { it.kills++ }
    }

    fun addExplosion(pos: Vec2, size: Float) {
        val e = Explosion(pos, size)
        explosions += e
        boomLog += e
    }
}
