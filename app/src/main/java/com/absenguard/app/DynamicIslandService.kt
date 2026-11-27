package com.absenguard.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class DynamicIslandService : Service() {

    companion object {
        private const val ACTION_SHOW = "com.absenguard.app.ACTION_SHOW_ISLAND"
        private const val ACTION_DISMISS = "com.absenguard.app.ACTION_DISMISS_ISLAND"
        private const val EXTRA_PRE_REMINDER = "extra_pre_reminder"
        private const val EXTRA_IS_TEST = "extra_is_test"

        fun show(
            context: Context,
            scheduleId: Long,
            isPreReminder: Boolean = false,
            isTest: Boolean = false
        ) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, DynamicIslandService::class.java).apply {
                action = ACTION_SHOW
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
                putExtra(EXTRA_PRE_REMINDER, isPreReminder)
                putExtra(EXTRA_IS_TEST, isTest)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun dismiss(context: Context) {
            val intent = Intent(context, DynamicIslandService::class.java).apply {
                action = ACTION_DISMISS
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var db: ScheduleDbHelper
    private var rootFrame: FrameLayout? = null
    private var capsuleContainer: FrameLayout? = null
    private var collapsedRow: LinearLayout? = null
    private var expandedCol: LinearLayout? = null
    private var statusDot: View? = null
    private var leftLabelView: TextView? = null
    private var rightBadgeView: TextView? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private var isExpanded = false
    private var currentSchedule: CourseSchedule? = null
    private var isPreReminderMode = false
    private var isTestMode = false

    private val handler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable {
        if (isExpanded && !isTestMode) {
            toggleExpand(false)
        }
    }
    private val autoDismissRunnable = Runnable {
        if (!isTestMode) {
            animateOutAndStop()
        }
    }
    private val clockTickRunnable = object : Runnable {
        override fun run() {
            updateCollapsedClockText()
            handler.postDelayed(this, 15000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        db = ScheduleDbHelper(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            animateOutAndStop()
            return START_NOT_STICKY
        }

        if (db.getSetting("dynamic_island_enabled", "1") != "1" || !Settings.canDrawOverlays(this)) {
            removeOverlayImmediate()
            stopSelf()
            return START_NOT_STICKY
        }

        val scheduleId = intent?.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, AlarmScheduler.TEST_SCHEDULE_ID)
            ?: AlarmScheduler.TEST_SCHEDULE_ID
        val preReminder = intent?.getBooleanExtra(EXTRA_PRE_REMINDER, false) ?: false
        val isTest = intent?.getBooleanExtra(EXTRA_IS_TEST, false) ?: false

        val schedule = resolveSchedule(scheduleId)
        val sameLivePreview = rootFrame != null && isTest && isTestMode && !isExpanded

        currentSchedule = schedule
        isPreReminderMode = preReminder
        isTestMode = isTest

        if (sameLivePreview) {
            applyLiveGeometry()
        } else {
            showOrRebuildOverlay()
        }

        handler.removeCallbacks(autoDismissRunnable)
        if (!isTestMode) {
            handler.postDelayed(autoDismissRunnable, 180_000L)
        }

        return START_NOT_STICKY
    }

    private fun resolveSchedule(scheduleId: Long): CourseSchedule {
        if (scheduleId == AlarmScheduler.TEST_SCHEDULE_ID) {
            return CourseSchedule(
                id = AlarmScheduler.TEST_SCHEDULE_ID,
                dayOfWeek = Calendar.MONDAY,
                startHour = 7,
                startMinute = 0,
                endHour = 8,
                endMinute = 40,
                courseName = "E-Learning UBP",
                room = "A405",
                classMode = "Offline",
                ringEnabled = true,
                vibrateEnabled = true,
                notifEnabled = true,
                active = true,
                courseUrl = "https://elearning.ubpkarawang.ac.id/my/"
            )
        }
        return db.getScheduleById(scheduleId) ?: CourseSchedule(
            id = scheduleId,
            dayOfWeek = Calendar.MONDAY,
            startHour = 7,
            startMinute = 0,
            endHour = 8,
            endMinute = 40,
            courseName = "Kuliah UBP",
            room = "-",
            classMode = "Online",
            ringEnabled = true,
            vibrateEnabled = true,
            notifEnabled = true,
            active = true,
            courseUrl = ""
        )
    }

    private fun getCalibratedOffsetYDp(): Int =
        (db.getSetting("island_offset_y", "0").toIntOrNull() ?: 0).coerceIn(-15, 40)

    private fun getCalibratedWidthDp(): Int {
        val extra = (db.getSetting("island_width_extra", "0").toIntOrNull() ?: 0).coerceIn(-60, 60)
        return (156 + extra).coerceIn(92, 230)
    }

    private fun getCalibratedHeightDp(): Int {
        val extra = (db.getSetting("island_height_extra", "0").toIntOrNull() ?: 0).coerceIn(-10, 24)
        return (28 + extra).coerceIn(18, 52)
    }

    private fun showOrRebuildOverlay() {
        removeOverlayImmediate()
        isExpanded = false

        val widthDp = getCalibratedWidthDp()
        val heightDp = getCalibratedHeightDp()
        val offsetYDp = getCalibratedOffsetYDp()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(8 + offsetYDp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowParams = params

        val root = FrameLayout(this)
        val capsule = FrameLayout(this).apply {
            background = buildCapsuleBackground(heightDp / 2f)
            elevation = dp(8).toFloat()
            scaleX = 0.72f
            scaleY = 0.72f
            alpha = 0f
        }

        val collapsed = buildCollapsedView(widthDp, heightDp)
        val expanded = buildExpandedView().apply {
            visibility = View.GONE
            alpha = 0f
        }

        capsule.addView(collapsed, FrameLayout.LayoutParams(dp(widthDp), dp(heightDp)))
        capsule.addView(
            expanded,
            FrameLayout.LayoutParams(dp(304), FrameLayout.LayoutParams.WRAP_CONTENT)
        )

        attachGestureController(capsule)
        root.addView(capsule)

        rootFrame = root
        capsuleContainer = capsule
        collapsedRow = collapsed
        expandedCol = expanded

        try {
            windowManager.addView(root, params)
            capsule.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(260L)
                .setInterpolator(OvershootInterpolator(1.15f))
                .start()
            handler.removeCallbacks(clockTickRunnable)
            handler.post(clockTickRunnable)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun applyLiveGeometry() {
        val root = rootFrame ?: return
        val capsule = capsuleContainer ?: return
        val collapsed = collapsedRow ?: return
        val params = windowParams ?: return

        val widthDp = getCalibratedWidthDp()
        val heightDp = getCalibratedHeightDp()
        val offsetYDp = getCalibratedOffsetYDp()

        params.y = dp(8 + offsetYDp)
        capsule.background = buildCapsuleBackground(heightDp / 2f)

        val lp = collapsed.layoutParams
        lp.width = dp(widthDp)
        lp.height = dp(heightDp)
        collapsed.layoutParams = lp

        val hPad = (heightDp * 0.34f).toInt().coerceIn(6, 14)
        collapsed.setPadding(dp(hPad), 0, dp(hPad), 0)

        val dotSize = (heightDp * 0.25f).toInt().coerceIn(5, 8)
        statusDot?.layoutParams = LinearLayout.LayoutParams(dp(dotSize), dp(dotSize)).apply {
            marginEnd = dp(5)
        }

        val fontSp = when {
            heightDp <= 21 -> 8.5f
            heightDp <= 25 -> 9.5f
            heightDp <= 32 -> 10.5f
            else -> 11.5f
        }
        leftLabelView?.textSize = fontSp
        rightBadgeView?.textSize = fontSp

        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    private fun buildCapsuleBackground(radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setColor(Color.parseColor("#F505070C"))
            setStroke(dp(1), Color.parseColor("#40F8FAFC"))
        }
    }

    private fun buildCollapsedView(widthDp: Int, heightDp: Int): LinearLayout {
        val schedule = currentSchedule!!
        val hPad = (heightDp * 0.34f).toInt().coerceIn(6, 14)
        val dotSize = (heightDp * 0.25f).toInt().coerceIn(5, 8)
        val fontSp = when {
            heightDp <= 21 -> 8.5f
            heightDp <= 25 -> 9.5f
            heightDp <= 32 -> 10.5f
            else -> 11.5f
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(hPad), 0, dp(hPad), 0)
        }

        val dotColor = when {
            isTestMode -> "#38BDF8"
            isPreReminderMode -> "#F59E0B"
            else -> "#10B981"
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(dotColor))
            }
        }
        statusDot = dot

        val shortTitle = schedule.courseName
            .replace("Algoritma & Pemrograman Komputer", "Alpro")
            .replace("Analisis dan Perancangan Kerja", "APK")
            .replace("Analisis Perancangan Kerja", "APK")
            .replace("Ilmu Bahan dan Kimia Lingkungan", "Kimia")
            .replace("Analisis dan Estimasi Biaya", "Est. Biaya")

        val leftLabel = TextView(this).apply {
            text = shortTitle
            textSize = fontSp
            includeFontPadding = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F1F5F9"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        leftLabelView = leftLabel

        // Center spacer leaves room for front punch-hole camera lens
        val cameraSpacer = View(this)

        val rightBadge = TextView(this).apply {
            text = formatBadgeText(schedule)
            textSize = fontSp
            includeFontPadding = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (isPreReminderMode) "#FCD34D" else "#6EE7B7"))
            maxLines = 1
        }
        rightBadgeView = rightBadge

        row.addView(dot, LinearLayout.LayoutParams(dp(dotSize), dp(dotSize)).apply { marginEnd = dp(5) })
        row.addView(leftLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(cameraSpacer, LinearLayout.LayoutParams(dp(26), dp(1)))
        row.addView(rightBadge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return row
    }

    private fun formatBadgeText(schedule: CourseSchedule): String {
        return if (isTestMode) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", schedule.startHour, schedule.startMinute)
        }
    }

    private fun updateCollapsedClockText() {
        val schedule = currentSchedule ?: return
        rightBadgeView?.text = formatBadgeText(schedule)
    }

    private fun buildExpandedView(): LinearLayout {
        val schedule = currentSchedule!!
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusLabel = when {
            isTestMode -> "KALIBRASI DYNAMIC ISLAND"
            isPreReminderMode -> "PERSIAPAN H-10 MENIT"
            else -> "WAKTUNYA ABSEN KULIAH"
        }
        val statusColor = when {
            isTestMode -> "#38BDF8"
            isPreReminderMode -> "#FBBF24"
            else -> "#34D399"
        }

        topRow.addView(TextView(this).apply {
            text = statusLabel
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(statusColor))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        topRow.addView(TextView(this).apply {
            text = String.format(
                Locale.getDefault(),
                "%02d:%02d - %02d:%02d",
                schedule.startHour,
                schedule.startMinute,
                schedule.endHour,
                schedule.endMinute
            )
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#CBD5E1"))
        })

        val titleView = TextView(this).apply {
            text = schedule.courseName
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        val metaView = TextView(this).apply {
            text = "Ruang ${schedule.room} • ${schedule.classMode} • Geser atas untuk tutup"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnOpen = makeIslandButton("Buka Kelas", "#4F46E5") {
            AlarmAlertActivity.stopActiveAlarm()
            cancelNotif(schedule.id)
            val portalIntent = Intent(this, SiptPortalActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, schedule.id)
                putExtra(SiptPortalActivity.EXTRA_COURSE_URL, schedule.courseUrl)
                putExtra(SiptPortalActivity.EXTRA_COURSE_NAME, schedule.courseName)
            }
            startActivity(portalIntent)
            animateOutAndStop()
        }

        val btnAttend = makeIslandButton("Hadir ✓", "#16A34A") {
            AlarmAlertActivity.stopActiveAlarm()
            cancelNotif(schedule.id)
            if (schedule.id > 0) {
                db.recordAttendance(schedule.id, schedule.courseName, "HADIR")
                NextClassWidgetProvider.updateAllWidgets(this)
            }
            Toast.makeText(this, "Hadir tercatat!", Toast.LENGTH_SHORT).show()
            animateOutAndStop()
        }

        val btnClose = makeIslandButton("Tutup", "#334155") {
            if (isTestMode) {
                toggleExpand(false)
            } else {
                animateOutAndStop()
            }
        }

        btnRow.addView(btnOpen, LinearLayout.LayoutParams(0, dp(34), 1.2f).apply { marginEnd = dp(5) })
        btnRow.addView(btnAttend, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(5) })
        btnRow.addView(btnClose, LinearLayout.LayoutParams(0, dp(34), 0.8f))

        col.addView(topRow)
        col.addView(space(5))
        col.addView(titleView)
        col.addView(space(2))
        col.addView(metaView)
        col.addView(space(10))
        col.addView(btnRow)
        return col
    }

    private fun makeIslandButton(label: String, bgHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(dp(8), 0, dp(8), 0)
            background = LiquidGlassKit.pillGlassSurface(this@DynamicIslandService, bgHex, "#66F8FAFC", 16f)
            setOnClickListener { onClick() }
        }
    }

    private fun toggleExpand(expand: Boolean) {
        val capsule = capsuleContainer ?: return
        val collapsed = collapsedRow ?: return
        val expanded = expandedCol ?: return
        if (isExpanded == expand) return
        isExpanded = expand

        handler.removeCallbacks(autoCollapseRunnable)

        if (expand) {
            capsule.background = buildCapsuleBackground(24f)
            collapsed.visibility = View.GONE
            expanded.visibility = View.VISIBLE
            expanded.alpha = 0f
            expanded.scaleX = 0.92f
            expanded.scaleY = 0.92f
            expanded.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220L)
                .setInterpolator(OvershootInterpolator(1.08f))
                .start()
            handler.postDelayed(autoCollapseRunnable, 12_000L)
        } else {
            val widthDp = getCalibratedWidthDp()
            val heightDp = getCalibratedHeightDp()
            capsule.background = buildCapsuleBackground(heightDp / 2f)
            expanded.visibility = View.GONE
            collapsed.layoutParams = FrameLayout.LayoutParams(dp(widthDp), dp(heightDp))
            collapsed.visibility = View.VISIBLE
            collapsed.alpha = 0f
            collapsed.animate()
                .alpha(1f)
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun attachGestureController(target: View) {
        var downX = 0f
        var downY = 0f
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dy < -dp(22)) {
                        animateOutAndStop()
                    } else if (abs(dx) < dp(12) && abs(dy) < dp(12)) {
                        toggleExpand(!isExpanded)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun cancelNotif(scheduleId: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = if (scheduleId == AlarmScheduler.TEST_SCHEDULE_ID) 999 else (scheduleId * 10 + 1).toInt()
        nm.cancel(id)
    }

    private fun animateOutAndStop() {
        val capsule = capsuleContainer
        if (capsule == null) {
            removeOverlayImmediate()
            stopSelf()
            return
        }
        capsule.animate()
            .translationY(-dp(36).toFloat())
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(180L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    removeOverlayImmediate()
                    stopSelf()
                }
            })
            .start()
    }

    private fun removeOverlayImmediate() {
        handler.removeCallbacks(clockTickRunnable)
        handler.removeCallbacks(autoCollapseRunnable)
        handler.removeCallbacks(autoDismissRunnable)
        rootFrame?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        rootFrame = null
        capsuleContainer = null
        collapsedRow = null
        expandedCol = null
        statusDot = null
        leftLabelView = null
        rightBadgeView = null
        windowParams = null
        isExpanded = false
    }

    private fun space(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        removeOverlayImmediate()
        super.onDestroy()
    }
}
