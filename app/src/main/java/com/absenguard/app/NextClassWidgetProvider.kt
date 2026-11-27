package com.absenguard.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Calendar
import java.util.Locale

class NextClassWidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NextClassWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                val provider = NextClassWidgetProvider()
                provider.onUpdate(context, manager, ids)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val next = AlarmScheduler.getNextUpcomingSchedule(context)
        val db = ScheduleDbHelper(context)

        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_next_class)
            if (next == null) {
                views.setTextViewText(R.id.widget_course_name, "Tidak ada jadwal aktif")
                views.setTextViewText(R.id.widget_time_room, "Buka AbsenGuard untuk mengatur jadwal")
                views.setTextViewText(R.id.widget_status_badge, "Kosong")
            } else {
                val s = next.first
                val dayLabel = when (s.dayOfWeek) {
                    Calendar.MONDAY -> "Senin"
                    Calendar.TUESDAY -> "Selasa"
                    Calendar.WEDNESDAY -> "Rabu"
                    Calendar.THURSDAY -> "Kamis"
                    Calendar.FRIDAY -> "Jum'at"
                    Calendar.SATURDAY -> "Sabtu"
                    else -> "Minggu"
                }
                val timeRoom = String.format(
                    Locale.getDefault(),
                    "%s • %02d:%02d - %02d:%02d • Ruang %s",
                    dayLabel,
                    s.startHour,
                    s.startMinute,
                    s.endHour,
                    s.endMinute,
                    s.room
                )
                val attended = db.isAttendedToday(s.id)
                views.setTextViewText(R.id.widget_course_name, s.courseName)
                views.setTextViewText(R.id.widget_time_room, timeRoom)
                views.setTextViewText(R.id.widget_status_badge, if (attended) "Hadir ✓" else s.classMode)
            }

            val openIntent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                context,
                widgetId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            if (next != null) {
                val courseIntent = Intent(context, SiptPortalActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, next.first.id)
                    putExtra(SiptPortalActivity.EXTRA_COURSE_URL, next.first.courseUrl)
                    putExtra(SiptPortalActivity.EXTRA_COURSE_NAME, next.first.courseName)
                }
                val coursePi = PendingIntent.getActivity(
                    context,
                    widgetId + 20000,
                    courseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_btn_open_course, coursePi)

                val attendIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = NotificationActionReceiver.ACTION_MARK_ATTENDED
                    putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, next.first.id)
                }
                val attendPi = PendingIntent.getBroadcast(
                    context,
                    widgetId + 30000,
                    attendIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_btn_attend, attendPi)
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
