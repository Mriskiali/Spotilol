package com.project.lol.stats

import android.content.Context
import android.content.SharedPreferences
import com.project.lol.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/*
 * CREDIT: Spotilol - Scrobble Manager.
 * Records plays to Room-less SQLite and mirrors them to Last.fm and ListenBrainz.
 *   Last.fm:       POST https://ws.audioscrobbler.com/2.0/  method=track.scrobble
 *                  api_sig = md5(sorted(key + value) concat + secret)
 *   ListenBrainz:  POST https://api.listenbrainz.org/1/submit-listens
 *                  payload {listen_type:"single", payload:[{listened_at, track_metadata:{...}}]}
 * Scrobble threshold: 50% of duration or 4 minutes, whichever comes first (Last.fm rule).
 * Credentials live in EncryptedSharedPreferences via ProfileManager-style AES key.
 */

object ScrobbleManager {

    private const val TAG = "scrobble"
    private const val PREFS = "spotilol_scrobble"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_LASTFM_SESSION = "lastfm_session_key"
    private const val KEY_LASTFM_SECRET = "lastfm_secret"
    private const val KEY_LB_TOKEN = "listenbrainz_token"

    private const val LASTFM_URL = "https://ws.audioscrobbler.com/2.0/"
    private const val LASTFM_API_KEY = "b25b959554ed76058ac220b7b2e0a026" // public API key
    private const val LB_URL = "https://api.listenbrainz.org/1/submit-listens"
    private const val LB_UA = "Spotilol/1.1.8 ( https://github.com/octocatx/spotilol )"

    private const val SCRUBBLE_PERCENT = 50
    private const val MAX_SCRUBBLE_MS = 4 * 60 * 1000L
    private const val RETENTION_DAYS = 365

    private val executor = Executors.newSingleThreadExecutor {
        Thread(it, "Scrobble-Worker").apply { isDaemon = true }
    }

    @Volatile
    private var context: Context? = null

