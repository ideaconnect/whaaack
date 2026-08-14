package tech.idct.whaaack.audio

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * All of the game's sound. Short effects go through a [SoundPool] (fire-and-forget, safe
 * to call from the render thread); the two music loops use [MediaPlayer], driven from the
 * main thread because MediaPlayer's state machine is not thread-safe.
 */
class AudioEngine(context: Context) {

    enum class Track { NONE, MENU, GAME }

    private val app = context.applicationContext
    private val assets: AssetManager = app.assets
    private val main = Handler(Looper.getMainLooper())

    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val loaded = AtomicBoolean(false)
    private var blipId = 0
    private var hurtId = 0
    private var loseId = 0
    private var splatIds = IntArray(0)

    // All five are touched only on the main thread, inside the posts below.
    private var player: MediaPlayer? = null
    private var currentTrack = Track.NONE
    private var desiredTrack = Track.NONE

    /** True between prepareAsync and onPrepared, while the player must not be touched. */
    private var preparing = false

    /** True between onPause and onResume, so a prepare that lands meanwhile stays quiet. */
    private var lifecyclePaused = false

    /**
     * True while another app owns the audio focus and we have been asked to stand down for
     * a while — a call, a navigation prompt, another player starting up. Read alongside
     * [lifecyclePaused] everywhere, because "should the music be audible right now" is the
     * conjunction of the two and neither one alone is the answer.
     */
    private var focusLostTransiently = false

    /** True while ducked for a transient-can-duck loss, so the volume can be restored. */
    private var ducked = false

    /** Bumped whenever the current player is discarded, to strand in-flight prepares. */
    private var generation = 0

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /**
     * Everything here is posted to [main] rather than run inline: the system delivers focus
     * changes on its own thread, and every field this touches is main-thread-confined.
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Permanent: somebody else owns the output now. Stop and forget, rather
                    // than lurking ready to barge back in over whatever they are playing.
                    focusLostTransiently = false
                    ducked = false
                    desiredTrack = Track.NONE
                    stopMusicInternal()
                    abandonFocus()
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // A call, or a prompt that wants silence. Pause; keep desiredTrack so
                    // AUDIOFOCUS_GAIN can put it back exactly where it was.
                    focusLostTransiently = true
                    runCatching { player?.takeIf { it.isPlaying }?.pause() }
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Navigation and notification audio: stay playing, get out of the way.
                    ducked = true
                    runCatching { player?.setVolume(DUCK_VOLUME, DUCK_VOLUME) }
                }

                AudioManager.AUDIOFOCUS_GAIN -> {
                    focusLostTransiently = false
                    if (ducked) {
                        ducked = false
                        runCatching { player?.setVolume(MUSIC_VOLUME, MUSIC_VOLUME) }
                    }
                    if (musicEnabled) applyTrack(desiredTrack)
                }
            }
        }
    }

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(focusAttributes)
            .setOnAudioFocusChangeListener(focusListener, main)
            .setWillPauseWhenDucked(false)
            .build()

    private var holdsFocus = false

    /** True only when nothing outside this class is telling the music to be quiet. */
    private val audible: Boolean get() = !lifecyclePaused && !focusLostTransiently

