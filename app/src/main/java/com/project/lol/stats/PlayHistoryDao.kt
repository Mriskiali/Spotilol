package com.project.lol.stats

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

// ponytail: framework SQLite, no Room/KSP — upgrade when queries outgrow one table.

class PlayHistoryDao(private val db: SQLiteDatabase) {

    data class TopTrack(
        val trackId: String,
        val title: String,
        val artist: String,
        val plays: Int,
        val lastPlayed: Long
    )

    fun insert(e: PlayHistory): Long {
        val v = ContentValues().apply {
            put("trackId", e.trackId)
            put("title", e.title)
            put("artist", e.artist)
            put("album", e.album)
            put("playedAt", e.playedAt)
            put("durationMs", e.durationMs)
            put("source", e.source)
        }
        return db.insert(PlayHistory.TABLE, null, v)
    }

    fun recent(limit: Int = 100): List<PlayHistory> =
        query("SELECT * FROM ${PlayHistory.TABLE} ORDER BY playedAt DESC LIMIT $limit")

    fun since(sinceMs: Long, limit: Int = 500): List<PlayHistory> =
        query(
            "SELECT * FROM ${PlayHistory.TABLE} WHERE playedAt >= $sinceMs ORDER BY playedAt DESC LIMIT $limit"
        )

    fun topTracks(sinceMs: Long = 0L, limit: Int = 25): List<TopTrack> {
        val out = ArrayList<TopTrack>()
        db.rawQuery(
            "SELECT trackId, title, artist, COUNT(*) AS plays, MAX(playedAt) AS lastPlayed" +
                " FROM ${PlayHistory.TABLE} WHERE playedAt >= ?" +
                " GROUP BY trackId ORDER BY plays DESC, lastPlayed DESC LIMIT $limit",
            arrayOf(sinceMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += TopTrack(
                    c.getString(0), c.getString(1), c.getString(2),
                    c.getInt(3), c.getLong(4)
                )
            }
        }
        return out
    }

    fun playCount(trackId: String): Int {
        db.rawQuery(
            "SELECT COUNT(*) FROM ${PlayHistory.TABLE} WHERE trackId = ?",
            arrayOf(trackId)
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun deleteOlderThan(cutoffMs: Long): Int =
        db.delete(PlayHistory.TABLE, "playedAt < ?", arrayOf(cutoffMs.toString()))

    fun clear() = db.delete(PlayHistory.TABLE, null, null)

    private fun query(sql: String): List<PlayHistory> {
        val out = ArrayList<PlayHistory>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out += PlayHistory(
                    id = c.getLong(0),
                    trackId = c.getString(1),
                    title = c.getString(2),
                    artist = c.getString(3),
                    album = c.getString(4),
                    playedAt = c.getLong(5),
                    durationMs = c.getLong(6),
                    source = c.getString(7)
                )
            }
        }
        return out
    }
}
