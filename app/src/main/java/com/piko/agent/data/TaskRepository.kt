package com.piko.agent.data

import com.piko.agent.core.AgentState
import com.piko.agent.core.PikoJson
import com.piko.agent.data.db.PikoDatabase
import com.piko.agent.data.db.TaskEntity
import com.piko.agent.data.db.TaskStepEntity
import com.piko.agent.data.db.TaskWithSteps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class TraceStep(
    val step: Int,
    val state: String,
    val actionType: String? = null,
    val actionJson: String? = null,
    val result: String? = null,
    val observationSummary: String? = null,
    val appPackage: String? = null,
    val screenshotIncluded: Boolean = false,
)

class TaskRepository(private val database: PikoDatabase) {
    private val dao = database.taskDao()

    fun observeRecent(limit: Int = 20): Flow<List<TaskEntity>> = dao.observeRecent(limit)

    fun observeAll(): Flow<List<TaskEntity>> = dao.observeAll()

    fun observeTask(id: String): Flow<TaskEntity?> = dao.observeTask(id)

    fun observeSteps(id: String): Flow<List<TaskStepEntity>> = dao.observeSteps(id)

    fun observeTaskWithSteps(id: String): Flow<TaskWithSteps?> =
        combine(dao.observeTask(id), dao.observeSteps(id)) { task, steps ->
            task?.let { TaskWithSteps(it, steps) }
        }

    suspend fun startTask(
        id: String,
        goal: String,
        providerName: String,
        modelName: String,
    ): TaskEntity = withContext(Dispatchers.IO) {
        val entity = TaskEntity(
            id = id,
            goal = goal,
            providerName = providerName,
            modelName = modelName,
            status = AgentState.PREPARING.name,
            startedAt = System.currentTimeMillis(),
        )
        dao.upsertTask(entity)
        entity
    }

    suspend fun updateProgress(
        id: String,
        status: AgentState,
        stepCount: Int,
        inputTokens: Long,
        outputTokens: Long,
        costUsd: Double,
        error: String? = null,
    ) = withContext(Dispatchers.IO) {
        val existing = dao.getTask(id) ?: return@withContext
        dao.upsertTask(
            existing.copy(
                status = status.name,
                stepCount = stepCount,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                estimatedCostUsd = costUsd,
                error = error,
                durationMs = System.currentTimeMillis() - existing.startedAt,
            ),
        )
    }

    suspend fun addStep(
        taskId: String,
        stepIndex: Int,
        state: AgentState,
        actionType: String?,
        actionJson: String?,
        result: String?,
        observationSummary: String?,
        appPackage: String?,
        screenshotIncluded: Boolean,
    ) = withContext(Dispatchers.IO) {
        dao.insertStep(
            TaskStepEntity(
                taskId = taskId,
                stepIndex = stepIndex,
                timestamp = System.currentTimeMillis(),
                state = state.name,
                actionType = actionType,
                actionJson = actionJson,
                result = result,
                observationSummary = observationSummary,
                appPackage = appPackage,
                screenshotIncluded = screenshotIncluded,
            ),
        )
    }

    suspend fun finishTask(
        id: String,
        status: AgentState,
        summary: String?,
        error: String?,
        verified: Boolean?,
        verification: String?,
        stepCount: Int,
        inputTokens: Long,
        outputTokens: Long,
        costUsd: Double,
    ) = withContext(Dispatchers.IO) {
        val existing = dao.getTask(id) ?: return@withContext
        val steps = dao.getSteps(id)
        val trace = steps.map { step ->
            TraceStep(
                step = step.stepIndex,
                state = step.state,
                actionType = step.actionType,
                actionJson = step.actionJson,
                result = step.result,
                observationSummary = step.observationSummary,
                appPackage = step.appPackage,
                screenshotIncluded = step.screenshotIncluded,
            )
        }
        dao.upsertTask(
            existing.copy(
                status = status.name,
                endedAt = System.currentTimeMillis(),
                durationMs = System.currentTimeMillis() - existing.startedAt,
                stepCount = stepCount,
                error = error,
                resultSummary = summary,
                verified = verified,
                verification = verification,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                estimatedCostUsd = costUsd,
                traceJson = PikoJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(TraceStep.serializer()),
                    trace,
                ),
            ),
        )
    }

    suspend fun getTask(id: String): TaskEntity? = withContext(Dispatchers.IO) { dao.getTask(id) }

    suspend fun markOrphansInterrupted(): Int = withContext(Dispatchers.IO) {
        dao.markRunningTasksInterrupted(
            now = System.currentTimeMillis(),
            reason = "Piko was stopped by Android or the app process ended. The task was not resumed automatically.",
        )
    }

    suspend fun findRunningTask(): TaskEntity? = withContext(Dispatchers.IO) { dao.findRunningTask() }

    suspend fun deleteAll() = withContext(Dispatchers.IO) { dao.deleteAllTasks() }

    suspend fun prune(retentionDays: Int) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - retentionDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        dao.deleteOlderThan(cutoff)
    }

    suspend fun getTaskWithSteps(id: String): TaskWithSteps? = withContext(Dispatchers.IO) {
        dao.getTaskWithSteps(id)
    }
}
