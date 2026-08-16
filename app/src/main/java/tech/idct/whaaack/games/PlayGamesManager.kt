package tech.idct.whaaack.games

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.google.android.gms.games.PlayGames
import com.google.android.gms.games.playergameevent.PlayerGameEvent
import tech.idct.whaaack.BuildConfig
import tech.idct.whaaack.game.GameEngine

/**
 * Everything the app does with Play Games Services: knowing whether the player is signed in,
 * offering them the way in if they are not, unlocking the survival milestones, and opening
 * the achievements UI.
 *
 * ## The sign-in it wraps
 *
 * PGS v2 has no sign-in *flow* for the app to drive. `PlayGamesSdk.initialize()` — called
 * once from [tech.idct.whaaack.WhaaackApp] — attempts an automatic sign-in against whichever
 * Play Games account the device already has, and by the time anything asks, it has usually
 * answered. So the app's job is not to authenticate anybody; it is to *ask* whether that
 * already happened ([refresh]) and, only if it did not, to offer a button that calls
 * [signIn]. That is the shape Google's guidance requires.
 *
 * Which is still a different act from signing into a Whaaack! *account*, the thing that owns
 * a leaderboard score — a player can have either, both or neither, and [signIn] creates no
 * account. What *does* is [serverAuthCode]: it turns an authenticated Play Games player into
 * proof our backend can check, and the account is minted from that. So "Continue with Play
 * Games" now appears on the auth screen beside Google, and the two really are alternatives
 * there. They are not interchangeable elsewhere, which is why the button is styled as its own
 * provider rather than as a second Google pill, and why Settings still offers Play Games
 * sign-in separately: that one is about achievements, and is reachable with no account at all.
 *
 * None of the ids in that handshake are configured here, because none of them can be: PGS
 * identifies the app by package name plus signing certificate against the OAuth *Android*
 * client registered in the console. There is nothing to paste into the code, which also
 * means a build signed with the wrong key fails to authenticate and can only say so — see
 * docs/PLAY-GAMES.md, which is worth reading before concluding that this class is broken.
 *
 * ## Failure is always silent to the player
 *
 * Every entry point no-ops when the SDK is unauthenticated, when Play Games is absent from
 * the device, or when an achievement id has not been configured yet. Achievements are a
 * bonus on top of a game that is complete without them; a player who has never opened Play
 * Games should not be shown an error about a service they did not ask for. The one exception
 * is [openAchievements], which the player asked for explicitly and so is owed an answer.
 *
 * ## Threading
 *
 * Main thread throughout. Every caller is the ViewModel or the Activity, and every Task
 * callback below is registered without an executor, which posts it to the main looper — so
 * [unlocked] needs no synchronisation.
 */
