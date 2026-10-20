package com.piko.agent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity)

    @Query("SELECT * FROM tasks ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTask(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeTask(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY stepIndex ASC, id ASC")
    fun observeSteps(taskId: String): Flow<List<TaskStepEntity>>

    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY stepIndex ASC, id ASC")
    suspend fun getSteps(taskId: String): List<TaskStepEntity>

    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTaskWithSteps(id: String): TaskWithSteps?

    @Insert
    suspend fun insertStep(step: TaskStepEntity)

    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    @Query("DELETE FROM tasks WHERE startedAt < :cutoff AND status IN ('COMPLETED','FAILED','CANCELLED')")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("UPDATE tasks SET status = 'FAILED', endedAt = :now, error = :reason, resultSummary = :reason WHERE status IN ('PREPARING','OBSERVING','REASONING','EXECUTING','VERIFYING','AWAITING_USER_CONFIRMATION','PAUSED')")
    suspend fun markRunningTasksInterrupted(now: Long, reason: String): Int

    @Query("SELECT * FROM tasks WHERE status IN ('PREPARING','OBSERVING','REASONING','EXECUTING','VERIFYING','AWAITING_USER_CONFIRMATION','PAUSED') LIMIT 1")
    suspend fun findRunningTask(): TaskEntity?
}
