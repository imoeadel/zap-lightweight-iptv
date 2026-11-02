package com.moe.zap.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Local copy of the catalog. Browsing and search only ever read this, never the provider's server. */
class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "zap.db", null, 2) {
    init {
        // Readers keep seeing the previous catalog while a sync writes the next one.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE category(kind INTEGER NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, pos INTEGER NOT NULL, PRIMARY KEY(kind, id)) WITHOUT ROWID")
        db.execSQL(
            """CREATE TABLE item(
                _id INTEGER PRIMARY KEY,
                kind INTEGER NOT NULL,
                sid INTEGER NOT NULL,
                cat TEXT NOT NULL,
                name TEXT NOT NULL,
                icon TEXT NOT NULL,
                ext TEXT NOT NULL,
                num INTEGER NOT NULL,
                added INTEGER NOT NULL,
                rating TEXT NOT NULL,
                epg TEXT NOT NULL,
                search TEXT NOT NULL,
                pos INTEGER NOT NULL,
                archive INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE UNIQUE INDEX item_key ON item(kind, sid)")
        db.execSQL("CREATE INDEX item_browse ON item(kind, cat, pos)")
        db.execSQL("CREATE INDEX item_all ON item(kind, pos)")
        db.execSQL("CREATE INDEX item_recent ON item(kind, added DESC)")
        // FTS4 with the simple tokenizer: Search.normalize already folded case, accents and Arabic spelling.
        db.execSQL("""CREATE VIRTUAL TABLE item_fts USING fts4(content="item", search, tokenize=simple, prefix="2,3")""")
        // Keyed by the provider's ids, so favorites and resume points survive every catalog refresh.
        db.execSQL("CREATE TABLE favorite(kind INTEGER NOT NULL, sid INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(kind, sid)) WITHOUT ROWID")
        db.execSQL(
            """CREATE TABLE progress(
                kind INTEGER NOT NULL,
                sid INTEGER NOT NULL,
                parent INTEGER NOT NULL,
                pos INTEGER NOT NULL,
                dur INTEGER NOT NULL,
                at INTEGER NOT NULL,
                PRIMARY KEY(kind, sid)) WITHOUT ROWID"""
        )
    }

    // Keeps the login, favorites and resume points. Catch-up days arrive with the next library refresh.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE item ADD COLUMN archive INTEGER NOT NULL DEFAULT 0")
            Repo.prefs.edit().remove("lastSync").apply()
        }
    }

    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM category")
            db.execSQL("DELETE FROM item")
            db.execSQL("INSERT INTO item_fts(item_fts) VALUES('rebuild')")
            db.execSQL("DELETE FROM favorite")
            db.execSQL("DELETE FROM progress")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
