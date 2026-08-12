package tech.idct.whaaack.game

import androidx.annotation.ColorInt

/**
 * The twelve fruits from the design's sprite pack, each paired with the two colours its
 * splat is tinted with (a bright core fading into a darker rind).
 */
enum class Fruit(
    val asset: String,
    @ColorInt val splatLight: Int,
    @ColorInt val splatDark: Int,
) {
    APPLE("fruits/apple.png", 0xFF8FD24E.toInt(), 0xFF4B8A28.toInt()),
    BANANA("fruits/banana.png", 0xFFFBE87A.toInt(), 0xFFD9A82B.toInt()),
    CHERRY("fruits/cherry.png", 0xFFE8514F.toInt(), 0xFF8E1B26.toInt()),
    GRAPE("fruits/grape.png", 0xFFB98BD9.toInt(), 0xFF6E4594.toInt()),
    KIWI("fruits/kiwi.png", 0xFFA9D66B.toInt(), 0xFF4E7A34.toInt()),
    LEMON("fruits/lemon.png", 0xFFFCEE8A.toInt(), 0xFFE0BE33.toInt()),
    ORANGE("fruits/orange.png", 0xFFFFB055.toInt(), 0xFFD06A18.toInt()),
    PEACH("fruits/peach.png", 0xFFFFC4A6.toInt(), 0xFFE27B5C.toInt()),
    PEAR("fruits/pear.png", 0xFFC6DC79.toInt(), 0xFF7C9B39.toInt()),
    PINEAPPLE("fruits/pineapple.png", 0xFFF5D96A.toInt(), 0xFFC08C24.toInt()),
    STRAWBERRY("fruits/strawberry.png", 0xFFF0685C.toInt(), 0xFFA32B33.toInt()),
    WATERMELON("fruits/watermelon.png", 0xFFFF7089.toInt(), 0xFF5FA34A.toInt());

    companion object {
        val ALL: List<Fruit> = entries
        const val SPLAT_VARIANTS = 36

        fun splatAsset(index: Int): String =
            "splats/splat%02d.png".format(index.coerceIn(0, SPLAT_VARIANTS - 1))
    }
}
