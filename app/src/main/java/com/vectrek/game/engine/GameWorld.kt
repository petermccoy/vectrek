package com.vectrek.game.engine

import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The whole battlefield: a large bounded rectangle littered with drifting
 * asteroids, suns (with orbiting planets) and paired wormholes. Suns, planets
 * and wormholes pull on ships and shots; suns kill on contact, wormholes
 * fling you out of their twin, and planets capture ships into a repair orbit.
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
    val stars = mutableListOf<Star>()
    val planets = mutableListOf<Planet>()
    val wormholes = mutableListOf<Wormhole>()
    val explosions = mutableListOf<Explosion>()
    val beams = mutableListOf<Beam>()

    /** Events since last drain; the host relays these to clients. */
    val boomLog = mutableListOf<Explosion>()
    val beamLog = mutableListOf<Beam>()
    /** Ships destroyed since last drain (id -> killer id). */
    val deathLog = mutableListOf<Pair<Int, Int>>()

    var tick = 0L
    private var nextShotId = 1
    private var nextShipId = 1
    private val rnd = Random(seed)

    // ------------------------------------------------------------------
    // World generation (deterministic from seed; clients rebuild scenery
    // locally, then keep asteroids in sync from snapshots)
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

        // Suns with 1-4 orbiting planets each
        repeat(2) {
            val starRadius = rand(40f, 55f)
            val planetCount = 1 + rnd.nextInt(4)
            val maxOrbit = starRadius + 90f + (planetCount - 1) * 62f + 20f
            val p = place(maxOrbit + 60f) ?: return@repeat
            val star = Star(p, starRadius, 1.9e7f)
            stars += star
            for (i in 0 until planetCount) {
                val orbitR = starRadius + 90f + i * 62f + rand(0f, 16f)
                planets += Planet(
                    star, orbitR, rand(0f, TWO_PI),
                    (if (rnd.nextBoolean()) 1f else -1f) * rand(0.12f, 0.3f),
                    rand(14f, 24f), 1.2e6f,
                )
            }
        }

        // Paired wormholes: fall into one, get flung out of the other
        repeat(2) {
            val p = place(300f) ?: return@repeat
            wormholes += Wormhole(p, 27f, 3.0e7f)
        }

        // Slowly drifting asteroid field
        repeat(54) {
            val r = rand(18f, 45f)
            val p = place(r + 30f) ?: return@repeat
            val drift = Vec2.fromAngle(rand(0f, TWO_PI), rand(8f, 35f))
            asteroids += Asteroid(p, r, Asteroid.makeShape(rnd), drift)
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
        if (wormholes.any { it.pos.dist(p) < it.horizon + clear + 250f }) return false
        if (planets.any { it.pos.dist(p) < it.radius + clear }) return false
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
        updateAsteroids(dt)

        for (s in ships) {
            if (!s.alive) continue
            if (s.orbitPlanet == null) s.vel += gravityAt(s.pos) * dt
            s.update(this, dt)
            if (s.orbitPlanet == null) {
                collideShipWithWorld(s)
                if (s.alive) rideWormholes(s)
            }
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

        updateEffects(dt)
    }

    /** Client-side smoothing between snapshots: dead-reckon, no game rules. */
    fun clientAdvance(dt: Float) {
        boomLog.clear()
        beamLog.clear()
        deathLog.clear()
        for (p in planets) p.update(dt)
        for (a in asteroids) a.pos += a.vel * dt
        for (s in ships) if (s.alive) s.pos += s.vel * dt
        for (s in shots) s.pos += s.vel * dt
        updateEffects(dt)
    }

    private fun updateEffects(dt: Float) {
        for (e in explosions) e.age += dt
        explosions.removeAll { it.done }
        for (b in beams) b.age += dt
        beams.removeAll { it.done }
    }

    private fun updateAsteroids(dt: Float) {
        for (a in asteroids) {
            a.pos += a.vel * dt
            // Arena walls
            if (a.pos.x < a.radius && a.vel.x < 0f) a.vel = Vec2(-a.vel.x, a.vel.y)
            if (a.pos.x > width - a.radius && a.vel.x > 0f) a.vel = Vec2(-a.vel.x, a.vel.y)
            if (a.pos.y < a.radius && a.vel.y < 0f) a.vel = Vec2(a.vel.x, -a.vel.y)
            if (a.pos.y > height - a.radius && a.vel.y > 0f) a.vel = Vec2(a.vel.x, -a.vel.y)
            // Bounce off suns, planets, wormholes
            for (s in stars) bounceAsteroidOff(a, s.pos, s.radius)
            for (p in planets) bounceAsteroidOff(a, p.pos, p.radius)
            for (w in wormholes) bounceAsteroidOff(a, w.pos, w.horizon)
        }
        // Gentle asteroid-vs-asteroid separation (equal-mass elastic bounce)
        for (i in asteroids.indices) {
            for (j in i + 1 until asteroids.size) {
                val a = asteroids[i]
                val b = asteroids[j]
                val minDist = a.radius + b.radius
                if (a.pos.distSq(b.pos) >= minDist * minDist) continue
                val n = (b.pos - a.pos).normalized()
                val overlap = minDist - a.pos.dist(b.pos)
                a.pos -= n * (overlap / 2f)
                b.pos += n * (overlap / 2f)
                val va = a.vel.dot(n)
                val vb = b.vel.dot(n)
                if (va - vb > 0f) {
                    a.vel += n * (vb - va)
                    b.vel += n * (va - vb)
                }
            }
        }
    }

    private fun bounceAsteroidOff(a: Asteroid, c: Vec2, r: Float) {
        val minDist = r + a.radius
        if (a.pos.distSq(c) >= minDist * minDist) return
        val n = (a.pos - c).normalized()
        a.pos = c + n * minDist
        val vn = a.vel.dot(n)
        if (vn < 0f) a.vel -= n * (2f * vn)
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
        for (w in wormholes) pull(w.pos, w.mass)
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
    // Collisions & transits
    // ------------------------------------------------------------------

    private fun collideShipWithWorld(s: Ship) {
        // Bounded arena walls: bounce, with damage on hard impacts.
        if (s.pos.x < s.radius) bounceShip(s, Vec2(1f, 0f), Vec2(s.radius, s.pos.y))
        if (s.pos.x > width - s.radius) bounceShip(s, Vec2(-1f, 0f), Vec2(width - s.radius, s.pos.y))
        if (s.pos.y < s.radius) bounceShip(s, Vec2(0f, 1f), Vec2(s.pos.x, s.radius))
        if (s.pos.y > height - s.radius) bounceShip(s, Vec2(0f, -1f), Vec2(s.pos.x, height - s.radius))

        for (a in asteroids) collideShipWithCircle(s, a.pos, a.radius)

        for (star in stars) {
            if (s.pos.dist(star.pos) < star.radius + s.radius * 0.5f) {
                // Flying into a sun is not survivable.
                s.hull = 0f
                s.alive = false
                onShipDestroyed(s, -1)
                return
            }
        }

        // Brushing a planet captures the ship into a repair orbit.
        if (s.orbitCooldown <= 0f) {
            for (p in planets) {
                if (s.pos.dist(p.pos) < p.radius + s.radius + 6f) {
                    s.enterOrbit(p)
                    return
                }
            }
        }
    }

    /** Fall into a wormhole, get flung out of its twin. */
    private fun rideWormholes(s: Ship) {
        if (s.wormholeCooldown > 0f || wormholes.size < 2) return
        for (hole in wormholes) {
            if (s.pos.dist(hole.pos) >= hole.horizon + s.radius * 0.5f) continue
            val exits = wormholes.filter { it !== hole }
            val exit = exits[(s.id + tick).toInt().mod(exits.size)]
            addExplosion(s.pos, 30f)
            var dir = s.vel.normalized()
            if (s.vel.length() < 20f) dir = (s.pos - hole.pos).normalized()
            s.pos = exit.pos + dir * (exit.horizon + s.radius + 40f)
            s.wormholeCooldown = 1.5f
            addExplosion(s.pos, 30f)
            return
        }
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

    private fun impactDamage(s: Ship, impactSpeed: Float) {
        if (impactSpeed > 120f) {
            s.takeDamage(this, (impactSpeed - 120f) * 0.06f)
            addExplosion(s.pos, 20f)
        }
    }

    /** @return true if the shot died on scenery (or left through a wormhole). */
    private fun collideShotWithWorld(shot: Shot): Boolean {
        val p = shot.pos
        var hit = p.x < 0f || p.x > width || p.y < 0f || p.y > height
        if (!hit) hit = asteroids.any { it.pos.dist(p) < it.radius + shot.radius }
        if (!hit) hit = planets.any { it.pos.dist(p) < it.radius + shot.radius }
        if (!hit) hit = stars.any { it.pos.dist(p) < it.radius + shot.radius }
        if (!hit) {
            for (hole in wormholes) {
                if (p.dist(hole.pos) >= hole.horizon) continue
                if (shot.kind == ShotKind.MINE || wormholes.size < 2) {
                    shot.alive = false  // swallowed
                    return true
                }
                // Shots ride wormholes too.
                val exits = wormholes.filter { it !== hole }
                val exit = exits[(shot.id + tick).toInt().mod(exits.size)]
                val dir = if (shot.vel.length() > 20f) shot.vel.normalized()
                    else (shot.pos - hole.pos).normalized()
                shot.pos = exit.pos + dir * (exit.horizon + shot.radius + 30f)
                return false
            }
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
                val kind = if (w == WeaponType.PROJECTILE) ShotKind.SLUG else ShotKind.MISSILE
                val pos = owner.pos + dir * (owner.radius + 8f)
                shots += Shot(nextShotId++, kind, owner.id, pos, owner.vel + dir * spec.speed, spec.damage, spec.life)
            }
        }
    }

    /**
     * Phaser: instantly locks every visible hostile in range and splits the
     * beam (and its damage) among them. @return false if nothing was in range.
     */
    fun fireBeam(owner: Ship, spec: WeaponSpec): Boolean {
        val targets = ships.filter {
            it.alive && it.id != owner.id && !it.cloakOn &&
                it.pos.dist(owner.pos) <= spec.range
        }
        if (targets.isEmpty()) return false
        val each = spec.damage / targets.size
        for (t in targets) {
            val beam = Beam(owner.pos, t.pos)
            beams += beam
            beamLog += beam
            addExplosion(t.pos, 22f)
            t.takeDamage(this, each, owner.id)
        }
        return true
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
                ship.vel += (ship.pos - mine.pos).normalized() * 145f * falloff
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
