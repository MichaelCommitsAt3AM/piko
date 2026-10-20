package com.piko.agent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.piko.agent.core.AgentState
import com.piko.agent.data.db.PikoDatabase
import com.piko.agent.data.db.TaskEntity
import com.piko.agent.data.db.TaskStepEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskPersistenceTest {
    private lateinit var database: PikoDatabase
    private lateinit var dao: com.piko.agent.data.db.TaskDao
    private lateinit var repository: TaskRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PikoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.taskDao()
        repository = TaskRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun task(
        id: String = "task-1",
        status: String = AgentState.REASONING.name,
        startedAt: Long = 1_000L,
    ) = TaskEntity(
        id = id,
        goal = "Open Settings and enable dark mode",
        providerName = "OpenCode Go",
        modelName = "DeepSeek V4.1 Flash",
        status = status,
        startedAt = startedAt,
        stepCount = 2,
    )

    @Test
    fun `task and steps persist with relation`() = runBlocking {
        dao.upsertTask(task())
        dao.insertStep(
            TaskStepEntity(
                taskId = "task-1",
                stepIndex = 1,
                timestamp = 2_000,
                state = AgentState.EXECUTING.name,
                actionType = "LAUNCH_APP",
                actionJson = """{"type":"launch_app","packageName":"com.android.settings"}""",
                result = "Requested launch",
                observationSummary = "Settings: Display",
                appPackage = "com.android.settings",
                screenshotIncluded = true,
            ),
        )
        dao.insertStep(
            TaskStepEntity(
                taskId = "task-1",
                stepIndex = 2,
                timestamp = 3_000,
                state = AgentState.VERIFYING.name,
                actionType = "TAP",
                result = "Screen changed after the action.",
            ),
        )
        val loaded = dao.getTaskWithSteps("task-1")
        assertNotNull(loaded)
        assertEquals(2, loaded!!.steps.size)
        assertEquals("LAUNCH_APP", loaded.steps.first().actionType)
        assertEquals("Settings: Display", loaded.steps.first().observationSummary)
    }

    @Test
    fun `recent task flow is newest first`() = runBlocking {
        dao.upsertTask(task(id = "old", status = AgentState.COMPLETED.name, startedAt = 100))
        dao.upsertTask(task(id = "new", status = AgentState.COMPLETED.name, startedAt = 300))
        val recent = dao.observeRecent(5).first()
        assertEquals(2, recent.size)
        assertEquals("new", recent.first().id)
    }

    @Test
    fun `run repository writes a detailed trace`() = runBlocking {
        repository.startTask("trace-1", "Turn on dark mode", "OpenCode Go", "DeepSeek")
        repository.addStep(
            taskId = "trace-1",
            stepIndex = 1,
            state = AgentState.EXECUTING,
            actionType = "TAP",
            actionJson = """{"type":"tap","elementId":"element_1"}""",
            result = "Tapped Display",
            observationSummary = "Settings",
            appPackage = "com.android.settings",
            screenshotIncluded = false,
        )
        repository.finishTask(
            id = "trace-1",
            status = AgentState.COMPLETED,
            summary = "Dark mode enabled",
            error = null,
            verified = true,
            verification = "Theme shows Dark",
            stepCount = 1,
            inputTokens = 100,
            outputTokens = 50,
            costUsd = 0.001,
        )
        val stored = dao.getTask("trace-1")
        assertNotNull(stored)
        assertEquals(AgentState.COMPLETED.name, stored!!.status)
        assertEquals(true, stored.verified)
        assertTrue(stored.traceJson!!.contains("Tapped Display"))
        assertEquals(1, stored.stepCount)
    }

    @Test
    fun `orphaned running tasks are marked interrupted`() = runBlocking {
        dao.upsertTask(task(id = "running", status = AgentState.OBSERVING.name))
        dao.upsertTask(task(id = "done", status = AgentState.COMPLETED.name))
        val count = dao.markRunningTasksInterrupted(10_000, "Interrupted")
        assertEquals(1, count)
        val running = dao.getTask("running")!!
        assertEquals(AgentState.FAILED.name, running.status)
        assertEquals("Interrupted", running.error)
        assertEquals(AgentState.COMPLETED.name, dao.getTask("done")!!.status)
    }

    @Test
    fun `deleting tasks cascades steps`() = runBlocking {
        dao.upsertTask(task(id = "delete-me"))
        dao.insertStep(
            TaskStepEntity(
                taskId = "delete-me",
                stepIndex = 1,
                timestamp = 1,
                state = AgentState.OBSERVING.name,
            ),
        )
        dao.deleteAllTasks()
        assertNull(dao.getTask("delete-me"))
        assertTrue(dao.getSteps("delete-me").isEmpty())
    }
}
