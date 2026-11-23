package com.absenguard.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class CourseSchedule(
    val id: Long,
    val dayOfWeek: Int,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val courseName: String,
    val room: String,
    val classMode: String,
    val ringEnabled: Boolean,
    val vibrateEnabled: Boolean,
    val notifEnabled: Boolean,
    val active: Boolean
)

data class AttendanceLog(
    val id: Long,
    val scheduleId: Long,
    val courseName: String,
    val dateStr: String,
    val status: String,
    val timestamp: Long
)

class ScheduleDbHelper(context: Context) : SQLiteOpenHelper(context, "absenguard.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE schedules (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "day_of_week INTEGER NOT NULL, " +
                "start_hour INTEGER NOT NULL, " +
                "start_minute INTEGER NOT NULL, " +
                "end_hour INTEGER NOT NULL, " +
                "end_minute INTEGER NOT NULL, " +
                "course_name TEXT NOT NULL, " +
                "room TEXT NOT NULL, " +
                "class_mode TEXT NOT NULL, " +
                "ring_enabled INTEGER NOT NULL DEFAULT 1, " +
                "vibrate_enabled INTEGER NOT NULL DEFAULT 1, " +
                "notif_enabled INTEGER NOT NULL DEFAULT 1, " +
                "active INTEGER NOT NULL DEFAULT 1)"
        )

        db.execSQL(
            "CREATE TABLE attendance_logs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "schedule_id INTEGER NOT NULL, " +
                "course_name TEXT NOT NULL, " +
                "date_str TEXT NOT NULL, " +
                "status TEXT NOT NULL, " +
                "timestamp INTEGER NOT NULL)"
        )

        db.execSQL(
            "CREATE TABLE app_settings (" +
                "key TEXT PRIMARY KEY, " +
                "value TEXT NOT NULL)"
        )

        val initialCourses = listOf(
            CourseSchedule(0, Calendar.MONDAY, 7, 0, 8, 40, "Pemasaran Industri", "A405", "Offline", true, true, true, true),
            CourseSchedule(0, Calendar.MONDAY, 9, 0, 10, 40, "Ilmu Bahan dan Kimia Lingkungan", "F405", "Offline", true, true, true, true),
            CourseSchedule(0, Calendar.MONDAY, 11, 0, 12, 40, "Teori Probabilitas", "A306", "Offline", true, true, true, true),
            CourseSchedule(0, Calendar.MONDAY, 13, 0, 14, 40, "Analisis dan Estimasi Biaya", "A405", "Offline", true, true, true, true),
            CourseSchedule(0, Calendar.TUESDAY, 7, 0, 8, 40, "Algoritma & Pemrograman Komputer (T)", "F302", "Offline", true, true, true, true),
            CourseSchedule(0, Calendar.TUESDAY, 9, 0, 10, 40, "Operasional Riset 1", "F207", "Offline", true, true, true, true),
            CourseSchedule(0, Calendar.WEDNESDAY, 9, 0, 10, 40, "Algoritma & Pemrograman Komputer (P)", "LAB", "Offline", true, true, true, true),
            CourseSchedule(0, Calendar.THURSDAY, 11, 0, 12, 40, "Analisis dan Perancangan Kerja (T)", "A404", "Offline", true, true, true, true),
            CourseSchedule(0, Calendar.THURSDAY, 13, 0, 14, 40, "Analisis Perancangan Kerja (P)", "LAB", "Offline", true, true, true, true),
            CourseSchedule(0, Calendar.FRIDAY, 13, 0, 14, 40, "Jatidiri Bangsa", "F207", "Offline", true, true, true, true)
        )

        for (course in initialCourses) {
            val cv = ContentValues().apply {
                put("day_of_week", course.dayOfWeek)
                put("start_hour", course.startHour)
                put("start_minute", course.startMinute)
                put("end_hour", course.endHour)
                put("end_minute", course.endMinute)
                put("course_name", course.courseName)
                put("room", course.room)
                put("class_mode", course.classMode)
                put("ring_enabled", 1)
                put("vibrate_enabled", 1)
                put("notif_enabled", 1)
                put("active", 1)
            }
            db.insert("schedules", null, cv)
        }

        val defaults = mapOf(
            "global_ring" to "1",
            "global_vibrate" to "1",
            "global_notif" to "1",
            "pre_reminder_10m" to "1",
            "math_dismiss" to "0",
            "portal_url" to "https://boc.ubpkarawang.ac.id"
        )
        for ((k, v) in defaults) {
            val cv = ContentValues().apply {
                put("key", k)
                put("value", v)
            }
            db.insert("app_settings", null, cv)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS schedules")
        db.execSQL("DROP TABLE IF EXISTS attendance_logs")
        db.execSQL("DROP TABLE IF EXISTS app_settings")
        onCreate(db)
    }

    fun getAllSchedules(): List<CourseSchedule> {
        val list = mutableListOf<CourseSchedule>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, day_of_week, start_hour, start_minute, end_hour, end_minute, course_name, room, class_mode, ring_enabled, vibrate_enabled, notif_enabled, active FROM schedules ORDER BY day_of_week ASC, start_hour ASC, start_minute ASC",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    CourseSchedule(
                        id = it.getLong(0),
                        dayOfWeek = it.getInt(1),
                        startHour = it.getInt(2),
                        startMinute = it.getInt(3),
                        endHour = it.getInt(4),
                        endMinute = it.getInt(5),
                        courseName = it.getString(6),
                        room = it.getString(7),
                        classMode = it.getString(8),
                        ringEnabled = it.getInt(9) == 1,
                        vibrateEnabled = it.getInt(10) == 1,
                        notifEnabled = it.getInt(11) == 1,
                        active = it.getInt(12) == 1
                    )
                )
            }
        }
        return list
    }

    fun getSchedulesForDay(dayOfWeek: Int): List<CourseSchedule> {
        return getAllSchedules().filter { it.dayOfWeek == dayOfWeek }
    }

    fun getScheduleById(id: Long): CourseSchedule? {
        return getAllSchedules().firstOrNull { it.id == id }
    }

    fun insertSchedule(schedule: CourseSchedule): Long {
        val cv = ContentValues().apply {
            put("day_of_week", schedule.dayOfWeek)
            put("start_hour", schedule.startHour)
            put("start_minute", schedule.startMinute)
            put("end_hour", schedule.endHour)
            put("end_minute", schedule.endMinute)
            put("course_name", schedule.courseName)
            put("room", schedule.room)
            put("class_mode", schedule.classMode)
            put("ring_enabled", if (schedule.ringEnabled) 1 else 0)
            put("vibrate_enabled", if (schedule.vibrateEnabled) 1 else 0)
            put("notif_enabled", if (schedule.notifEnabled) 1 else 0)
            put("active", if (schedule.active) 1 else 0)
        }
        return writableDatabase.insert("schedules", null, cv)
    }

    fun updateSchedule(schedule: CourseSchedule) {
        val cv = ContentValues().apply {
            put("day_of_week", schedule.dayOfWeek)
            put("start_hour", schedule.startHour)
            put("start_minute", schedule.startMinute)
            put("end_hour", schedule.endHour)
            put("end_minute", schedule.endMinute)
            put("course_name", schedule.courseName)
            put("room", schedule.room)
            put("class_mode", schedule.classMode)
            put("ring_enabled", if (schedule.ringEnabled) 1 else 0)
            put("vibrate_enabled", if (schedule.vibrateEnabled) 1 else 0)
            put("notif_enabled", if (schedule.notifEnabled) 1 else 0)
            put("active", if (schedule.active) 1 else 0)
        }
        writableDatabase.update("schedules", cv, "id = ?", arrayOf(schedule.id.toString()))
    }

    fun deleteSchedule(id: Long) {
        writableDatabase.delete("schedules", "id = ?", arrayOf(id.toString()))
        writableDatabase.delete("attendance_logs", "schedule_id = ?", arrayOf(id.toString()))
    }

    fun updateScheduleModes(id: Long, ring: Boolean, vibrate: Boolean, notif: Boolean, active: Boolean) {
        val cv = ContentValues().apply {
            put("ring_enabled", if (ring) 1 else 0)
            put("vibrate_enabled", if (vibrate) 1 else 0)
            put("notif_enabled", if (notif) 1 else 0)
            put("active", if (active) 1 else 0)
        }
        writableDatabase.update("schedules", cv, "id = ?", arrayOf(id.toString()))
    }

    fun recordAttendance(scheduleId: Long, courseName: String, status: String) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val now = System.currentTimeMillis()
        val db = writableDatabase
        val cursor = db.rawQuery(
            "SELECT id FROM attendance_logs WHERE schedule_id = ? AND date_str = ?",
            arrayOf(scheduleId.toString(), today)
        )
        var existingId = -1L
        cursor.use {
            if (it.moveToFirst()) {
                existingId = it.getLong(0)
            }
        }
        val cv = ContentValues().apply {
            put("schedule_id", scheduleId)
            put("course_name", courseName)
            put("date_str", today)
            put("status", status)
            put("timestamp", now)
        }
        if (existingId > 0) {
            db.update("attendance_logs", cv, "id = ?", arrayOf(existingId.toString()))
        } else {
            db.insert("attendance_logs", null, cv)
        }
    }

    fun isAttendedToday(scheduleId: Long): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM attendance_logs WHERE schedule_id = ? AND date_str = ? AND status = 'HADIR'",
            arrayOf(scheduleId.toString(), today)
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0) > 0
            }
        }
        return false
    }

    fun getAttendanceStats(scheduleId: Long): Pair<Int, Int> {
        var hadir = 0
        var total = 0
        val cursor = readableDatabase.rawQuery(
            "SELECT status FROM attendance_logs WHERE schedule_id = ?",
            arrayOf(scheduleId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                total++
                if (it.getString(0) == "HADIR") {
                    hadir++
                }
            }
        }
        return Pair(hadir, total)
    }

    fun getSetting(key: String, defaultValue: String): String {
        val cursor = readableDatabase.rawQuery(
            "SELECT value FROM app_settings WHERE key = ?",
            arrayOf(key)
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getString(0)
            }
        }
        return defaultValue
    }

    fun setSetting(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict("app_settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