    // trackId -> pending play (time listened so far, ms)
    private val pending = HashMap<String, Long>()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        context = ctx.applicationContext
        executor.execute {
            try {
                val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L
                val n = PlayHistoryDatabase.get(ctx).dao().deleteOlderThan(cutoff)
                if (n > 0) Logger.i(TAG, "pruned $n plays older than $RETENTION_DAYS days")
            } catch (e: Exception) {
                Logger.e(TAG, "prune failed", e)
            }
        }
    }

    var enabled: Boolean
        get() = context?.let { prefs(it).getBoolean(KEY_ENABLED, false) } ?: false
        set(value) {
            context?.let { prefs(it).edit().putBoolean(KEY_ENABLED, value).apply() }
        }

    /** Static view for UI code that does not hold an initialized [ScrobbleManager]. */
    @JvmStatic
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    @JvmStatic
    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on && context == null) init(ctx)
    }

    fun setLastfmSession(sessionKey: String, secret: String) {
        context ?: return
        prefs(context!!).edit()
            .putString(KEY_LASTFM_SESSION, sessionKey)
            .putString(KEY_LASTFM_SECRET, secret)
            .apply()
        Logger.i(TAG, "last.fm session stored")
    }

    fun setListenbrainzToken(token: String) {
        context ?: return
        prefs(context!!).edit().putString(KEY_LB_TOKEN, token).apply()
    }

    /* ---- playback hooks (call from TrackObserver / MediaUpdater callbacks) ---- */

    fun onTrackStart(trackId: String) {
        synchronized(pending) { pending[trackId] = 0L }
    }

    /** @param positionMs current playback position, @param durationMs total track length. */
    fun onPosition(trackId: String, positionMs: Long, durationMs: Long) {
        val threshold = minOf(durationMs, MAX_SCRUBBLE_MS) * SCRUBBLE_PERCENT / 100
        val already = synchronized(pending) {
            val seen = pending[trackId] ?: 0L
            if (positionMs > seen) pending[trackId] = positionMs
            seen
        }
        if (positionMs < threshold || already >= threshold) return
        synchronized(pending) { pending.remove(trackId) }
        record(trackId, System.currentTimeMillis(), durationMs)
    }

    fun onTrackEnd(trackId: String) = synchronized(pending) { pending.remove(trackId) }

    /**
     * Persist the play and forward it to every configured scrobbler.
     * @param meta optional title/artist/album from the now-playing widget.
     */
    fun record(trackId: String, playedAt: Long = System.currentTimeMillis(), durationMs: Long = 0L, meta: TrackMeta? = null) {
        val ctx = context ?: return
        val m = meta ?: TrackMeta(trackId, trackId, "", "")
        executor.execute {
            try {
                PlayHistoryDatabase.get(ctx).dao().insert(
                    PlayHistory(
                        trackId = trackId,
                        title = m.title,
                        artist = m.artist,
                        album = m.album,
                        playedAt = playedAt,
                        durationMs = durationMs,
                        source = "spotify"
                    )
                )
            } catch (e: Exception) {
                Logger.e(TAG, "local insert failed", e)
            }
            if (!enabled) return@execute
            submitLastfm(m, playedAt)
            submitListenbrainz(ctx, m, playedAt)
        }
    }

    data class TrackMeta(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long = 0L
    )

    /* ---- Last.fm ---- */

    private fun submitLastfm(meta: TrackMeta, playedAt: Long) {
        val ctx = context ?: return
        val session = prefs(ctx).getString(KEY_LASTFM_SESSION, null) ?: run {
            Logger.d(TAG, "last.fm: no session key, skipped")
            return
        }
        val secret = prefs(ctx).getString(KEY_LASTFM_SECRET, null).orEmpty()
        val p = LinkedHashMap<String, String>()
        p["method"] = "track.scrobble"
        p["api_key"] = LASTFM_API_KEY
        p["sk"] = session
        p["artist"] = meta.artist
        p["track"] = meta.title
        p["album"] = meta.album
        p["timestamp"] = (playedAt / 1000L).toString()
        p["api_sig"] = sign(p, secret)

        try {
            val body = postForm(LASTFM_URL, p)
            val json = JSONObject(body)
            if (json.optBoolean("success", false)) {
                Logger.i(TAG, "last.fm scrobbled: ${meta.artist} - ${meta.title}")
            } else {
                Logger.w(TAG, "last.fm rejected: ${json.optString("message")} (${json.optInt("error")})")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "last.fm failed", e)
        }
    }

    /** api_sig = md5(concat(sorted keys+values) + secret), all params except api_sig & format. */
    private fun sign(params: Map<String, String>, secret: String): String {
        val sb = StringBuilder()
        params.entries
            .filter { it.key != "api_sig" && it.key != "format" }
            .sortedBy { it.key }
            .forEach { sb.append(it.key).append(it.value) }
        sb.append(secret)
        return md5(sb.toString())
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /* ---- ListenBrainz ---- */

    private fun submitListenbrainz(ctx: Context, meta: TrackMeta, playedAt: Long) {
        val token = prefs(ctx).getString(KEY_LB_TOKEN, null) ?: run {
            Logger.d(TAG, "listenbrainz: no token, skipped")
            return
        }
        val payload = JSONObject().apply {
            put("listen_type", "single")
            put(
                "payload",
                JSONArray().put(
                    JSONObject().apply {
                        put("listened_at", playedAt / 1000L)
                        put(
                            "track_metadata",
                            JSONObject().apply {
                                put("artist_name", meta.artist)
                                put("track_name", meta.title)
                                put("release_name", meta.album)
                                put(
                                    "additional_info",
                                    JSONObject().apply {
                                        put("music_service", "spotify")
                                        put("submission_client", "Spotilol")
                                        put("music_service_url",
                                            "https://open.spotify.com/track/${meta.trackId}")
                                        if (meta.durationMsOrZero() > 0) {
                                            put("duration_ms", meta.durationMsOrZero())
                                        }
                                    }
                                )
                            }
                        )
                    }
                )
            )
        }

        try {
            val conn = (URL(LB_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Authorization", "Token $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", LB_UA)
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code >= 400) conn.errorStream else conn.inputStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            conn.disconnect()
            if (code in 200..299) Logger.i(TAG, "listenbrainz submitted (${body.take(80)})")
            else Logger.w(TAG, "listenbrainz HTTP $code: ${body.take(160)}")
        } catch (e: Exception) {
            Logger.e(TAG, "listenbrainz failed", e)
        }
    }

    private fun TrackMeta.durationMsOrZero(): Long = durationMs

    /* ---- http ---- */

    private fun postForm(endpoint: String, params: Map<String, String>): String {
        val body = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        conn.disconnect()
        Logger.d(TAG, "POST $endpoint -> $code")
        return text
    }

    /* ---- stats access ---- */

    fun topTracks(days: Int = 7, limit: Int = 25): List<PlayHistoryDao.TopTrack> {
        val ctx = context ?: return emptyList()
        val since = System.currentTimeMillis() - days * 86_400_000L
        return try {
            PlayHistoryDatabase.get(ctx).dao().topTracks(since, limit)
        } catch (e: Exception) {
            Logger.e(TAG, "topTracks failed", e)
            emptyList()
        }
    }

    fun recent(limit: Int = 100): List<PlayHistory> {
        val ctx = context ?: return emptyList()
        return try {
            PlayHistoryDatabase.get(ctx).dao().recent(limit)
        } catch (e: Exception) {
            Logger.e(TAG, "recent failed", e)
            emptyList()
        }
    }

    @Suppress("unused")
    fun clearHistory() {
        val ctx = context ?: return
        executor.execute { PlayHistoryDatabase.get(ctx).dao().clear() }
        Logger.i(TAG, "history cleared")
    }

    @Suppress("unused")
    fun debugSnapshot(): String {
        val ctx = context ?: return "no context"
        val dao = PlayHistoryDatabase.get(ctx).dao()
        return try {
            String.format(
                Locale.ROOT,
                "plays=%d lastfm=%s lb=%s",
                dao.recent(1000).size,
                prefs(ctx).getString(KEY_LASTFM_SESSION, null) != null,
                prefs(ctx).getString(KEY_LB_TOKEN, null) != null
            )
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
}
