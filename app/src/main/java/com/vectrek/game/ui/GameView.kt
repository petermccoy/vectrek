package com.vectrek.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.vectrek.game.engine.DefenseType
import com.vectrek.game.engine.ShipInput
import com.vectrek.game.engine.Vec2
import kotlin.math.min

/**
 * The play surface: owns the game loop thread, translates touches into pilot
 * input (touch the void to turn-and-burn toward it; touch a HUD button to
 * fire or toggle a defense) and renders everything.
 */
@SuppressLint("ViewConstructor")
class GameView(
    context: Context,
    private val session: GameSession,
    private val onExit: () -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private val renderer = Renderer()
    private val hud = Hud(resources.displayMetrics.density)

    /** Written by the UI thread (touch), snapshotted by the game thread. */
    private val localInput = ShipInput()
    private val frameInput = ShipInput()
    private var steerPointerId = -1
    private var steerScreen: Vec2? = null
    private val buttonPointers = HashMap<Int, Hud.Btn>()
    // The tap is momentary but input packets are lossy state, so hold the
    // leave-orbit flag up briefly; extra trues after release are no-ops.
    @Volatile private var leaveOrbitUntil = 0L

    private var thread: Thread? = null
    @Volatile private var running = false

    init {
        holder.addCallback(this)
        keepScreenOn = true
        isFocusable = true
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        running = true
        thread = Thread(this, "vectrek-game").also { it.start() }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(h: SurfaceHolder) {
        running = false
        thread?.join(1000)
        thread = null
    }

    override fun run() {
        var last = System.nanoTime()
        var acc = 0f
        while (running) {
            val now = System.nanoTime()
            acc += min((now - last) / 1e9f, 0.1f)
            last = now

            synchronized(localInput) {
                frameInput.copyFrom(localInput)
                frameInput.steer = steerScreen?.let { renderer.screenToWorld(it) }
                frameInput.thrust = steerScreen != null
                frameInput.leaveOrbit = System.currentTimeMillis() < leaveOrbitUntil
            }

            var steps = 0
            while (acc >= DT && steps < 4) {
                session.update(DT, frameInput)
                acc -= DT
                steps++
            }
            if (steps == 4) acc = 0f

            val canvas = holder.lockCanvas() ?: continue
            try {
                val time = (System.currentTimeMillis() % 3_600_000L) / 1000f
                val world = session.world
                if (world == null) {
                    renderer.drawWorld(canvas, EMPTY_WORLD, null, time)
                    hud.draw(canvas, session, null, frameInput, time)
                } else {
                    synchronized(world) {
                        val me = session.playerShip()
                        renderer.drawWorld(canvas, world, me, time)
                        hud.draw(canvas, session, me, frameInput, time)
                    }
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (session.finished) {
                    post { onExit() }
                    return true
                }
                val idx = e.actionIndex
                val pid = e.getPointerId(idx)
                val x = e.getX(idx)
                val y = e.getY(idx)
                val btn = hud.hitTest(x, y)
                synchronized(localInput) {
                    if (btn != null) {
                        buttonPointers[pid] = btn
                        btn.weapon?.let { localInput.fireHeld += it }
                        if (btn.leaveOrbit) leaveOrbitUntil = System.currentTimeMillis() + 400
                        when (btn.defense) {
                            DefenseType.SHIELD -> localInput.shield = !localInput.shield
                            DefenseType.CLOAK -> localInput.cloak = !localInput.cloak
                            null -> {}
                        }
                    } else if (steerPointerId == -1) {
                        steerPointerId = pid
                        steerScreen = Vec2(x, y)
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> synchronized(localInput) {
                for (i in 0 until e.pointerCount) {
                    if (e.getPointerId(i) == steerPointerId) {
                        steerScreen = Vec2(e.getX(i), e.getY(i))
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = e.getPointerId(e.actionIndex)
                synchronized(localInput) {
                    buttonPointers.remove(pid)?.weapon?.let { localInput.fireHeld -= it }
                    if (pid == steerPointerId) {
                        steerPointerId = -1
                        steerScreen = null
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> synchronized(localInput) {
                buttonPointers.clear()
                localInput.fireHeld.clear()
                steerPointerId = -1
                steerScreen = null
            }
        }
        return true
    }

    companion object {
        private const val DT = 1f / 60f
        /** Placeholder starfield while a client waits for the host's seed. */
        private val EMPTY_WORLD = com.vectrek.game.engine.GameWorld(0L)
    }
}
