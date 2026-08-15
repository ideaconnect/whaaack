package tech.idct.whaaack.game

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Draws the whole play screen — parallax orchard, board, HUD and overlays — onto whatever
 * Canvas it is handed. Owned and driven exclusively by the render thread.
 */
class GameRenderer(density: Float) {

    /**
     * Re-read on every surface change rather than captured once. `density` is in the
     * activity's configChanges list, so changing Settings > Display > Display size while the
     * game screen is open does not recreate the activity and does not re-run AndroidView's
     * factory — this renderer survives with the old value, and every dp() below then sizes
     * the card, the board and the pill for a density the device no longer has.
     */
    @Volatile
    var density: Float = density

    private fun dp(v: Float) = v * density

    // ---- palette lifted from the design ------------------------------------------------
    private val cream = 0xFFFFF3E6.toInt()
    private val panel = 0x8C091428.toInt()
    private val panelStrong = 0x94091428.toInt()
    private val hairline = 0x24FFF3E6
    private val accentLight = 0xFFFFC97A.toInt()
    private val accentDark = 0xFFF2704F.toInt()
    private val strikeOn = 0xFFE2574C.toInt()
    private val strikeOff = 0x38FFF3E6
    private val tileTints = intArrayOf(0x21FFF3E6, 0x21FFC97A, 0x1FD9508F, 0x1F8FBF5A)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sprite = Paint().apply {
        // Nearest-neighbour keeps the 32px fruit sprites crunchy instead of blurry.
        isFilterBitmap = false
        isDither = false
    }
    private val splatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Unlike the fruit, the splat masks are 256px organic blobs drawn rotated to an
        // arbitrary angle and scaled down — not pixel art at 1:1. Paint(flags) sets exactly
        // the flags given, so filtering was off and every splat came out with a visibly
        // stair-stepped rotated edge.
        isFilterBitmap = true
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val scoreText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = cream
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = cream
    }

    /**
     * One gradient per fruit, built in unit space and re-aimed with a local matrix at draw
     * time so the render loop stays allocation-free.
     */
    private val splatGradients = HashMap<Fruit, LinearGradient>(Fruit.ALL.size)
    private val shaderMatrix = Matrix()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val tmpRect = RectF()

    /** Reused, never reallocated: this is on the per-frame path and allocation there is the
     *  one thing the render loop is careful about. */
    private val clipPath = Path()

    private var skyShader: BitmapShader? = null
    private var treeShader: BitmapShader? = null
    private var hillShader: BitmapShader? = null
    private val skyMatrix = Matrix()
    private val treeMatrix = Matrix()
    private val hillMatrix = Matrix()

    /**
     * Full-viewport shaders, rebuilt only when the surface changes size.
     *
     * Constructing a gradient allocates a native Shader, so anything built inside [draw]
     * costs one of those every frame. The speed bar's gradient is kept in unit space and
     * re-aimed with [barMatrix], and the strike flash is drawn at full strength and faded
     * with the Paint's alpha, which modulates the shader identically.
     */
    private var scrimShader: LinearGradient? = null
    private var strikeShader: RadialGradient? = null
    private val barGradient = LinearGradient(
        0f, 0f, 1f, 0f, accentLight, accentDark, Shader.TileMode.CLAMP,
    )
    private val barMatrix = Matrix()

    private var width = 0
    private var height = 0
    private var safeTop = 0f
    private var safeBottom = 0f

    // Board geometry, recomputed only when the surface changes size.
    private var boardLeft = 0f
    private var boardTop = 0f
    private var tileSize = 0f

    /**
     * False when the viewport leaves no room for a playable board — below roughly 280dp of
     * usable height, which in practice means split-screen or a free-form window. The board
     * already declines to draw at that point; this is what stops the run continuing to be
     * lost behind it. See GameSurfaceView's render loop.
     */
    val boardDrawable: Boolean get() = tileSize > 0f
    private var tileGap = 0f
    private var boardInset = 0f
    private val cardRect = RectF()
    private val boardRect = RectF()
    private val endRunRect = RectF()

    /**
     * The geometry the UI thread hit-tests against, published as one immutable snapshot.
     *
     * [onSurfaceChanged] runs on the render thread while touches arrive on the main thread,
     * and the board fields above are plain floats with no happens-before edge between the
     * two. Reading them directly from [tileAt] risked a touch racing a resize seeing half of
     * the new layout and half of the old — or, in principle, never seeing the new layout at
     * all. A copy published through a @Volatile reference hands the touch path a coherent
     * set in one read.
     */
    private class TouchLayout(
        @JvmField val boardRect: RectF,
        @JvmField val endRunRect: RectF,
        @JvmField val boardLeft: Float,
        @JvmField val boardTop: Float,
        @JvmField val tileSize: Float,
        @JvmField val tileGap: Float,
    )

    @Volatile
    private var touchLayout: TouchLayout? = null

    private var driftFar = 0f
    private var driftMid = 0f
    private var driftNear = 0f
    private var lastFrameNs = 0L

    private var burst: Array<BurstPiece>? = null

    private class BurstPiece(
        val fruit: Fruit,
        val vx: Float,
        val vy: Float,
        val x0: Float,
        val spin: Float,
        val size: Float,
        val delayMs: Float,
    )

    fun onSurfaceChanged(w: Int, h: Int, topInset: Float, bottomInset: Float, assets: GameAssets) {
        width = w
        height = h
        safeTop = topInset
        safeBottom = bottomInset

        skyShader = BitmapShader(assets.sky, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
        treeShader = BitmapShader(assets.trees, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
        hillShader = BitmapShader(assets.hills, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)

        scrimShader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x6B091428, 0x1A091428, 0x57091428),
            floatArrayOf(0f, 0.34f, 1f),
            Shader.TileMode.CLAMP,
        )
        strikeShader = RadialGradient(
            w / 2f,
            h * 0.5f,
            maxOf(w, h) * 0.75f,
            intArrayOf(Color.argb(46, 226, 87, 76), Color.argb(168, 178, 42, 48)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )

        val sidePad = dp(16f)
        val cardTop = safeTop + dp(14f) + dp(28f) + dp(12f)
        cardRect.set(sidePad, cardTop, w - sidePad, cardTop + dp(104f))

        endRunRect.set(
            w / 2f - dp(52f),
            h - safeBottom - dp(18f) - dp(38f),
            w / 2f + dp(52f),
            h - safeBottom - dp(18f),
        )

        // The board takes the space between the score card and the End-run pill.
        boardInset = dp(14f)
        tileGap = dp(10f)
        val availableTop = cardRect.bottom + dp(16f)
        val availableBottom = endRunRect.top - dp(14f)
        val maxBoardWidth = w - 2 * sidePad
        val maxBoardHeight = availableBottom - availableTop

        val sizeFromWidth = (maxBoardWidth - 2 * boardInset - 3 * tileGap) / 4f
        val sizeFromHeight = (maxBoardHeight - 2 * boardInset - 3 * tileGap) / 4f
        // Clamped: a viewport too short to hold the card, the board and the pill leaves no
        // room at all, and a negative tile size feeds inverted rects to every draw call and
        // an inverted destination to every drawBitmap.
        tileSize = min(sizeFromWidth, sizeFromHeight).coerceAtLeast(0f)

        val boardW = tileSize * 4 + tileGap * 3 + boardInset * 2
        val boardH = boardW
        val bx = (w - boardW) / 2f
        val by = availableTop + (maxBoardHeight - boardH) / 2f
        boardRect.set(bx, by, bx + boardW, by + boardH)
        boardLeft = bx + boardInset
        boardTop = by + boardInset

        scoreText.textSize = dp(46f)
        label.textSize = dp(11f)
        // Sized here rather than at construction for the same reason `density` is re-read
        // on every surface change: a display-size change reaches this renderer without
        // recreating it, and a stroke width captured once would stay at the old density.
        stroke.strokeWidth = dp(1f)

        touchLayout = TouchLayout(
            boardRect = RectF(boardRect),
            endRunRect = RectF(endRunRect),
            boardLeft = boardLeft,
            boardTop = boardTop,
            tileSize = tileSize,
            tileGap = tileGap,
        )
    }

    /**
     * Maps a touch point to a board tile, or -1 when the tap landed off the board entirely.
     *
     * Every point inside the board card belongs to a tile, gutters and padding included.
     * Tapping bare board costs the player nothing, so refusing a near-miss could never help
     * them and could only turn a whack they meant into a strike they did not: the gutters
     * used to be dead, which at four fruit and a 200 ms cycle is a lot of stolen hits.
     */
    fun tileAt(x: Float, y: Float): Int {
        val layout = touchLayout ?: return -1
        if (layout.tileSize <= 0f) return -1
        if (!layout.boardRect.contains(x, y)) return -1
        val pitch = layout.tileSize + layout.tileGap
        val col = ((x - layout.boardLeft) / pitch).toInt().coerceIn(0, GameEngine.TILE_COLUMNS - 1)
        val row = ((y - layout.boardTop) / pitch).toInt().coerceIn(0, GameEngine.TILE_ROWS - 1)
        return row * GameEngine.TILE_COLUMNS + col
    }

    /** End-run pill hit-test for the UI thread, reading the same published snapshot. */
    fun hitsEndRun(x: Float, y: Float): Boolean =
        touchLayout?.endRunRect?.contains(x, y) == true

    fun draw(canvas: Canvas, engine: GameEngine, assets: GameAssets, nowNs: Long) {
        val dtSec = if (lastFrameNs == 0L) 0f else (nowNs - lastFrameNs) / 1_000_000_000f
        lastFrameNs = nowNs

        advanceParallax(dtSec, engine)
        drawBackground(canvas, assets)
        drawScrim(canvas)
        // The strike flash sits on the orchard, under the board and HUD, so a missed
        // fruit reads as the world reacting rather than the interface blinking out.
        drawStrikeFlash(canvas, engine, nowNs)
        drawTopBar(canvas, engine)
        drawScoreCard(canvas, engine)
        drawBoard(canvas, engine, assets, nowNs)
        drawEndRun(canvas, engine, nowNs)
        drawOutro(canvas, engine, assets, nowNs)
        drawCountdown(canvas, engine)
    }

    // ---- background --------------------------------------------------------------------

    private fun advanceParallax(dtSec: Float, engine: GameEngine) {
        // Tied to the same saturating curve the speed bar reads, so the orchard stops
        // accelerating at the point the run genuinely stops getting harder. Driving this
        // off the raw level instead let it keep speeding up indefinitely.
        val boost = 1f + GameEngine.speedFraction(engine.level) * 1.8f
        driftFar += dp(7f) * dtSec
        driftMid += dp(22f) * dtSec
        driftNear += dp(48f) * dtSec * boost
    }

    private fun drawBackground(canvas: Canvas, assets: GameAssets) {
        canvas.drawColor(0xFF4C82D0.toInt())

        // Sky fills the viewport; the two ground layers hug the bottom edge and scroll faster.
        drawLayer(canvas, assets.sky, skyShader, skyMatrix, driftFar, height.toFloat(), 0f)

        val groundHeight = height * 0.63f
        val groundTop = height - groundHeight
        drawLayer(canvas, assets.trees, treeShader, treeMatrix, driftMid, groundHeight, groundTop)
        drawLayer(canvas, assets.hills, hillShader, hillMatrix, driftNear, groundHeight, groundTop)
    }

    private fun drawLayer(
        canvas: Canvas,
        bitmap: Bitmap,
        shader: BitmapShader?,
        matrix: Matrix,
        drift: Float,
        layerHeight: Float,
        top: Float,
    ) {
        shader ?: return
        val scale = layerHeight / bitmap.height
        val tileWidth = bitmap.width * scale
        val offset = -(drift % tileWidth)
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(offset, top)
        shader.setLocalMatrix(matrix)
        fill.reset()
        fill.isAntiAlias = true
        fill.isFilterBitmap = false
        fill.shader = shader
        canvas.drawRect(0f, top, width.toFloat(), top + layerHeight, fill)
        fill.shader = null
    }

    private fun drawScrim(canvas: Canvas) {
        val shader = scrimShader ?: return
        fill.reset()
        fill.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null
    }

    // ---- HUD ---------------------------------------------------------------------------

    private fun drawTopBar(canvas: Canvas, engine: GameEngine) {
        val y = safeTop + dp(14f)
        val chipH = dp(28f)

        // Mode chip
        val text = if (engine.ranked) "RANKED" else "FOR FUN"
        label.textSize = dp(10f)
        label.textAlign = Paint.Align.LEFT
        val chipW = label.measureText(text) + dp(24f)
        tmpRect.set(dp(16f), y, dp(16f) + chipW, y + chipH)
        fill.reset()
        fill.isAntiAlias = true
        fill.color = if (engine.ranked) 0x33FFC97A else panel
        canvas.drawRoundRect(tmpRect, chipH / 2, chipH / 2, fill)
        stroke.color = hairline
        canvas.drawRoundRect(tmpRect, chipH / 2, chipH / 2, stroke)
        label.color = if (engine.ranked) accentLight else 0xB3FFF3E6.toInt()
        canvas.drawText(text, tmpRect.left + dp(12f), tmpRect.centerY() + dp(3.5f), label)

        // Strike dots, filling left to right as strikes land.
        val dot = dp(12f)
        var cx = width - dp(16f) - dot / 2
        for (i in GameEngine.MAX_STRIKES downTo 1) {
            fill.color = if (engine.strikes >= i) strikeOn else strikeOff
            canvas.drawCircle(cx, y + chipH / 2, dot / 2, fill)
            if (engine.strikes >= i) {
                fill.color = 0x38E2574C
                canvas.drawCircle(cx, y + chipH / 2, dot / 2 + dp(3f), fill)
            }
            cx -= dot + dp(6f)
        }
    }

    private fun drawScoreCard(canvas: Canvas, engine: GameEngine) {
        fill.reset()
        fill.isAntiAlias = true
        fill.color = panelStrong
        canvas.drawRoundRect(cardRect, dp(26f), dp(26f), fill)
        stroke.color = hairline
        canvas.drawRoundRect(cardRect, dp(26f), dp(26f), stroke)

        // Score: milliseconds survived.
        val score = engine.elapsedMs.toString()
        scoreText.textSize = dp(46f)
        scoreText.textAlign = Paint.Align.CENTER
        val baseline = cardRect.top + dp(52f)
        val scoreWidth = scoreText.measureText(score)
        canvas.drawText(score, cardRect.centerX() - dp(11f), baseline, scoreText)

        label.textSize = dp(11f)
        label.textAlign = Paint.Align.LEFT
        label.color = 0x99FFF3E6.toInt()
        canvas.drawText("MS", cardRect.centerX() + scoreWidth / 2 - dp(6f), baseline, label)

        // Speed bar
        val barLeft = cardRect.left + dp(16f)
        val barRight = cardRect.right - dp(16f)
        val barTop = cardRect.top + dp(66f)
        val barH = dp(6f)
        tmpRect.set(barLeft, barTop, barRight, barTop + barH)
        fill.color = 0x66140A1A
        canvas.drawRoundRect(tmpRect, barH / 2, barH / 2, fill)

        val fraction = GameEngine.speedFraction(engine.level)
        if (fraction > 0f) {
            val filled = (barRight - barLeft) * fraction
            tmpRect.set(barLeft, barTop, barLeft + filled, barTop + barH)
            barMatrix.reset()
            barMatrix.setScale(filled, 1f)
            barMatrix.postTranslate(barLeft, 0f)
            barGradient.setLocalMatrix(barMatrix)
            fill.shader = barGradient
            canvas.drawRoundRect(tmpRect, barH / 2, barH / 2, fill)
            fill.shader = null
        }

        label.color = 0xBFFFF3E6.toInt()
        label.textAlign = Paint.Align.LEFT
        val speed = if (GameEngine.isTopSpeed(engine.level)) {
            "TOP SPEED"
        } else {
            "SPEED ${GameEngine.displaySpeed(engine.level)}"
        }
        canvas.drawText(speed, barLeft, barTop + dp(20f), label)
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText("${engine.hits} HITS", barRight, barTop + dp(20f), label)
    }

    /**
     * The End-run pill, in one of two states.
     *
     * Resting it is quiet on purpose — it sits fourteen dp under a board the player is
     * hammering, and should not invite a press. Armed it is the opposite: the first press
     * does not end the run, so this has to say so loudly enough that a player who hit it by
     * accident notices before pressing again. It fills with a warning tint, the label changes
     * to a question, and the remaining arming window drains left to right so the state
     * visibly has a deadline rather than looking stuck.
     */
    private fun drawEndRun(canvas: Canvas, engine: GameEngine, nowNs: Long) {
        val armedUntil = engine.quitArmedUntilNs
        val remaining = if (armedUntil == 0L) 0f else {
            ((armedUntil - nowNs).toFloat() / (GameEngine.QUIT_ARM_MS * 1_000_000f))
                .coerceIn(0f, 1f)
        }
        val armed = remaining > 0f
        val radius = dp(14f)

        fill.reset()
        fill.isAntiAlias = true
        fill.color = if (armed) 0x4DF2704F else 0x33091428
        canvas.drawRoundRect(endRunRect, radius, radius, fill)

        if (armed) {
            // The window draining away. Clipped to the pill so the bar keeps its rounded ends.
            canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(endRunRect, radius, radius, Path.Direction.CW)
            canvas.clipPath(clipPath)
            fill.color = 0x59F2704F
            canvas.drawRect(
                endRunRect.left,
                endRunRect.top,
                endRunRect.left + endRunRect.width() * remaining,
                endRunRect.bottom,
                fill,
            )
            canvas.restore()
        }

        stroke.color = if (armed) 0xB8F2704F.toInt() else 0x33FFF3E6
        canvas.drawRoundRect(endRunRect, radius, radius, stroke)

        label.textSize = dp(12f)
        label.textAlign = Paint.Align.CENTER
        label.color = if (armed) 0xFFFFF3E6.toInt() else 0xA8FFF3E6.toInt()
        canvas.drawText(
            if (armed) "End run?" else "End run",
            endRunRect.centerX(),
            endRunRect.centerY() + dp(4f),
            label,
        )
    }

    // ---- board -------------------------------------------------------------------------

    private fun drawBoard(canvas: Canvas, engine: GameEngine, assets: GameAssets, nowNs: Long) {
        if (tileSize <= 0f) return
        fill.reset()
        fill.isAntiAlias = true
        fill.color = panel
        canvas.drawRoundRect(boardRect, dp(30f), dp(30f), fill)
        stroke.color = 0x29FFF3E6
        canvas.drawRoundRect(boardRect, dp(30f), dp(30f), stroke)

        for (tile in 0 until GameEngine.TILE_COUNT) {
            val col = tile % GameEngine.TILE_COLUMNS
            val row = tile / GameEngine.TILE_COLUMNS
            val left = boardLeft + col * (tileSize + tileGap)
            val top = boardTop + row * (tileSize + tileGap)
            tmpRect.set(left, top, left + tileSize, top + tileSize)

            fill.color = tileTints[(tile + row) % tileTints.size]
            canvas.drawRoundRect(tmpRect, dp(20f), dp(20f), fill)
            stroke.color = 0x21FFF3E6
            canvas.drawRoundRect(tmpRect, dp(20f), dp(20f), stroke)

            // The hole the fruit rises out of.
            val holeH = tileSize * 0.30f
            tmpRect.set(
                left + tileSize * 0.10f,
                top + tileSize * 0.88f - holeH,
                left + tileSize * 0.90f,
                top + tileSize * 0.88f,
            )
            fill.color = 0x990E1422.toInt()
            canvas.drawOval(tmpRect, fill)
        }

        for (active in engine.slots) {
            active ?: continue
            drawFruit(canvas, assets, active, nowNs)
        }
        // Iterate by index: the list is only mutated by this same thread.
        for (i in engine.splats.indices) {
            val splat = engine.splats.getOrNull(i) ?: continue
            drawSplat(canvas, assets, splat, nowNs)
        }
    }

    private fun drawFruit(
        canvas: Canvas,
        assets: GameAssets,
        active: GameEngine.ActiveFruit,
        nowNs: Long,
    ) {
        val bitmap = assets.fruits[active.fruit] ?: return
        val ageMs = (nowNs - active.bornNs) / 1_000_000f
        val rise = min(1f, ageMs / 150f)
        val remaining = active.lifeMs - ageMs
        val fade = if (remaining < 160f) max(0f, remaining / 160f) else 1f
        if (fade <= 0f) return

        val col = active.tile % GameEngine.TILE_COLUMNS
        val row = active.tile / GameEngine.TILE_COLUMNS
        val left = boardLeft + col * (tileSize + tileGap)
        val top = boardTop + row * (tileSize + tileGap)

        val artSize = tileSize * 0.76f
        val scale = 0.72f + rise * 0.28f
        val drawSize = artSize * scale
        val cx = left + tileSize / 2f
        // Anchored to the hole, sliding up into view.
        val bottom = top + tileSize * 0.82f + (1f - rise) * tileSize * 0.5f

        srcRect.set(0, 0, bitmap.width, bitmap.height)
        dstRect.set(cx - drawSize / 2f, bottom - drawSize, cx + drawSize / 2f, bottom)

        sprite.alpha = (fade * 255).toInt().coerceIn(0, 255)
        canvas.withClip(left, top, left + tileSize, top + tileSize) {
            drawBitmap(bitmap, srcRect, dstRect, sprite)
        }
        sprite.alpha = 255
    }

    private fun drawSplat(
        canvas: Canvas,
        assets: GameAssets,
        splat: GameEngine.Splat,
        nowNs: Long,
    ) {
        val mask = assets.splatMasks.getOrNull(splat.variant) ?: return
        val progress = ((nowNs - splat.bornNs) / 1_000_000f) / GameEngine.SPLAT_LIFE_MS
        if (progress >= 1f) return
        val alpha = if (progress < 0.55f) 1f else max(0f, 1f - (progress - 0.55f) / 0.45f)
        val pop = 0.95f + min(1f, progress * 12f) * 0.15f

        val col = splat.tile % GameEngine.TILE_COLUMNS
        val row = splat.tile / GameEngine.TILE_COLUMNS
        val cx = boardLeft + col * (tileSize + tileGap) + tileSize / 2f
        val cy = boardTop + row * (tileSize + tileGap) + tileSize * 0.52f
        val size = tileSize * 1.18f * pop

        canvas.withTranslation(cx, cy) {
            rotate(splat.rotationDeg)

            // The mask is ALPHA_8, so the shader below supplies every pixel's colour.
            val gradient = splatGradients.getOrPut(splat.fruit) {
                LinearGradient(
                    0f, 0f, 1f, 1f,
                    intArrayOf(splat.fruit.splatLight, splat.fruit.splatLight, splat.fruit.splatDark),
                    floatArrayOf(0f, 0.38f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            shaderMatrix.reset()
            shaderMatrix.setScale(size, size)
            shaderMatrix.postTranslate(-size / 2f, -size / 2f)
            gradient.setLocalMatrix(shaderMatrix)
            splatPaint.shader = gradient
            splatPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)

            srcRect.set(0, 0, mask.width, mask.height)
            dstRect.set(-size / 2f, -size / 2f, size / 2f, size / 2f)
            drawBitmap(mask, srcRect, dstRect, splatPaint)

            splatPaint.shader = null
            splatPaint.alpha = 255
        }
    }

    // ---- overlays ----------------------------------------------------------------------

    private fun drawStrikeFlash(canvas: Canvas, engine: GameEngine, nowNs: Long) {
        val since = engine.lastStrikeNs
        if (since == 0L) return
        val ageMs = (nowNs - since) / 1_000_000f
        if (ageMs > GameEngine.STRIKE_FLASH_MS) return
        val shader = strikeShader ?: return
        val t = 1f - ageMs / GameEngine.STRIKE_FLASH_MS
        fill.reset()
        // Strongest at the edges, so the centre of the board stays readable while the
        // whole orchard still visibly pulses red. The gradient holds the flash at full
        // strength; the Paint's alpha scales both stops, which is what the fade needs.
        fill.shader = shader
        fill.alpha = (t * 255).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null
        fill.alpha = 255
    }

    private fun drawCountdown(canvas: Canvas, engine: GameEngine) {
        if (engine.phase != GameEngine.Phase.COUNTDOWN) return
        fill.reset()
        fill.color = 0x9E140A1A.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

        val text = if (engine.countdownValue <= 0) "GO" else engine.countdownValue.toString()
        scoreText.textSize = dp(96f)
        scoreText.textAlign = Paint.Align.CENTER
        scoreText.color = cream
        canvas.drawText(text, width / 2f, height / 2f + dp(34f), scoreText)
        scoreText.textSize = dp(46f)
    }

    private fun drawOutro(canvas: Canvas, engine: GameEngine, assets: GameAssets, nowNs: Long) {
        if (engine.phase != GameEngine.Phase.OUTRO) {
            burst = null
            return
        }
        val pieces = burst ?: createBurst().also { burst = it }
        val sinceMs = (nowNs - engine.outroStartedNs) / 1_000_000f

        // White bloom on impact.
        if (sinceMs < 260f) {
            fill.reset()
            fill.color = Color.argb(
                ((1f - sinceMs / 260f) * 200).toInt().coerceIn(0, 255), 255, 255, 255,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }

        val originX = width / 2f
        val originY = height * 0.52f
        for (piece in pieces) {
            val t = max(0f, sinceMs - piece.delayMs) / 1000f
            if (t <= 0f) continue
            val bitmap = assets.fruits[piece.fruit] ?: continue
            val alpha = max(0f, 1f - t / 1.4f)
            if (alpha <= 0f) continue

            val x = originX + piece.x0 + piece.vx * t
            val y = originY + piece.vy * t + 900f * density * t * t

            canvas.withTranslation(x, y) {
                rotate(piece.spin * t)
                srcRect.set(0, 0, bitmap.width, bitmap.height)
                dstRect.set(
                    -piece.size / 2f, -piece.size / 2f, piece.size / 2f, piece.size / 2f,
                )
                sprite.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                drawBitmap(bitmap, srcRect, dstRect, sprite)
                sprite.alpha = 255
            }
        }
    }

    private fun createBurst(): Array<BurstPiece> = Array(16) {
        val angle = -Math.PI.toFloat() / 2f + (Random.nextFloat() - 0.5f) * 2.5f
        val speed = (300f + Random.nextFloat() * 460f) * density
        BurstPiece(
            fruit = Fruit.ALL[Random.nextInt(Fruit.ALL.size)],
            vx = (kotlin.math.cos(angle) * speed),
            vy = (kotlin.math.sin(angle) * speed),
            x0 = (Random.nextFloat() - 0.5f) * dp(120f),
            spin = (Random.nextFloat() - 0.5f) * 900f,
            size = dp(34f + Random.nextFloat() * 30f),
            delayMs = Random.nextFloat() * 180f,
        )
    }

    fun reset() {
        burst = null
        lastFrameNs = 0L
    }

    /**
     * Drops the frame-delta baseline without touching the outro burst, so a run that is
     * resumed after the surface was rebuilt does not see one enormous dt and jump the
     * parallax forward by however long the app was away.
     */
    fun resetFrameClock() {
        lastFrameNs = 0L
    }
}
