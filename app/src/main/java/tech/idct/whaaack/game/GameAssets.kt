package tech.idct.whaaack.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decoded sprite sheet for the run. Built once off the main thread and then read-only,
 * so the render thread never blocks on I/O mid-frame.
 */
class GameAssets private constructor(
    val fruits: Map<Fruit, Bitmap>,
    /** Splat silhouettes kept as ALPHA_8 masks so a Paint shader supplies the colour. */
    val splatMasks: List<Bitmap>,
    val sky: Bitmap,
    val trees: Bitmap,
    val hills: Bitmap,
) {
    fun recycle() {
        fruits.values.forEach { it.recycle() }
        splatMasks.forEach { it.recycle() }
        sky.recycle()
        trees.recycle()
        hills.recycle()
    }

    companion object {
        /** Blocking; call from a background dispatcher. */
        fun load(context: Context): GameAssets {
            val am = context.assets

            // Pixel art must not be pre-scaled by density or it loses its crisp edges.
            val exact = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            fun decode(path: String): Bitmap =
                am.open(path).use { BitmapFactory.decodeStream(it, null, exact)!! }

            val fruits = Fruit.ALL.associateWith { decode(it.asset) }

            val masks = (0 until Fruit.SPLAT_VARIANTS).map { i ->
                val full = decode(Fruit.splatAsset(i))
                // extractAlpha() hands back an ALPHA_8 copy: a quarter of the memory, and
                // drawing it takes its colour from the Paint, which is exactly the tinting
                // behaviour the design asks for.
                val mask = full.extractAlpha()
                full.recycle()
                mask
            }

            return GameAssets(
                fruits = fruits,
                splatMasks = masks,
                sky = decode("bg-sky.png"),
                trees = decode("bg-trees.png"),
                hills = decode("bg-hills.png"),
            )
        }
    }
}
