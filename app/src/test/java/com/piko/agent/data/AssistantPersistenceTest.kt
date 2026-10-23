package com.piko.agent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.piko.agent.data.db.*
import com.piko.agent.workflow.WorkflowScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlinx.serialization.json.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: PikoDatabase
    private lateinit var memories: MemoryRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, PikoDatabase::class.java).allowMainThreadQueries().build()
        memories = MemoryRepository(db.assistantDao())
    }
    @After fun teardown() { db.close() }
    @Test fun approvedMemoryDeduplicatesUpdatesAndDeletes() = runBlocking {
        val original = memories.save("Music app", "I prefer Spotify")
        val updated = memories.save("music APP", "I prefer YouTube Music")
        assertEquals(original.id, updated.id)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(1, memories.memories.first().size)
        assertEquals("I prefer YouTube Music", memories.relevant("Open my music app").single().content)
        assertTrue(memories.forget("music app"))
        assertTrue(memories.memories.first().isEmpty())
    }
    @Test fun suggestionsAndExpiredEntriesAreNeverRetrieved() = runBlocking {
        memories.save("music", "I prefer Spotify", approved = false)
        db.assistantDao().putMemory(MemoryEntity("expired", "music-old", "Music app", "Preference", 0, 0, 1, approved = true))
        assertTrue(memories.relevant("music").isEmpty())
        memories.clear()
        assertTrue(memories.memories.first().isEmpty())
    }
    @Test fun modelSuggestionCannotReplaceAnApprovedPreference() = runBlocking {
        memories.save("music", "I prefer Spotify")
        assertTrue(runCatching { memories.save("music", "I prefer another app", approved = false) }.isFailure)
        assertEquals("I prefer Spotify", memories.relevant("music").single().content)
    }
    @Test fun secretsAreRejectedAndRetrievalIsBounded() = runBlocking {
        for (secret in listOf("password is hunter2", "my API key abc", "card 4242 4242 4242 4242", "private message from John")) {
            assertTrue(runCatching { memories.save("bad", secret) }.isFailure)
        }
        repeat(9) { memories.save("music $it", "Music preference number $it") }
        assertEquals(5, memories.relevant("music").size)
        assertTrue(memories.relevant("unrelated weather").isEmpty())
    }
    @Test fun scheduleCancellationRemovesTheAlarm() {
        val scheduler = WorkflowScheduler(context)
        val schedule = ScheduleEntity("schedule-test", "workflow", 9, 0, "daily", 1, System.currentTimeMillis() + 60000)
        scheduler.arm(schedule)
        val alarm = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        assertTrue(alarm.scheduledAlarms.isNotEmpty())
        scheduler.cancel(schedule.id)
        assertTrue(alarm.scheduledAlarms.isEmpty())
    }
    @Test fun migrationPreservesExistingTasksAndAddsAssistantTables() = runBlocking {
        val name = "migration-assistant-test.db"
        context.deleteDatabase(name)
        val schemaPath = listOf(File("schemas/com.piko.agent.data.db.PikoDatabase/1.json"), File("app/schemas/com.piko.agent.data.db.PikoDatabase/1.json")).first { it.exists() }
        val schema = Json.parseToJsonElement(schemaPath.readText()).jsonObject["database"]!!.jsonObject
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            schema["entities"]!!.jsonArray.forEach { element ->
                val entity = element.jsonObject
                old.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", entity["tableName"]!!.jsonPrimitive.content))
                entity["indices"]?.jsonArray?.forEach { index -> old.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", entity["tableName"]!!.jsonPrimitive.content)) }
            }
            old.execSQL("INSERT INTO tasks (id,goal,providerName,modelName,status,startedAt,durationMs,stepCount,inputTokens,outputTokens,estimatedCostUsd) VALUES ('old','Keep me','OpenCode Go','model','COMPLETED',1,0,0,0,0,0)")
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, PikoDatabase::class.java, name).addMigrations(PikoDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            assertEquals("Keep me", migrated.taskDao().getTaskWithSteps("old")!!.task.goal)
            assertTrue(migrated.assistantDao().memories().first().isEmpty())
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
