package com.absenguard.app

import android.animation.ValueAnimator
import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
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
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var db: ScheduleDbHelper
    private lateinit var auroraView: AuroraMeshView
    private lateinit var rootContainer: FrameLayout
    private lateinit var mainContent: LinearLayout
    private lateinit var permissionCard: LinearLayout
    private lateinit var nextClassCard: FrameLayout
    private lateinit var nextClassTitle: TextView
    private lateinit var nextClassMeta: TextView
    private lateinit var nextClassCountdown: TextView
    private lateinit var nextClassOpenBtn: TextView
    private lateinit var filterScroll: HorizontalScrollView
    private lateinit var filterPillIndicator: View
    private lateinit var filterRow: LinearLayout
    private lateinit var scheduleListContainer: LinearLayout
    private val dayChips = mutableMapOf<Int, TextView>()
    private var selectedDayFilter = 0
    private var currentNextSchedule: CourseSchedule? = null
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
        applySystemBars()

        rootContainer = FrameLayout(this)
        auroraView = AuroraMeshView(this) { isDarkMode() }
        rootContainer.addView(
            auroraView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_ALWAYS
        }
        mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(96))
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
            setTextColor(paletteLightText())
            setPadding(dp(22), dp(13), dp(22), dp(13))
            background = LiquidGlassKit.pillGlassSurface(
                this@MainActivity,
                "#E64338CA",
                "#A6F8FAFC",
                28f
            )
            LiquidGlassKit.applyGlassElevation(this, 10f, isDarkMode())
            setOnClickListener { showScheduleEditorDialog(null) }
            LiquidGlassKit.attachLiquidTouch(this, 0.94f)
        }
        val fabParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(dp(16), dp(16), dp(16), dp(22))
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
        auroraView.startMotion()
        refreshAll()
    }

    override fun onPause() {
        auroraView.stopMotion()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(countdownRunnable)
        super.onDestroy()
    }

    private fun refreshAll() {
        updatePermissionVisibility()
        updateNextClassBanner()
        updateFilterPillSelection(animate = false)
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
        titleCol.addView(TextView(this).apply {
            text = "AbsenGuard"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteText())
        })
        titleCol.addView(TextView(this).apply {
            text = "E-Learning & SIPT UBP • Dynamic Island"
            textSize = 12f
            setTextColor(paletteSubtext())
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttons.addView(headerButton(if (isDarkMode()) "Cerah" else "Gelap") {
            db.setSetting("dark_mode", if (isDarkMode()) "0" else "1")
            recreate()
        })
        buttons.addView(headerButton("Riwayat") { showHistoryDialog() })
        buttons.addView(headerButton("Setelan") { showSettingsDialog() })
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
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = LiquidGlassKit.glassSurface(this@MainActivity, isDarkMode(), 18f)
            LiquidGlassKit.applyGlassElevation(this, 3f, isDarkMode())
            setOnClickListener { action() }
            LiquidGlassKit.attachLiquidTouch(this)
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply { marginStart = dp(6) }
        }
    }

    private fun buildPermissionCard() {
        permissionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = LiquidGlassKit.glassSurface(
                this@MainActivity,
                isDarkMode(),
                22f,
                if (isDarkMode()) "#4D991B1B" else "#D9FEE2E2"
            )
            LiquidGlassKit.applyGlassElevation(this, 4f, isDarkMode())
        }
        permissionCard.addView(TextView(this).apply {
            text = "Izin Alarm, Notifikasi & Dynamic Island Belum Lengkap"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (isDarkMode()) "#FCA5A5" else "#DC2626"))
        })
        permissionCard.addView(space(4))
        permissionCard.addView(TextView(this).apply {
            text = "Aktifkan izin notifikasi, alarm presisi, bebas hemat baterai, dan tampil di atas aplikasi lain."
            textSize = 12f
            setTextColor(paletteText())
        })
        permissionCard.addView(space(10))
        val button = Button(this).apply {
            text = "Izinkan Sekarang"
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteLightText())
            background = LiquidGlassKit.pillGlassSurface(this@MainActivity, "#DC2626", "#99F8FAFC", 18f)
            setOnClickListener { requestMissingPermissions() }
            LiquidGlassKit.attachLiquidTouch(this)
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
        if (db.getSetting("dynamic_island_enabled", "1") == "1" && !Settings.canDrawOverlays(this)) {
            return true
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
        if (db.getSetting("dynamic_island_enabled", "1") == "1" && !Settings.canDrawOverlays(this)) {
            openOverlayPermissionSettings()
            return
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

    private fun openOverlayPermissionSettings() {
        Toast.makeText(this, "Aktifkan 'Tampilkan di atas aplikasi lain' untuk Dynamic Island", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun buildNextClassCard() {
        nextClassCard = FrameLayout(this).apply {
            background = LiquidGlassKit.heroGlassSurface(this@MainActivity, 26f)
            LiquidGlassKit.applyGlassElevation(this, 10f, isDarkMode())
            LiquidGlassKit.attachLiquidTouch(this, 0.98f)
        }

        val shimmer = GlassShimmerOverlay(this)
        nextClassCard.addView(
            shimmer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val topTagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tagPill = TextView(this).apply {
            text = "JADWAL KULIAH TERDEKAT"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteLightText())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = LiquidGlassKit.pillGlassSurface(this@MainActivity, "#33F8FAFC", "#80F8FAFC", 12f)
        }
        topTagRow.addView(tagPill)
        topTagRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        nextClassOpenBtn = TextView(this).apply {
            text = "Buka Kelas →"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteLightText())
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = LiquidGlassKit.pillGlassSurface(this@MainActivity, "#4DF8FAFC", "#B3F8FAFC", 14f)
            setOnClickListener { openCoursePortal(currentNextSchedule) }
            LiquidGlassKit.attachLiquidTouch(this)
        }
        topTagRow.addView(nextClassOpenBtn)

        nextClassTitle = TextView(this).apply {
            text = "Memuat..."
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteLightText())
        }
        nextClassMeta = TextView(this).apply {
            text = "-"
            textSize = 12f
            setTextColor(Color.parseColor("#E0E7FF"))
        }
        nextClassCountdown = TextView(this).apply {
            text = "00:00:00"
            textSize = 25f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteLightText())
        }

        content.addView(topTagRow)
        content.addView(space(8))
        content.addView(nextClassTitle)
        content.addView(space(3))
        content.addView(nextClassMeta)
        content.addView(space(10))
        content.addView(nextClassCountdown)

        nextClassCard.addView(content)
        mainContent.addView(nextClassCard)
        mainContent.addView(space(14))
    }

    private fun updateNextClassBanner() {
        val next = AlarmScheduler.getNextUpcomingSchedule(this)
        currentNextSchedule = next?.first
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
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = LiquidGlassKit.glassSurface(this@MainActivity, isDarkMode(), 24f)
            LiquidGlassKit.applyGlassElevation(this, 6f, isDarkMode())
        }
        card.addView(TextView(this).apply {
            text = "Mode Peringatan Global"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteText())
        })
        card.addView(space(8))
        card.addView(makeSwitchRow("Dynamic Island Kamera (Punch-Hole)", "dynamic_island_enabled", true) { enabled ->
            if (enabled) {
                if (!Settings.canDrawOverlays(this)) {
                    openOverlayPermissionSettings()
                } else {
                    DynamicIslandService.show(this, AlarmScheduler.TEST_SCHEDULE_ID, isPreReminder = false, isTest = true)
                }
            } else {
                DynamicIslandService.dismiss(this)
            }
            updatePermissionVisibility()
        })
        card.addView(makeSwitchRow("Mode Dering Suara", "global_ring", true))
        card.addView(makeSwitchRow("Mode Getar HP", "global_vibrate", true))
        card.addView(makeSwitchRow("Mode Notifikasi Pop-up", "global_notif", true))
        card.addView(makeSwitchRow("Pengingat Persiapan H-10 Menit", "pre_reminder_10m", true))
        card.addView(space(12))

        val rowTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val testAlarm = Button(this).apply {
            text = "Tes Alarm"
            isAllCaps = false
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteLightText())
            background = LiquidGlassKit.pillGlassSurface(this@MainActivity, "#4F46E5", "#99F8FAFC", 16f)
            setOnClickListener {
                AlarmScheduler.triggerInstantTest(this@MainActivity)
                Toast.makeText(this@MainActivity, "Tes alarm aktif 3 detik", Toast.LENGTH_SHORT).show()
            }
            LiquidGlassKit.attachLiquidTouch(this)
        }
        val testIsland = Button(this).apply {
            text = "Tes Island"
            isAllCaps = false
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteLightText())
            background = LiquidGlassKit.pillGlassSurface(this@MainActivity, "#0F172A", "#99F8FAFC", 16f)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    openOverlayPermissionSettings()
                } else {
                    DynamicIslandService.show(
                        this@MainActivity,
                        AlarmScheduler.TEST_SCHEDULE_ID,
                        isPreReminder = false,
                        isTest = true
                    )
                    Toast.makeText(this@MainActivity, "Ketuk kapsul kamera untuk memperluas, geser atas untuk tutup", Toast.LENGTH_SHORT).show()
                }
            }
            LiquidGlassKit.attachLiquidTouch(this)
        }
        val silent = Button(this).apply {
            text = getSilentButtonLabel()
            isAllCaps = false
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteLightText())
            background = LiquidGlassKit.pillGlassSurface(this@MainActivity, "#D97706", "#99F8FAFC", 16f)
            setOnClickListener { toggleSilentMode(this) }
            LiquidGlassKit.attachLiquidTouch(this)
        }
        val p1 = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) }
        val p2 = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
        val p3 = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4) }
        rowTools.addView(testAlarm, p1)
        rowTools.addView(testIsland, p2)
        rowTools.addView(silent, p3)
        card.addView(rowTools)

        card.addView(space(8))

        val rowPortals = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val viaSiptBtn = Button(this).apply {
            text = "Via SIPT"
            isAllCaps = false
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteLightText())
            background = LiquidGlassKit.pillGlassSurface(this@MainActivity, "#0284C7", "#99F8FAFC", 16f)
            setOnClickListener { openSiptPortal() }
            LiquidGlassKit.attachLiquidTouch(this)
        }
        val directElearningBtn = Button(this).apply {
            text = "E-Learning"
            isAllCaps = false
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteLightText())
            background = LiquidGlassKit.pillGlassSurface(this@MainActivity, "#2E8B57", "#99F8FAFC", 16f)
            setOnClickListener { openDirectElearning() }
            LiquidGlassKit.attachLiquidTouch(this)
        }
        val pSipt = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) }
        val pElearn = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) }
        rowPortals.addView(viaSiptBtn, pSipt)
        rowPortals.addView(directElearningBtn, pElearn)
        card.addView(rowPortals)

        mainContent.addView(card)
        mainContent.addView(space(14))
    }

    private fun openSiptPortal() {
        val siptUrl = db.getSetting("sipt_url", "https://sipt2.ubpkarawang.ac.id/apps")
            .ifBlank { "https://sipt2.ubpkarawang.ac.id/apps" }
        val intent = Intent(this, SiptPortalActivity::class.java).apply {
            putExtra(SiptPortalActivity.EXTRA_COURSE_URL, siptUrl)
            putExtra(SiptPortalActivity.EXTRA_COURSE_NAME, "Portal SIPT UBP")
            putExtra("extra_portal_mode", "SIPT")
            putExtra("extra_portal_password", "ubpk2024")
        }
        startActivity(intent)
    }

    private fun openDirectElearning() {
        val elearningUrl = db.getSetting("portal_url", "https://elearning.ubpkarawang.ac.id/login/index.php")
            .ifBlank { "https://elearning.ubpkarawang.ac.id/login/index.php" }
        val intent = Intent(this, SiptPortalActivity::class.java).apply {
            putExtra(SiptPortalActivity.EXTRA_COURSE_URL, elearningUrl)
            putExtra(SiptPortalActivity.EXTRA_COURSE_NAME, "E-Learning UBP")
            putExtra("extra_portal_mode", "ELEARNING")
            putExtra("extra_portal_password", "Buana0000!")
        }
        startActivity(intent)
    }

    private fun openCoursePortal(schedule: CourseSchedule?) {
        val intent = Intent(this, SiptPortalActivity::class.java)
        if (schedule != null) {
            val isSiptUrl = schedule.courseUrl.contains("sipt", ignoreCase = true)
            intent.putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, schedule.id)
            intent.putExtra(SiptPortalActivity.EXTRA_COURSE_URL, schedule.courseUrl)
            intent.putExtra(SiptPortalActivity.EXTRA_COURSE_NAME, schedule.courseName)
            intent.putExtra("extra_portal_mode", if (isSiptUrl) "SIPT" else "ELEARNING")
            intent.putExtra("extra_portal_password", if (isSiptUrl) "ubpk2024" else "Buana0000!")
        } else {
            val elearningUrl = db.getSetting("portal_url", "https://elearning.ubpkarawang.ac.id/login/index.php")
            intent.putExtra(SiptPortalActivity.EXTRA_COURSE_URL, elearningUrl)
            intent.putExtra(SiptPortalActivity.EXTRA_COURSE_NAME, "E-Learning UBP")
            intent.putExtra("extra_portal_mode", "ELEARNING")
            intent.putExtra("extra_portal_password", "Buana0000!")
        }
        startActivity(intent)
    }

    private fun getSilentButtonLabel(): String {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return if (nm.isNotificationPolicyAccessGranted && am.ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            "Hening ✓"
        } else {
            "Hening"
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

    private fun makeSwitchRow(
        label: String,
        settingKey: String,
        defaultOn: Boolean,
        onExtraChange: ((Boolean) -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val text = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(paletteText())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val initial = db.getSetting(settingKey, if (defaultOn) "1" else "0") == "1"
        val toggle = LiquidToggleSwitch(this, initial, isDarkMode()) { checked ->
            db.setSetting(settingKey, if (checked) "1" else "0")
            AlarmScheduler.scheduleAll(this@MainActivity)
            onExtraChange?.invoke(checked)
        }
        row.addView(text)
        row.addView(toggle)
        return row
    }

    private fun buildFilterChips() {
        filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            background = LiquidGlassKit.glassSurface(this@MainActivity, isDarkMode(), 24f, strongRim = false)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            LiquidGlassKit.applyGlassElevation(this, 4f, isDarkMode())
        }

        val trackFrame = FrameLayout(this)

        filterPillIndicator = View(this).apply {
            background = LiquidGlassKit.pillGlassSurface(
                this@MainActivity,
                "#4F46E5",
                "#CCF8FAFC",
                20f
            )
            LiquidGlassKit.applyGlassElevation(this, 4f, isDarkMode())
        }
        trackFrame.addView(
            filterPillIndicator,
            FrameLayout.LayoutParams(dp(72), FrameLayout.LayoutParams.MATCH_PARENT)
        )

        filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val items = listOf(
            0 to "Semua",
            Calendar.MONDAY to "Senin",
            Calendar.TUESDAY to "Selasa",
            Calendar.WEDNESDAY to "Rabu",
            Calendar.THURSDAY to "Kamis",
            Calendar.FRIDAY to "Jum'at"
        )
        dayChips.clear()
        for ((day, label) in items) {
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (selectedDayFilter == day) paletteLightText() else paletteText())
                setPadding(dp(16), dp(9), dp(16), dp(9))
                gravity = Gravity.CENTER
                setOnClickListener {
                    if (selectedDayFilter != day) {
                        selectedDayFilter = day
                        updateFilterPillSelection(animate = true)
                        renderScheduleCards()
                    }
                }
                LiquidGlassKit.attachLiquidTouch(this, 0.94f)
            }
            dayChips[day] = chip
            filterRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(4) }
            )
        }

        trackFrame.addView(filterRow)
        filterScroll.addView(trackFrame)
        mainContent.addView(filterScroll)
        mainContent.addView(space(14))

        filterRow.post { updateFilterPillSelection(animate = false) }
    }

    private fun updateFilterPillSelection(animate: Boolean) {
        for ((day, chip) in dayChips) {
            chip.setTextColor(if (day == selectedDayFilter) paletteLightText() else paletteText())
        }
        val activeChip = dayChips[selectedDayFilter] ?: return
        activeChip.post {
            val targetX = activeChip.left.toFloat()
            val targetW = activeChip.width
            val targetH = activeChip.height
            if (targetW <= 0 || targetH <= 0) return@post

            val lp = filterPillIndicator.layoutParams as FrameLayout.LayoutParams
            lp.height = targetH

            if (!animate) {
                lp.width = targetW
                filterPillIndicator.layoutParams = lp
                filterPillIndicator.translationX = targetX
            } else {
                val startW = filterPillIndicator.width.takeIf { it > 0 } ?: targetW
                filterPillIndicator.animate()
                    .translationX(targetX)
                    .setDuration(290L)
                    .setInterpolator(OvershootInterpolator(1.15f))
                    .start()

                ValueAnimator.ofInt(startW, targetW).apply {
                    duration = 260L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { anim ->
                        val params = filterPillIndicator.layoutParams as FrameLayout.LayoutParams
                        params.width = anim.animatedValue as Int
                        params.height = targetH
                        filterPillIndicator.layoutParams = params
                    }
                    start()
                }
            }

            val scrollTarget = (activeChip.left - (filterScroll.width - activeChip.width) / 2).coerceAtLeast(0)
            filterScroll.smoothScrollTo(scrollTarget, 0)
        }
    }

    private fun renderScheduleCards() {
        scheduleListContainer.removeAllViews()
        val schedules = if (selectedDayFilter == 0) db.getAllSchedules() else db.getSchedulesForDay(selectedDayFilter)
        if (schedules.isEmpty()) {
            val emptyBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(28), dp(20), dp(28))
                background = LiquidGlassKit.glassSurface(this@MainActivity, isDarkMode(), 22f)
            }
            emptyBox.addView(TextView(this).apply {
                text = "Tidak ada jadwal kuliah pada hari ini."
                textSize = 14f
                setTextColor(paletteSubtext())
                gravity = Gravity.CENTER
            })
            scheduleListContainer.addView(emptyBox)
            return
        }

        schedules.forEachIndexed { index, schedule ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(15), dp(16), dp(15))
                background = LiquidGlassKit.glassSurface(this@MainActivity, isDarkMode(), 24f)
                LiquidGlassKit.applyGlassElevation(this, 6f, isDarkMode())
                alpha = 0f
                translationY = dp(22).toFloat()
                scaleX = 0.97f
                scaleY = 0.97f
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(260L)
                    .setStartDelay(index * 45L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                LiquidGlassKit.attachLiquidTouch(this, 0.985f)
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
                setTextColor(Color.parseColor(if (isDarkMode()) "#93C5FD" else "#4338CA"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            val online = schedule.classMode.equals("Online", ignoreCase = true)
            top.addView(TextView(this).apply {
                text = if (online) "Online" else "Offline"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(paletteLightText())
                setPadding(dp(11), dp(4), dp(11), dp(4))
                background = LiquidGlassKit.pillGlassSurface(
                    this@MainActivity,
                    if (online) "#D97706" else "#4F46E5",
                    "#80F8FAFC",
                    12f
                )
                setOnClickListener {
                    db.updateSchedule(schedule.copy(classMode = if (online) "Offline" else "Online"))
                    refreshAll()
                }
                LiquidGlassKit.attachLiquidTouch(this)
            })
            card.addView(top)
            card.addView(space(6))

            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(this).apply {
                text = schedule.courseName
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(paletteText())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (schedule.courseUrl.isNotBlank()) {
                val isDirectAbsen = schedule.courseUrl.contains("/mod/attendance/")
                titleRow.addView(TextView(this).apply {
                    text = if (isDirectAbsen) "Direct Absen" else "Link Kelas"
                    textSize = 10f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(if (isDarkMode()) "#6EE7B7" else "#2E8B57"))
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    background = LiquidGlassKit.pillGlassSurface(
                        this@MainActivity,
                        if (isDarkMode()) "#2610B981" else "#242E8B57",
                        if (isDarkMode()) "#666EE7B7" else "#662E8B57",
                        10f
                    )
                })
            }
            card.addView(titleRow)
            card.addView(space(6))

            val stats = db.getAttendanceStats(schedule.id)
            val percent = if (stats.second > 0) (stats.first * 100) / stats.second else 100
            val healthy = !(percent < 80 && stats.second > 0)
            val accentHex = if (healthy) "#2E8B57" else "#DC2626"

            val statsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            statsRow.addView(TextView(this).apply {
                text = "Kehadiran: ${stats.first}/${stats.second} ($percent%)"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (isDarkMode() && healthy) "#6EE7B7" else accentHex))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            val barTrack = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (isDarkMode()) "#334155" else "#CBD5E1"))
                    cornerRadius = dp(6).toFloat()
                }
            }
            val barFill = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(accentHex))
                    cornerRadius = dp(6).toFloat()
                }
            }
            val fillWidth = (dp(76) * (percent.coerceIn(0, 100) / 100f)).toInt().coerceAtLeast(dp(6))
            barTrack.addView(barFill, FrameLayout.LayoutParams(fillWidth, dp(6)))
            statsRow.addView(barTrack, LinearLayout.LayoutParams(dp(76), dp(6)))
            card.addView(statsRow)
            card.addView(space(10))

            val toggleScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
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
            toggleScroll.addView(toggleRow)
            card.addView(toggleScroll)
            card.addView(space(10))

            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val openClass = Button(this).apply {
                text = "Buka Kelas"
                isAllCaps = false
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(paletteLightText())
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = LiquidGlassKit.pillGlassSurface(this@MainActivity, "#4F46E5", "#99F8FAFC", 16f)
                setOnClickListener { openCoursePortal(schedule) }
                LiquidGlassKit.attachLiquidTouch(this)
            }
            val attended = db.isAttendedToday(schedule.id)
            val checkIn = Button(this).apply {
                text = if (attended) "Hadir ✓" else "Absen"
                isAllCaps = false
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (attended) Color.parseColor(if (isDarkMode()) "#6EE7B7" else "#2E8B57") else paletteLightText())
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = if (attended) {
                    LiquidGlassKit.pillGlassSurface(
                        this@MainActivity,
                        if (isDarkMode()) "#3310B981" else "#292E8B57",
                        "#802E8B57",
                        16f
                    )
                } else {
                    LiquidGlassKit.pillGlassSurface(this@MainActivity, "#2E8B57", "#99F8FAFC", 16f)
                }
                setOnClickListener {
                    db.recordAttendance(schedule.id, schedule.courseName, "HADIR")
                    Toast.makeText(this@MainActivity, "Hadir tercatat", Toast.LENGTH_SHORT).show()
                    refreshAll()
                }
                LiquidGlassKit.attachLiquidTouch(this)
            }
            val share = Button(this).apply {
                text = "WA"
                isAllCaps = false
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(paletteLightText())
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = LiquidGlassKit.pillGlassSurface(this@MainActivity, "#16A34A", "#99F8FAFC", 16f)
                setOnClickListener { showWhatsAppTemplateDialog(schedule) }
                LiquidGlassKit.attachLiquidTouch(this)
            }
            val btnEdit = TextView(this).apply {
                text = "Edit"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (isDarkMode()) "#93C5FD" else "#4338CA"))
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { showScheduleEditorDialog(schedule) }
                LiquidGlassKit.attachLiquidTouch(this)
            }
            val btnDelete = TextView(this).apply {
                text = "Hapus"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (isDarkMode()) "#FCA5A5" else "#DC2626"))
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Hapus Jadwal")
                        .setMessage("Hapus ${schedule.courseName}?")
                        .setPositiveButton("Hapus") { _, _ ->
                            db.deleteSchedule(schedule.id)
                            refreshAll()
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }
                LiquidGlassKit.attachLiquidTouch(this)
            }

            actionRow.addView(openClass, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(35)))
            actionRow.addView(checkIn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(35)).apply { marginStart = dp(6) })
            actionRow.addView(share, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(35)).apply { marginStart = dp(6) })
            actionRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            actionRow.addView(btnEdit)
            actionRow.addView(btnDelete)

            card.addView(actionRow)
            scheduleListContainer.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(13) })
        }
    }

    private fun showWhatsAppTemplateDialog(schedule: CourseSchedule) {
        val targetLink = schedule.courseUrl.ifBlank { "https://elearning.ubpkarawang.ac.id" }
        val day = dayName(schedule.dayOfWeek)
        val timeRange = String.format(
            Locale.getDefault(),
            "%02d:%02d - %02d:%02d",
            schedule.startHour,
            schedule.startMinute,
            schedule.endHour,
            schedule.endMinute
        )
        val startTime = String.format(Locale.getDefault(), "%02d:%02d", schedule.startHour, schedule.startMinute)

        val options = arrayOf(
            "Gaya Santai (Grup Kelas)",
            "Gaya Formal (Dosen / Komting)",
            "Ringkas Satu Baris (Cepat)",
            "Pengumuman Pindah Mode (${schedule.classMode})"
        )

        AlertDialog.Builder(this)
            .setTitle("Pilih Format Pesan WhatsApp")
            .setItems(options) { _, which ->
                val message = when (which) {
                    0 -> "Halo guys, info kuliah bentar lagi ya:\n" +
                        "*${schedule.courseName}*\n" +
                        "Hari/Jam: $day, $timeRange\n" +
                        "Ruang: ${schedule.room} (${schedule.classMode})\n" +
                        "Link E-Learning: $targetLink\n" +
                        "Jangan lupa siapin absennya ya!"
                    1 -> "Assalamu'alaikum Wr. Wb. / Selamat pagi.\n" +
                        "Izin menginformasikan jadwal perkuliahan:\n" +
                        "• Mata Kuliah: ${schedule.courseName}\n" +
                        "• Waktu: $day, $timeRange\n" +
                        "• Tempat/Metode: Ruang ${schedule.room} (${schedule.classMode})\n" +
                        "• Tautan E-Learning: $targetLink\n" +
                        "Terima kasih."
                    2 -> "[$day $startTime] ${schedule.courseName} — Ruang ${schedule.room} (${schedule.classMode}) | $targetLink"
                    else -> "*UPDATE PERKULIAHAN*\n" +
                        "Mata Kuliah *${schedule.courseName}* ($day, $timeRange) dilaksanakan secara *${schedule.classMode.uppercase(Locale.getDefault())}* (Ruang ${schedule.room}).\n" +
                        "Akses kelas & presensi: $targetLink"
                }
                sendTextToWhatsApp(message)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun sendTextToWhatsApp(message: String) {
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
                "Bagikan pesan perkuliahan"
            ))
        }
    }

    private fun makeMiniToggle(label: String, enabled: Boolean, onToggle: () -> Unit): TextView {
        return TextView(this).apply {
            text = if (enabled) "● $label" else "○ $label"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(
                if (enabled) Color.parseColor(if (isDarkMode()) "#6EE7B7" else "#2E8B57")
                else paletteSubtext()
            )
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = if (enabled) {
                LiquidGlassKit.pillGlassSurface(
                    this@MainActivity,
                    if (isDarkMode()) "#3310B981" else "#262E8B57",
                    if (isDarkMode()) "#806EE7B7" else "#802E8B57",
                    13f
                )
            } else {
                LiquidGlassKit.glassSurface(this@MainActivity, isDarkMode(), 13f, strongRim = false)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { onToggle() }
            LiquidGlassKit.attachLiquidTouch(this)
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
        val urlInput = EditText(this).apply { hint = "URL E-Learning Matkul"; setText(existing?.courseUrl ?: "") }
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
        container.addView(urlInput)
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
                    true,
                    urlInput.text.toString().trim()
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
                    text = "${log.dateStr} • ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.timestamp))}\n${log.courseName}"
                    textSize = 13f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(paletteText())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this).apply {
                    text = log.status
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(paletteLightText())
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = LiquidGlassKit.pillGlassSurface(
                        this@MainActivity,
                        if (log.status == "HADIR") "#2E8B57" else "#DC2626",
                        "#80F8FAFC",
                        12f
                    )
                })
                container.addView(row)
            }
        }
        scroll.addView(container)
        AlertDialog.Builder(this)
            .setTitle("Riwayat Kehadiran")
            .setView(scroll)
            .setNeutralButton("Ekspor PDF") { _, _ -> exportAttendancePdf() }
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun exportAttendancePdf() {
        try {
            val schedules = db.getAllSchedules()
            val logs = db.getAllAttendanceLogs()

            val pdf = PdfDocument()
            val pageWidth = 595
            val pageHeight = 842
            var pageNumber = 1
            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            var page = pdf.startPage(pageInfo)
            var canvas = page.canvas

            val bannerPaint = Paint().apply { color = Color.parseColor("#312E81") }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val subBannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E0E7FF")
                textSize = 10.5f
            }
            val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = 12.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val headerBgPaint = Paint().apply { color = Color.parseColor("#E2E8F0") }
            val zebraPaint = Paint().apply { color = Color.parseColor("#F8FAFC") }
            val linePaint = Paint().apply {
                color = Color.parseColor("#CBD5E1")
                strokeWidth = 1f
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1E293B")
                textSize = 10f
            }
            val boldTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = 10f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val safePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#15803D")
                textSize = 9.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#B91C1C")
                textSize = 9.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            canvas.drawRoundRect(RectF(32f, 32f, (pageWidth - 32).toFloat(), 112f), 14f, 14f, bannerPaint)
            canvas.drawText("REKAPITULASI KEHADIRAN PERKULIAHAN", 48f, 64f, titlePaint)
            val datePrinted = SimpleDateFormat("dd MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
            canvas.drawText("AbsenGuard • Universitas Buana Perjuangan Karawang • Dicetak: $datePrinted", 48f, 88f, subBannerPaint)

            var totalHadir = 0
            var totalSesi = 0
            for (s in schedules) {
                val st = db.getAttendanceStats(s.id)
                totalHadir += st.first
                totalSesi += st.second
            }
            val avgPercent = if (totalSesi > 0) (totalHadir * 100) / totalSesi else 100

            var y = 138f
            canvas.drawText("1. RINGKASAN PERSENTASE PER MATA KULIAH", 36f, y, sectionPaint)
            y += 14f
            canvas.drawRect(36f, y, (pageWidth - 36).toFloat(), y + 22f, headerBgPaint)
            canvas.drawText("No", 42f, y + 15f, boldTextPaint)
            canvas.drawText("Mata Kuliah", 66f, y + 15f, boldTextPaint)
            canvas.drawText("Jadwal", 295f, y + 15f, boldTextPaint)
            canvas.drawText("Hadir", 415f, y + 15f, boldTextPaint)
            canvas.drawText("Status", 475f, y + 15f, boldTextPaint)
            y += 22f

            schedules.forEachIndexed { idx, s ->
                if (y > pageHeight - 70) {
                    pdf.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    page = pdf.startPage(pageInfo)
                    canvas = page.canvas
                    y = 48f
                }
                if (idx % 2 == 1) {
                    canvas.drawRect(36f, y, (pageWidth - 36).toFloat(), y + 22f, zebraPaint)
                }
                val st = db.getAttendanceStats(s.id)
                val pct = if (st.second > 0) (st.first * 100) / st.second else 100
                val healthy = !(pct < 80 && st.second > 0)
                val shortCourse = if (s.courseName.length > 36) s.courseName.take(33) + "..." else s.courseName
                val scheduleStr = String.format(
                    Locale.getDefault(),
                    "%s %02d:%02d (%s)",
                    dayName(s.dayOfWeek).take(3),
                    s.startHour,
                    s.startMinute,
                    s.room
                )

                canvas.drawText("${idx + 1}", 44f, y + 15f, textPaint)
                canvas.drawText(shortCourse, 66f, y + 15f, textPaint)
                canvas.drawText(scheduleStr, 295f, y + 15f, textPaint)
                canvas.drawText("${st.first}/${st.second} ($pct%)", 415f, y + 15f, boldTextPaint)
                canvas.drawText(
                    if (healthy) "AMAN" else "PERHATIAN",
                    475f,
                    y + 15f,
                    if (healthy) safePaint else warnPaint
                )
                canvas.drawLine(36f, y + 22f, (pageWidth - 36).toFloat(), y + 22f, linePaint)
                y += 22f
            }

            y += 16f
            canvas.drawText(
                "Total Kehadiran Keseluruhan: $totalHadir dari $totalSesi pertemuan ($avgPercent%)",
                36f,
                y,
                boldTextPaint
            )

            y += 28f
            if (y > pageHeight - 90) {
                pdf.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdf.startPage(pageInfo)
                canvas = page.canvas
                y = 48f
            }
            canvas.drawText("2. RIWAYAT LOG PRESENSI (${logs.size} CATATAN)", 36f, y, sectionPaint)
            y += 14f
            canvas.drawRect(36f, y, (pageWidth - 36).toFloat(), y + 22f, headerBgPaint)
            canvas.drawText("Tanggal & Jam", 42f, y + 15f, boldTextPaint)
            canvas.drawText("Mata Kuliah", 165f, y + 15f, boldTextPaint)
            canvas.drawText("Keterangan", 475f, y + 15f, boldTextPaint)
            y += 22f

            if (logs.isEmpty()) {
                canvas.drawText("Belum ada catatan presensi terekam.", 42f, y + 16f, textPaint)
            } else {
                logs.forEachIndexed { idx, log ->
                    if (y > pageHeight - 60) {
                        pdf.finishPage(page)
                        pageNumber++
                        pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                        page = pdf.startPage(pageInfo)
                        canvas = page.canvas
                        y = 48f
                    }
                    if (idx % 2 == 1) {
                        canvas.drawRect(36f, y, (pageWidth - 36).toFloat(), y + 20f, zebraPaint)
                    }
                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.timestamp))
                    val cName = if (log.courseName.length > 46) log.courseName.take(43) + "..." else log.courseName
                    canvas.drawText("${log.dateStr} • $timeStr", 42f, y + 14f, textPaint)
                    canvas.drawText(cName, 165f, y + 14f, textPaint)
                    canvas.drawText(
                        log.status,
                        475f,
                        y + 14f,
                        if (log.status == "HADIR") safePaint else warnPaint
                    )
                    canvas.drawLine(36f, y + 20f, (pageWidth - 36).toFloat(), y + 20f, linePaint)
                    y += 20f
                }
            }

            pdf.finishPage(page)

            val pdfDir = File(cacheDir, "pdfs").apply { if (!exists()) mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val outFile = File(pdfDir, "Rekap_Kehadiran_AbsenGuard_$stamp.pdf")
            FileOutputStream(outFile).use { pdf.writeTo(it) }
            pdf.close()

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Rekap Kehadiran AbsenGuard")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Simpan atau Kirim Rekap PDF"))
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuat PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSettingsDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val urlLabel = TextView(this).apply {
            text = "URL Login Utama E-Learning"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteText())
        }
        val urlInput = EditText(this).apply {
            setText(db.getSetting("portal_url", "https://elearning.ubpkarawang.ac.id/login/index.php"))
        }

        val siptUrlLabel = TextView(this).apply {
            text = "URL Portal SIPT (Cadangan / SSO)"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteText())
        }
        val siptUrlInput = EditText(this).apply {
            setText(db.getSetting("sipt_url", "https://sipt2.ubpkarawang.ac.id/apps"))
        }

        val currentOffsetY = (db.getSetting("island_offset_y", "0").toIntOrNull() ?: 0).coerceIn(-15, 40)
        val offsetYLabel = TextView(this).apply {
            text = "Posisi Kamera (Atas / Bawah): ${currentOffsetY}dp"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteText())
        }
        val offsetYSeek = SeekBar(this).apply {
            max = 55
            progress = currentOffsetY + 15
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val dpVal = value - 15
                    offsetYLabel.text = "Posisi Kamera (Atas / Bawah): ${dpVal}dp"
                    db.setSetting("island_offset_y", dpVal.toString())
                    if (fromUser && Settings.canDrawOverlays(this@MainActivity)) {
                        DynamicIslandService.show(this@MainActivity, AlarmScheduler.TEST_SCHEDULE_ID, isPreReminder = false, isTest = true)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val currentWidthExtra = (db.getSetting("island_width_extra", "0").toIntOrNull() ?: 0).coerceIn(-60, 60)
        val widthExtraLabel = TextView(this).apply {
            text = "Panjang Kapsul (Kiri / Kanan): ${currentWidthExtra}dp"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteText())
        }
        val widthExtraSeek = SeekBar(this).apply {
            max = 120
            progress = currentWidthExtra + 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val dpVal = value - 60
                    widthExtraLabel.text = "Panjang Kapsul (Kiri / Kanan): ${dpVal}dp"
                    db.setSetting("island_width_extra", dpVal.toString())
                    if (fromUser && Settings.canDrawOverlays(this@MainActivity)) {
                        DynamicIslandService.show(this@MainActivity, AlarmScheduler.TEST_SCHEDULE_ID, isPreReminder = false, isTest = true)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val currentHeightExtra = (db.getSetting("island_height_extra", "0").toIntOrNull() ?: 0).coerceIn(-10, 24)
        val heightExtraLabel = TextView(this).apply {
            text = "Tinggi / Tebal Kapsul (Lebar): ${currentHeightExtra}dp"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(paletteText())
        }
        val heightExtraSeek = SeekBar(this).apply {
            max = 34
            progress = currentHeightExtra + 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val dpVal = value - 10
                    heightExtraLabel.text = "Tinggi / Tebal Kapsul (Lebar): ${dpVal}dp"
                    db.setSetting("island_height_extra", dpVal.toString())
                    if (fromUser && Settings.canDrawOverlays(this@MainActivity)) {
                        DynamicIslandService.show(this@MainActivity, AlarmScheduler.TEST_SCHEDULE_ID, isPreReminder = false, isTest = true)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val mathSwitch = SwitchCompat(this).apply {
            text = "Tantangan Hitungan Pagi"
            isChecked = db.getSetting("math_dismiss", "0") == "1"
        }
        val darkSwitch = SwitchCompat(this).apply {
            text = "Mode Gelap (Dark Obsidian Glass)"
            isChecked = isDarkMode()
        }
        val ringButton = Button(this).apply {
            text = "Pilih Nada Dering (MP3)"
            setOnClickListener { ringtonePicker.launch(arrayOf("audio/*")) }
        }
        val resetRingButton = Button(this).apply {
            text = "Reset Nada Default"
            setOnClickListener {
                db.setSetting("custom_ringtone_uri", "")
                Toast.makeText(this@MainActivity, "Nada default dipulihkan", Toast.LENGTH_SHORT).show()
            }
        }
        val backupButton = Button(this).apply {
            text = "Backup Data (JSON)"
            setOnClickListener { backupPicker.launch("absenguard-backup.json") }
        }
        val restoreButton = Button(this).apply {
            text = "Restore Data (JSON)"
            setOnClickListener { restorePicker.launch(arrayOf("application/json", "*/*")) }
        }
        container.addView(urlLabel)
        container.addView(urlInput)
        container.addView(space(8))
        container.addView(siptUrlLabel)
        container.addView(siptUrlInput)
        container.addView(space(10))
        container.addView(offsetYLabel)
        container.addView(offsetYSeek)
        container.addView(space(6))
        container.addView(widthExtraLabel)
        container.addView(widthExtraSeek)
        container.addView(space(6))
        container.addView(heightExtraLabel)
        container.addView(heightExtraSeek)
        container.addView(space(8))
        container.addView(mathSwitch)
        container.addView(darkSwitch)
        container.addView(ringButton)
        container.addView(resetRingButton)
        container.addView(backupButton)
        container.addView(restoreButton)
        scroll.addView(container)

        AlertDialog.Builder(this)
            .setTitle("Pengaturan AbsenGuard")
            .setView(scroll)
            .setPositiveButton("Simpan") { _, _ ->
                db.setSetting("portal_url", urlInput.text.toString().trim()
                    .ifEmpty { "https://elearning.ubpkarawang.ac.id/login/index.php" })
                db.setSetting("sipt_url", siptUrlInput.text.toString().trim()
                    .ifEmpty { "https://sipt2.ubpkarawang.ac.id/apps" })
                db.setSetting("math_dismiss", if (mathSwitch.isChecked) "1" else "0")
                db.setSetting("dark_mode", if (darkSwitch.isChecked) "1" else "0")
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

    private fun isDarkMode(): Boolean = db.getSetting("dark_mode", "0") == "1"

    private fun applySystemBars() {
        val dark = isDarkMode()
        window.statusBarColor = Color.parseColor(if (dark) "#080C16" else "#E8EEFF")
        window.navigationBarColor = Color.parseColor(if (dark) "#0B1324" else "#E6F7FA")
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun paletteText(): Int = Color.parseColor(if (isDarkMode()) "#F1F5F9" else "#0F172A")
    private fun paletteSubtext(): Int = Color.parseColor(if (isDarkMode()) "#94A3B8" else "#475569")
    private fun paletteLightText(): Int = Color.parseColor("#F8FAFC")

    private fun startBreathing(view: View) {
        view.animate().scaleX(1.012f).scaleY(1.012f).setDuration(1400L)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(1400L)
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
