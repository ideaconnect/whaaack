package tech.idct.whaaack.ui

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
    onQuit: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnHit by rememberUpdatedState(onHit)
    val currentOnStrike by rememberUpdatedState(onStrike)
    val currentOnGameOver by rememberUpdatedState(onGameOver)
    val currentOnLose by rememberUpdatedState(onLose)
    val currentOnQuit by rememberUpdatedState(onQuit)
    val haptics by rememberUpdatedState(hapticsEnabled)

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    fun buzz(ms: Long, amplitude: Int) {
        if (!haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
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
                    override fun onGameOver(result: GameEngine.Result) {
                        currentOnLose()
                        currentOnGameOver(result)
                    }

                    override fun onQuit() = currentOnQuit()

                    override fun onHit(fruit: Fruit) {
                        currentOnHit(fruit)
                        buzz(18, VibrationEffect.DEFAULT_AMPLITUDE)
                    }

                    override fun onStrike(strikes: Int) {
                        currentOnStrike()
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