    private fun acquireFocus(): Boolean {
        if (holdsFocus) return true
        val granted = runCatching { audioManager.requestAudioFocus(focusRequest) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        holdsFocus = granted
        return granted
    }

    private fun abandonFocus() {
        if (!holdsFocus) return
        holdsFocus = false
        focusLostTransiently = false
        ducked = false
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    @Volatile
    var soundEnabled: Boolean = true

    @Volatile
    var musicEnabled: Boolean = true
        set(value) {
            // The settings flow re-publishes every preference on every write, so this is
            // assigned again whenever sound, haptics, parallax or the local best changes.
            // Only a real change is worth a trip to the main thread.
            if (field == value) return
            field = value
            main.post {
                if (value) {
                    applyTrack(desiredTrack)
                } else {
                    stopMusicInternal()
                    abandonFocus()
                }
            }
        }

    /** Decodes the effect bank. Blocking; call off the main thread. */
    fun preload() {
        if (loaded.getAndSet(true)) return
        blipId = loadEffect("audio/menu_blip.wav")
        hurtId = loadEffect("audio/hurt.wav")
        loseId = loadEffect("audio/lose.wav")
        splatIds = SPLAT_FILES.map { loadEffect("audio/splats/$it") }.toIntArray()
    }

    private fun loadEffect(path: String): Int = try {
        assets.openFd(path).use { pool.load(it, 1) }
    } catch (e: Exception) {
        Log.w(TAG, "Missing sound asset $path", e)
        0
    }

    // ---- effects (safe from any thread) --------------------------------------------

    fun blip() = play(blipId, 0.75f)

    fun hurt() = play(hurtId, 1f)

    fun lose() = play(loseId, 1f)

    /** A random squelch from the splats collection, with a little pitch variation. */
    fun splat() {
        if (splatIds.isEmpty()) return
        val id = splatIds[Random.nextInt(splatIds.size)]
        play(id, 0.9f, rate = 0.92f + Random.nextFloat() * 0.18f)
    }

    private fun play(soundId: Int, volume: Float, rate: Float = 1f) {
        if (!soundEnabled || soundId == 0) return
        runCatching { pool.play(soundId, volume, volume, 1, 0, rate) }
    }

    // ---- music ---------------------------------------------------------------------

    fun playTrack(track: Track) {
        desiredTrack = track
        main.post { if (musicEnabled) applyTrack(track) else stopMusicInternal() }
    }

    fun stopMusic() {
        desiredTrack = Track.NONE
        main.post {
            stopMusicInternal()
            abandonFocus()
        }
    }

    /** Pauses the loop without forgetting which one should resume (for onPause). */
    fun pauseMusic() {
        main.post {
            lifecyclePaused = true
            runCatching { player?.takeIf { it.isPlaying }?.pause() }
            // Backgrounded, or an interstitial is about to take the screen. Holding focus
            // while silent would keep whatever the player switches to ducked underneath us.
            abandonFocus()
        }
    }

    fun resumeMusic() {
        main.post {
            lifecyclePaused = false
            if (musicEnabled) applyTrack(desiredTrack)
        }
    }

    private fun applyTrack(track: Track) {
        if (track == Track.NONE) {
            stopMusicInternal()
            abandonFocus()
            return
        }
        // Ask before playing. Without this the loop simply talks over an incoming call or a
        // navigation prompt, and starting the game does not stop whatever the player already
        // had running — both play at once. A refusal is not an error: some other app is
        // legitimately using the output, so stay silent and let AUDIOFOCUS_GAIN bring us back.
        if (!acquireFocus()) return
        if (track == currentTrack && player != null) {
            // Already loaded: nudge it back into playing. Still loading: leave it alone and
            // let onPrepared start it. start() on a preparing MediaPlayer is an illegal
            // transition — the engine reports an error and parks the object in the Error
            // state, after which onPrepared never arrives and the track is silently dead for
            // the rest of the session. Two playTrack calls landing back to back is ordinary
            // (a navigation posts one and the screen's effect posts another), so this was
            // reachable on essentially every menu.
            if (!preparing && audible) {
                runCatching { player?.takeIf { !it.isPlaying }?.start() }
            }
            return
        }
        stopMusicInternal()

        val asset = when (track) {
            Track.MENU -> "audio/MENU.ogg"
            Track.GAME -> "audio/GAME.ogg"
            Track.NONE -> return
        }
        // prepareAsync, not prepare: these are multi-megabyte OGGs and this runs on the main
        // thread, so a synchronous prepare stalls the UI at every track change and on resume.
        val token = ++generation
        player = runCatching {
            MediaPlayer().apply {
                assets.openFd(asset).use { fd ->
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                isLooping = true
                setVolume(MUSIC_VOLUME, MUSIC_VOLUME)
                setOnErrorListener { failed, what, extra ->
                    Log.w(TAG, "MediaPlayer error $what/$extra on $asset")
                    // Leave nothing behind in the Error state: a failed player can never be
                    // started again, so drop it and let the next playTrack build a fresh one
                    // rather than poke at a corpse. Posted rather than run inline because an
                    // error can come back synchronously from prepareAsync, before this player
                    // has been assigned to the field the teardown is meant to clear.
                    main.post {
                        if (token == generation) {
                            stopMusicInternal()
                        } else {
                            runCatching { failed.release() }
                        }
                    }
                    true
                }
                setOnPreparedListener { prepared ->
                    // The track may have been switched, or the app backgrounded, while this
                    // was loading. A stale player is nobody's job but ours to release.
                    if (token != generation) {
                        runCatching { prepared.release() }
                        return@setOnPreparedListener
                    }
                    preparing = false
                    // Volume is set here rather than only at construction because a duck can
                    // land while this player was still loading.
                    runCatching {
                        val v = if (ducked) DUCK_VOLUME else MUSIC_VOLUME
                        prepared.setVolume(v, v)
                    }
                    if (musicEnabled && audible) runCatching { prepared.start() }
                }
                preparing = true
                prepareAsync()
            }
        }.onFailure { Log.w(TAG, "Could not start $asset", it) }.getOrNull()

        if (player == null) {
            preparing = false
            currentTrack = Track.NONE
        } else {
            currentTrack = track
        }
    }

    /**
   * Tears the player down but deliberately does NOT touch audio focus: [applyTrack] calls
   * this in the middle of a track switch, and abandoning there would hand the output away
   * between two of our own loops — and very likely fail to get it back. Focus is released
   * by the callers that mean "no music for now", each marked below.
   */
    private fun stopMusicInternal() {
        generation++
        preparing = false
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        currentTrack = Track.NONE
    }

    fun release() {
        main.post {
            stopMusicInternal()
            abandonFocus()
        }
        runCatching { pool.release() }
    }

    private companion object {
        const val TAG = "AudioEngine"
        const val MUSIC_VOLUME = 0.45f

        /** Where the loop sits while something more important is talking over it. */
        const val DUCK_VOLUME = 0.1f

        val SPLAT_FILES = listOf(
            "crunch_quick.wav",
            "crunch_splat.wav",
            "crunch_splat_2.wav",
            "splat_double_quick.wav",
            "splat_quick.wav",
            "squelching_1.wav",
            "squelching_2.wav",
            "squelching_3.wav",
            "squelching_4.wav",
        )
    }
}
