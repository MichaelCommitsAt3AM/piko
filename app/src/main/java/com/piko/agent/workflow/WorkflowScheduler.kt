package com.piko.agent.workflow

import android.app.*
import android.content.*
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.piko.agent.MainActivity
import com.piko.agent.PikoApplication
import com.piko.agent.R
import com.piko.agent.data.db.ScheduleEntity
import kotlinx.coroutines.launch
import java.time.*

object ScheduleCalculator {
    fun next(hour: Int, minute: Int, recurrence: String, weekday: Int, after: Instant, zone: ZoneId): Long {
        require(hour in 0..23 && minute in 0..59 && weekday in 1..7 && recurrence in setOf("once", "daily", "weekly"))
        val local = after.atZone(zone)
        var date = local.toLocalDate()
        repeat(9) {
            val candidate = date.atTime(hour, minute).atZone(zone)
            if (candidate.toInstant().isAfter(after) && (recurrence != "weekly" || date.dayOfWeek.value == weekday)) return candidate.toInstant().toEpochMilli()
            date = date.plusDays(1)
        }
        error("Could not compute next schedule")
    }
}

/** Inexact alarms issue reminders, never background UI automation or foreground-service starts. */
class WorkflowScheduler(private val context: Context) {
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private fun pending(id: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, WorkflowScheduleReceiver::class.java).setAction("com.piko.agent.SCHEDULE")
            .setData(Uri.parse("piko://schedule/$id")).putExtra("scheduleId", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun arm(schedule: ScheduleEntity) {
        if (!schedule.enabled) { alarm.cancel(pending(schedule.id)); return }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, schedule.nextRunAt, pending(schedule.id))
    }
    suspend fun restore(dao: com.piko.agent.data.db.AssistantDao) {
        dao.enabledSchedules().forEach { old ->
            val now = System.currentTimeMillis()
            val next = if (old.recurrence == "once") old.nextRunAt.coerceAtLeast(now + 5000) else
                ScheduleCalculator.next(old.hour, old.minute, old.recurrence, old.weekday, Instant.ofEpochMilli(now), ZoneId.systemDefault())
            val updated = old.copy(nextRunAt = next, lastResult = if (old.nextRunAt <= now) "Missed while unavailable; rescheduled for user review" else old.lastResult)
            dao.putSchedule(updated); arm(updated)
        }
    }
    fun cancel(id: String) {
        alarm.cancel(pending(id))
        NotificationManagerCompat.from(context).cancel(id.hashCode())
    }
}

class WorkflowScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val completion = goAsync()
        val app = PikoApplication.from(context)
        app.applicationScope.launch {
            try {
                val dao = app.database.assistantDao()
                val scheduler = WorkflowScheduler(context)
                val id = intent.getStringExtra("scheduleId")
                if (id == null) {
                    scheduler.restore(dao)
                } else {
                    val schedule = dao.schedule(id) ?: return@launch
                    if (!schedule.enabled) return@launch
                    val workflow = dao.workflow(schedule.workflowId) ?: run { scheduler.cancel(id); dao.deleteSchedule(id); return@launch }
                    val allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
                    var delivered = false
                    if (allowed) {
                        val open = PendingIntent.getActivity(context, id.hashCode(),
                            Intent(context, MainActivity::class.java).putExtra("openAssistant", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                        val note = NotificationCompat.Builder(context, PikoApplication.CHANNEL_SETUP)
                            .setSmallIcon(R.drawable.ic_stat_piko).setContentTitle("Ready: ${workflow.name}")
                            .setContentText("Unlock your phone and open Assistant → Workflows to review and run.")
                            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
                        try { NotificationManagerCompat.from(context).notify(id.hashCode(), note); delivered = true } catch (_: SecurityException) { }
                    }
                    val updated = schedule.copy(
                        enabled = schedule.recurrence != "once",
                        nextRunAt = if (schedule.recurrence == "once") schedule.nextRunAt else ScheduleCalculator.next(schedule.hour, schedule.minute, schedule.recurrence, schedule.weekday, Instant.now(), ZoneId.systemDefault()),
                        lastResult = if (delivered) "Reminder delivered; waiting for user to run" else "Missed: notification permission denied",
                    )
                    dao.putSchedule(updated)
                    scheduler.arm(updated)
                }
            } finally { completion.finish() }
        }
    }
}
