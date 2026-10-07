package com.project.lol.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.project.lol.util.Logger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * CREDIT: Spotilol - True gapless + crossfade playback engine.
 *
 * Two ExoPlayer instances ("A" and "B") share one audio session:
 *
 *   crossfadeMs == 0  -> gapless. A single player holds the whole queue via
 *                        setMediaItems(), which is Media3's native gapless
 *                        path: the next item is prepared and swapped at the
 *                        sample-accurate boundary, so there is no silence.
 *
 *   crossfadeMs > 0   -> true overlap. B prepares the next item and starts
 *                        N ms before A ends while both volumes ramp:
 *                        A: 1 -> 0, B: 0 -> 1. Requires two decoders, which is
 *                        the actual cost of a real crossfade.
 *
 * Audio comes from a resolved stream URL (the same InnerTube path the offline
 * downloader uses), NOT from the WebView: MSE/DRM streams cannot be decoded
 * twice, so a genuine crossfade is impossible on that path.
 */

data class QueueTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val streamUrl: String,
    val durationMs: Long = C.TIME_UNSET,
)

interface PlaybackEngineListener {
    /** Fired on the main thread whenever the now-playing item changes. */
    fun onTrackChanged(track: QueueTrack?, index: Int, total: Int)
    fun onPlayingChanged(playing: Boolean)
    fun onPositionChanged(positionMs: Long, durationMs: Long)
    fun onQueueEnd()
    fun onError(error: String)
}

class PlaybackEngine(private val context: Context) {

    companion object {
        private const val TAG = "PlaybackEngine"
        private const val KEY_CROSSFADE_MS = "crossfade_ms"
        private const val PREF_FALLBACK = 0
        private const val TICK_MS = 250L
        private const val RAMP_STEP_MS = 50L
        /** Crossfade longer than this is meaningless and hurts decoder load. */
        const val MAX_CROSSFADE_MS = 12_000
    }

    private val handler = Handler(Looper.getMainLooper())
    private var listener: PlaybackEngineListener? = null

    private var trackA: QueueTrack? = null
    private var trackB: QueueTrack? = null
    private var queue: List<QueueTrack> = emptyList()
    private var index = 0
    private var active: Player? = null
    private var ramping = false

    /** Set true while the WebView owns audio; ExoPlayer stays silent until cleared. */
    var suspended = false
        @MainThread set(value) {
            field = value
            if (value) pauseInternal()
        }

    val crossfadeMs: Int
        get() = runCatching {
            context.getSharedPreferences("spotilol_prefs", Context.MODE_PRIVATE)
                .getInt(KEY_CROSSFADE_MS, PREF_FALLBACK)
                .coerceIn(0, MAX_CROSSFADE_MS)
        }.getOrDefault(PREF_FALLBACK)

