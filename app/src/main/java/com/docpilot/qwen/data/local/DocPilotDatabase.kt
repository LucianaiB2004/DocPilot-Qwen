package com.docpilot.qwen.data.local

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DocumentEntity::class, ChatMessageEntity::class, ExtractionEntity::class],
    version = 2,
    exportSchema = false
)
abstract class DocPilotDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE documents ADD COLUMN folder TEXT NOT NULL DEFAULT '默认'")
                database.execSQL("ALTER TABLE documents ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE documents ADD COLUMN pageCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE documents ADD COLUMN parseProgress INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE documents ADD COLUMN citationsJson TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("ALTER TABLE chat_messages ADD COLUMN streaming INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chat_messages ADD COLUMN citationsJson TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("ALTER TABLE extractions ADD COLUMN citationsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }
    }
}