class PlayGamesManager(
    private val ids: Map<Achievement, String> = CONFIGURED_IDS,
) {

    /**
     * Null until [refresh] has answered once. Screens distinguish it from `false`, so nothing
     * offers a "Sign in to Play Games" row during the fraction of a second before the SDK's
     * automatic attempt has resolved — the same reason `sessionResolved` exists for Supabase.
     */
    private val _authenticated = MutableStateFlow<Boolean?>(null)
    val authenticated: StateFlow<Boolean?> = _authenticated.asStateFlow()

    /**
     * Milestones Play Games has confirmed for this process, so a re-award is cheap.
     *
     * Only ever added to on *success*. A failed unlock deliberately stays out, which is what
     * turns [award]'s re-run on every resume into a retry loop: an unlock that could not be
     * delivered — offline, or a Play Games outage — is attempted again the next time the app
     * comes to the foreground, without any queue of our own to persist and get wrong.
     */
    private val unlocked = mutableSetOf<Achievement>()

    /**
     * Asks the SDK whether its automatic sign-in has produced an authenticated player.
     *
     * [onResult] runs on the main thread once the answer is in. It exists so the caller can
     * hand back an [Activity] it still holds — the achievement clients need one, and the
     * back-fill in [award] has to happen exactly here, at the moment authentication is first
     * confirmed, rather than from a flow collector with no Activity in reach.
     */
    fun refresh(activity: Activity, onResult: (Boolean) -> Unit = {}) {
        PlayGames.getGamesSignInClient(activity).isAuthenticated()
            .addOnCompleteListener { task ->
                val ok = task.isSuccessful && task.result.isAuthenticated
                // A player who signed out of Play Games elsewhere must not keep the unlocks
                // this process believes it delivered: clearing them means the next successful
                // sign-in re-awards from the stored best rather than assuming it already did.
                if (!ok) unlocked.clear()
                _authenticated.value = ok
                onResult(ok)
            }
    }

    /**
     * Shows the Play Games sign-in prompt. Only ever called from a control the player pressed
     * — v2 already tried on its own at startup, and a second uninvited attempt would just be
     * a dialog in front of a game nobody asked to interrupt.
     */
    fun signIn(activity: Activity, onResult: (Boolean) -> Unit) {
        PlayGames.getGamesSignInClient(activity).signIn()
            .addOnCompleteListener { task ->
                val ok = task.isSuccessful && task.result.isAuthenticated
                _authenticated.value = ok
                onResult(ok)
            }
    }

    /**
     * Asks Play Games for a one-time code our backend can exchange for proof of who this
     * player is.
     *
     * The code is useless to anybody without the web client's secret, which is why it is safe
     * to hand it to the app at all — and why the app can never shortcut this by simply
     * claiming a player id. [serverClientId] must be the **web** OAuth client registered as a
     * Game server credential in the Play Games Services configuration, not the Android one.
     *
     * `forceRefreshToken` is false: the backend uses the resulting access token once, inside
     * the same request, to ask who the player is. Nothing here acts on their behalf later, so
     * asking Google to mint a refresh token would be requesting offline access we would then
     * have to store and protect for no purpose.
     *
     * Returns null when Play Games is not authenticated, when no server client is configured,
     * or when Play Games declines — all of which are the caller's cue to leave the player
     * exactly where they were.
     */
    suspend fun serverAuthCode(activity: Activity, serverClientId: String): String? {
        if (_authenticated.value != true) return null
        if (serverClientId.isBlank()) return null
        return suspendCancellableCoroutine { continuation ->
            PlayGames.getGamesSignInClient(activity)
                .requestServerSideAccess(serverClientId, false)
                .addOnCompleteListener { task ->
                    // `task.result` *throws* on a failed Task rather than returning null, so
                    // the success check has to happen before it is read, not inside a takeIf
                    // alongside it — that ordering turns every declined sign-in into a crash
                    // on the main thread.
                    if (!task.isSuccessful) {
                        Log.w(TAG, "No server auth code", task.exception)
                        continuation.resume(null)
                        return@addOnCompleteListener
                    }
                    continuation.resume(task.result?.takeIf { it.isNotBlank() })
                }
        }
    }

    /**
     * Unlocks every milestone [millisSurvived] has earned.
     *
     * Safe to call with a personal best rather than a single run, and that is the intended
     * second use: replaying the stored best after authentication is what gives a player who
     * has been playing signed out — or offline, or on a device with no Play Games at all —
     * their achievements the moment they do sign in. Play Games treats a repeated unlock as a
     * no-op, and [unlocked] keeps the repeats from leaving the device in the first place.
     */
    fun award(activity: Activity, millisSurvived: Long) {
        if (_authenticated.value != true) return
        val client = PlayGames.getAchievementsClient(activity)
        for (achievement in Achievement.earnedBy(millisSurvived)) {
            if (achievement in unlocked) continue
            val id = ids[achievement]
            if (id.isNullOrBlank()) continue
            // unlockImmediate rather than the fire-and-forget unlock: the ids are hand-copied
            // out of the console, and a typo in one is the single most likely thing to be
            // wrong here. Fire-and-forget would swallow that into a milestone that simply
            // never appears, with nothing in logcat tying it to the id that caused it.
            client.unlockImmediate(id).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    unlocked += achievement
                } else {
                    Log.w(TAG, "Could not unlock ${achievement.name} ($id)", task.exception)
                }
            }
        }
    }

    /**
     * Reports a finished run to Game Stats.
     *
     * `recordEvent` only buffers locally; `requestEventsUpload` is what actually sends. Both
     * are called here because a run is exactly the "game loop completion" the documentation
     * says to submit as soon as it occurs, and runs are minutes apart — there is no traffic
     * to save by batching them, and a buffer that is never flushed is a stat that never moves.
     *
     * Unlike [award] this has no back-fill and cannot have one: an achievement is a fact about
     * a personal best that is still on the device, while an event is a fact about a moment
     * that has passed. A run played while unauthenticated is not reported, and there is
     * nothing left afterwards to reconstruct it from. That is a real gap and the reason this
     * is gated rather than queued — inventing a local queue would mean inventing timestamps
     * for events the server would then have to trust.
     */
    fun reportRun(activity: Activity, result: GameEngine.Result, bestMillis: Long) {
        // Both in one batch, which is what `recordEvents` is for: a finished run is two facts
        // — what this run was, and what the best now stands at — and sending them together
        // means one upload rather than two, and no window in which the profile shows a run
        // that its own best does not account for.
        submit(
            activity,
            event(GameStats.RUN_COMPLETED, GameStats.runCompleted(result)),
            event(GameStats.PROGRESS_UPDATE, GameStats.progressUpdate(bestMillis)),
        )
    }

    /**
     * Sends the personal best on its own — at launch once authentication resolves, and after
     * a sign-in. The documentation asks for the progression event at launch precisely so the
     * stat exists for every player, and here it doubles as the back-fill for anyone who has
     * been playing with Play Games signed out.
     */
    fun reportBest(activity: Activity, bestMillis: Long) {
        submit(activity, event(GameStats.PROGRESS_UPDATE, GameStats.progressUpdate(bestMillis)))
    }

    private fun event(name: String, values: Map<GameStats.Property, Any>): PlayerGameEvent {
        val builder = PlayerGameEvent.Builder(name)
        for ((property, value) in values) {
            // `addProperty` is overloaded per type, and picking the wrong overload is exactly
            // the failure the schema exists to prevent: the event uploads, the console
            // rejects it for a type mismatch, and the app is told nothing at all. The `else`
            // is unreachable while GameStats keeps its promise, and says so loudly if it stops.
            when (value) {
                is Long -> builder.addProperty(property.name, value)
                is Double -> builder.addProperty(property.name, value)
                is Boolean -> builder.addProperty(property.name, value)
                is String -> builder.addProperty(property.name, value)
                else -> error("unsupported value ${value::class} for ${property.name}")
            }
        }
        return builder.build()
    }

    /**
     * `recordEvent`/`recordEvents` only buffer locally; `requestEventsUpload` is what sends.
     * Both happen here because these are the "game loop completion" moments the documentation
     * says to submit as soon as they occur, and they are minutes apart — there is no traffic
     * to save by holding them back, and a buffer nothing ever flushes is a stat that never
     * moves.
     */
    private fun submit(activity: Activity, vararg events: PlayerGameEvent) {
        if (_authenticated.value != true) return
        val client = PlayGames.getGameStatsClient(activity)
        if (events.size == 1) client.recordEvent(events[0]) else client.recordEvents(events.toList())
        client.requestEventsUpload()
    }

    /**
     * Opens the Play Games achievements UI.
     *
     * [onUnavailable] is called with a line fit to show the player: this one is reachable
     * only by pressing a control that says "Achievements", so silence would read as a dead
     * button. It fires when Play Games declines to build the Intent, which in practice means
     * the sign-in did not hold — the Activity may also be gone by the time the Task lands,
     * which is a race, not a failure, and is left to the platform to ignore.
     */
    fun openAchievements(activity: Activity, onUnavailable: (String) -> Unit) {
        PlayGames.getAchievementsClient(activity).getAchievementsIntent()
            .addOnSuccessListener { intent -> activity.startActivity(intent) }
            .addOnFailureListener { error ->
                Log.w(TAG, "Achievements UI unavailable", error)
                _authenticated.value = false
                onUnavailable("Couldn't open Play Games — try signing in to it again.")
            }
    }

    internal companion object {
        private const val TAG = "PlayGames"

        /**
         * Console ids per milestone, by way of BuildConfig — committed defaults, overridable
         * from local.properties. A blank id is skipped rather than sent, which is what let
         * the app ship and play normally in the window before the achievements existed.
         *
         * Internal rather than private so `AchievementTest` can check this table itself. The
         * four ids differ only in their final character, which makes pasting the same one
         * twice the realistic mistake here — and one that would be found by a player earning
         * the wrong badge rather than by anything else in the build.
         */
        internal val CONFIGURED_IDS: Map<Achievement, String> = mapOf(
            Achievement.SURVIVE_30 to BuildConfig.PGS_ACHIEVEMENT_SURVIVE_30,
            Achievement.SURVIVE_60 to BuildConfig.PGS_ACHIEVEMENT_SURVIVE_60,
            Achievement.SURVIVE_90 to BuildConfig.PGS_ACHIEVEMENT_SURVIVE_90,
            Achievement.SURVIVE_120 to BuildConfig.PGS_ACHIEVEMENT_SURVIVE_120,
        )
    }
}
