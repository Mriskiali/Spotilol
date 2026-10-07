package com.project.lol.stats

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class PlayHistoryDatabase private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "play_history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE ${PlayHistory.TABLE} (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "trackId TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "artist TEXT NOT NULL," +
                "album TEXT NOT NULL DEFAULT ''," +
                "playedAt INTEGER NOT NULL," +
                "durationMs INTEGER NOT NULL DEFAULT 0," +
                "source TEXT NOT NULL DEFAULT 'spotify')"
        )
        db.execSQL("CREATE INDEX idx_ph_playedAt ON ${PlayHistory.TABLE} (playedAt)")
        db.execSQL("CREATE INDEX idx_ph_trackId ON ${PlayHistory.TABLE} (trackId)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun dao(): PlayHistoryDao = PlayHistoryDao(writableDatabase)

    companion object {
        @Volatile
        private var instance: PlayHistoryDatabase? = null

        fun get(context: Context): PlayHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: PlayHistoryDatabase(context).also { instance = it }
            }
    }
}
