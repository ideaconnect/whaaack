package tech.idct.whaaack.games

/**
 * The four survival milestones the game publishes to Play Games Services.
 *
 * Deliberately free of Android and of the SDK, so the rule that decides what a run earned is
 * testable on the JVM — the same reason [tech.idct.whaaack.game.GameEngine] is. The mapping
 * from a milestone to the console id that represents it lives in [PlayGamesManager], because
 * that is configuration and this is not.
 *
 * All four are one-shot rather than incremental. That matters beyond the console checkbox:
 * an incremental achievement accumulates steps *across* runs, which would let someone reach
 * "two minutes" in four half-minute attempts. The whole claim being made here is that a
 * single run lasted that long.
 */
enum class Achievement(val seconds: Int) {
    SURVIVE_30(30),
    SURVIVE_60(60),
    SURVIVE_90(90),
    SURVIVE_120(120),
    ;

    /**
     * The run length that earns this, in the only unit the game actually counts in — the
     * score on the game-over card is a raw millisecond count, and so is the leaderboard's.
     */
    val thresholdMillis: Long = seconds * 1_000L

    companion object {
        /**
         * Every milestone a run of [millisSurvived] earns, lowest first.
         *
         * All of them, not just the highest: a player whose first ever run lasts 100 seconds
         * has genuinely survived 30, 60 and 90 seconds too, and Play Games does not infer
         * that. It is also what makes the call idempotent enough to repeat — see
         * [PlayGamesManager.award], which re-runs this against the stored personal best every
         * time the app comes back to the foreground.
         *
         * The comparison is inclusive: 30_000 ms is thirty seconds survived, and a player who
         * lands exactly on it and is told otherwise has been robbed by a rounding rule.
         */
        fun earnedBy(millisSurvived: Long): List<Achievement> =
            entries.filter { millisSurvived >= it.thresholdMillis }
    }
}
