package com.piko.agent.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memories", indices = [Index(value = ["memoryKey"], unique = true)])
data class MemoryEntity(
    @PrimaryKey val id: String,
    val memoryKey: String,
    val content: String,
    val category: String,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long? = null,
    val source: String = "Explicit user input",
    val approved: Boolean = false,
)

@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val stepsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "workflow_schedules")
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val hour: Int,
    val minute: Int,
    val recurrence: String,
    val weekday: Int,
    val nextRunAt: Long,
    val enabled: Boolean = true,
    val lastResult: String? = null,
)

@Dao
interface AssistantDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC") fun memories(): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memories WHERE approved = 1 AND (expiresAt IS NULL OR expiresAt > :now) ORDER BY updatedAt DESC LIMIT 200") suspend fun candidates(now: Long): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE memoryKey = :key LIMIT 1") suspend fun memoryByKey(key: String): MemoryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMemory(memory: MemoryEntity)
    @Query("DELETE FROM memories WHERE id = :id") suspend fun deleteMemory(id: String)
    @Query("DELETE FROM memories") suspend fun clearMemories()
    @Query("SELECT * FROM workflows ORDER BY updatedAt DESC") fun workflows(): Flow<List<WorkflowEntity>>
    @Query("SELECT * FROM workflows WHERE id = :id LIMIT 1") suspend fun workflow(id: String): WorkflowEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putWorkflow(workflow: WorkflowEntity)
    @Query("DELETE FROM workflows WHERE id = :id") suspend fun deleteWorkflow(id: String)
    @Query("SELECT * FROM workflow_schedules ORDER BY nextRunAt") fun schedules(): Flow<List<ScheduleEntity>>
    @Query("SELECT * FROM workflow_schedules WHERE enabled = 1") suspend fun enabledSchedules(): List<ScheduleEntity>
    @Query("SELECT * FROM workflow_schedules WHERE id = :id LIMIT 1") suspend fun schedule(id: String): ScheduleEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSchedule(schedule: ScheduleEntity)
    @Query("DELETE FROM workflow_schedules WHERE id = :id") suspend fun deleteSchedule(id: String)
}
