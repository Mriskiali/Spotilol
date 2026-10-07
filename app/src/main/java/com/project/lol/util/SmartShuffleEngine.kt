package com.project.lol.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.project.lol.stats.PlayHistoryDatabase
import com.project.lol.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/*
 * CREDIT: Spotilol - Smart Shuffle / Enhance.
 * Reads the current queue from the Spotify Web Player (DOM, same selectors as
 * TrackObserver/MediaUpdater), then recommends similar tracks:
 *   1) GET https://api.spotify.com/v1/recommendations?seed_tracks=...  (Bearer token)
 *   2) fallback: cosine similarity over local play history (title/artist/album tokens).
 * All callbacks run on the main thread.
 */

object SmartShuffleEngine {

    private const val TAG = "smartshuffle"
    private const val REC_URL = "https://api.spotify.com/v1/recommendations"
    private const val UA = "Spotilol/1.1.8"

    // Queue rows live in the "Queue" panel; each track row links /track/<22 chars>.
    private val JS_GET_QUEUE = """
        (function(){
            var ids = [];
            var els = document.querySelectorAll('a[href*="/track/"]');
            for (var i = 0; i < els.length; i++) {
                var m = /\/track\/([a-zA-Z0-9]{22})/.exec(els[i].getAttribute('href') || '');
                if (m && ids.indexOf(m[1]) === -1) ids.push(m[1]);
            }
            return JSON.stringify(ids);
        })()
    """.trimIndent()

