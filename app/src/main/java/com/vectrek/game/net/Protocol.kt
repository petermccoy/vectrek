package com.vectrek.game.net

import com.vectrek.game.engine.GameWorld
import com.vectrek.game.engine.Loadout
import com.vectrek.game.engine.Beam
import com.vectrek.game.engine.Ship
import com.vectrek.game.engine.ShipInput
import com.vectrek.game.engine.Shot
import com.vectrek.game.engine.ShotKind
import com.vectrek.game.engine.Vec2
import com.vectrek.game.engine.WeaponType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire format: single JSON object per UDP datagram. The host is authoritative;
 * clients send pilot inputs and receive personalized world snapshots (cloaked
 * enemy ships are simply omitted from what a client is told about).
 *
 * client -> host:  hello { name, lo }   (repeated until welcomed)
 *                  in    { sx, sy, so, th, f[], sh, cl, lo }
 *                  bye   {}
 * host -> client:  wel   { id, seed, w, h }
 *                  snap  { tk, you, ships[], shots[], rocks[], booms[], beams[] }
 *
 * Scenery is rebuilt from the seed; asteroids drift, so their state rides
 * along in every snapshot (index-aligned with the generated list).
 */
object Protocol {
    const val DEFAULT_PORT = 47810
    const val SERVICE_TYPE = "_vectrek._udp."
    const val MAX_PACKET = 60000

    fun hello(name: String, loadout: Loadout): JSONObject =
        JSONObject().put("t", "hello").put("name", name).put("lo", loadout.toJson())

    fun bye(): JSONObject = JSONObject().put("t", "bye")

    fun welcome(id: Int, seed: Long, w: Float, h: Float): JSONObject =
        JSONObject().put("t", "wel").put("id", id).put("seed", seed)
            .put("w", w.toDouble()).put("h", h.toDouble())

    fun input(input: ShipInput): JSONObject {
        val o = JSONObject().put("t", "in")
        val steer = input.steer
        o.put("so", steer != null)
        if (steer != null) {
            o.put("sx", steer.x.toDouble()).put("sy", steer.y.toDouble())
        }
        o.put("th", input.thrust)
        val f = JSONArray()
        for (w in input.fireHeld) f.put(w.ordinal)
        o.put("f", f)
        o.put("sh", input.shield).put("cl", input.cloak)
        o.put("lo", input.leaveOrbit)
        return o
    }

    fun readInput(o: JSONObject, into: ShipInput) {
        into.steer = if (o.optBoolean("so")) {
            Vec2(o.optDouble("sx").toFloat(), o.optDouble("sy").toFloat())
        } else null
        into.thrust = o.optBoolean("th")
        into.fireHeld.clear()
        val f = o.optJSONArray("f")
        if (f != null) {
            for (i in 0 until f.length()) {
                val ord = f.optInt(i, -1)
                if (ord in WeaponType.entries.indices) into.fireHeld += WeaponType.entries[ord]
            }
        }
        into.shield = o.optBoolean("sh")
        into.cloak = o.optBoolean("cl")
        into.leaveOrbit = o.optBoolean("lo")
    }

    /** Snapshot personalized for [forShipId]; cloaked enemies are omitted. */
    fun snapshot(
        world: GameWorld,
        forShipId: Int,
        alive: Boolean,
        booms: List<Triple<Float, Float, Float>>,
        beams: List<Beam>,
    ): JSONObject {
        val o = JSONObject().put("t", "snap").put("tk", world.tick).put("you", alive)
        val ships = JSONArray()
        for (s in world.ships) {
            if (!s.alive) continue
            if (s.cloakOn && s.id != forShipId) continue
            ships.put(shipJson(s, s.id == forShipId))
        }
        o.put("ships", ships)
        val shots = JSONArray()
        for (s in world.shots) {
            shots.put(
                JSONArray().put(s.id).put(s.kind.ordinal).put(s.ownerId)
                    .put(s.pos.x.toDouble()).put(s.pos.y.toDouble())
                    .put(s.vel.x.toDouble()).put(s.vel.y.toDouble())
            )
        }
        o.put("shots", shots)
        val rocks = JSONArray()
        for (a in world.asteroids) {
            rocks.put(
                JSONArray().put(a.pos.x.toDouble()).put(a.pos.y.toDouble())
                    .put(a.vel.x.toDouble()).put(a.vel.y.toDouble())
            )
        }
        o.put("rocks", rocks)
        val bo = JSONArray()
        for ((x, y, size) in booms) {
            bo.put(JSONArray().put(x.toDouble()).put(y.toDouble()).put(size.toDouble()))
        }
        o.put("booms", bo)
        val be = JSONArray()
        for (b in beams) {
            be.put(
                JSONArray().put(b.from.x.toDouble()).put(b.from.y.toDouble())
                    .put(b.to.x.toDouble()).put(b.to.y.toDouble())
            )
        }
        o.put("beams", be)
        return o
    }

