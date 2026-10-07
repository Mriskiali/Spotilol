package com.project.lol.util

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/*
 * CREDIT: Spotilol - SponsorBlock.
 * Fetches community-submitted sponsor/intro/outro segments from the public
 * SponsorBlock API and caches them per YouTube videoId.
 *
 * ponytail: single shared client + in-memory LRU only.
 * Upgrade path when segments must survive process death: persist the same
 * Segment list as JSON in SharedPreferences and seed the LRU on init.
 */

object SponsorBlockClient {

    private const val TAG = "sponsorblock"
    private const val BASE = "https://sponsor.ajay.app/api/skipSegments"

    /** Segment categories requested from the API. */
    val CATEGORIES = listOf("sponsor", "intro", "outro", "interaction", "music_offtopic")

    private const val CACHE_MAX = 64
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6h

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            // No expectSuccess: 404 means "no segments submitted", which is a normal
            // answer we want to cache rather than an exception to swallow.
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
            engine {
                config {
                    retryOnConnectionFailure(true)
                    protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
                }
            }
            defaultRequest {
                header("Accept", "application/json")
            }
        }
    }

    @Serializable
    data class Segment(
        /** SponsorBlock returns [start, end] as a two-element array. */
        val segment: List<Float> = emptyList(),
        val category: String = "",
        @SerialName("UUID") val uuid: String = "",
        @SerialName("videoDuration") val videoDuration: Float = 0f,
        /** Server sends 0/1, not true/false — Int keeps parsing from blowing up. */
        val locked: Int = 0,
        val votes: Int = 0,
    ) {
        val start: Double get() = segment.getOrNull(0)?.toDouble() ?: 0.0
        val end: Double get() = segment.getOrNull(1)?.toDouble() ?: 0.0

        /** Drop malformed/zero-length rows and clamp so end > start always holds. */
        fun valid(): Boolean =
            segment.size >= 2 &&
                start >= 0.0 &&
                end > start &&
                (videoDuration <= 0f || start < videoDuration)
    }

    private class Entry(val segments: List<Segment>, val at: Long)

    // accessOrder=true + removeEldestEntry = LRU without a dependency.
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > CACHE_MAX
    }

    /** Cached segments for [videoId] if still fresh, else null. */
    @Synchronized
    fun cached(videoId: String): List<Segment>? {
        val e = cache[videoId] ?: return null
        if (System.currentTimeMillis() - e.at > CACHE_TTL_MS) {
            cache.remove(videoId)
            return null
        }
        return e.segments
    }

    /**
     * Segments for [videoId], network-cached. Returns an empty list on any failure
     * (offline, 404 with no submissions, malformed JSON) so callers never have to
     * special-case errors — an empty list simply means "skip nothing".
     */
    suspend fun segments(videoId: String): List<Segment> {
        if (!videoId.isValidVideoId()) return emptyList()
        cached(videoId)?.let { return it }

        return try {
            val response = client.get(BASE) {
                // API is case-sensitive: only videoID (capital D) is accepted.
                parameter("videoID", videoId)
                parameter("categories", json.encodeToString(CATEGORIES))
            }
            // 404 = no segments submitted; cache empty list and return.
            if (response.status == io.ktor.http.HttpStatusCode.NotFound) {
                put(videoId, emptyList())
                Logger.d(TAG, "segments $videoId -> 404 (no submissions)")
                return emptyList()
            }
            // Only parse JSON on 2xx.
            if (!response.status.isSuccess()) {
                Logger.w(TAG, "segments $videoId -> ${response.status}")
                return emptyList()
            }
            val raw: List<Segment> = response.body()
            val ok = raw.filter { it.valid() }.sortedBy { it.start }
            put(videoId, ok)
            Logger.d(TAG, "segments $videoId -> ${ok.size}")
            ok
        } catch (e: Exception) {
            Logger.e(TAG, "segments $videoId failed: ${e.message}", e)
            emptyList()
        }
    }

    @Synchronized
    private fun put(videoId: String, segments: List<Segment>) {
        cache[videoId] = Entry(segments, System.currentTimeMillis())
    }

    @Synchronized
    fun clear() = cache.clear()

    @Synchronized
    fun size(): Int = cache.size

    /**
     * JSON payload for the JS hook: `[[startSeconds, endSeconds, "category"], ...]`.
     * Seconds, not milliseconds — matches HTMLMediaElement.currentTime.
     */
    fun toJsArray(segments: List<Segment>): String =
        segments.joinToString(",", "[", "]") { s ->
            "[${fmt(s.start)},${fmt(s.end)},${json.encodeToString(s.category)}]"
        }

    // Sub-second precision is enough for a seek target and keeps the JS payload small.
    private fun fmt(v: Double): String {
        val r = Math.round(v * 1000.0) / 1000.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }

    private fun String.isValidVideoId() =
        length == 11 && all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == '-' || it == '_' }
}