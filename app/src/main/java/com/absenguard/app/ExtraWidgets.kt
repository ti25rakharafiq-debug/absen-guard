package com.absenguard.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar
import java.util.Locale

object WidgetHub {

    fun updateAllWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)

        val nextIds = manager.getAppWidgetIds(ComponentName(context, NextClassWidgetProvider::class.java))
        if (nextIds.isNotEmpty()) {
            NextClassWidgetProvider.renderInstances(context, manager, nextIds)
        }

        val todayIds = manager.getAppWidgetIds(ComponentName(context, TodayScheduleWidgetProvider::class.java))
        if (todayIds.isNotEmpty()) {
            TodayScheduleWidgetProvider.renderInstances(context, manager, todayIds)
        }

        val circleIds = manager.getAppWidgetIds(ComponentName(context, CircleOrbWidgetProvider::class.java))
        if (circleIds.isNotEmpty()) {
            CircleOrbWidgetProvider.renderInstances(context, manager, circleIds)
        }

        val digitalIds = manager.getAppWidgetIds(ComponentName(context, DigitalClockWidgetProvider::class.java))
        if (digitalIds.isNotEmpty()) {
            DigitalClockWidgetProvider.renderInstances(context, manager, digitalIds)
        }

        val analogIds = manager.getAppWidgetIds(ComponentName(context, AnalogClockWidgetProvider::class.java))
        if (analogIds.isNotEmpty()) {
            AnalogClockWidgetProvider.renderInstances(context, manager, analogIds)
        }
    }

    fun shortDayName(dayOfWeek: Int): String {
        return when (dayOfWeek) {
            Calendar.MONDAY -> "Senin"
            Calendar.TUESDAY -> "Selasa"
            Calendar.WEDNESDAY -> "Rabu"
            Calendar.THURSDAY -> "Kamis"
            Calendar.FRIDAY -> "Jum'at"
            Calendar.SATURDAY -> "Sabtu"
            else -> "Minggu"
        }
    }

    fun buildOpenMainPendingIntent(context: Context, requestCode: Int): PendingIntent {
        return PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildOpenCoursePendingIntent(context: Context, requestCode: Int, schedule: CourseSchedule): PendingIntent {
        val intent = Intent(context, SiptPortalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, schedule.id)
            putExtra(SiptPortalActivity.EXTRA_COURSE_URL, schedule.courseUrl)
            putExtra(SiptPortalActivity.EXTRA_COURSE_NAME, schedule.courseName)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class TodayScheduleWidgetProvider : AppWidgetProvider() {

    companion object {
        fun renderInstances(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val db = ScheduleDbHelper(context)
            val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            val todayList = db.getSchedulesForDay(today).filter { it.active }
            val dayLabel = WidgetHub.shortDayName(today)

            val rowIds = intArrayOf(R.id.today_row_1, R.id.today_row_2, R.id.today_row_3, R.id.today_row_4)
            val timeIds = intArrayOf(R.id.today_time_1, R.id.today_time_2, R.id.today_time_3, R.id.today_time_4)
            val courseIds = intArrayOf(R.id.today_course_1, R.id.today_course_2, R.id.today_course_3, R.id.today_course_4)
            val statusIds = intArrayOf(R.id.today_status_1, R.id.today_status_2, R.id.today_status_3, R.id.today_status_4)

            for (widgetId in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_today_schedule)
                views.setTextViewText(R.id.today_day_badge, dayLabel)
                views.setOnClickPendingIntent(
                    R.id.today_widget_root,
                    WidgetHub.buildOpenMainPendingIntent(context, widgetId + 41000)
                )

                if (todayList.isEmpty()) {
                    views.setViewVisibility(R.id.today_empty_text, View.VISIBLE)
                    views.setViewVisibility(R.id.today_list_container, View.GONE)
                } else {
                    views.setViewVisibility(R.id.today_empty_text, View.GONE)
                    views.setViewVisibility(R.id.today_list_container, View.VISIBLE)

                    for (i in 0 until 4) {
                        if (i < todayList.size) {
                            val s = todayList[i]
                            val attended = db.isAttendedToday(s.id)
                            views.setViewVisibility(rowIds[i], View.VISIBLE)
                            views.setTextViewText(
                                timeIds[i],
                                String.format(Locale.getDefault(), "%02d:%02d", s.startHour, s.startMinute)
                            )
                            views.setTextViewText(courseIds[i], s.courseName)
                            views.setTextViewText(
                                statusIds[i],
                                if (attended) "Hadir ✓" else s.room
                            )
                        } else {
                            views.setViewVisibility(rowIds[i], View.GONE)
                        }
                    }
                }
                manager.updateAppWidget(widgetId, views)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderInstances(context, appWidgetManager, appWidgetIds)
    }
}

class CircleOrbWidgetProvider : AppWidgetProvider() {

    companion object {
        fun renderInstances(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val next = AlarmScheduler.getNextUpcomingSchedule(context)
            val db = ScheduleDbHelper(context)

            for (widgetId in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_circle_orb)
                if (next == null) {
                    views.setTextViewText(R.id.orb_day_badge, "KOSONG")
                    views.setTextViewText(R.id.orb_time_big, "--:--")
                    views.setTextViewText(R.id.orb_course_name, "Tidak ada jadwal")
                    views.setTextViewText(R.id.orb_room_text, "AbsenGuard")
                    views.setOnClickPendingIntent(
                        R.id.orb_widget_root,
                        WidgetHub.buildOpenMainPendingIntent(context, widgetId + 42000)
                    )
                } else {
                    val s = next.first
                    val attended = db.isAttendedToday(s.id)
                    views.setTextViewText(
                        R.id.orb_day_badge,
                        if (attended) "HADIR ✓" else WidgetHub.shortDayName(s.dayOfWeek).uppercase(Locale.getDefault())
                    )
                    views.setTextViewText(
                        R.id.orb_time_big,
                        String.format(Locale.getDefault(), "%02d:%02d", s.startHour, s.startMinute)
                    )
                    views.setTextViewText(R.id.orb_course_name, s.courseName)
                    views.setTextViewText(R.id.orb_room_text, "Ruang ${s.room}")
                    views.setOnClickPendingIntent(
                        R.id.orb_widget_root,
                        WidgetHub.buildOpenCoursePendingIntent(context, widgetId + 42500, s)
                    )
                }
                manager.updateAppWidget(widgetId, views)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderInstances(context, appWidgetManager, appWidgetIds)
    }
}

class DigitalClockWidgetProvider : AppWidgetProvider() {

    companion object {
        fun renderInstances(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val next = AlarmScheduler.getNextUpcomingSchedule(context)
            val db = ScheduleDbHelper(context)

            for (widgetId in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_digital_clock)
                views.setOnClickPendingIntent(
                    R.id.digital_widget_root,
                    WidgetHub.buildOpenMainPendingIntent(context, widgetId + 43000)
                )

                if (next == null) {
                    views.setTextViewText(R.id.digital_status_text, "Tidak ada jadwal aktif")
                    views.setTextViewText(R.id.digital_next_course, "Ketuk untuk mengatur jadwal")
                } else {
                    val s = next.first
                    val attended = db.isAttendedToday(s.id)
                    views.setTextViewText(
                        R.id.digital_status_text,
                        if (attended) "Sudah Absen ✓" else "${WidgetHub.shortDayName(s.dayOfWeek)} • Ruang ${s.room}"
                    )
                    views.setTextViewText(
                        R.id.digital_next_course,
                        String.format(Locale.getDefault(), "%02d:%02d • %s", s.startHour, s.startMinute, s.courseName)
                    )
                    views.setOnClickPendingIntent(
                        R.id.digital_btn_open,
                        WidgetHub.buildOpenCoursePendingIntent(context, widgetId + 43500, s)
                    )
                }
                manager.updateAppWidget(widgetId, views)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderInstances(context, appWidgetManager, appWidgetIds)
    }
}

class AnalogClockWidgetProvider : AppWidgetProvider() {

    companion object {
        fun renderInstances(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val next = AlarmScheduler.getNextUpcomingSchedule(context)

            for (widgetId in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_analog_clock)
                views.setOnClickPendingIntent(
                    R.id.analog_widget_root,
                    WidgetHub.buildOpenMainPendingIntent(context, widgetId + 44000)
                )

                if (next == null) {
                    views.setTextViewText(R.id.analog_next_badge, "AbsenGuard")
                } else {
                    val s = next.first
                    views.setTextViewText(
                        R.id.analog_next_badge,
                        String.format(
                            Locale.getDefault(),
                            "%s %02d:%02d • %s",
                            WidgetHub.shortDayName(s.dayOfWeek).take(3),
                            s.startHour,
                            s.startMinute,
                            s.room
                        )
                    )
                }
                manager.updateAppWidget(widgetId, views)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderInstances(context, appWidgetManager, appWidgetIds)
    }
}
