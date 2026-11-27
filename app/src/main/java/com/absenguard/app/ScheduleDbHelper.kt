package com.absenguard.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

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
    val active: Boolean,
    val courseUrl: String = ""
)

data class AttendanceLog(
    val id: Long,
    val scheduleId: Long,
    val courseName: String,
    val dateStr: String,
    val status: String,
    val timestamp: Long
)

data class VaultPdf(
    val id: Long,
    val courseKey: String,
    val title: String,
    val fileUri: String,
    val pageCount: Int,
    val bodyText: String,
    val analysisJson: String,
    val suggestedBase: String?,
    val confidence: Double,
    val createdAt: Long
)

data class RecordingPart(
    val uri: String,
    val offsetMs: Long,
    val durationMs: Long
)

data class RecordingSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

data class VaultRecording(
    val id: Long,
    val courseKey: String,
    val title: String,
    val parts: List<RecordingPart>,
    val durationMs: Long,
    val segments: List<RecordingSegment>,
    val transcriptText: String,
    val createdAt: Long
)

data class VaultBoard(
    val id: Long,
    val courseKey: String,
    val imageUri: String,
    val ocrText: String,
    val enhanced: Boolean,
    val createdAt: Long
)

data class VaultFormula(
    val id: Long,
    val courseKey: String,
    val title: String,
    val expression: String,
    val notes: String,
    val source: String,
    val createdAt: Long
)

data class VaultCounts(
    val pdf: Int = 0,
    val recording: Int = 0,
    val board: Int = 0,
    val formula: Int = 0
)

data class VaultSearchHit(
    val type: String,
    val id: Long,
    val courseKey: String,
    val title: String,
    val snippet: String,
    val createdAt: Long
)

class ScheduleDbHelper(context: Context) : SQLiteOpenHelper(context, "absenguard.db", null, 4) {

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
                "active INTEGER NOT NULL DEFAULT 1, " +
                "course_url TEXT NOT NULL DEFAULT '')"
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

        createVaultTables(db)

