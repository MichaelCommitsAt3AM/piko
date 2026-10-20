package com.piko.agent.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val goal: String,
    val providerName: String,
    val modelName: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val durationMs: Long = 0,
    val stepCount: Int = 0,
    val error: String? = null,
    val resultSummary: String? = null,
    val verified: Boolean? = null,
    val verification: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val estimatedCostUsd: Double = 0.0,
    val traceJson: String? = null,
)

@Entity(
    tableName = "task_steps",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class TaskStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val stepIndex: Int,
    val timestamp: Long,
    val state: String,
    val actionType: String? = null,
    val actionJson: String? = null,
    val result: String? = null,
    val observationSummary: String? = null,
    val appPackage: String? = null,
    val screenshotIncluded: Boolean = false,
)

data class TaskWithSteps(
    @Embedded val task: TaskEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "taskId",
    )
    val steps: List<TaskStepEntity>,
)
