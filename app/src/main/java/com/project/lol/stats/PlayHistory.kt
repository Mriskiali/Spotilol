package com.project.lol.stats

// ponytail: plain data class, not a @Entity — Room/KSP would add build cost and a new
// dep for one table; upgrade to Room when queries outgrow SQLiteOpenHelper.

data class PlayHistory(
    val id: Long = 0L,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val playedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val source: String = "spotify" // spotify | yt | local
) {
    companion object {
        const val TABLE = "play_history"
    }
}