        val initialCourses = listOf(
            CourseSchedule(0, Calendar.MONDAY, 7, 0, 8, 40, "Pemasaran Industri", "A405", "Offline", true, true, true, true, "https://elearning.ubpkarawang.ac.id/course/view.php?id=393"),
            CourseSchedule(0, Calendar.MONDAY, 9, 0, 10, 40, "Ilmu Bahan dan Kimia Lingkungan", "F405", "Offline", true, true, true, true, "https://elearning.ubpkarawang.ac.id/course/view.php?id=401"),
            CourseSchedule(0, Calendar.MONDAY, 11, 0, 12, 40, "Teori Probabilitas", "A306", "Offline", true, true, true, true, "https://elearning.ubpkarawang.ac.id/course/view.php?id=400"),
            CourseSchedule(0, Calendar.MONDAY, 13, 0, 14, 40, "Analisis dan Estimasi Biaya", "A405", "Offline", true, true, true, true, "https://elearning.ubpkarawang.ac.id/course/view.php?id=392"),
            CourseSchedule(0, Calendar.TUESDAY, 7, 0, 8, 40, "Algoritma & Pemrograman Komputer (T)", "F302", "Offline", true, true, true, true, "https://elearning.ubpkarawang.ac.id/mod/attendance/view.php?id=1297"),
            CourseSchedule(0, Calendar.TUESDAY, 9, 0, 10, 40, "Operasional Riset 1", "F207", "Offline", true, true, true, true, "https://elearning.ubpkarawang.ac.id/course/view.php?id=396"),
            CourseSchedule(0, Calendar.WEDNESDAY, 9, 0, 10, 40, "Algoritma & Pemrograman Komputer (P)", "LAB", "Offline", true, true, true, true, "https://elearning.ubpkarawang.ac.id/mod/attendance/view.php?id=2543"),
            CourseSchedule(0, Calendar.THURSDAY, 11, 0, 12, 40, "Analisis dan Perancangan Kerja (T)", "A404", "Offline", true, true, true, true, "https://elearning.ubpkarawang.ac.id/course/view.php?id=398"),
            CourseSchedule(0, Calendar.THURSDAY, 13, 0, 14, 40, "Analisis Perancangan Kerja (P)", "LAB", "Offline", true, true, true, true, "https://elearning.ubpkarawang.ac.id/course/view.php?id=397"),
            CourseSchedule(0, Calendar.FRIDAY, 13, 0, 14, 40, "Jatidiri Bangsa", "F207", "Offline", true, true, true, true, "https://elearning.ubpkarawang.ac.id/mod/attendance/view.php?id=1372")
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
                put("course_url", course.courseUrl)
            }
            db.insert("schedules", null, cv)
        }

        val defaults = mapOf(
            "global_ring" to "1",
            "global_vibrate" to "1",
            "global_notif" to "1",
            "pre_reminder_10m" to "1",
            "math_dismiss" to "0",
            "portal_url" to "https://elearning.ubpkarawang.ac.id/login/index.php",
            "sipt_username" to "ti25.rakharafiq@mhs.ubpkarawang.ac.id",
            "sipt_password" to "Buana0000!",
            "custom_ringtone_uri" to "",
            "dark_mode" to "0"
        )
        for ((k, v) in defaults) {
            val cv = ContentValues().apply {
                put("key", k)
                put("value", v)
            }
            db.insert("app_settings", null, cv)
        }
    }

    private fun createVaultTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS vault_pdfs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "course_key TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "file_uri TEXT NOT NULL, " +
                "page_count INTEGER NOT NULL DEFAULT 0, " +
                "body_text TEXT NOT NULL DEFAULT '', " +
                "analysis_json TEXT NOT NULL DEFAULT '{}', " +
                "suggested_base TEXT, " +
                "confidence REAL NOT NULL DEFAULT 0, " +
                "created_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS vault_recordings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "course_key TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "parts_json TEXT NOT NULL DEFAULT '[]', " +
                "duration_ms INTEGER NOT NULL DEFAULT 0, " +
                "transcript_json TEXT NOT NULL DEFAULT '[]', " +
                "transcript_text TEXT NOT NULL DEFAULT '', " +
                "created_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS vault_boards (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "course_key TEXT NOT NULL, " +
                "image_uri TEXT NOT NULL, " +
                "ocr_text TEXT NOT NULL DEFAULT '', " +
                "enhanced INTEGER NOT NULL DEFAULT 0, " +
                "created_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS vault_formulas (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "course_key TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "expression TEXT NOT NULL, " +
                "notes TEXT NOT NULL DEFAULT '', " +
                "source TEXT NOT NULL DEFAULT 'manual', " +
                "created_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_pdfs_course ON vault_pdfs(course_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_rec_course ON vault_recordings(course_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_boards_course ON vault_boards(course_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_formulas_course ON vault_formulas(course_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE schedules ADD COLUMN course_url TEXT NOT NULL DEFAULT ''")
            } catch (_: Exception) {
            }
            val urlMap = mapOf(
                "Pemasaran Industri" to "https://elearning.ubpkarawang.ac.id/course/view.php?id=393",
                "Ilmu Bahan dan Kimia Lingkungan" to "https://elearning.ubpkarawang.ac.id/course/view.php?id=401",
                "Teori Probabilitas" to "https://elearning.ubpkarawang.ac.id/course/view.php?id=400",
                "Analisis dan Estimasi Biaya" to "https://elearning.ubpkarawang.ac.id/course/view.php?id=392",
                "Algoritma & Pemrograman Komputer (T)" to "https://elearning.ubpkarawang.ac.id/mod/attendance/view.php?id=1297",
                "Operasional Riset 1" to "https://elearning.ubpkarawang.ac.id/course/view.php?id=396",
                "Algoritma & Pemrograman Komputer (P)" to "https://elearning.ubpkarawang.ac.id/mod/attendance/view.php?id=2543",
                "Analisis dan Perancangan Kerja (T)" to "https://elearning.ubpkarawang.ac.id/course/view.php?id=398",
                "Analisis Perancangan Kerja (P)" to "https://elearning.ubpkarawang.ac.id/course/view.php?id=397",
                "Jatidiri Bangsa" to "https://elearning.ubpkarawang.ac.id/mod/attendance/view.php?id=1372"
            )
            for ((name, url) in urlMap) {
                val cv = ContentValues().apply { put("course_url", url) }
                db.update("schedules", cv, "course_name = ? AND course_url = ''", arrayOf(name))
            }
            val portalCv = ContentValues().apply {
                put("value", "https://elearning.ubpkarawang.ac.id/login/index.php")
            }
            db.update("app_settings", portalCv, "key = 'portal_url' AND value LIKE '%sipt2%'", null)
        }
        if (oldVersion < 3) {
            val passCv = ContentValues().apply {
                put("value", "Buana0000!")
            }
            db.update("app_settings", passCv, "key = 'sipt_password' AND value = 'ubpk2024'", null)
        }
        if (oldVersion < 4) {
            createVaultTables(db)
        }
    }

    // ==================== ABSENGUARD SCHEDULES & ATTENDANCE ====================

    fun getAllSchedules(): List<CourseSchedule> {
        val list = mutableListOf<CourseSchedule>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, day_of_week, start_hour, start_minute, end_hour, end_minute, course_name, room, class_mode, ring_enabled, vibrate_enabled, notif_enabled, active, course_url FROM schedules ORDER BY day_of_week ASC, start_hour ASC, start_minute ASC",
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
                        active = it.getInt(12) == 1,
                        courseUrl = it.getString(13) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun getSchedulesForDay(dayOfWeek: Int): List<CourseSchedule> =
        getAllSchedules().filter { it.dayOfWeek == dayOfWeek }

    fun getScheduleById(id: Long): CourseSchedule? =
        getAllSchedules().firstOrNull { it.id == id }

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
            put("course_url", schedule.courseUrl)
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
            put("course_url", schedule.courseUrl)
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
            "SELECT id, status FROM attendance_logs WHERE schedule_id = ? AND date_str = ?",
            arrayOf(scheduleId.toString(), today)
        )
        var existingId = -1L
        var existingStatus = ""
        cursor.use {
            if (it.moveToFirst()) {
                existingId = it.getLong(0)
                existingStatus = it.getString(1)
            }
        }
        if (status == "TERLEWAT" && existingStatus == "HADIR") return
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

    fun getAllAttendanceLogs(): List<AttendanceLog> {
        val list = mutableListOf<AttendanceLog>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, schedule_id, course_name, date_str, status, timestamp FROM attendance_logs ORDER BY timestamp DESC",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    AttendanceLog(
                        it.getLong(0),
                        it.getLong(1),
                        it.getString(2),
                        it.getString(3),
                        it.getString(4),
                        it.getLong(5)
                    )
                )
            }
        }
        return list
    }

    fun isSkippedToday(scheduleId: Long): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return getSetting("skip_${scheduleId}_${today}", "0") == "1"
    }

    fun setSkipToday(scheduleId: Long, skip: Boolean) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        setSetting("skip_${scheduleId}_${today}", if (skip) "1" else "0")
    }

    fun isAttendedToday(scheduleId: Long): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM attendance_logs WHERE schedule_id = ? AND date_str = ? AND status = 'HADIR'",
            arrayOf(scheduleId.toString(), today)
        )
        cursor.use {
            if (it.moveToFirst()) return it.getInt(0) > 0
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
                if (it.getString(0) == "HADIR") hadir++
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
            if (it.moveToFirst()) return it.getString(0)
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

    // ==================== KULIAHVAULT IE: PDF, REKAMAN, PAPAN TULIS, RUMUS ====================

    fun insertVaultPdf(
        courseKey: String,
        title: String,
        fileUri: String,
        pageCount: Int,
        bodyText: String,
        analysisJson: String,
        suggestedBase: String?,
        confidence: Double
    ): Long {
        val cv = ContentValues().apply {
            put("course_key", courseKey)
            put("title", title)
            put("file_uri", fileUri)
            put("page_count", pageCount)
            put("body_text", bodyText)
            put("analysis_json", analysisJson)
            put("suggested_base", suggestedBase)
            put("confidence", confidence)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("vault_pdfs", null, cv)
    }

    fun listVaultPdfs(courseKey: String? = null): List<VaultPdf> {
        val list = mutableListOf<VaultPdf>()
        val cursor = if (courseKey.isNullOrBlank()) {
            readableDatabase.rawQuery(
                "SELECT id, course_key, title, file_uri, page_count, '', analysis_json, suggested_base, confidence, created_at FROM vault_pdfs ORDER BY created_at DESC",
                null
            )
        } else {
            readableDatabase.rawQuery(
                "SELECT id, course_key, title, file_uri, page_count, '', analysis_json, suggested_base, confidence, created_at FROM vault_pdfs WHERE course_key = ? ORDER BY created_at DESC",
                arrayOf(courseKey)
            )
        }
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    VaultPdf(
                        id = it.getLong(0),
                        courseKey = it.getString(1),
                        title = it.getString(2),
                        fileUri = it.getString(3),
                        pageCount = it.getInt(4),
                        bodyText = it.getString(5) ?: "",
                        analysisJson = it.getString(6) ?: "{}",
                        suggestedBase = it.getString(7),
                        confidence = it.getDouble(8),
                        createdAt = it.getLong(9)
                    )
                )
            }
        }
        return list
    }

    fun getVaultPdf(id: Long): VaultPdf? {
        val cursor = readableDatabase.rawQuery(
            "SELECT id, course_key, title, file_uri, page_count, body_text, analysis_json, suggested_base, confidence, created_at FROM vault_pdfs WHERE id = ?",
            arrayOf(id.toString())
        )
        cursor.use {
            if (it.moveToFirst()) {
                return VaultPdf(
                    id = it.getLong(0),
                    courseKey = it.getString(1),
                    title = it.getString(2),
                    fileUri = it.getString(3),
                    pageCount = it.getInt(4),
                    bodyText = it.getString(5) ?: "",
                    analysisJson = it.getString(6) ?: "{}",
                    suggestedBase = it.getString(7),
                    confidence = it.getDouble(8),
                    createdAt = it.getLong(9)
                )
            }
        }
        return null
    }

    fun moveVaultPdf(id: Long, newCourseKey: String) {
        val cv = ContentValues().apply { put("course_key", newCourseKey) }
        writableDatabase.update("vault_pdfs", cv, "id = ?", arrayOf(id.toString()))
    }

    fun renameVaultPdf(id: Long, newTitle: String) {
        val cv = ContentValues().apply { put("title", newTitle) }
        writableDatabase.update("vault_pdfs", cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteVaultPdf(id: Long) {
        writableDatabase.delete("vault_pdfs", "id = ?", arrayOf(id.toString()))
    }

    fun insertVaultRecording(
        courseKey: String,
        title: String,
        parts: List<RecordingPart>,
        durationMs: Long,
        segments: List<RecordingSegment>
    ): Long {
        val partsArr = JSONArray().apply {
            parts.forEach { p ->
                put(JSONObject().apply {
                    put("uri", p.uri)
                    put("offsetMs", p.offsetMs)
                    put("durationMs", p.durationMs)
                })
            }
        }
        val segArr = JSONArray().apply {
            segments.forEach { s ->
                put(JSONObject().apply {
                    put("text", s.text)
                    put("startMs", s.startMs)
                    put("endMs", s.endMs)
                })
            }
        }
        val fullText = segments.joinToString(" ") { it.text }
        val cv = ContentValues().apply {
            put("course_key", courseKey)
            put("title", title)
            put("parts_json", partsArr.toString())
            put("duration_ms", durationMs)
            put("transcript_json", segArr.toString())
            put("transcript_text", fullText)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("vault_recordings", null, cv)
    }

    fun listVaultRecordings(courseKey: String? = null): List<VaultRecording> {
        val list = mutableListOf<VaultRecording>()
        val cursor = if (courseKey.isNullOrBlank()) {
            readableDatabase.rawQuery(
                "SELECT id, course_key, title, parts_json, duration_ms, transcript_json, transcript_text, created_at FROM vault_recordings ORDER BY created_at DESC",
                null
            )
        } else {
            readableDatabase.rawQuery(
                "SELECT id, course_key, title, parts_json, duration_ms, transcript_json, transcript_text, created_at FROM vault_recordings WHERE course_key = ? ORDER BY created_at DESC",
                arrayOf(courseKey)
            )
        }
        cursor.use {
            while (it.moveToNext()) {
                val parts = parseParts(it.getString(3))
                val segs = parseSegments(it.getString(5))
                list.add(
                    VaultRecording(
                        id = it.getLong(0),
                        courseKey = it.getString(1),
                        title = it.getString(2),
                        parts = parts,
                        durationMs = it.getLong(4),
                        segments = segs,
                        transcriptText = it.getString(6) ?: "",
                        createdAt = it.getLong(7)
                    )
                )
            }
        }
        return list
    }

    fun getVaultRecording(id: Long): VaultRecording? =
        listVaultRecordings().firstOrNull { it.id == id }

    fun renameVaultRecording(id: Long, newTitle: String) {
        val cv = ContentValues().apply { put("title", newTitle) }
        writableDatabase.update("vault_recordings", cv, "id = ?", arrayOf(id.toString()))
    }

    fun moveVaultRecording(id: Long, newCourseKey: String) {
        val cv = ContentValues().apply { put("course_key", newCourseKey) }
        writableDatabase.update("vault_recordings", cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteVaultRecording(id: Long) {
        writableDatabase.delete("vault_recordings", "id = ?", arrayOf(id.toString()))
    }

    private fun parseParts(raw: String?): List<RecordingPart> {
        val out = mutableListOf<RecordingPart>()
        try {
            val arr = JSONArray(raw ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(RecordingPart(o.optString("uri"), o.optLong("offsetMs"), o.optLong("durationMs")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun parseSegments(raw: String?): List<RecordingSegment> {
        val out = mutableListOf<RecordingSegment>()
        try {
            val arr = JSONArray(raw ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(RecordingSegment(o.optString("text"), o.optLong("startMs"), o.optLong("endMs")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun insertVaultBoard(courseKey: String, imageUri: String, ocrText: String, enhanced: Boolean): Long {
        val cv = ContentValues().apply {
            put("course_key", courseKey)
            put("image_uri", imageUri)
            put("ocr_text", ocrText)
            put("enhanced", if (enhanced) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("vault_boards", null, cv)
    }

    fun listVaultBoards(courseKey: String? = null): List<VaultBoard> {
        val list = mutableListOf<VaultBoard>()
        val cursor = if (courseKey.isNullOrBlank()) {
            readableDatabase.rawQuery(
                "SELECT id, course_key, image_uri, ocr_text, enhanced, created_at FROM vault_boards ORDER BY created_at DESC",
                null
            )
        } else {
            readableDatabase.rawQuery(
                "SELECT id, course_key, image_uri, ocr_text, enhanced, created_at FROM vault_boards WHERE course_key = ? ORDER BY created_at DESC",
                arrayOf(courseKey)
            )
        }
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    VaultBoard(
                        id = it.getLong(0),
                        courseKey = it.getString(1),
                        imageUri = it.getString(2),
                        ocrText = it.getString(3) ?: "",
                        enhanced = it.getInt(4) == 1,
                        createdAt = it.getLong(5)
                    )
                )
            }
        }
        return list
    }

    fun getVaultBoard(id: Long): VaultBoard? =
        listVaultBoards().firstOrNull { it.id == id }

    fun updateVaultBoardText(id: Long, newText: String) {
        val cv = ContentValues().apply { put("ocr_text", newText) }
        writableDatabase.update("vault_boards", cv, "id = ?", arrayOf(id.toString()))
    }

    fun moveVaultBoard(id: Long, newCourseKey: String) {
        val cv = ContentValues().apply { put("course_key", newCourseKey) }
        writableDatabase.update("vault_boards", cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteVaultBoard(id: Long) {
        writableDatabase.delete("vault_boards", "id = ?", arrayOf(id.toString()))
    }

    fun insertVaultFormula(
        courseKey: String,
        title: String,
        expression: String,
        notes: String = "",
        source: String = "manual"
    ): Long {
        val cv = ContentValues().apply {
            put("course_key", courseKey)
            put("title", title)
            put("expression", expression)
            put("notes", notes)
            put("source", source)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("vault_formulas", null, cv)
    }

    fun updateVaultFormula(id: Long, courseKey: String, title: String, expression: String, notes: String) {
        val cv = ContentValues().apply {
            put("course_key", courseKey)
            put("title", title)
            put("expression", expression)
            put("notes", notes)
        }
        writableDatabase.update("vault_formulas", cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteVaultFormula(id: Long) {
        writableDatabase.delete("vault_formulas", "id = ?", arrayOf(id.toString()))
    }

    fun listVaultFormulas(courseKey: String? = null): List<VaultFormula> {
        val list = mutableListOf<VaultFormula>()
        val cursor = if (courseKey.isNullOrBlank()) {
            readableDatabase.rawQuery(
                "SELECT id, course_key, title, expression, notes, source, created_at FROM vault_formulas ORDER BY created_at DESC",
                null
            )
        } else {
            readableDatabase.rawQuery(
                "SELECT id, course_key, title, expression, notes, source, created_at FROM vault_formulas WHERE course_key = ? ORDER BY created_at DESC",
                arrayOf(courseKey)
            )
        }
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    VaultFormula(
                        id = it.getLong(0),
                        courseKey = it.getString(1),
                        title = it.getString(2),
                        expression = it.getString(3),
                        notes = it.getString(4) ?: "",
                        source = it.getString(5) ?: "manual",
                        createdAt = it.getLong(6)
                    )
                )
            }
        }
        return list
    }

    fun getVaultCourseCounts(): Map<String, VaultCounts> {
        val map = mutableMapOf<String, VaultCounts>()
        fun queryCount(table: String, updater: (VaultCounts, Int) -> VaultCounts) {
            val c = readableDatabase.rawQuery("SELECT course_key, COUNT(*) FROM $table GROUP BY course_key", null)
            c.use {
                while (it.moveToNext()) {
                    val key = it.getString(0)
                    val n = it.getInt(1)
                    val cur = map[key] ?: VaultCounts()
                    map[key] = updater(cur, n)
                }
            }
        }
        queryCount("vault_pdfs") { cur, n -> cur.copy(pdf = n) }
        queryCount("vault_recordings") { cur, n -> cur.copy(recording = n) }
        queryCount("vault_boards") { cur, n -> cur.copy(board = n) }
        queryCount("vault_formulas") { cur, n -> cur.copy(formula = n) }
        return map
    }

    fun searchVaultAll(query: String): List<VaultSearchHit> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val likeArg = "%$q%"
        val hits = mutableListOf<VaultSearchHit>()

        readableDatabase.rawQuery(
            "SELECT id, course_key, title, body_text, created_at FROM vault_pdfs WHERE LOWER(title) LIKE ? OR LOWER(body_text) LIKE ? ORDER BY created_at DESC LIMIT 20",
            arrayOf(likeArg, likeArg)
        ).use {
            while (it.moveToNext()) {
                hits.add(
                    VaultSearchHit(
                        type = "pdf",
                        id = it.getLong(0),
                        courseKey = it.getString(1),
                        title = it.getString(2),
                        snippet = KuliahVaultEngine.makeSnippet(it.getString(3) ?: it.getString(2), q),
                        createdAt = it.getLong(4)
                    )
                )
            }
        }

        readableDatabase.rawQuery(
            "SELECT id, course_key, title, transcript_text, created_at FROM vault_recordings WHERE LOWER(title) LIKE ? OR LOWER(transcript_text) LIKE ? ORDER BY created_at DESC LIMIT 20",
            arrayOf(likeArg, likeArg)
        ).use {
            while (it.moveToNext()) {
                hits.add(
                    VaultSearchHit(
                        type = "recording",
                        id = it.getLong(0),
                        courseKey = it.getString(1),
                        title = it.getString(2),
                        snippet = KuliahVaultEngine.makeSnippet(it.getString(3) ?: it.getString(2), q),
                        createdAt = it.getLong(4)
                    )
                )
            }
        }

        readableDatabase.rawQuery(
            "SELECT id, course_key, ocr_text, created_at FROM vault_boards WHERE LOWER(ocr_text) LIKE ? ORDER BY created_at DESC LIMIT 20",
            arrayOf(likeArg)
        ).use {
            while (it.moveToNext()) {
                hits.add(
                    VaultSearchHit(
                        type = "board",
                        id = it.getLong(0),
                        courseKey = it.getString(1),
                        title = "Papan Tulis",
                        snippet = KuliahVaultEngine.makeSnippet(it.getString(2) ?: "", q),
                        createdAt = it.getLong(3)
                    )
                )
            }
        }

        return hits
    }

    fun listVaultMeetingDates(courseKey: String): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val keys = linkedSetOf<String>()
        listOf("vault_pdfs", "vault_recordings", "vault_boards").forEach { table ->
            readableDatabase.rawQuery(
                "SELECT created_at FROM $table WHERE course_key = ? ORDER BY created_at DESC",
                arrayOf(courseKey)
            ).use {
                while (it.moveToNext()) {
                    keys.add(fmt.format(Date(it.getLong(0))))
                }
            }
        }
        return keys.toList().sortedDescending()
    }

    fun getVaultMeetingMaterial(
        courseKey: String,
        dateKey: String
    ): Triple<List<VaultPdf>, List<VaultRecording>, List<VaultBoard>> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val pdfs = listVaultPdfs(courseKey).filter { fmt.format(Date(it.createdAt)) == dateKey }
        val recs = listVaultRecordings(courseKey).filter { fmt.format(Date(it.createdAt)) == dateKey }
        val boards = listVaultBoards(courseKey).filter { fmt.format(Date(it.createdAt)) == dateKey }
        return Triple(pdfs, recs, boards)
    }

    // ==================== BACKUP & RESTORE ====================

    fun exportBackupJson(): String {
        val root = JSONObject()
        val schedules = JSONArray()
        for (schedule in getAllSchedules()) {
            schedules.put(JSONObject().apply {
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
                put("course_url", schedule.courseUrl)
            })
        }
        root.put("schedules", schedules)
        val logs = JSONArray()
        for (log in getAllAttendanceLogs()) {
            logs.put(JSONObject().apply {
                put("schedule_id", log.scheduleId)
                put("course_name", log.courseName)
                put("date_str", log.dateStr)
                put("status", log.status)
                put("timestamp", log.timestamp)
            })
        }
        root.put("attendance_logs", logs)
        val settings = JSONObject()
        val cursor = readableDatabase.rawQuery("SELECT key, value FROM app_settings", null)
        cursor.use {
            while (it.moveToNext()) {
                settings.put(it.getString(0), it.getString(1))
            }
        }
        root.put("app_settings", settings)
        return root.toString(2)
    }

    fun importBackupJson(jsonStr: String): Boolean {
        return try {
            val root = JSONObject(jsonStr)
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete("schedules", null, null)
                db.delete("attendance_logs", null, null)
                db.delete("app_settings", null, null)
                val schedules = root.optJSONArray("schedules") ?: JSONArray()
                for (i in 0 until schedules.length()) {
                    val item = schedules.getJSONObject(i)
                    val cv = ContentValues().apply {
                        put("day_of_week", item.getInt("day_of_week"))
                        put("start_hour", item.getInt("start_hour"))
                        put("start_minute", item.getInt("start_minute"))
                        put("end_hour", item.getInt("end_hour"))
                        put("end_minute", item.getInt("end_minute"))
                        put("course_name", item.getString("course_name"))
                        put("room", item.getString("room"))
                        put("class_mode", item.getString("class_mode"))
                        put("ring_enabled", item.optInt("ring_enabled", 1))
                        put("vibrate_enabled", item.optInt("vibrate_enabled", 1))
                        put("notif_enabled", item.optInt("notif_enabled", 1))
                        put("active", item.optInt("active", 1))
                        put("course_url", item.optString("course_url", ""))
                    }
                    db.insert("schedules", null, cv)
                }
                val logs = root.optJSONArray("attendance_logs") ?: JSONArray()
                for (i in 0 until logs.length()) {
                    val item = logs.getJSONObject(i)
                    val cv = ContentValues().apply {
                        put("schedule_id", item.getLong("schedule_id"))
                        put("course_name", item.getString("course_name"))
                        put("date_str", item.getString("date_str"))
                        put("status", item.getString("status"))
                        put("timestamp", item.getLong("timestamp"))
                    }
                    db.insert("attendance_logs", null, cv)
                }
                val settings = root.optJSONObject("app_settings") ?: JSONObject()
                settings.keys().forEach { key ->
                    val rawVal = settings.getString(key)
                    val finalVal = if (key == "sipt_password" && rawVal == "ubpk2024") "Buana0000!" else rawVal
                    val cv = ContentValues().apply {
                        put("key", key)
                        put("value", finalVal)
                    }
                    db.insert("app_settings", null, cv)
                }
                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        } catch (_: Exception) {
            false
        }
    }
}
