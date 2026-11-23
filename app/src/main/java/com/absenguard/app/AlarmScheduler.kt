package com.absenguard.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {

    const val EXTRA_SCHEDULE_ID = "schedule_id"
    const val EXTRA_IS_PRE_REMINDER = "is_pre_reminder"
    const val TEST_SCHEDULE_ID = -999L

    fun scheduleAll(context: Context) {
        val db = ScheduleDbHelper(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val schedules = db.getAllSchedules()
        val preReminderEnabled = db.getSetting("pre_reminder_10m", "1") == "1"
        val now = System.currentTimeMillis()

        for (s in schedules) {
            val mainReq = (s.id * 10 + 1).toInt()
            val preReq = (s.id * 10 + 2).toInt()
            cancelPending(context, alarmManager, mainReq)
            cancelPending(context, alarmManager, preReq)

            if (!s.active) {
                continue
            }

            val triggerAt = computeNextTriggerMillis(s.dayOfWeek, s.startHour, s.startMinute, now)
            scheduleExact(context, alarmManager, mainReq, triggerAt, s.id, false)

            if (preReminderEnabled) {
                var preAt = triggerAt - 10 * 60 * 1000L
                if (preAt <= now) {
                    preAt += 7L * 24L * 60L * 60L * 1000L
                }
                scheduleExact(context, alarmManager, preReq, preAt, s.id, true)
            }
        }
    }

    fun scheduleSnooze(context: Context, scheduleId: Long, minutes: Int = 5) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        scheduleExact(context, alarmManager, 8888, triggerAt, scheduleId, false)
    }

    fun triggerInstantTest(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + 3000L
        scheduleExact(context, alarmManager, 9999, triggerAt, TEST_SCHEDULE_ID, false)
    }

    fun getNextUpcomingSchedule(context: Context): Pair<CourseSchedule, Long>? {
        val db = ScheduleDbHelper(context)
        val now = System.currentTimeMillis()
        var best: Pair<CourseSchedule, Long>? = null
        for (s in db.getAllSchedules()) {
            if (!s.active) {
                continue
            }
            val trigger = computeNextTriggerMillis(s.dayOfWeek, s.startHour, s.startMinute, now)
            if (best == null || trigger < best.second) {
                best = Pair(s, trigger)
            }
        }
        return best
    }

    private fun computeNextTriggerMillis(dayOfWeek: Int, hour: Int, minute: Int, now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.WEEK_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun scheduleExact(
        context: Context,
        alarmManager: AlarmManager,
        requestCode: Int,
        triggerAtMillis: Long,
        scheduleId: Long,
        isPreReminder: Boolean
    ) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_IS_PRE_REMINDER, isPreReminder)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val showIntent = PendingIntent.getActivity(
            context,
            requestCode + 50000,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (!isPreReminder) {
                val info = AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent)
                alarmManager.setAlarmClock(info, pi)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun cancelPending(context: Context, alarmManager: AlarmManager, requestCode: Int) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }
}
