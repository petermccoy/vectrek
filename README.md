# VecTrek

A 2D space combat simulator for Android with old-school vector graphics,
inspired by Asteroids, Omega Race and Netrek. Turn-and-burn touch controls,
a points-based ship outfitting system, a hazard-littered arena with real
gravity, and local-network multiplayer between phones.

Pure Kotlin, zero external dependencies — just the Android framework
(SurfaceView/Canvas rendering, NSD + UDP networking).

## How it plays

- **Flying**: touch anywhere in space and your ship turns toward that point
  and thrusts while you hold. Release to coast on inertia (thrust costs
  energy; drifting is free).
- **The arena**: a large but bounded battlefield (8000×6000). Hitting the
  boundary bounces you — hard impacts damage the hull. The board is littered
  with slowly drifting asteroids, suns with 1–4 orbiting planets each, and
  paired wormholes. Suns, planets and wormholes exert real gravity on ships
  *and* shots. Touching a sun is instant death. Falling into a wormhole
  flings you out of its twin somewhere else on the board, velocity intact —
  shots ride wormholes too.
- **Planets**: brush against one and your ship is captured into a parking
  orbit where the hull slowly repairs and planetary stores refill your
  ammunition — the only way to fix damage or rearm. A LEAVE ORBIT button
  appears to cast off. You can still fire (and be hit) while parked.
- **Energy**: one shared pool feeds thrust, the energy weapon, shields and
  cloak. It regenerates slowly toward its maximum. Manage it or drift.
- **Hull**: damage only mends in planetary orbit. When the hull reaches
  zero, the ship is destroyed and the game is over.

## Weapons & defenses

| System | Type | Limits |
|---|---|---|
| Cannon | fast projectile | limited ammo |
| Phaser | lock-on beam: instantly hits every visible ship in range, damage split among them — and on each target, half burns hull, half drains their energy banks | unlimited, costs energy per shot, slow recharge |
| Missiles | guided, homes on nearest visible ship | limited ammo |
| Mines | dropped behind, proximity-triggered blast | limited ammo |
| Shields | soaks most of a hit, energy pays for what it absorbs | drains energy while raised |
| Cloak | invisible to ships, radar and missile seekers | drains energy; firing decloaks you |

## Outfitting (the points system)

Every ship is built from a fixed budget of **20 points** in the outfitting
bay. Weapons and defenses each have a base cost plus optional MK-level
upgrades (more damage/ammo, cheaper shots, lower defense drain, stronger
soak). Points can also go into core systems:

- **Energy cells** (2 pt/level): bigger pool, faster regeneration
- **Engines** (1 pt/level): more thrust and turn rate
- **Radar** (1 pt/level): longer minimap sensor range

Hull design (SABER / CRUISER / TALON) is a free cosmetic pick.

You can't afford everything — a cloaked mine-layer, a shielded brawler and
a fast radar scout are all valid 20-point ships.

## Multiplayer (LAN)

Two or more phones on the **same Wi-Fi network**:

1. One player taps **HOST BATTLE**. The game is advertised via mDNS/NSD and
   the host's IP:port shows at the top of their screen.
2. Others tap **JOIN BATTLE** — discovered games appear automatically, or
   type the host's IP manually if the network filters multicast.

The host phone is authoritative: it simulates the world, remote phones send
pilot inputs (~30/s) and receive personalized snapshots (~20/s) over UDP.
The arena scenery is generated deterministically from a shared seed, so only
dynamic state crosses the wire. Cloaked ships are omitted from other
players' snapshots entirely — packet sniffing won't reveal them.

**PRACTICE ARENA** is single player against respawning AI drones — good for
learning the ship before a battle.

## Building

Requires an Android SDK (API 34) and JDK 17+. Point `ANDROID_HOME` (or
`local.properties`) at the SDK, then:

```
./gradlew assembleDebug
```

APK lands in `app/build/outputs/apk/debug/`. Min SDK is 26 (Android 8.0).

## Code map

```
app/src/main/java/com/vectrek/game/
├── engine/          # pure game logic, no Android UI types
│   ├── Vec2.kt         vector math
│   ├── Loadout.kt      points system, weapon/defense specs, derived ship stats
│   ├── Entity.kt       Ship (energy/hull/weapons/defenses), Shot
│   ├── Scenery.kt      asteroids, barriers, stars, planets, black holes
│   ├── GameWorld.kt    world generation, gravity, stepping, collisions
│   └── AIController.kt practice-drone brain
├── net/             # LAN multiplayer
│   ├── Protocol.kt     JSON-over-UDP messages, snapshot encode/apply
│   ├── NsdHelper.kt    mDNS game discovery
│   ├── GameServer.kt   authoritative host
│   └── GameClient.kt   input sender + world mirror
└── ui/              # all programmatic UI, no XML layouts
    ├── GameSession.kt  wires world + drones/server/client per mode
    ├── GameView.kt     game loop thread + touch controls
    ├── Renderer.kt     vector-style world rendering
    ├── Hud.kt          bars, radar, weapon/defense buttons, overlays
    └── *Activity.kt    menu, outfitting, join, game screens
```

## Roadmap ideas

- Sound (engine rumble, weapon fire, explosions)
- Respawn/round scoring options for longer multiplayer sessions
- Wi-Fi Direct / Bluetooth transport for play without a shared network
- More hazards: pulsars, drifting debris, wormhole pairs