    private val JS_GET_TOKEN = """
        (function(){
            try {
                var s = localStorage.getItem('accessToken') || sessionStorage.getItem('accessToken') || '';
                return s;
            } catch(e) { return ''; }
        })()
    """.trimIndent()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SmartShuffle-Worker").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    data class Track(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String = "",
        val score: Double = 0.0,
        val reason: String = ""
    ) {
        val uri: String get() = "spotify:track:$trackId"

        fun toJson() = JSONObject().apply {
            put("trackId", trackId)
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("score", score)
            put("reason", reason)
            put("uri", uri)
        }
    }

    /**
     * Queue -> recommendations. Falls back to local history cosine similarity
     * when the recommendations endpoint fails or no token is available.
     * @param cb invoked on the main thread with ordered suggestions.
     */
    fun recommend(context: Context, webView: WebView, limit: Int = 10, cb: (List<Track>) -> Unit) {
        readQueue(webView) { queueIds ->
            readToken(webView) { token ->
                executor.execute {
                    val result = runCatching {
                        val api = token?.takeIf { it.isNotBlank() }
                            ?.let { fetchRecommendations(queueIds, it, limit) }
                            .orEmpty()
                        if (api.isNotEmpty()) api else localSimilar(context, queueIds, limit)
                    }.getOrElse { e ->
                        Logger.e(TAG, "recommend failed, trying local fallback", e)
                        runCatching { localSimilar(context, queueIds, limit) }
                            .getOrElse { emptyList() }
                    }
                    main.post { cb(result) }
                }
            }
        }
    }

    /** Queue track ids (22-char base62) currently shown in the player. */
    fun readQueue(webView: WebView, cb: (List<String>) -> Unit) {
        main.post {
            webView.evaluateJavascript(JS_GET_QUEUE) { raw ->
                cb(parseIdList(raw))
            }
        }
    }

    private fun parseIdList(raw: String?): List<String> {
        val json = raw?.trim('"') ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i, "").takeIf { it.length == 22 }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "queue parse failed: $raw")
            emptyList()
        }
    }

    private fun readToken(webView: WebView, cb: (String?) -> Unit) {
        main.post {
            webView.evaluateJavascript(JS_GET_TOKEN) { raw ->
                val token = raw?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                cb(token)
            }
        }
    }

    /** GET /v1/recommendations?seed_tracks=id1,id2,... — max 5 seeds, max 100 results. */
    private fun fetchRecommendations(seedIds: List<String>, token: String, limit: Int): List<Track> {
        if (seedIds.isEmpty()) return emptyList()
        val seeds = seedIds.take(5).joinToString(",")
        val url = "$REC_URL?seed_tracks=$seeds&limit=$limit&market=from_token"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("User-Agent", UA)
            val code = conn.responseCode
            val body = (if (code >= 400) conn.errorStream else conn.inputStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code != 200) {
                Logger.w(TAG, "recommendations HTTP $code: ${body.take(160)}")
                emptyList()
            } else {
                parseTracks(body).map { it.copy(reason = "seed_tracks") }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTracks(json: String): List<Track> {
        val arr = JSONObject(json).optJSONArray("tracks") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = t.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            val artists = t.optJSONArray("artists")?.let { a ->
                (0 until a.length()).joinToString(", ") { a.optJSONObject(it)?.optString("name").orEmpty() }
            }.orEmpty()
            Track(
                trackId = id,
                title = t.optString("name"),
                artist = artists,
                album = t.optJSONObject("album")?.optString("name").orEmpty()
            )
        }
    }

    /* ---- local fallback: cosine similarity over history term vectors ---- */

    private fun localSimilar(context: Context, queueIds: List<String>, limit: Int): List<Track> {
        val dao = PlayHistoryDatabase.get(context).dao()
        val recent = dao.recent(500)
        if (recent.isEmpty()) return emptyList()

        // profile of the current queue = centroid of queue tracks' history vectors
        val vectors = HashMap<String, Map<String, Double>>()
        recent.forEach { e -> vectors.getOrPut(e.trackId) { termVector(e.title, e.artist, e.album) } }

        val queueVecs = queueIds.mapNotNull { vectors[it] }
        val centroid = centroidOf(queueVecs)

        val queueSet = queueIds.toSet()
        val scored = HashMap<String, Track>()
        recent.forEach { e ->
            if (e.trackId in queueSet) return@forEach
            if (scored.containsKey(e.trackId)) return@forEach
            val sim = cosine(centroid, vectors.getOrPut(e.trackId) { termVector(e.title, e.artist, e.album) })
            if (sim > 0.0) {
                scored[e.trackId] = Track(e.trackId, e.title, e.artist, e.album, sim, "cosine")
            }
        }
        return scored.values.sortedByDescending { it.score }.take(limit)
    }

    internal fun termVector(title: String, artist: String, album: String): Map<String, Double> {
        val counts = HashMap<String, Double>()
        (listOf(title, artist, album)).forEach { field ->
            field.lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length > 1 && it !in STOP_WORDS }
                .forEach { counts.merge(it, 1.0, Double::plus) }
        }
        // log-tf dampens repeats inside one field
        return counts.mapValues { (_, v) -> 1.0 + kotlin.math.ln(v) }
    }

    internal fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dot = a.entries.sumOf { (k, v) -> v * (b[k] ?: 0.0) }
        if (dot == 0.0) return 0.0
        val na = kotlin.math.sqrt(a.values.sumOf { it * it })
        val nb = kotlin.math.sqrt(b.values.sumOf { it * it })
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
    }

    private fun centroidOf(vectors: List<Map<String, Double>>): Map<String, Double> {
        if (vectors.isEmpty()) return emptyMap()
        val sums = HashMap<String, Double>()
        vectors.forEach { v -> v.forEach { (k, x) -> sums.merge(k, x, Double::plus) } }
        val n = vectors.size.toDouble()
        return sums.mapValues { (_, v) -> v / n }
    }

    private val STOP_WORDS = setOf(
        "the", "a", "an", "of", "and", "or", "to", "in", "on", "with", "feat", "ft", "remix", "edit", "version"
    )

    @Suppress("unused")
    fun debugSnapshot(context: Context): String = try {
        "history=${PlayHistoryDatabase.get(context).dao().recent(1000).size}"
    } catch (e: Exception) {
        "error: ${e.message}"
    }
}