    private fun shipJson(s: Ship, own: Boolean): JSONObject {
        val o = JSONObject()
            .put("id", s.id).put("n", s.name)
            .put("x", s.pos.x.toDouble()).put("y", s.pos.y.toDouble())
            .put("vx", s.vel.x.toDouble()).put("vy", s.vel.y.toDouble())
            .put("h", s.heading.toDouble())
            .put("hl", s.hull.toDouble()).put("mh", s.maxHull.toDouble())
            .put("en", s.energy.toDouble()).put("me", s.maxEnergy.toDouble())
            .put("sh", s.shieldOn).put("cl", s.cloakOn).put("th", s.thrusting)
            .put("ob", s.inOrbit)
            .put("st", s.hullStyle)
            .put("k", s.kills)
        if (own) {
            val am = JSONObject()
            for ((w, n) in s.ammo) am.put(w.name, n)
            o.put("am", am)
        }
        return o
    }

    /** Apply a snapshot to a client's mirror world. Caller holds the world lock. */
    fun applySnapshot(o: JSONObject, world: GameWorld, myId: Int) {
        val seenShips = HashSet<Int>()
        val ships = o.optJSONArray("ships") ?: JSONArray()
        for (i in 0 until ships.length()) {
            val js = ships.getJSONObject(i)
            val id = js.getInt("id")
            seenShips += id
            val ship = world.ships.find { it.id == id }
                ?: world.addMirrorShip(id, js.optString("n", "?"))
            ship.name = js.optString("n", ship.name)
            ship.pos = Vec2(js.getDouble("x").toFloat(), js.getDouble("y").toFloat())
            ship.vel = Vec2(js.getDouble("vx").toFloat(), js.getDouble("vy").toFloat())
            ship.heading = js.getDouble("h").toFloat()
            ship.hull = js.getDouble("hl").toFloat()
            ship.maxHull = js.getDouble("mh").toFloat()
            ship.energy = js.getDouble("en").toFloat()
            ship.maxEnergy = js.getDouble("me").toFloat()
            ship.shieldOn = js.getBoolean("sh")
            ship.cloakOn = js.getBoolean("cl")
            ship.thrusting = js.getBoolean("th")
            ship.netOrbiting = js.optBoolean("ob")
            ship.hullStyle = js.optInt("st")
            ship.kills = js.optInt("k")
            if (id == myId) {
                val am = js.optJSONObject("am")
                if (am != null) {
                    for (w in WeaponType.entries) {
                        if (am.has(w.name)) ship.ammo[w] = am.getInt(w.name)
                    }
                }
            }
        }
        world.ships.removeAll { it.id !in seenShips }

        val seenShots = HashSet<Int>()
        val shots = o.optJSONArray("shots") ?: JSONArray()
        for (i in 0 until shots.length()) {
            val ja = shots.getJSONArray(i)
            val id = ja.getInt(0)
            seenShots += id
            val existing = world.shots.find { it.id == id }
            val pos = Vec2(ja.getDouble(3).toFloat(), ja.getDouble(4).toFloat())
            val vel = Vec2(ja.getDouble(5).toFloat(), ja.getDouble(6).toFloat())
            if (existing != null) {
                existing.pos = pos
                existing.vel = vel
                if (vel.lengthSq() > 1f) existing.heading = vel.angle()
            } else {
                val kind = ShotKind.entries[ja.getInt(1)]
                val shot = Shot(id, kind, ja.getInt(2), pos, vel, 0f, 999f)
                shot.age = Shot.MINE_ARM_TIME + 1f  // render mines as armed
                world.shots += shot
            }
        }
        world.shots.removeAll { it.id !in seenShots }

        val rocks = o.optJSONArray("rocks") ?: JSONArray()
        val n = minOf(rocks.length(), world.asteroids.size)
        for (i in 0 until n) {
            val ja = rocks.getJSONArray(i)
            val a = world.asteroids[i]
            a.pos = Vec2(ja.getDouble(0).toFloat(), ja.getDouble(1).toFloat())
            a.vel = Vec2(ja.getDouble(2).toFloat(), ja.getDouble(3).toFloat())
        }

        val booms = o.optJSONArray("booms") ?: JSONArray()
        for (i in 0 until booms.length()) {
            val b = booms.getJSONArray(i)
            world.addExplosion(
                Vec2(b.getDouble(0).toFloat(), b.getDouble(1).toFloat()),
                b.getDouble(2).toFloat(),
            )
        }
        val beams = o.optJSONArray("beams") ?: JSONArray()
        for (i in 0 until beams.length()) {
            val b = beams.getJSONArray(i)
            world.beams += Beam(
                Vec2(b.getDouble(0).toFloat(), b.getDouble(1).toFloat()),
                Vec2(b.getDouble(2).toFloat(), b.getDouble(3).toFloat()),
            )
        }
    }
}
