package com.piko.agent.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TaskEntity::class, TaskStepEntity::class, MemoryEntity::class, WorkflowEntity::class, ScheduleEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class PikoDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun assistantDao(): AssistantDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS memories (id TEXT NOT NULL PRIMARY KEY, memoryKey TEXT NOT NULL, content TEXT NOT NULL, category TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, expiresAt INTEGER, source TEXT NOT NULL, approved INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memories_memoryKey ON memories(memoryKey)")
                db.execSQL("CREATE TABLE IF NOT EXISTS workflows (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL, stepsJson TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS workflow_schedules (id TEXT NOT NULL PRIMARY KEY, workflowId TEXT NOT NULL, hour INTEGER NOT NULL, minute INTEGER NOT NULL, recurrence TEXT NOT NULL, weekday INTEGER NOT NULL, nextRunAt INTEGER NOT NULL, enabled INTEGER NOT NULL, lastResult TEXT)")
            }
        }
        @Volatile private var instance: PikoDatabase? = null

        fun get(context: Context): PikoDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PikoDatabase::class.java,
                    "piko.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
