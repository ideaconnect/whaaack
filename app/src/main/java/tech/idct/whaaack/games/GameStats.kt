package tech.idct.whaaack.games

import tech.idct.whaaack.game.GameEngine

/**
 * The event Whaaack! reports to Play Games Services Game Stats, and the shape the console has
 * been told to expect.
 *
 * ## Why this is a schema and not just a few `addProperty` calls
 *
 * Every uploaded event is validated against the declaration in the console's
 * `PlayerGameEvent.csv`, and one that does not match is **not submitted** — no exception, no
 * failed Task, nothing in the app to notice. A renamed property or an `INT64` sent as a
 * `DOUBLE` therefore costs a stat that simply stays at zero for every player, for as long as
 * it takes somebody to wonder why.
 *
 * So the declaration lives here as data, `assets/game-stats/PlayerGameEvent.csv` is its
 * counterpart, and `GameStatsTest` reads that file and fails if the two have drifted. That
 * test is the only mechanism that catches this class of mistake before players do.
 *
 * ## One event, not several
 *
 * A run is the only thing this game does, and everything worth counting is a fact about one:
 * runs played is that event's count, total and longest survival are the sum and max of one
 * property, fruit whacked is a sum. Splitting it into per-hit or per-strike events would
 * multiply the upload traffic and buy no stat that this cannot express. The console allows 20
 * event names; using one is not thrift, it is the right shape.
 */
object GameStats {

    /** Sent once per finished run, whether it was lost or ended by the player. */
    const val RUN_COMPLETED = "run_completed"

    /**
     * The console's predefined progression event, and the only one a progression stat may be
     * built on — hence the camel case, which is Google's spelling and not this file's style.
     *
     * It carries a *current value* rather than a thing that happened, and that is what makes
     * it worth having here. A `run_completed` is a fact about a moment: a run played while
     * Play Games was signed out is gone, because the only honest way to send it later would be
     * to invent a timestamp for it. The personal best is not a moment — it is a number sitting
     * in preferences — so it can be re-sent on every launch, and a player who has been playing
     * signed out still arrives with their best intact the moment they authenticate. It is the
     * same back-fill the achievements get, for the one figure players actually care about.
     */
    const val PROGRESS_UPDATE = "progressUpdate"

    /**
     * The four types `PlayerGameEvent.csv` accepts, spelled exactly as its Property Type
     * column spells them — the column is case-sensitive, and `INT64` in particular is not
     * what the overview page calls it ("INT"). The format spec wins.
     */
    enum class Type { INT64, DOUBLE, STRING, BOOL }

    /**
     * Names are limited to 50 characters of letters, numbers and underscores, must be unique
     * within the event, and must avoid the console's reserved words — `id`, `count`, `key`,
     * `value`, `where`, `from`, `and`, `or`, `true`, `false` — which read as a hint that these
     * end up in a query language somewhere. `GameStatsTest` enforces all of that, so a
     * property added later cannot quietly break the upload.
     */
    data class Property(val name: String, val type: Type)

    /** The run's length in the unit the game itself counts in and puts on the game-over card. */
    val SURVIVED_MS = Property("survived_ms", Type.INT64)

    /**
     * The same run in whole seconds, and the reason both exist.
     *
     * Game Stats has **no time unit** — the supported list is unitless, percentage, distance
     * and speed, and nothing else, whatever the documentation's prose suggests. So a duration
     * is displayed as a bare number and the choice is only which number: a total of 4,210,500
     * means nothing to anybody, while 4,210 seconds at least reads as a quantity of time. The
     * milliseconds stay because they are the game's real currency and the exact value, and
     * because the two count-style stats key off them.
     *
     * `INT64` rather than `DOUBLE` for the same reason: with no unit to format it, a summed
     * `DOUBLE` would show a fringe of decimals on the profile. Truncating each run before
     * summing loses under a second per run, which no player will ever see, and keeps the
     * `>= 60` filter behind "Minute Runs" exactly right — 59.9 seconds truncates to 59 and is
     * correctly not a minute.
     */
    val SURVIVED_SECONDS = Property("survived_seconds", Type.INT64)

    /** Fruit whacked. */
    val FRUIT_HIT = Property("fruit_hit", Type.INT64)

    /** Fruit that escaped: the run's strikes, so never more than three. */
    val FRUIT_MISSED = Property("fruit_missed", Type.INT64)

    /** The speed number the HUD showed, clamped the way the HUD clamps it. */
    val TOP_SPEED = Property("top_speed", Type.INT64)

    /** Whether the run counted towards the leaderboard. Lets a stat filter to ranked play. */
    val RANKED = Property("ranked", Type.BOOL)

    /**
     * The best run so far, in whole seconds.
     *
     * The name is fixed by the console — a progression stat reads `currentProgress` and
     * nothing else — and the type must be `INT64` or `STRING`. Seconds rather than the
     * milliseconds used everywhere else, for the reason given on [SURVIVED_SECONDS]: with no
     * time unit to format it, this number is displayed raw, and "93" reads as a duration
     * where "93500" reads as a score. The stat is named "Best Run (seconds)" to close the
     * gap the missing unit leaves.
     */
    val CURRENT_PROGRESS = Property("currentProgress", Type.INT64)

    /** Declaration order per event, which is also the order of rows in `PlayerGameEvent.csv`. */
    val SCHEMA: Map<String, List<Property>> = mapOf(
        RUN_COMPLETED to listOf(
            SURVIVED_MS,
            SURVIVED_SECONDS,
            FRUIT_HIT,
            FRUIT_MISSED,
            TOP_SPEED,
            RANKED,
        ),
        PROGRESS_UPDATE to listOf(CURRENT_PROGRESS),
    )

    /**
     * The properties of one finished run, keyed by their declaration.
     *
     * Returned as data rather than written straight onto a `PlayerGameEvent.Builder` so the
     * mapping is testable without the SDK: the value types here are what decide whether the
     * upload matches the declaration, and getting one wrong is invisible at runtime.
     *
     * Every value is a `Long`, `Double`, `String` or `Boolean`, matching its [Property.type] —
     * asserted in `GameStatsTest`, because Kotlin will happily hand an `Int` to a `Long`
     * declaration through the wrong `addProperty` overload.
     */
    fun runCompleted(result: GameEngine.Result): Map<Property, Any> = mapOf(
        SURVIVED_MS to result.millisSurvived,
        SURVIVED_SECONDS to result.millisSurvived / 1_000L,
        FRUIT_HIT to result.hits.toLong(),
        FRUIT_MISSED to result.strikes.toLong(),
        // The HUD's number, not the raw level: a run that reached the plateau reads "TOP
        // SPEED 61" on the card, and a stat that disagreed with the card would be wrong in
        // the only place the player can check it.
        TOP_SPEED to GameEngine.displaySpeed(result.topSpeedLevel).toLong(),
        RANKED to result.ranked,
    )

    /**
     * The progression event for a personal best of [bestMillis].
     *
     * Truncates rather than rounds: a best of 59,900 ms reporting as a minute would put a
     * player one tenth of a second short of the "Minute Made" achievement on a profile
     * claiming they had done it. Sent even at zero, because the documentation asks for this
     * event at launch specifically so the stat exists for a player who has not yet earned one.
     */
    fun progressUpdate(bestMillis: Long): Map<Property, Any> = mapOf(
        CURRENT_PROGRESS to bestMillis / 1_000L,
    )
}
