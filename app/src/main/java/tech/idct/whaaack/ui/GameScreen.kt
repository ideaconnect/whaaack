package tech.idct.whaaack.ui

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import tech.idct.whaaack.game.Fruit
import tech.idct.whaaack.game.GameAssets
import tech.idct.whaaack.game.GameEngine
import tech.idct.whaaack.game.GameSurfaceView

/**
 * Hosts the game surface. Everything visible here — orchard, board, HUD, overlays — is
 * drawn by [GameSurfaceView]'s own render thread, so Compose neither recomposes nor
 * re-records anything while a run is in progress.
 */
@Composable
fun GameScreen(
    assets: GameAssets,
    ranked: Boolean,
    hapticsEnabled: Boolean,
    onHit: (Fruit) -> Unit,
    onStrike: () -> Unit,
    onGameOver: (GameEngine.Result) -> Unit,
    onLose: () -> Unit,
) {
    val context = LocalContext.current

    // Plain volatile fields rather than rememberUpdatedState: onHit is delivered on the
    // render thread, and reading a Compose snapshot state off the main thread is not
    // something the snapshot system promises anything about.
    val live = remember { LiveCallbacks() }
    SideEffect {
        live.onHit = onHit
        live.onStrike = onStrike
        live.onGameOver = onGameOver
        live.onLose = onLose
        live.haptics = hapticsEnabled
    }

    val vibrator = remember {
        val service = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        // hasVibrator() is a binder call; ask once rather than on every whack.
        service?.takeIf { it.hasVibrator() }
    }

    fun buzz(ms: Long, amplitude: Int) {
        if (!live.haptics) return
        val v = vibrator ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, amplitude)) }
    }

    // Held so the render thread can be stopped deterministically when Compose disposes the
    // view, rather than relying on surface-teardown ordering.
    val viewRef = remember { arrayOfNulls<GameSurfaceView>(1) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            GameSurfaceView(ctx).also { viewRef[0] = it }.apply {
                attachAssets(assets)
                callbacks = object : GameSurfaceView.Callbacks {
                    // Main thread.
                    override fun onGameOver(result: GameEngine.Result) {
                        // A run the player ended themselves is not a defeat, so it does not
                        // get the defeat sting.
                        if (!result.quit) live.onLose()
                        live.onGameOver(result)
                    }

                    // Render thread: SoundPool.play only, so the splat lands on the frame
                    // that simulated it.
                    override fun onHit(fruit: Fruit) {
                        live.onHit(fruit)
                        post { buzz(18, VibrationEffect.DEFAULT_AMPLITUDE) }
                    }

                    // Main thread.
                    override fun onStrike(strikes: Int) {
                        live.onStrike()
                        buzz(60, VibrationEffect.DEFAULT_AMPLITUDE)
                    }
                }
                startRun(ranked)
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            viewRef[0]?.stopRendering()
            viewRef[0] = null
        }
    }
}

/** Mutable relay between recomposition (main thread) and the render thread. */
private class LiveCallbacks {
    @Volatile
    var onHit: (Fruit) -> Unit = {}

    @Volatile
    var onStrike: () -> Unit = {}

    @Volatile
    var onGameOver: (GameEngine.Result) -> Unit = {}

    @Volatile
    var onLose: () -> Unit = {}

    @Volatile
    var haptics: Boolean = true
}
