package com.absenguard.app

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var db: ScheduleDbHelper
    private lateinit var rootContainer: FrameLayout
    private lateinit var mainContent: LinearLayout
    private lateinit var permissionCard: LinearLayout
    private lateinit var nextClassCard: LinearLayout
    private lateinit var nextClassTitle: TextView
    private lateinit var nextClassMeta: TextView
    private lateinit var nextClassCountdown: TextView
    private lateinit var filterRow: LinearLayout
    private lateinit var scheduleListContainer: LinearLayout
    private var selectedDayFilter = 0
    private val handler = Handler(Looper.getMainLooper())

    private val ringtonePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            db.setSetting("custom_ringtone_uri", it.toString())
            Toast.makeText(this, "Nada dering tersimpan", Toast.LENGTH_SHORT).show()
        }
    }
    private val backupPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { writeBackup(it) }
    }
    private val restorePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { restoreBackup(it) }
    }

    private val countdownRunnable = object : Runnable {
        override fun run() {
            updateNextClassBanner()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = ScheduleDbHelper(this)
        applyDarkMode()

        rootContainer = FrameLayout(this).apply {
            background = gradient(paletteBg())
        }
        val scrollView = ScrollView(this).apply { isFillViewport = true }
        mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(88))
        }
        buildHeader()
        buildPermissionCard()
        buildNextClassCard()
        buildControlCard()
        buildFilterChips()
        scheduleListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mainContent.addView(scheduleListContainer)
        scrollView.addView(mainContent)
        rootContainer.addView(scrollView)

        val fab = Button(this).apply {
            text = "+ Tambah Jadwal"
            isAllCaps = false
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteWhite())
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = gradient("#5B6AD0", radius = dp(26).toFloat())
            setOnClickListener { showScheduleEditorDialog(null) }
            attachSpringPress(this)
        }
        val fabParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(dp(16), dp(16), dp(16), dp(20))
        }
        rootContainer.addView(fab, fabParams)
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }
        setContentView(rootContainer)
        refreshAll()
        startBreathing(nextClassCard)
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
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "AbsenGuard"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteText())
        })
        titleCol.addView(TextView(this).apply {
            text = "Pengingat Absen Kuliah & Alarm Otomatis"
            textSize = 12f
            setTextColor(paletteSubtext())
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(headerButton("Riwayat") { showHistoryDialog() })
        buttons.addView(headerButton("Pengaturan") { showSettingsDialog() })
        row.addView(titleCol)
        row.addView(buttons)
        mainContent.addView(row)
        mainContent.addView(space(14))
    }

    private fun headerButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteText())
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = gradient(paletteCard(), paletteBorder(), dp(18).toFloat())
            setOnClickListener { action() }
            attachSpringPress(this)
        }.also { it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginStart = dp(6) } }
    }

    private fun buildPermissionCard() {
        permissionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = gradient("#FEE2E2", "#DC2626", dp(18).toFloat())
        }
        permissionCard.addView(TextView(this).apply {
            text = "Izin Alarm & Notifikasi Sistem Belum Lengkap"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#DC2626"))
        })
        permissionCard.addView(space(4))
        permissionCard.addView(TextView(this).apply {
            text = "Aktifkan izin notifikasi, alarm presisi, dan bebas hemat baterai."
            textSize = 12f
            setTextColor(paletteText())
        })
        permissionCard.addView(space(10))
        val button = Button(this).apply {
            text = "Izinkan Sekarang"
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteWhite())
            background = gradient("#DC2626", radius = dp(18).toFloat())
            setOnClickListener { requestMissingPermissions() }
            attachSpringPress(this)
        }
        permissionCard.addView(button)
        mainContent.addView(permissionCard)
        mainContent.addView(space(12))
    }

    private fun updatePermissionVisibility() {
        permissionCard.visibility = if (hasMissingSystemPermissions()) View.VISIBLE else View.GONE
    }

    private fun hasMissingSystemPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) return true
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestMissingPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            return
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
        nextClassCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = gradient("#5B6AD0", radius = dp(20).toFloat())
        }
        nextClassCard.addView(TextView(this).apply {
            text = "JADWAL KULIAH TERDEKAT"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteWhite())
        })
        nextClassCard.addView(space(4))
        nextClassTitle = TextView(this).apply {
            text = "Memuat..."
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteWhite())
        }
        nextClassMeta = TextView(this).apply {
            text = "-"
            textSize = 12f
            setTextColor(paletteWhite())
        }
        nextClassCountdown = TextView(this).apply {
            text = "00:00:00"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteWhite())
        }
        nextClassCard.addView(nextClassTitle)
        nextClassCard.addView(space(2))
        nextClassCard.addView(nextClassMeta)
        nextClassCard.addView(space(8))
        nextClassCard.addView(nextClassCountdown)
        mainContent.addView(nextClassCard)
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
        val schedule = next.first
        val diff = maxOf(0L, next.second - System.currentTimeMillis())
        val total = diff / 1000L
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        nextClassTitle.text = schedule.courseName
        nextClassMeta.text = String.format(
            Locale.getDefault(),
            "%s • %02d:%02d - %02d:%02d • Ruang %s (%s)",
            dayName(schedule.dayOfWeek),
            schedule.startHour,
            schedule.startMinute,
            schedule.endHour,
            schedule.endMinute,
            schedule.room,
            schedule.classMode
        )
        nextClassCountdown.text = String.format(
            Locale.getDefault(),
            "Mulai dalam %02d:%02d:%02d",
            hours,
            minutes,
            seconds
        )
    }

    private fun buildControlCard() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = gradient(paletteCard(), paletteBorder(), dp(20).toFloat())
        }
        card.addView(TextView(this).apply {
            text = "Mode Peringatan Global"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteText())
        })
        card.addView(space(8))
        card.addView(makeSwitchRow("Mode Dering Suara", "global_ring", true))
        card.addView(makeSwitchRow("Mode Getar HP", "global_vibrate", true))
        card.addView(makeSwitchRow("Mode Notifikasi Pop-up", "global_notif", true))
        card.addView(makeSwitchRow("Pengingat Persiapan H-10 Menit", "pre_reminder_10m", true))
        card.addView(space(10))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val test = Button(this).apply {
            text = "Tes Dering"
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteWhite())
            background = gradient("#5B6AD0", radius = dp(18).toFloat())
            setOnClickListener {
                AlarmScheduler.triggerInstantTest(this@MainActivity)
                Toast.makeText(this@MainActivity, "Tes alarm aktif", Toast.LENGTH_SHORT).show()
            }
            attachSpringPress(this)
        }
        val portal = Button(this).apply {
            text = "Buka Portal SIPT2"
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteWhite())
            background = gradient("#2E8B57", radius = dp(18).toFloat())
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SiptPortalActivity::class.java))
            }
            attachSpringPress(this)
        }
        val silent = Button(this).apply {
            text = getSilentButtonLabel()
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteWhite())
            background = gradient("#D97706", radius = dp(18).toFloat())
            setOnClickListener { toggleSilentMode(this) }
            attachSpringPress(this)
        }
        val p1 = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) }
        val p2 = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5); marginEnd = dp(5) }
        val p3 = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) }
        row.addView(test, p1)
        row.addView(portal, p2)
        row.addView(silent, p3)
        card.addView(row)
        mainContent.addView(card)
        mainContent.addView(space(14))
    }

    private fun getSilentButtonLabel(): String {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return if (nm.isNotificationPolicyAccessGranted && am.ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            "Mode Hening ✓"
        } else {
            "Mode Hening Kelas"
        }
    }

    private fun toggleSilentMode(button: Button) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            Toast.makeText(this, "Aktifkan akses Mode Jangan Ganggu untuk Mode Hening", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            } catch (_: Exception) {
            }
            return
        }
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = if (am.ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            AudioManager.RINGER_MODE_NORMAL
        } else {
            AudioManager.RINGER_MODE_VIBRATE
        }
        button.text = getSilentButtonLabel()
    }

    private fun makeSwitchRow(label: String, settingKey: String, defaultOn: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val text = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(paletteText())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switch = SwitchCompat(this).apply {
            isChecked = db.getSetting(settingKey, if (defaultOn) "1" else "0") == "1"
            setOnCheckedChangeListener { _, checked ->
                db.setSetting(settingKey, if (checked) "1" else "0")
                AlarmScheduler.scheduleAll(this@MainActivity)
            }
        }
        row.addView(text)
        row.addView(switch)
        return row
    }

    private fun buildFilterChips() {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
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
        for ((day, label) in items) {
            val active = selectedDayFilter == day
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (active) paletteWhite() else paletteText())
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = if (active) {
                    gradient("#5B6AD0", radius = dp(18).toFloat())
                } else {
                    gradient(paletteCard(), paletteBorder(), dp(18).toFloat())
                }
                setOnClickListener {
                    selectedDayFilter = day
                    renderFilterChips()
                    renderScheduleCards()
                }
                attachSpringPress(this)
            }
            filterRow.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
    }

    private fun renderScheduleCards() {
        scheduleListContainer.removeAllViews()
        val schedules = if (selectedDayFilter == 0) db.getAllSchedules() else db.getSchedulesForDay(selectedDayFilter)
        if (schedules.isEmpty()) {
            scheduleListContainer.addView(TextView(this).apply {
                text = "Tidak ada jadwal pada hari ini."
                textSize = 14f
                setTextColor(paletteSubtext())
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(24), dp(16), dp(24))
            })
            return
        }
        schedules.forEachIndexed { index, schedule ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = gradient(paletteCard(), paletteBorder(), dp(18).toFloat())
                alpha = 0f
                translationY = dp(24).toFloat()
                animate().alpha(1f).translationY(0f).setDuration(220)
                    .setStartDelay(index * 45L).setInterpolator(DecelerateInterpolator()).start()
                attachSpringPress(this)
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = String.format(
                    Locale.getDefault(),
                    "%s • %02d:%02d - %02d:%02d • %s",
                    dayName(schedule.dayOfWeek),
                    schedule.startHour,
                    schedule.startMinute,
                    schedule.endHour,
                    schedule.endMinute,
                    schedule.room
                )
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor("#5B6AD0".let(::parseColor))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val online = schedule.classMode.equals("Online", ignoreCase = true)
            top.addView(TextView(this).apply {
                text = if (online) "Online" else "Offline"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(paletteWhite())
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = gradient(if (online) "#D97706" else "#5B6AD0", radius = dp(12).toFloat())
            })
            card.addView(top)
            card.addView(space(6))
            card.addView(TextView(this).apply {
                text = schedule.courseName
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(paletteText())
            })
            val stats = db.getAttendanceStats(schedule.id)
            val percent = if (stats.second > 0) (stats.first * 100) / stats.second else 100
            card.addView(TextView(this).apply {
                text = "Kehadiran: ${stats.first}/${stats.second} ($percent%)"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(parseColor(if (percent < 80 && stats.second > 0) "#DC2626" else "#2E8B57"))
            })
            card.addView(space(8))
            val toggleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            toggleRow.addView(makeMiniToggle("Dering", schedule.ringEnabled) {
                db.updateScheduleModes(schedule.id, !schedule.ringEnabled, schedule.vibrateEnabled, schedule.notifEnabled, schedule.active)
                refreshAll()
            })
            toggleRow.addView(makeMiniToggle("Getar", schedule.vibrateEnabled) {
                db.updateScheduleModes(schedule.id, schedule.ringEnabled, !schedule.vibrateEnabled, schedule.notifEnabled, schedule.active)
                refreshAll()
            })
            toggleRow.addView(makeMiniToggle("Notif", schedule.notifEnabled) {
                db.updateScheduleModes(schedule.id, schedule.ringEnabled, schedule.vibrateEnabled, !schedule.notifEnabled, schedule.active)
                refreshAll()
            })
            val skipped = db.isSkippedToday(schedule.id)
            toggleRow.addView(makeMiniToggle("Lewati Hari Ini", skipped) {
                db.setSkipToday(schedule.id, !db.isSkippedToday(schedule.id))
                refreshAll()
            })
            card.addView(toggleRow)
            card.addView(space(8))
            val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val attended = db.isAttendedToday(schedule.id)
            val checkIn = Button(this).apply {
                text = if (attended) "Sudah Absen ✓" else "Absen Sekarang"
                isAllCaps = false
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (attended) parseColor("#2E8B57") else paletteWhite())
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = gradient(if (attended) "#E6F4EA" else "#2E8B57", if (attended) "#2E8B57" else null, dp(16).toFloat())
                setOnClickListener {
                    db.recordAttendance(schedule.id, schedule.courseName, "HADIR")
                    Toast.makeText(this@MainActivity, "Hadir tercatat", Toast.LENGTH_SHORT).show()
                    refreshAll()
                }
                attachSpringPress(this)
            }
            val share = Button(this).apply {
                text = "Share WA"
                isAllCaps = false
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(paletteWhite())
                background = gradient("#25D366", radius = dp(16).toFloat())
                setOnClickListener { shareSchedule(schedule) }
                attachSpringPress(this)
            }
            actionRow.addView(checkIn)
            actionRow.addView(share, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                marginStart = dp(8)
            })
            card.addView(actionRow)
            scheduleListContainer.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }
    }

    private fun shareSchedule(schedule: CourseSchedule) {
        val message = "Pengingat Kuliah: ${schedule.courseName}\n" +
            "Hari/Jam: ${dayName(schedule.dayOfWeek)}, " +
            String.format(Locale.getDefault(), "%02d:%02d - %02d:%02d\n", schedule.startHour, schedule.startMinute, schedule.endHour, schedule.endMinute) +
            "Ruang/Mode: ${schedule.room} (${schedule.classMode})\n" +
            "Portal Absen: https://sipt2.ubpkarawang.ac.id/apps"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            setPackage("com.whatsapp")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                },
                "Bagikan pengingat kuliah"
            ))
        }
    }

    private fun makeMiniToggle(label: String, enabled: Boolean, onToggle: () -> Unit): TextView {
        return TextView(this).apply {
            text = if (enabled) "● $label" else "○ $label"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(parseColor(if (enabled) "#2E8B57" else "#5A6577"))
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = gradient(
                if (enabled) "#E6F4EA" else paletteCard(),
                if (enabled) "#2E8B57" else paletteBorder(),
                dp(12).toFloat()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { onToggle() }
            attachSpringPress(this)
        }
    }

    private fun showScheduleEditorDialog(existing: CourseSchedule?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val dayNames = listOf("Senin", "Selasa", "Rabu", "Kamis", "Jum'at")
        val dayCodes = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        val daySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, dayNames)
            setSelection(if (existing == null) 0 else dayCodes.indexOf(existing.dayOfWeek).coerceAtLeast(0))
        }
        val nameInput = EditText(this).apply { hint = "Nama Mata Kuliah"; setText(existing?.courseName ?: "") }
        val roomInput = EditText(this).apply { hint = "Ruangan"; setText(existing?.room ?: "") }
        val modes = listOf("Offline", "Online")
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes)
            setSelection(if (existing?.classMode.equals("Online", true)) 1 else 0)
        }
        var startH = existing?.startHour ?: 7
        var startM = existing?.startMinute ?: 0
        var endH = existing?.endHour ?: 8
        var endM = existing?.endMinute ?: 40
        val startButton = Button(this).apply {
            text = String.format(Locale.getDefault(), "Jam Mulai: %02d:%02d", startH, startM)
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, h, m ->
                    startH = h; startM = m
                    text = String.format(Locale.getDefault(), "Jam Mulai: %02d:%02d", h, m)
                }, startH, startM, true).show()
            }
        }
        val endButton = Button(this).apply {
            text = String.format(Locale.getDefault(), "Jam Selesai: %02d:%02d", endH, endM)
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, h, m ->
                    endH = h; endM = m
                    text = String.format(Locale.getDefault(), "Jam Selesai: %02d:%02d", h, m)
                }, endH, endM, true).show()
            }
        }
        container.addView(daySpinner)
        container.addView(nameInput)
        container.addView(roomInput)
        container.addView(modeSpinner)
        container.addView(startButton)
        container.addView(endButton)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Tambah Jadwal Kuliah" else "Edit Jadwal Kuliah")
            .setView(container)
            .setPositiveButton("Simpan") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Nama mata kuliah wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val schedule = CourseSchedule(
                    existing?.id ?: 0L,
                    dayCodes[daySpinner.selectedItemPosition],
                    startH,
                    startM,
                    endH,
                    endM,
                    name,
                    roomInput.text.toString().trim().ifEmpty { "-" },
                    modes[modeSpinner.selectedItemPosition],
                    existing?.ringEnabled ?: true,
                    existing?.vibrateEnabled ?: true,
                    existing?.notifEnabled ?: true,
                    true
                )
                if (existing == null) db.insertSchedule(schedule) else db.updateSchedule(schedule)
                refreshAll()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showHistoryDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val logs = db.getAllAttendanceLogs()
        if (logs.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Belum ada riwayat kehadiran."
                setTextColor(paletteSubtext())
            })
        } else {
            for (log in logs) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                }
                row.addView(TextView(this).apply {
                    text = "${log.dateStr}\n${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.timestamp))}\n${log.courseName}"
                    textSize = 13f
                    setTextColor(paletteText())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this).apply {
                    text = log.status
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(paletteWhite())
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = gradient(
                        if (log.status == "HADIR") "#2E8B57" else "#DC2626",
                        radius = dp(12).toFloat()
                    )
                })
                container.addView(row)
            }
        }
        scroll.addView(container)
        AlertDialog.Builder(this).setTitle("Riwayat Kehadiran").setView(scroll).setPositiveButton("Tutup", null).show()
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val urlLabel = TextView(this).apply {
            text = "Link Portal Absen"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteText())
        }
        val urlInput = EditText(this).apply {
            setText(db.getSetting("portal_url", "https://sipt2.ubpkarawang.ac.id/apps"))
        }
        val mathSwitch = SwitchCompat(this).apply {
            text = "Tantangan Hitungan Pagi"
            isChecked = db.getSetting("math_dismiss", "0") == "1"
        }
        val darkSwitch = SwitchCompat(this).apply {
            text = "Mode Gelap (Dark Mode)"
            isChecked = db.getSetting("dark_mode", "0") == "1"
        }
        val ringButton = Button(this).apply {
            text = "Pilih Nada Dering (MP3)"
            setOnClickListener { ringtonePicker.launch(arrayOf("audio/*")) }
            attachSpringPress(this)
        }
        val resetRingButton = Button(this).apply {
            text = "Reset Nada Default"
            setOnClickListener {
                db.setSetting("custom_ringtone_uri", "")
                Toast.makeText(this@MainActivity, "Nada default dipulihkan", Toast.LENGTH_SHORT).show()
            }
            attachSpringPress(this)
        }
        val backupButton = Button(this).apply {
            text = "Backup Data (JSON)"
            setOnClickListener { backupPicker.launch("absenguard-backup.json") }
            attachSpringPress(this)
        }
        val restoreButton = Button(this).apply {
            text = "Restore Data (JSON)"
            setOnClickListener { restorePicker.launch(arrayOf("application/json", "*/*")) }
            attachSpringPress(this)
        }
        container.addView(urlLabel)
        container.addView(urlInput)
        container.addView(space(8))
        container.addView(mathSwitch)
        container.addView(darkSwitch)
        container.addView(ringButton)
        container.addView(resetRingButton)
        container.addView(backupButton)
        container.addView(restoreButton)
        AlertDialog.Builder(this)
            .setTitle("Pengaturan AbsenGuard")
            .setView(container)
            .setPositiveButton("Simpan") { _, _ ->
                db.setSetting("portal_url", urlInput.text.toString().trim()
                    .ifEmpty { "https://sipt2.ubpkarawang.ac.id/apps" })
                db.setSetting("math_dismiss", if (mathSwitch.isChecked) "1" else "0")
                db.setSetting("dark_mode", if (darkSwitch.isChecked) "1" else "0")
                applyDarkMode()
                Toast.makeText(this, "Pengaturan disimpan", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun writeBackup(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(db.exportBackupJson().toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "Backup tersimpan", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Backup gagal", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreBackup(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            if (db.importBackupJson(json)) {
                Toast.makeText(this, "Data berhasil dipulihkan", Toast.LENGTH_SHORT).show()
                refreshAll()
            } else {
                Toast.makeText(this, "File backup tidak valid", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Restore gagal", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyDarkMode() {
        if (db.getSetting("dark_mode", "0") == "1") {
            window.statusBarColor = parseColor("#12151C")
            window.navigationBarColor = parseColor("#12151C")
        } else {
            window.statusBarColor = parseColor("#F8F9FA")
            window.navigationBarColor = parseColor("#F8F9FA")
        }
    }

    private fun paletteBg(): String = if (db.getSetting("dark_mode", "0") == "1") "#12151C" else "#F8F9FA"
    private fun paletteCard(): String = if (db.getSetting("dark_mode", "0") == "1") "#1C212C" else "#EEF1F6"
    private fun paletteBorder(): String = if (db.getSetting("dark_mode", "0") == "1") "#2D3545" else "#D5DCE6"
    private fun paletteText(): Int = parseColor(if (db.getSetting("dark_mode", "0") == "1") "#F1F5F9" else "#1E2430")
    private fun paletteSubtext(): Int = parseColor(if (db.getSetting("dark_mode", "0") == "1") "#94A3B8" else "#5A6577")
    private fun paletteWhite(): Int = parseColor("#F8F9FA")

    private fun gradient(color: String, border: String? = null, radius: Float = 0f): GradientDrawable {
        return GradientDrawable().apply {
            setColor(parseColor(color))
            cornerRadius = radius
            border?.let { setStroke(dp(1), parseColor(it)) }
        }
    }

    private fun parseColor(color: String): Int = Color.parseColor(color)

    private fun attachSpringPress(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                }
            }
            false
        }
    }

    private fun startBreathing(view: View) {
        view.animate().scaleX(1.015f).scaleY(1.015f).setDuration(900)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(900)
                    .withEndAction { startBreathing(view) }.start()
            }.start()
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
