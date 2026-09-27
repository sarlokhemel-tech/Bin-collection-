package com.hemel.dailynotebook.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

@Database(entities = [Notebook::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // v1 -> v2: added free-write text and the draft (temporary/auto-save) flag.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notebooks ADD COLUMN freeText TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN freeTextSpans TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN isDraft INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v2 -> v3: remembers whether the "extra page" (double-width, horizontally
        // scrollable) mode was turned on for this notebook.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notebooks ADD COLUMN extraPage INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hemel_notebook.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}