    fun setCrossfade(ms: Int) {
        context.getSharedPreferences("spotilol_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CROSSFADE_MS, ms.coerceIn(0, MAX_CROSSFADE_MS))
            .apply()
        // Gapless <-> crossfade changes the player topology, so rebuild lazily.
        if (ms == 0 && active === playerA) {
            // stay on A; next setQueue call re-selects the topology
        }
    }

    private fun buildPlayer(): ExoPlayer =
        ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // Hold enough buffer that the next item is ready before its
                    // start time, otherwise gapless degrades into a rebuffer.
                    .setBufferDurationsMs(15_000, 120_000, 1_500, 3_000)
                    .build()
            )
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                addListener(PlayerEvents())
            }

    val playerA: ExoPlayer by lazy { buildPlayer() }
    val playerB: ExoPlayer by lazy { buildPlayer() }

    private val ticker = object : Runnable {
        override fun run() {
            val p = active
            if (p != null && listener != null) {
                val dur = p.duration
                listener?.onPositionChanged(
                    p.currentPosition.coerceAtLeast(0L),
                    if (dur == C.TIME_UNSET || dur < 0) 0L else dur
                )
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
            trackA?.let {
                listener?.onTrackChanged(it, index, queue.size)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener?.onPlayingChanged(isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            Logger.e(TAG, "playback error", error)
            listener?.onError(error.errorCodeName)
        }
    }

    @MainThread
    fun setListener(l: PlaybackEngineListener?) {
        listener = l
    }

    @MainThread
    fun startTicker() = handler.postDelayed(ticker, TICK_MS)

    @MainThread
    fun stopTicker() = handler.removeCallbacks(ticker)

    @MainThread
    private fun mediaItemFor(track: QueueTrack): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.streamUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.coverUrl?.let { android.net.Uri.parse(it) })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    /**
     * Replace the queue and start at [startIndex]. When crossfade is enabled the
     * first item starts on A and B is left idle until the ramp window opens.
     */
    @MainThread
    fun setQueue(tracks: List<QueueTrack>, startIndex: Int = 0, playWhenReady: Boolean = true) {
        if (tracks.isEmpty()) {
            stop()
            return
        }
        stopTicker()
        queue = tracks
        index = startIndex.coerceIn(0, tracks.lastIndex)
        trackB = null

        if (crossfadeMs == 0) {
            // Gapless: one player, whole queue prepared up front.
            playerB.stop()
            playerB.clearMediaItems()
            playerA.clearMediaItems()
            playerA.setMediaItems(tracks.map(::mediaItemFor), index, C.TIME_UNSET)
            playerA.prepare()
            trackA = tracks[index]
            active = playerA
        } else {
            playerB.stop()
            playerB.clearMediaItems()
            playerA.clearMediaItems()
            playerA.setMediaItem(mediaItemFor(tracks[index]))
            playerA.prepare()
            trackA = tracks[index]
            active = playerA
            armCrossfade()
        }

        playerA.volume = 1f
        playerB.volume = 0f
        playerA.playWhenReady = playWhenReady && !suspended
        listener?.onTrackChanged(trackA, index, queue.size)
        startTicker()
    }

    /** Prepare B with the next item so the ramp has something decoded to reveal. */
    @MainThread
    private fun armCrossfade() {
        val next = queue.getOrNull(index + 1) ?: return
        if (playerB.mediaItemCount > 0) return
        trackB = next
        playerB.setMediaItem(mediaItemFor(next))
        playerB.prepare()
        // Never auto-advance B; the ramp drives it.
        playerB.playWhenReady = false
        playerB.volume = 0f
    }

    @MainThread
    fun play() {
        if (suspended) return
        when (active) {
            playerB -> swapToB()
            else -> playerA.play()
        }
    }

    @MainThread
    fun pause() {
        playerA.pause()
        playerB.pause()
    }

    private fun pauseInternal() {
        playerA.pause()
        playerB.pause()
    }

    @MainThread
    fun seekTo(positionMs: Long) {
        active?.seekTo(positionMs.coerceAtLeast(0L))
    }

    @MainThread
    fun skipToNext() {
        val wasPlaying = active?.playWhenReady == true
        stopRamp()
        if (crossfadeMs > 0) {
            // Hard cut is preferable to a ramp from a stale position.
            playerB.pause()
            playerB.volume = 0f
            playerB.stop()
            trackB = null
            playerA.stop()
            val next = index + 1
            if (next > queue.lastIndex) {
                finishQueue()
                return
            }
            index = next
            trackA = queue[next]
            playerA.setMediaItem(mediaItemFor(trackA!!))
            playerA.prepare()
            playerA.volume = 1f
            playerA.playWhenReady = wasPlaying && !suspended
            active = playerA
            armCrossfade()
            listener?.onTrackChanged(trackA, index, queue.size)
        } else {
            if (index >= queue.lastIndex) {
                finishQueue()
                return
            }
            playerA.seekToNextMediaItem()
        }
    }

    @MainThread
    fun skipToPrevious() {
        // Standard behaviour: restart the track unless we are near its start.
        val p = active
        if (p != null && p.currentPosition > 3_000L) {
            p.seekTo(0L)
            return
        }
        stopRamp()
        playerB.pause()
        playerB.stop()
        playerB.volume = 0f
        trackB = null
        index = if (index > 0) index - 1 else 0
        trackA = queue.getOrNull(index) ?: run { finishQueue(); return }
        val wasPlaying = active?.playWhenReady == true
        playerA.stop()
        playerA.setMediaItem(mediaItemFor(trackA!!))
        playerA.prepare()
        playerA.volume = 1f
        playerA.playWhenReady = wasPlaying && !suspended
        active = playerA
        armCrossfade()
        listener?.onTrackChanged(trackA, index, queue.size)
    }

    /** Playback settings passthrough used by the DSP/equalizer work. */
    @MainThread
    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 3f)
        listOf(playerA, playerB).forEach {
            it.playbackParameters = PlaybackParameters(clamped)
        }
    }

    @MainThread
    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        // Volume rides on top of the crossfade ramp, so keep the ramp factors
        // separate instead of overwriting player volume directly.
        playerA.setVolume(baseVolume * v)
        playerB.setVolume(baseVolume * v)
    }

    private var baseVolume = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    @MainThread
    fun stop() {
        stopRamp()
        stopTicker()
        listOf(playerA, playerB).forEach {
            it.stop()
            it.clearMediaItems()
            it.volume = 1f
        }
        active = null
        trackA = null
        trackB = null
        queue = emptyList()
        index = 0
        baseVolume = 1f
    }

    @MainThread
    fun release() {
        stop()
        handler.removeCallbacksAndMessages(null)
        listener = null
        runCatching { playerA.release() }
        runCatching { playerB.release() }
    }

    private fun finishQueue() {
        stopRamp()
        playerA.pause()
        playerB.pause()
        listener?.onPlayingChanged(false)
        listener?.onQueueEnd()
    }

    // ---- crossfade ramp -------------------------------------------------

    private val rampRunnable = object : Runnable {
        override fun run() {
            if (!ramping) return
            val a = active
            if (a == null) { stopRamp(); return }
            val aDur = a.duration
            if (aDur == C.TIME_UNSET || aDur <= 0) {
                // Unknown duration: give up on the overlap, cut cleanly.
                Logger.w(TAG, "unknown duration, skipping crossfade ramp")
                stopRamp()
                swapToB()
                return
            }
            val remaining = aDur - a.currentPosition
            val window = max(1L, crossfadeMs.toLong())
            if (remaining > window) {
                handler.postDelayed(this, RAMP_STEP_MS)
                return
            }
            if (remaining <= 0) {
                stopRamp()
                return
            }

            val progress = 1f - (remaining.toFloat() / window)
            val volume = min(1f, max(0f, progress))
            a.volume = (1f - volume) * baseVolume
            playerB.volume = volume * baseVolume
            if (volume >= 1f || playerB.playbackState == Player.STATE_ENDED) {
                stopRamp()
            } else {
                handler.postDelayed(this, RAMP_STEP_MS)
            }
        }
    }

    @MainThread
    private fun startRamp() {
        if (ramping || crossfadeMs == 0) return
        if (playerB.mediaItemCount == 0) return
        ramping = true
        playerB.playWhenReady = !suspended
        handler.post(rampRunnable)
    }

    @MainThread
    private fun stopRamp() {
        if (!ramping) return
        ramping = false
        handler.removeCallbacks(rampRunnable)
    }

    /** A finished its ramp: hand control to B and roll the queue forward. */
    @MainThread
    private fun swapToB() {
        val next = trackB ?: queue.getOrNull(index + 1) ?: run { finishQueue(); return }
        playerA.pause()
        playerA.stop()
        playerA.volume = 1f
        index += 1
        trackA = next
        trackB = null
        active = playerB
        playerB.volume = baseVolume
        playerB.playWhenReady = !suspended
        playerB.clearMediaItems()
        // Roll A forward to become the standby player for the next track.
        queue.getOrNull(index + 1)?.let { upcoming ->
            playerA.setMediaItem(mediaItemFor(upcoming))
            playerA.prepare()
            playerA.volume = 0f
            trackB = upcoming
        } ?: run {
            playerA.clearMediaItems()
        }
        listener?.onTrackChanged(trackA, index, queue.size)
        armCrossfade()
    }

    /** Called from the A player when the ramp window should open. */
    @MainThread
    fun onAApproachingEnd() = startRamp()

    @MainThread
    fun currentPositionMs(): Long = active?.currentPosition?.coerceAtLeast(0L) ?: 0L

    @MainThread
    fun durationMs(): Long {
        val d = active?.duration ?: C.TIME_UNSET
        return if (d == C.TIME_UNSET || d < 0) 0L else d
    }

    @MainThread
    fun isPlaying(): Boolean = active?.isPlaying == true

    @MainThread
    fun currentTrack(): QueueTrack? = trackA
}