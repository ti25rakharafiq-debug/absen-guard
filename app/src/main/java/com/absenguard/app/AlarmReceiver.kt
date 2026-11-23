package com.absenguard.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.Calendar

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AbsenGuard:AlarmWakeLock")
        wakeLock.acquire(15_000L)

        try {
            val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)
            val isPreReminder = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_PRE_REMINDER, false)
            val db = ScheduleDbHelper(context)

            val schedule = if (scheduleId == AlarmScheduler.TEST_SCHEDULE_ID) {
                CourseSchedule(
                    id = AlarmScheduler.TEST_SCHEDULE_ID,
                    dayOfWeek = Calendar.MONDAY,
                    startHour = 0,
                    startMinute = 0,
                    endHour = 0,
                    endMinute = 0,
                    courseName = "Simulasi Tes Absen BOC",
                    room = "Online",
                    classMode = "Online",
                    ringEnabled = true,
                    vibrateEnabled = true,
                    notifEnabled = true,
                    active = true
                )
            } else {
                db.getScheduleById(scheduleId) ?: return
            }

            val notifEnabled = (db.getSetting("global_notif", "1") == "1") && schedule.notifEnabled
            val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(notifManager)

            val notifId = if (scheduleId == AlarmScheduler.TEST_SCHEDULE_ID) 999 else (schedule.id * 10 + if (isPreReminder) 2 else 1).toInt()

            if (isPreReminder) {
                if (notifEnabled) {
                    val openMainPi = PendingIntent.getActivity(
                        context,
                        notifId,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val preNotif = NotificationCompat.Builder(context, "absenguard_alarm")
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle("10 Menit Lagi: ${schedule.courseName}")
                        .setContentText("Ruang ${schedule.room} (${schedule.classMode}) • Siapkan absen BOC")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_REMINDER)
                        .setAutoCancel(true)
                        .setContentIntent(openMainPi)
                        .build()
                    notifManager.notify(notifId, preNotif)
                }
                AlarmScheduler.scheduleAll(context)
                return
            }

            val fullScreenIntent = Intent(context, AlarmAlertActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, schedule.id)
            }
            val fullScreenPi = PendingIntent.getActivity(
                context,
                notifId,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val attendIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_MARK_ATTENDED
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, schedule.id)
                putExtra("notif_id", notifId)
            }
            val attendPi = PendingIntent.getBroadcast(
                context,
                notifId + 10000,
                attendIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val snoozeIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, schedule.id)
                putExtra("notif_id", notifId)
            }
            val snoozePi = PendingIntent.getBroadcast(
                context,
                notifId + 20000,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (notifEnabled) {
                val alarmNotif = NotificationCompat.Builder(context, "absenguard_alarm")
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Waktunya Absen: ${schedule.courseName}")
                    .setContentText("Ruang ${schedule.room} (${schedule.classMode}) • Ketuk untuk buka")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setFullScreenIntent(fullScreenPi, true)
                    .setAutoCancel(true)
                    .setOngoing(true)
                    .addAction(0, "Sudah Absen", attendPi)
                    .addAction(0, "Tunda 5 Menit", snoozePi)
                    .build()
                notifManager.notify(notifId, alarmNotif)
            }

            try {
                context.startActivity(fullScreenIntent)
            } catch (_: Exception) {
            }

            if (schedule.id != AlarmScheduler.TEST_SCHEDULE_ID) {
                AlarmScheduler.scheduleAll(context)
            }
            NextClassWidgetProvider.updateAllWidgets(context)
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel("absenguard_alarm")
            if (existing == null) {
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val channel = NotificationChannel(
                    "absenguard_alarm",
                    "Pengingat Absen Kuliah",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifikasi alarm dan pengingat absen mata kuliah"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 600, 300, 600)
                    setSound(soundUri, attrs)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
