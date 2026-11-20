package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationSessionEntity::class,
        ConversationMessageEntity::class,
        MemoryEntity::class,
        NoteEntity::class,
        ReminderEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class BumbleDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun noteDao(): NoteDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile
        private var INSTANCE: BumbleDatabase? = null

        fun getInstance(context: Context): BumbleDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BumbleDatabase::class.java,
                    "bumble_ai_local.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
