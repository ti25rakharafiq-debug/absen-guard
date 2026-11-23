package com.absenguard.app

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var db: ScheduleDbHelper
    private lateinit var rootContainer: FrameLayout
    private lateinit var mainContent: LinearLayout
    private lateinit var permissionCard: LinearLayout
    private lateinit var nextClassTitle: TextView
    private lateinit var nextClassMeta: TextView
    private lateinit var nextClassCountdown: TextView
    private lateinit var filterRow: LinearLayout
    private lateinit var scheduleListContainer: LinearLayout

    private var selectedDayFilter = 0
    private val handler = Handler(Looper.getMainLooper())

    private val countdownRunnable = object : Runnable {
        override fun run() {
            updateNextClassBanner()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = ScheduleDbHelper(this)

        rootContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F8F9FA"))
            }
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(88))
        }

        buildHeader()
        buildPermissionCard()
        buildNextClassCard()
        buildControlCard()
        buildFilterChips()

        scheduleListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainContent.addView(scheduleListContainer)

        scrollView.addView(mainContent)
        rootContainer.addView(scrollView)

        val fabAdd = Button(this).apply {
            text = "+ Tambah Jadwal"
            isAllCaps = false
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8F9FA"))
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#5B6AD0"))
                cornerRadius = dp(26).toFloat()
            }
            setOnClickListener { showScheduleEditorDialog(null) }
        }
        val fabParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(dp(16), dp(16), dp(16), dp(20))
        }
        rootContainer.addView(fabAdd, fabParams)

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        setContentView(rootContainer)
        refreshAll()
        handler.post(countdownRunnable)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onDestroy() {
        handler.removeCallbacks(countdownRunnable)
        super.onDestroy()
    }

    private fun refreshAll() {
        updatePermissionVisibility()
        updateNextClassBanner()
        renderFilterChips()
        renderScheduleCards()
        AlarmScheduler.scheduleAll(this)
        NextClassWidgetProvider.updateAllWidgets(this)
    }

    private fun buildHeader() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = "AbsenGuard"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E2430"))
        }

        val subtitle = TextView(this).apply {
            text = "Pengingat Absen Kuliah & Alarm Otomatis"
            textSize = 12f
            setTextColor(Color.parseColor("#5A6577"))
        }

        titleCol.addView(title)
        titleCol.addView(subtitle)

        val btnSettings = Button(this).apply {
            text = "Pengaturan"
            isAllCaps = false
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E2430"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EEF1F6"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#D5DCE6"))
            }
            setOnClickListener { showSettingsDialog() }
        }

        row.addView(titleCol)
        row.addView(btnSettings)
        mainContent.addView(row)
        mainContent.addView(space(14))
    }

    private fun buildPermissionCard() {
        permissionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FEE2E2"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#DC2626"))
            }
        }

        val warnTitle = TextView(this).apply {
            text = "Izin Alarm & Notifikasi Sistem Belum Lengkap"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#DC2626"))
        }

        val warnDesc = TextView(this).apply {
            text = "Aktifkan izin notifikasi, alarm presisi, dan bebas hemat baterai agar HP tetap berdering saat terkunci."
            textSize = 12f
            setTextColor(Color.parseColor("#1E2430"))
        }

        val btnGrant = Button(this).apply {
            text = "Izinkan Sekarang"
            isAllCaps = false
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8F9FA"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DC2626"))
                cornerRadius = dp(18).toFloat()
            }
            setOnClickListener { requestMissingPermissions() }
        }

        permissionCard.addView(warnTitle)
        permissionCard.addView(space(4))
        permissionCard.addView(warnDesc)
        permissionCard.addView(space(10))
        permissionCard.addView(btnGrant, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))

        mainContent.addView(permissionCard)
        mainContent.addView(space(12))
    }

    private fun updatePermissionVisibility() {
        val missing = hasMissingSystemPermissions()
        permissionCard.visibility = if (missing) View.VISIBLE else View.GONE
    }

    private fun hasMissingSystemPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return true
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                return true
            }
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            return true
        }
        return false
    }

    private fun requestMissingPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                return
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
                return
            }
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun buildNextClassCard() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#5B6AD0"))
                cornerRadius = dp(20).toFloat()
            }
        }

        val tag = TextView(this).apply {
            text = "JADWAL KULIAH TERDEKAT"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E6F4EA"))
        }

        nextClassTitle = TextView(this).apply {
            text = "Memuat..."
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8F9FA"))
        }

        nextClassMeta = TextView(this).apply {
            text = "-"
            textSize = 12f
            setTextColor(Color.parseColor("#EEF1F6"))
        }

        nextClassCountdown = TextView(this).apply {
            text = "00:00:00"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8F9FA"))
        }

        card.addView(tag)
        card.addView(space(4))
        card.addView(nextClassTitle)
        card.addView(space(2))
        card.addView(nextClassMeta)
        card.addView(space(8))
        card.addView(nextClassCountdown)

        mainContent.addView(card)
        mainContent.addView(space(14))
    }

    private fun updateNextClassBanner() {
        val next = AlarmScheduler.getNextUpcomingSchedule(this)
        if (next == null) {
            nextClassTitle.text = "Belum ada jadwal aktif"
            nextClassMeta.text = "Tambahkan atau aktifkan jadwal kuliah"
            nextClassCountdown.text = "--:--:--"
            return
        }
        val s = next.first
        val diff = maxOf(0L, next.second - System.currentTimeMillis())
        val totalSec = diff / 1000L
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L

        nextClassTitle.text = s.courseName
        nextClassMeta.text = String.format(
            Locale.getDefault(),
            "%s • %02d:%02d - %02d:%02d • Ruang %s (%s)",
            dayName(s.dayOfWeek),
            s.startHour,
            s.startMinute,
            s.endHour,
            s.endMinute,
            s.room,
            s.classMode
        )
        nextClassCountdown.text = String.format(Locale.getDefault(), "Mulai dalam %02d:%02d:%02d", hours, mins, secs)
    }

    private fun buildControlCard() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EEF1F6"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#D5DCE6"))
            }
        }

        val sectionTitle = TextView(this).apply {
            text = "Mode Peringatan Global"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E2430"))
        }
        card.addView(sectionTitle)
        card.addView(space(8))

        card.addView(makeSwitchRow("Mode Dering Suara", "global_ring", true))
        card.addView(makeSwitchRow("Mode Getar HP", "global_vibrate", true))
        card.addView(makeSwitchRow("Mode Notifikasi Pop-up", "global_notif", true))
        card.addView(makeSwitchRow("Pengingat Persiapan H-10 Menit", "pre_reminder_10m", true))

        card.addView(space(10))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnTest = Button(this).apply {
            text = "Tes Dering 3 Detik"
            isAllCaps = false
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8F9FA"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#5B6AD0"))
                cornerRadius = dp(18).toFloat()
            }
            setOnClickListener {
                AlarmScheduler.triggerInstantTest(this@MainActivity)
                Toast.makeText(this@MainActivity, "Tes dering, getar & notif aktif dalam 3 detik...", Toast.LENGTH_SHORT).show()
            }
        }

        val btnPortal = Button(this).apply {
            text = "Buka Portal BOC"
            isAllCaps = false
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8F9FA"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2E8B57"))
                cornerRadius = dp(18).toFloat()
            }
            setOnClickListener {
                val url = db.getSetting("portal_url", "https://boc.ubpkarawang.ac.id")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Browser tidak ditemukan", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val p1 = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
        val p2 = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) }
        btnRow.addView(btnTest, p1)
        btnRow.addView(btnPortal, p2)
        card.addView(btnRow)

        mainContent.addView(card)
        mainContent.addView(space(14))
    }

    private fun makeSwitchRow(label: String, settingKey: String, defaultOn: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val tv = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor("#1E2430"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = SwitchCompat(this).apply {
            isChecked = db.getSetting(settingKey, if (defaultOn) "1" else "0") == "1"
            setOnCheckedChangeListener { _, isChecked ->
                db.setSetting(settingKey, if (isChecked) "1" else "0")
                AlarmScheduler.scheduleAll(this@MainActivity)
            }
        }
        row.addView(tv)
        row.addView(sw)
        return row
    }

    private fun buildFilterChips() {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        scroll.addView(filterRow)
        mainContent.addView(scroll)
        mainContent.addView(space(12))
    }

    private fun renderFilterChips() {
        filterRow.removeAllViews()
        val items = listOf(
            0 to "Semua",
            Calendar.MONDAY to "Senin",
            Calendar.TUESDAY to "Selasa",
            Calendar.WEDNESDAY to "Rabu",
            Calendar.THURSDAY to "Kamis",
            Calendar.FRIDAY to "Jum'at"
        )
        for ((dayCode, label) in items) {
            val active = selectedDayFilter == dayCode
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (active) "#F8F9FA" else "#1E2430"))
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (active) "#5B6AD0" else "#EEF1F6"))
                    cornerRadius = dp(18).toFloat()
                    if (!active) {
                        setStroke(dp(1), Color.parseColor("#D5DCE6"))
                    }
                }
                setOnClickListener {
                    selectedDayFilter = dayCode
                    renderFilterChips()
                    renderScheduleCards()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            filterRow.addView(chip, lp)
        }
    }

    private fun renderScheduleCards() {
        scheduleListContainer.removeAllViews()
        val schedules = if (selectedDayFilter == 0) {
            db.getAllSchedules()
        } else {
            db.getSchedulesForDay(selectedDayFilter)
        }

        if (schedules.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Tidak ada jadwal pada hari ini."
                textSize = 14f
                setTextColor(Color.parseColor("#5A6577"))
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(24), dp(16), dp(24))
            }
            scheduleListContainer.addView(empty)
            return
        }

        for (s in schedules) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EEF1F6"))
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(1), Color.parseColor("#D5DCE6"))
                }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val meta = TextView(this).apply {
                text = String.format(
                    Locale.getDefault(),
                    "%s • %02d:%02d - %02d:%02d • %s",
                    dayName(s.dayOfWeek),
                    s.startHour,
                    s.startMinute,
                    s.endHour,
                    s.endMinute,
                    s.room
                )
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#5B6AD0"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val isOnline = s.classMode.equals("Online", ignoreCase = true)
            val modeBadge = TextView(this).apply {
                text = if (isOnline) "Online (BOC)" else "Offline"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#F8F9FA"))
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (isOnline) "#D97706" else "#5B6AD0"))
                    cornerRadius = dp(12).toFloat()
                }
                setOnClickListener {
                    val toggled = s.copy(classMode = if (isOnline) "Offline" else "Online")
                    db.updateSchedule(toggled)
                    renderScheduleCards()
                    updateNextClassBanner()
                }
            }

            topRow.addView(meta)
            topRow.addView(modeBadge)

            val courseTitle = TextView(this).apply {
                text = s.courseName
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1E2430"))
            }

            val stats = db.getAttendanceStats(s.id)
            val pct = if (stats.second > 0) (stats.first * 100) / stats.second else 100
            val statsView = TextView(this).apply {
                text = "Kehadiran: ${stats.first}/${stats.second} ($pct%)"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (pct < 80 && stats.second > 0) "#DC2626" else "#2E8B57"))
            }

            val toggleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            toggleRow.addView(makeMiniToggle("Dering", s.ringEnabled) {
                db.updateScheduleModes(s.id, !s.ringEnabled, s.vibrateEnabled, s.notifEnabled, s.active)
                refreshAll()
            })
            toggleRow.addView(makeMiniToggle("Getar", s.vibrateEnabled) {
                db.updateScheduleModes(s.id, s.ringEnabled, !s.vibrateEnabled, s.notifEnabled, s.active)
                refreshAll()
            })
            toggleRow.addView(makeMiniToggle("Notif", s.notifEnabled) {
                db.updateScheduleModes(s.id, s.ringEnabled, s.vibrateEnabled, !s.notifEnabled, s.active)
                refreshAll()
            })

            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val attendedToday = db.isAttendedToday(s.id)
            val btnCheckIn = Button(this).apply {
                text = if (attendedToday) "Sudah Absen ✓" else "Absen Sekarang"
                isAllCaps = false
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (attendedToday) "#2E8B57" else "#F8F9FA"))
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (attendedToday) "#E6F4EA" else "#2E8B57"))
                    cornerRadius = dp(16).toFloat()
                    if (attendedToday) {
                        setStroke(dp(1), Color.parseColor("#2E8B57"))
                    }
                }
                setOnClickListener {
                    db.recordAttendance(s.id, s.courseName, "HADIR")
                    Toast.makeText(this@MainActivity, "Hadir tercatat: ${s.courseName}", Toast.LENGTH_SHORT).show()
                    refreshAll()
                }
            }

            val btnEdit = TextView(this).apply {
                text = "Edit"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#5B6AD0"))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { showScheduleEditorDialog(s) }
            }

            val btnDelete = TextView(this).apply {
                text = "Hapus"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#DC2626"))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Hapus Jadwal")
                        .setMessage("Hapus ${s.courseName}?")
                        .setPositiveButton("Hapus") { _, _ ->
                            db.deleteSchedule(s.id)
                            refreshAll()
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            }

            actionRow.addView(btnCheckIn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))
            actionRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            actionRow.addView(btnEdit)
            actionRow.addView(btnDelete)

            card.addView(topRow)
            card.addView(space(6))
            card.addView(courseTitle)
            card.addView(space(4))
            card.addView(statsView)
            card.addView(space(10))
            card.addView(toggleRow)
            card.addView(space(10))
            card.addView(actionRow)

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            scheduleListContainer.addView(card, lp)
        }
    }

    private fun makeMiniToggle(label: String, enabled: Boolean, onToggle: () -> Unit): TextView {
        return TextView(this).apply {
            text = if (enabled) "● $label" else "○ $label"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (enabled) "#2E8B57" else "#5A6577"))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (enabled) "#E6F4EA" else "#F8F9FA"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor(if (enabled) "#2E8B57" else "#D5DCE6"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { onToggle() }
        }
    }

    private fun showScheduleEditorDialog(existing: CourseSchedule?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val days = listOf("Senin", "Selasa", "Rabu", "Kamis", "Jum'at")
        val dayCodes = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        val daySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, days)
            val idx = if (existing != null) dayCodes.indexOf(existing.dayOfWeek).coerceAtLeast(0) else 0
            setSelection(idx)
        }

        val nameInput = EditText(this).apply {
            hint = "Nama Mata Kuliah"
            setText(existing?.courseName ?: "")
        }

        val roomInput = EditText(this).apply {
            hint = "Ruangan (misal: A405 / LAB)"
            setText(existing?.room ?: "")
        }

        val modes = listOf("Offline", "Online")
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes)
            setSelection(if (existing?.classMode.equals("Online", true)) 1 else 0)
        }

        var startH = existing?.startHour ?: 7
        var startM = existing?.startMinute ?: 0
        var endH = existing?.endHour ?: 8
        var endM = existing?.endMinute ?: 40

        val btnStart = Button(this).apply {
            text = String.format(Locale.getDefault(), "Jam Mulai: %02d:%02d", startH, startM)
            isAllCaps = false
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, h, m ->
                    startH = h
                    startM = m
                    text = String.format(Locale.getDefault(), "Jam Mulai: %02d:%02d", startH, startM)
                }, startH, startM, true).show()
            }
        }

        val btnEnd = Button(this).apply {
            text = String.format(Locale.getDefault(), "Jam Selesai: %02d:%02d", endH, endM)
            isAllCaps = false
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, h, m ->
                    endH = h
                    endM = m
                    text = String.format(Locale.getDefault(), "Jam Selesai: %02d:%02d", endH, endM)
                }, endH, endM, true).show()
            }
        }

        container.addView(daySpinner)
        container.addView(nameInput)
        container.addView(roomInput)
        container.addView(modeSpinner)
        container.addView(btnStart)
        container.addView(btnEnd)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Tambah Jadwal Kuliah" else "Edit Jadwal Kuliah")
            .setView(container)
            .setPositiveButton("Simpan") { _, _ ->
                val name = nameInput.text.toString().trim()
                val room = roomInput.text.toString().trim().ifEmpty { "-" }
                if (name.isEmpty()) {
                    Toast.makeText(this, "Nama mata kuliah wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val item = CourseSchedule(
                    id = existing?.id ?: 0L,
                    dayOfWeek = dayCodes[daySpinner.selectedItemPosition],
                    startHour = startH,
                    startMinute = startM,
                    endHour = endH,
                    endMinute = endM,
                    courseName = name,
                    room = room,
                    classMode = modes[modeSpinner.selectedItemPosition],
                    ringEnabled = existing?.ringEnabled ?: true,
                    vibrateEnabled = existing?.vibrateEnabled ?: true,
                    notifEnabled = existing?.notifEnabled ?: true,
                    active = true
                )
                if (existing == null) {
                    db.insertSchedule(item)
                } else {
                    db.updateSchedule(item)
                }
                refreshAll()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val urlLabel = TextView(this).apply {
            text = "Link Portal Absen (BOC / E-Learning):"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }

        val urlInput = EditText(this).apply {
            setText(db.getSetting("portal_url", "https://boc.ubpkarawang.ac.id"))
        }

        val mathSwitch = SwitchCompat(this).apply {
            text = "Tantangan Hitungan Pagi Saat Matikan Alarm"
            isChecked = db.getSetting("math_dismiss", "0") == "1"
        }

        container.addView(urlLabel)
        container.addView(urlInput)
        container.addView(space(10))
        container.addView(mathSwitch)

        AlertDialog.Builder(this)
            .setTitle("Pengaturan AbsenGuard")
            .setView(container)
            .setPositiveButton("Simpan") { _, _ ->
                val url = urlInput.text.toString().trim().ifEmpty { "https://boc.ubpkarawang.ac.id" }
                db.setSetting("portal_url", url)
                db.setSetting("math_dismiss", if (mathSwitch.isChecked) "1" else "0")
                Toast.makeText(this, "Pengaturan disimpan", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun dayName(dayOfWeek: Int): String {
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

    private fun space(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
