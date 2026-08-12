package tech.idct.whaaack.audio

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
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

    private var player: MediaPlayer? = null
    private var currentTrack = Track.NONE
    private var desiredTrack = Track.NONE

    @Volatile
    var soundEnabled: Boolean = true

    @Volatile
    var musicEnabled: Boolean = true
        set(value) {
            field = value
            main.post { if (value) applyTrack(desiredTrack) else stopMusicInternal() }
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
        main.post { stopMusicInternal() }
    }

    /** Pauses the loop without forgetting which one should resume (for onPause). */
    fun pauseMusic() {
        main.post { runCatching { player?.takeIf { it.isPlaying }?.pause() } }
    }

    fun resumeMusic() {
        main.post { if (musicEnabled) applyTrack(desiredTrack) }
    }

    private fun applyTrack(track: Track) {
        if (track == Track.NONE) {
            stopMusicInternal()
            return
        }
        if (track == currentTrack && player != null) {
            runCatching { player?.takeIf { !it.isPlaying }?.start() }
            return
        }
        stopMusicInternal()

        val asset = when (track) {
            Track.MENU -> "audio/MENU.ogg"
            Track.GAME -> "audio/GAME.ogg"
            Track.NONE -> return
        }
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
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error $what/$extra on $asset")
                    true
                }
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "Could not start $asset", it) }.getOrNull()

        currentTrack = if (player != null) track else Track.NONE
    }

    private fun stopMusicInternal() {
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        currentTrack = Track.NONE
    }

    fun release() {
        main.post { stopMusicInternal() }
        runCatching { pool.release() }
    }

    private companion object {
        const val TAG = "AudioEngine"
        const val MUSIC_VOLUME = 0.45f

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
