package com.absenguard.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_MARK_ATTENDED = "com.absenguard.app.ACTION_MARK_ATTENDED"
        const val ACTION_SNOOZE = "com.absenguard.app.ACTION_SNOOZE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)
        val notifId = intent.getIntExtra("notif_id", -1)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        AlarmAlertActivity.stopActiveAlarm()
        if (notifId != -1) {
            nm.cancel(notifId)
        }

        when (intent.action) {
            ACTION_MARK_ATTENDED -> {
                if (scheduleId > 0) {
                    val db = ScheduleDbHelper(context)
                    val schedule = db.getScheduleById(scheduleId)
                    if (schedule != null) {
                        db.recordAttendance(scheduleId, schedule.courseName, "HADIR")
                    }
                }
                Toast.makeText(context, "Absen tercatat hadir!", Toast.LENGTH_SHORT).show()
                NextClassWidgetProvider.updateAllWidgets(context)
            }
            ACTION_SNOOZE -> {
                AlarmScheduler.scheduleSnooze(context, scheduleId, 5)
            }
        }
    }
}
