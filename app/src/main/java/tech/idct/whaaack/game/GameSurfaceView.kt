package tech.idct.whaaack.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.Process
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner

/**
 * Hosts the game on a dedicated render thread.
 *
 * Frames are composed with [SurfaceHolder.lockHardwareCanvas], which returns a Canvas backed
 * by HWUI's GPU pipeline — draw calls are recorded into a display list and rasterised by the
 * GPU, never by the CPU, and never on the main thread. The main thread's only involvement is
 * forwarding touch coordinates, which it hands over through a lock-free queue in
 * [GameEngine].
 *
 * Shutdown ordering is the delicate part. The framework tears down the buffer queue as soon
 * as `surfaceDestroyed` returns, and destroying it while a buffer is still dequeued segfaults
 * inside EGL. Two things guard against that:
 *
 *  - [stopRendering] joins the render thread with no timeout — a bounded wait would
 *    reintroduce exactly the race it exists to close;
 *  - it is called from [onDetachedFromWindow] as well as `surfaceDestroyed`, so the thread is
 *    already stopped by the time the view is being taken apart.
 */
class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /**
     * [onHit] arrives on the render thread so the splat can be sounded the instant it is
     * simulated; it must do nothing that could stall a frame. Everything else is delivered
     * on the main thread.
     */
    interface Callbacks {
        fun onGameOver(result: GameEngine.Result)
        fun onHit(fruit: Fruit)
        fun onStrike(strikes: Int)

        /**
         * A live run was suspended without finishing — backgrounded, or the window became
         * too small to play in. Delivered on the main thread so the elapsed time can be
         * banked; the process may not survive to deliver [onGameOver].
         */
        fun onRunInterrupted(millisSurvived: Long)

        /** The End-run control was armed and is waiting for a confirming press. */
        fun onQuitArmed()
    }

    val engine = GameEngine()
    var callbacks: Callbacks? = null

    @Volatile
    private var assets: GameAssets? = null
    private val renderer = GameRenderer(resources.displayMetrics.density)

    @Volatile
    private var thread: RenderThread? = null

    @Volatile
    private var topInset = 0f

    @Volatile
    private var bottomInset = 0f

    /** Set by [startRun], consumed by the render thread at the top of a frame. */
    @Volatile
    private var pendingStart: Boolean? = null

    private var drainDeadlineNs = 0L

    @Volatile
    private var reportedOver = false

    /**
     * Stops the thread at ON_STOP rather than waiting for `surfaceDestroyed`.
     *
     * Leaving the game screen disposes the composition first, so `stopRendering` has normally
     * already run by the time the surface goes — but backgrounding *during a run* does not
     * dispose anything, and then `surfaceDestroyed` is the first thing to call it. That runs
     * on the main thread and blocks on an unbounded `join()` while the render thread may be
     * sitting inside `lockHardwareCanvas()` waiting on the buffer queue, plus up to 48ms of
     * drain. Under GPU pressure that is an unbounded main-thread block on the single most
     * common lifecycle transition in the app — an ANR shape, and the likeliest source of ANRs
     * in this codebase. ON_STOP arrives before the surface is destroyed, so by the time
     * `surfaceDestroyed` runs the thread is already joined and the drain window has passed.
     */
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            stopRendering()
        }
    }

    private var observedLifecycle: LifecycleOwner? = null

    init {
        holder.addCallback(this)
        // The renderer paints every pixel each frame, so an opaque surface lets the
        // compositor skip blending this layer against the window behind it.
        setZOrderOnTop(false)
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset = bars.top.toFloat()
            bottomInset = bars.bottom.toFloat()
            thread?.invalidateLayout()
            insets
        }

        engine.listener = object : GameEngine.Listener {
            override fun onHit(fruit: Fruit) {
                callbacks?.onHit(fruit)
            }

            override fun onStrike(strikes: Int) {
                // Hopped to the main thread: a strike drives haptics, and asking the system
                // vibrator for anything is a binder round trip that must not sit inside a
                // frame. A hit's audio stays inline because SoundPool.play does not block.
                post { callbacks?.onStrike(strikes) }
            }

            override fun onGameOver(result: GameEngine.Result) {
                if (reportedOver) return
                reportedOver = true
                post { callbacks?.onGameOver(result) }
            }
        }
    }

    /** Supplies the preloaded sprite sheet; safe to call before the surface exists. */
    fun attachAssets(assets: GameAssets) {
        this.assets = assets
        thread?.invalidateLayout()
    }

    /**
     * Queues a run. It is applied by the render thread rather than here even when one is
     * already alive: [GameEngine.start] rewrites `slots` and `splats`, which that thread
     * reads every frame, so doing it from the UI thread would be the same race that
     * [quitRun] exists to avoid.
     */
    fun startRun(ranked: Boolean) {
        reportedOver = false
        pendingStart = ranked
    }

    /**
     * A press of the End-run control. The first arms it, a second inside the arming window
     * confirms — see [GameEngine.requestQuit].
     */
    fun quitRun(): GameEngine.QuitPress {
        // Handled by the engine on the next frame rather than here: tearing the run down
        // from the UI thread would rewrite `slots` and `phase` under the render thread.
        return engine.requestQuit(System.nanoTime())
    }

    /**
     * Stops the render thread, joins it, and suspends the run's clock. Safe to call more
     * than once. Does not block for the buffer drain — see [awaitBufferDrain].
     */
    fun stopRendering() {
        val running = thread
        thread = null
        if (running == null) return
        running.shutdown()
        // Bank whatever was survived before the clock stops. A backgrounded process holding
        // ~4.6 MB of bitmaps is a routine kill on a 2 GB device, and the local best is only
        // otherwise written in onGameOver — so without this, a killed run loses the score as
        // well as the run. Reported after the thread is joined, so the engine is quiescent.
        val survived = engine.survivedMillisIfLive()
        if (survived > 0L) post { callbacks?.onRunInterrupted(survived) }
        // unlockCanvasAndPost hands the frame to HWUI's shared render thread, which finishes
        // it asynchronously, so the last buffer may still be dequeued for a few vsyncs after
        // our own thread has gone. Note when that window closes rather than sleeping through
        // it here: the only moment it has to have elapsed is before surfaceDestroyed returns.
        drainDeadlineNs = System.nanoTime() + HWUI_DRAIN_MS * 1_000_000L
        engine.pause(System.nanoTime())
    }

    /**
     * Blocks for whatever is left of the buffer-drain window, which is normally nothing.
     *
     * Leaving the game screen stops the thread at composition-dispose time, several frames
     * before the framework destroys the surface, so by the time it matters the window has
     * already passed and this returns immediately instead of dropping frames on the way out.
     */
    private fun awaitBufferDrain() {
        val remainingMs = (drainDeadlineNs - System.nanoTime()) / 1_000_000L
        drainDeadlineNs = 0L
        if (remainingMs > 0L) android.os.SystemClock.sleep(remainingMs)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // ACTION_POINTER_DOWN counts as well as ACTION_DOWN. Four fruit are up at once by the
        // seventh gear, so a second thumb landing while the first is still down is how the
        // game is meant to be played — taking only ACTION_DOWN dropped every one of those
        // taps on the floor and let the fruit expire into a strike.
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) {
            return true
        }
        // ...and the coordinates have to come from the pointer that went down, not from
        // whichever one happens to be at index 0.
        val pointer = event.actionIndex
        val x = event.getX(pointer)
        val y = event.getY(pointer)

        if (renderer.endRunRect.contains(x, y)) {
            // Arming is worth a sound: it is the only feedback that the press registered but
            // did not do the thing, and silence there reads as an unresponsive button.
            if (quitRun() == GameEngine.QuitPress.ARMED) callbacks?.onQuitArmed()
            return true
        }
        val tile = renderer.tileAt(x, y)
        if (tile >= 0) engine.postTap(tile)
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        findViewTreeLifecycleOwner()?.let { owner ->
            observedLifecycle = owner
            owner.lifecycle.addObserver(lifecycleObserver)
        }
    }

    override fun onDetachedFromWindow() {
        observedLifecycle?.lifecycle?.removeObserver(lifecycleObserver)
        observedLifecycle = null
        // Runs before the surface is torn down, which is the calmest moment to stop.
        stopRendering()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        readRootInsets()
        // A surface can be destroyed and rebuilt without the run ever ending — backgrounding
        // the app does exactly that — so the clock resumes from where it was suspended
        // instead of counting the absence as survived time.
        engine.resume(System.nanoTime())
        renderer.resetFrameClock()
        renderer.density = resources.displayMetrics.density
        requestFrameRate(holder)
        val t = RenderThread(holder)
        thread = t
        t.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Compose's AndroidView does not always forward insets down to us, so read them
        // straight off the window root. This callback is on the main thread and runs after
        // layout, so the values are settled by now.
        readRootInsets()
        // Density can change under a surviving view: it is in the activity's configChanges
        // list, so Settings > Display > Display size never recreates anything.
        renderer.density = resources.displayMetrics.density
        requestFrameRate(holder)
        thread?.resize(width, height)
    }

    /**
     * Asks the compositor for 60 Hz.
     *
     * The loop's only pacing is `lockHardwareCanvas` blocking on the buffer queue, so it
     * otherwise runs at whatever the panel refreshes at — 120 Hz, sometimes 144 — for a game
     * whose fastest fruit lives 430ms. That is two to two-and-a-half times the GPU work,
     * battery draw and heat for no gameplay benefit whatsoever, on top of roughly 3.5x
     * full-screen overdraw per frame. FIXED_SOURCE tells the compositor this is content with
     * an inherent rate rather than a preference, which is what lets it pick a divisor of the
     * display rate cleanly.
     */
    private fun requestFrameRate(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            holder.surface.setFrameRate(
                TARGET_FPS.toFloat(),
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
            )
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRendering()
        // The framework tears the buffer queue down as soon as this returns, so this is the
        // one place the drain window actually has to have elapsed.
        awaitBufferDrain()
    }

    private fun readRootInsets() {
        val bars = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
            ?: return
        topInset = bars.top.toFloat()
        bottomInset = bars.bottom.toFloat()
    }

    private inner class RenderThread(
        private val surfaceHolder: SurfaceHolder,
    ) : Thread("whaaack-render") {

        @Volatile
        private var running = true

        @Volatile
        private var pendingWidth = 0

        @Volatile
        private var pendingHeight = 0

        @Volatile
        private var layoutDirty = true

        private var appliedWidth = 0
        private var appliedHeight = 0

        fun resize(w: Int, h: Int) {
            pendingWidth = w
            pendingHeight = h
            layoutDirty = true
        }

        fun invalidateLayout() {
            layoutDirty = true
        }

        fun shutdown() {
            running = false
            // Unbounded on purpose: the loop only ever waits on the buffer queue for about
            // a frame, and any timeout here would let the surface be destroyed while a
            // buffer is still dequeued.
            while (true) {
                try {
                    join()
                    return
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        override fun run() {
            // The main thread being busy must not cost frames on a reflex game. DISPLAY is
            // the band HWUI's own render thread runs in, which is exactly the company this
            // loop keeps.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }

            var lastFrameNs = 0L
            while (running) {
                val sheet = assets
                if (sheet == null) {
                    // Sprites still decoding: idle rather than spin.
                    try {
                        sleep(16)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                // Queued by startRun on the UI thread; applied here so the engine's state is
                // only ever rewritten by the thread that reads it.
                pendingStart?.let { ranked ->
                    pendingStart = null
                    renderer.reset()
                    engine.start(ranked, System.nanoTime())
                }

                if (layoutDirty || pendingWidth != appliedWidth || pendingHeight != appliedHeight) {
                    appliedWidth = pendingWidth
                    appliedHeight = pendingHeight
                    layoutDirty = false
                    if (appliedWidth > 0 && appliedHeight > 0) {
                        renderer.onSurfaceChanged(
                            appliedWidth, appliedHeight, topInset, bottomInset, sheet,
                        )
                    }
                }
                if (appliedWidth <= 0 || appliedHeight <= 0) {
                    try {
                        sleep(8)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                // A window too small to hold a board is not a board you can lose on. The
                // renderer already declines to draw one; without this the clock and the
                // strikes would carry on behind it, so a player who dragged the game into a
                // split-screen pane would come back to a run they had already lost. Pause
                // the clock instead and let surfaceChanged bring it back. (The manifest
                // declares resizeableActivity="false", so this should be unreachable — but
                // free-form and desktop windowing do not always honour that.)
                if (!renderer.boardDrawable) {
                    engine.pause(System.nanoTime())
                    try {
                        sleep(32)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                engine.resume(System.nanoTime())

                // Re-check immediately before acquiring a buffer so a teardown that began
                // while the previous frame was drawing stops us here instead of leaving a
                // buffer dequeued.
                if (!running || !surfaceHolder.surface.isValid) break

                // setFrameRate is a hint the compositor may decline, so the cap is also
                // enforced here. Without either, the loop free-runs at the panel rate —
                // 120 Hz or more — for a game whose simulation is entirely time-based and
                // gains nothing above 60.
                val beforeLockNs = System.nanoTime()
                val sinceLastNs = beforeLockNs - lastFrameNs
                if (lastFrameNs != 0L && sinceLastNs < FRAME_BUDGET_NS) {
                    try {
                        sleep((FRAME_BUDGET_NS - sinceLastNs) / 1_000_000L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (!running) break
                }
                lastFrameNs = System.nanoTime()

                val canvas = lockCanvas() ?: break
                try {
                    val now = System.nanoTime()
                    engine.update(now)
                    renderer.draw(canvas, engine, sheet, now)
                } finally {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (_: IllegalStateException) {
                        // Surface went away underneath us; the callback stops the thread.
                        break
                    }
                }
            }
        }

        /**
         * Prefers the GPU-backed canvas. The software path is only a defensive fallback for
         * devices that refuse a hardware surface. (minSdk is 26, which is where
         * lockHardwareCanvas arrived, so no version check is needed.)
         */
        private fun lockCanvas(): Canvas? = try {
            surfaceHolder.lockHardwareCanvas() ?: surfaceHolder.lockCanvas()
        } catch (_: IllegalStateException) {
            null
        }
    }

    private companion object {
        /** Roughly three vsyncs at 60 Hz. */
        const val HWUI_DRAIN_MS = 48L

        const val TARGET_FPS = 60
        const val FRAME_BUDGET_NS = 1_000_000_000L / TARGET_FPS
    }
}
