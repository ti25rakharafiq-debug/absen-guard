package com.absenguard.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.display.DisplayManager
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
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class DynamicIslandService : Service() {

    companion object {
        const val ACTION_SHOW = "com.absenguard.app.ACTION_SHOW_ISLAND"
        const val ACTION_DISMISS = "com.absenguard.app.ACTION_DISMISS_ISLAND"
        const val EXTRA_IS_TEST = "extra_is_test"

        fun show(context: Context, scheduleId: Long, isPreReminder: Boolean, isTest: Boolean = false) {
            val db = ScheduleDbHelper(context)
            if (!isTest && db.getSetting("dynamic_island_enabled", "1") != "1") return
            if (!Settings.canDrawOverlays(context)) return

            val intent = Intent(context, DynamicIslandService::class.java).apply {
                action = ACTION_SHOW
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
                putExtra(AlarmScheduler.EXTRA_IS_PRE_REMINDER, isPreReminder)
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
    private lateinit var displayManager: DisplayManager
    private lateinit var db: ScheduleDbHelper
    private var rootFrame: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var compactContainer: LinearLayout? = null
    private var expandedContainer: LinearLayout? = null
    private var dotView: View? = null
    private var statusTagView: TextView? = null
    private var timerView: TextView? = null
    private var expandedTimerView: TextView? = null
    private var dotPulseAnim: ObjectAnimator? = null

    private var currentSchedule: CourseSchedule? = null
    private var isPreReminderMode = false
    private var isTestMode = false
    private var isExpanded = false
    private var shownAtMillis = 0L
    private var touchStartY = 0f
    private var touchMoved = false

    private val handler = Handler(Looper.getMainLooper())

    private val autoCollapseRunnable = Runnable {
        if (isExpanded) {
            setExpandedState(false, animate = true)
        }
    }

    private val autoExpireRunnable = Runnable {
        animateDismiss()
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            val s = currentSchedule ?: return
            if (!isTestMode && s.id > 0 && db.isAttendedToday(s.id)) {
                animateDismiss()
                return
            }
            updateLiveTimerText()
            handler.postDelayed(this, 1000L)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (rootFrame != null) {
                if (isLandscape() && isExpanded) {
                    setExpandedState(false, animate = false)
                } else {
                    rebuildIslandContent(animateEntrance = false)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        db = ScheduleDbHelper(this)
        displayManager.registerDisplayListener(displayListener, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                animateDismiss()
                return START_NOT_STICKY
            }
            ACTION_SHOW -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)
                isPreReminderMode = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_PRE_REMINDER, false)
                isTestMode = intent.getBooleanExtra(EXTRA_IS_TEST, false)

                val resolved = when {
                    scheduleId == AlarmScheduler.TEST_SCHEDULE_ID || isTestMode -> {
                        val next = AlarmScheduler.getNextUpcomingSchedule(this)?.first
                        next ?: CourseSchedule(
                            id = AlarmScheduler.TEST_SCHEDULE_ID,
                            dayOfWeek = Calendar.MONDAY,
                            startHour = 7,
                            startMinute = 0,
                            endHour = 8,
                            endMinute = 40,
                            courseName = "Simulasi Dynamic Island",
                            room = "A405",
                            classMode = "Online",
                            ringEnabled = true,
                            vibrateEnabled = true,
                            notifEnabled = true,
                            active = true,
                            courseUrl = "https://elearning.ubpkarawang.ac.id/login/index.php"
                        )
                    }
                    else -> db.getScheduleById(scheduleId)
                }

                if (resolved == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                currentSchedule = resolved
                shownAtMillis = System.currentTimeMillis()
                isExpanded = false
                mountOrRefreshOverlay()
            }
        }
        return START_NOT_STICKY
    }

    private fun mountOrRefreshOverlay() {
        handler.removeCallbacks(tickerRunnable)
        handler.removeCallbacks(autoCollapseRunnable)
        handler.removeCallbacks(autoExpireRunnable)

        if (rootFrame == null) {
            val container = FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
            }
            val params = createBaseLayoutParams()
            rootFrame = container
            windowParams = params
            try {
                windowManager.addView(container, params)
            } catch (_: Exception) {
                rootFrame = null
                stopSelf()
                return
            }
        }

        rebuildIslandContent(animateEntrance = true)
        handler.post(tickerRunnable)
        handler.postDelayed(autoExpireRunnable, 15 * 60 * 1000L)
    }

    private fun createBaseLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun rebuildIslandContent(animateEntrance: Boolean) {
        val root = rootFrame ?: return
        val schedule = currentSchedule ?: return
        dotPulseAnim?.cancel()
        root.removeAllViews()

        val landscape = isLandscape()
        val cutout = getCutoutRect()
        val offsetYDp = db.getSetting("island_offset_y", "0").toIntOrNull() ?: 0
        val extraWidthDp = db.getSetting("island_width_extra", "0").toIntOrNull() ?: 0

        val screenWidth = resources.displayMetrics.widthPixels
        val cutoutWidth = cutout?.width()?.takeIf { it > 0 } ?: dp(28)
        val cutoutHeight = cutout?.height()?.takeIf { it > 0 } ?: dp(28)
        val cutoutTop = cutout?.top ?: dp(8)
        val cutoutBottom = cutout?.bottom ?: (cutoutTop + cutoutHeight)
        val cutoutCenterX = cutout?.centerX() ?: (screenWidth / 2)

        val accentHex = if (isPreReminderMode) "#10B981" else "#F59E0B"
        val badgeBgHex = if (isPreReminderMode) "#3310B981" else "#38EF4444"
        val badgeTextHex = if (isPreReminderMode) "#6EE7B7" else "#FCA5A5"

        val compactHeight = if (landscape) {
            dp(30)
        } else {
            (cutoutHeight + dp(10)).coerceAtLeast(dp(36))
        }
        val compactWidth = if (landscape) {
            dp(156)
        } else {
            (cutoutWidth + dp(176) + dp(extraWidthDp)).coerceIn(dp(186), screenWidth - dp(24))
        }

        val compact = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = createOpticalIslandSurface(compactHeight / 2f, isExpanded = false)
            elevation = dp(10).toFloat()
        }

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(accentHex))
            }
        }
        dotView = dot
        dotPulseAnim = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.25f).apply {
            duration = 750L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }

        val leftLabel = TextView(this).apply {
            text = if (isPreReminderMode) "H-10" else "ABSEN"
            textSize = if (landscape) 10f else 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(badgeTextHex))
            maxLines = 1
        }
        statusTagView = leftLabel

        val rightTimer = TextView(this).apply {
            text = "00:00"
            textSize = if (landscape) 10f else 11f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            gravity = Gravity.END
            maxLines = 1
        }
        timerView = rightTimer

        if (landscape) {
            compact.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
            compact.addView(spaceW(6))
            compact.addView(leftLabel)
            compact.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            compact.addView(rightTimer)
        } else {
            val leftWing = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            leftWing.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
            leftWing.addView(spaceW(6))
            leftWing.addView(leftLabel)

            val cameraHoleGap = View(this)

            val rightWing = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            rightWing.addView(rightTimer)

            compact.addView(leftWing, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            compact.addView(cameraHoleGap, LinearLayout.LayoutParams(cutoutWidth + dp(12), dp(10)))
            compact.addView(rightWing, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val expandedWidth = (screenWidth - dp(24)).coerceAtMost(dp(420))
        val topLensClearance = if (landscape) dp(14) else (cutoutBottom + dp(8)).coerceAtLeast(dp(42))

        val expanded = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), topLensClearance, dp(16), dp(16))
            background = createOpticalIslandSurface(dp(28).toFloat(), isExpanded = true)
            elevation = dp(16).toFloat()
            visibility = if (isExpanded) View.VISIBLE else View.GONE
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pillTag = TextView(this).apply {
            text = if (isPreReminderMode) "PENGINGAT H-10 MENIT" else "WAKTUNYA ABSEN KULIAH"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(badgeTextHex))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(badgeBgHex))
                cornerRadius = dp(12).toFloat()
            }
        }
        val expTimer = TextView(this).apply {
            text = "00:00"
            textSize = 12f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(dp(8), 0, dp(8), 0)
        }
        expandedTimerView = expTimer

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#334155"))
                setStroke(dp(1), Color.parseColor("#66F8FAFC"))
            }
            setOnClickListener { animateDismiss() }
            LiquidGlassKit.attachLiquidTouch(this, 0.90f)
        }

        topRow.addView(pillTag)
        topRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        topRow.addView(expTimer)
        topRow.addView(closeBtn, LinearLayout.LayoutParams(dp(28), dp(28)))

        val courseTitle = TextView(this).apply {
            text = schedule.courseName
            textSize = 16f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        }

        val courseMeta = TextView(this).apply {
            text = String.format(
                Locale.getDefault(),
                "%02d:%02d - %02d:%02d • Ruang %s (%s)",
                schedule.startHour,
                schedule.startMinute,
                schedule.endHour,
                schedule.endMinute,
                schedule.room,
                schedule.classMode
            )
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnOpen = Button(this).apply {
            text = "Buka Kelas"
            isAllCaps = false
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            background = LiquidGlassKit.pillGlassSurface(this@DynamicIslandService, "#4F46E5", "#99F8FAFC", 16f)
            setOnClickListener {
                val portalIntent = Intent(this@DynamicIslandService, SiptPortalActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, schedule.id)
                    putExtra(SiptPortalActivity.EXTRA_COURSE_URL, schedule.courseUrl)
                    putExtra(SiptPortalActivity.EXTRA_COURSE_NAME, schedule.courseName)
                }
                startActivity(portalIntent)
                animateDismiss()
            }
            LiquidGlassKit.attachLiquidTouch(this)
        }

        val btnAttend = Button(this).apply {
            text = "Sudah Absen ✓"
            isAllCaps = false
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            background = LiquidGlassKit.pillGlassSurface(this@DynamicIslandService, "#2E8B57", "#99F8FAFC", 16f)
            setOnClickListener {
                if (schedule.id > 0) {
                    db.recordAttendance(schedule.id, schedule.courseName, "HADIR")
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel((schedule.id * 10 + 1).toInt())
                    NextClassWidgetProvider.updateAllWidgets(this@DynamicIslandService)
                }
                Toast.makeText(this@DynamicIslandService, "Absen tercatat hadir!", Toast.LENGTH_SHORT).show()
                animateDismiss()
            }
            LiquidGlassKit.attachLiquidTouch(this)
        }

        val btnSnooze = Button(this).apply {
            text = "5m"
            isAllCaps = false
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            background = LiquidGlassKit.pillGlassSurface(this@DynamicIslandService, "#D97706", "#99F8FAFC", 16f)
            setOnClickListener {
                AlarmScheduler.scheduleSnooze(this@DynamicIslandService, schedule.id, 5)
                Toast.makeText(this@DynamicIslandService, "Ditunda 5 menit", Toast.LENGTH_SHORT).show()
                animateDismiss()
            }
            LiquidGlassKit.attachLiquidTouch(this)
        }

        btnRow.addView(btnOpen, LinearLayout.LayoutParams(0, dp(38), 1.15f).apply { marginEnd = dp(6) })
        btnRow.addView(btnAttend, LinearLayout.LayoutParams(0, dp(38), 1.25f).apply { marginEnd = dp(6) })
        btnRow.addView(btnSnooze, LinearLayout.LayoutParams(dp(52), dp(38)))

        expanded.addView(topRow)
        expanded.addView(spaceH(8))
        expanded.addView(courseTitle)
        expanded.addView(spaceH(3))
        expanded.addView(courseMeta)
        expanded.addView(spaceH(12))
        expanded.addView(btnRow)

        compactContainer = compact
        expandedContainer = expanded

        compact.visibility = if (isExpanded) View.GONE else View.VISIBLE
        root.addView(compact, FrameLayout.LayoutParams(compactWidth, compactHeight))
        root.addView(expanded, FrameLayout.LayoutParams(expandedWidth, FrameLayout.LayoutParams.WRAP_CONTENT))

        attachIslandGestures(compact)
        attachIslandGestures(expanded)

        updateLiveTimerText()
        positionWindow(
            landscape = landscape,
            cutoutCenterX = cutoutCenterX,
            cutoutTop = cutoutTop,
            offsetYDp = offsetYDp,
            activeWidth = if (isExpanded) expandedWidth else compactWidth
        )

        if (animateEntrance) {
            root.scaleX = 0.72f
            root.scaleY = 0.72f
            root.alpha = 0f
            root.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(320L)
                .setInterpolator(OvershootInterpolator(1.45f))
                .start()
        }
    }

    private fun setExpandedState(expand: Boolean, animate: Boolean) {
        if (isExpanded == expand) return
        isExpanded = expand
        handler.removeCallbacks(autoCollapseRunnable)

        val root = rootFrame ?: return
        val compact = compactContainer ?: return
        val expanded = expandedContainer ?: return

        val landscape = isLandscape()
        val cutout = getCutoutRect()
        val offsetYDp = db.getSetting("island_offset_y", "0").toIntOrNull() ?: 0
        val extraWidthDp = db.getSetting("island_width_extra", "0").toIntOrNull() ?: 0
        val screenWidth = resources.displayMetrics.widthPixels
        val cutoutWidth = cutout?.width()?.takeIf { it > 0 } ?: dp(28)
        val cutoutTop = cutout?.top ?: dp(8)
        val cutoutCenterX = cutout?.centerX() ?: (screenWidth / 2)

        val compactWidth = if (landscape) dp(156) else (cutoutWidth + dp(176) + dp(extraWidthDp)).coerceIn(dp(186), screenWidth - dp(24))
        val expandedWidth = (screenWidth - dp(24)).coerceAtMost(dp(420))

        if (expand) {
            compact.visibility = View.GONE
            expanded.visibility = View.VISIBLE
            positionWindow(landscape, cutoutCenterX, cutoutTop, offsetYDp, expandedWidth)
            if (animate) {
                expanded.scaleX = 0.84f
                expanded.scaleY = 0.75f
                expanded.alpha = 0.4f
                expanded.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(280L)
                    .setInterpolator(OvershootInterpolator(1.25f))
                    .start()
            }
            handler.postDelayed(autoCollapseRunnable, 9000L)
        } else {
            expanded.visibility = View.GONE
            compact.visibility = View.VISIBLE
            positionWindow(landscape, cutoutCenterX, cutoutTop, offsetYDp, compactWidth)
            if (animate) {
                compact.scaleX = 1.15f
                compact.scaleY = 1.15f
                compact.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun positionWindow(
        landscape: Boolean,
        cutoutCenterX: Int,
        cutoutTop: Int,
        offsetYDp: Int,
        activeWidth: Int
    ) {
        val params = windowParams ?: return
        val root = rootFrame ?: return
        val screenWidth = resources.displayMetrics.widthPixels

        if (landscape) {
            params.x = ((screenWidth - activeWidth) / 2).coerceAtLeast(dp(12))
            params.y = (dp(8) + dp(offsetYDp)).coerceAtLeast(0)
        } else {
            val centeredX = if (isExpanded) {
                (screenWidth - activeWidth) / 2
            } else {
                cutoutCenterX - (activeWidth / 2)
            }
            params.x = centeredX.coerceIn(dp(8), (screenWidth - activeWidth - dp(8)).coerceAtLeast(dp(8)))
            params.y = (cutoutTop - dp(5) + dp(offsetYDp)).coerceAtLeast(0)
        }

        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    private fun attachIslandGestures(target: View) {
        target.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartY = event.rawY
                    touchMoved = false
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80L).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - touchStartY
                    if (abs(dy) > dp(10)) {
                        touchMoved = true
                    }
                    if (dy < 0f) {
                        rootFrame?.translationY = dy * 0.65f
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180L).setInterpolator(OvershootInterpolator(2f)).start()
                    val dy = event.rawY - touchStartY
                    if (dy < -dp(26)) {
                        animateDismiss()
                    } else {
                        rootFrame?.animate()?.translationY(0f)?.setDuration(180L)?.start()
                        if (!touchMoved) {
                            setExpandedState(!isExpanded, animate = true)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun animateDismiss() {
        handler.removeCallbacks(tickerRunnable)
        handler.removeCallbacks(autoCollapseRunnable)
        handler.removeCallbacks(autoExpireRunnable)
        val root = rootFrame
        if (root == null) {
            stopSelf()
            return
        }
        root.animate()
            .scaleX(0.55f)
            .scaleY(0.55f)
            .alpha(0f)
            .translationY(-dp(18).toFloat())
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                removeOverlayNow()
                stopSelf()
            }
            .start()
    }

    private fun removeOverlayNow() {
        dotPulseAnim?.cancel()
        rootFrame?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        rootFrame = null
        windowParams = null
    }

    private fun updateLiveTimerText() {
        val schedule = currentSchedule ?: return
        val formatted = if (isPreReminderMode) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, schedule.startHour)
                set(Calendar.MINUTE, schedule.startMinute)
                set(Calendar.SECOND, 0)
            }
            val diffSec = ((target.timeInMillis - now.timeInMillis) / 1000L).coerceIn(0L, 5999L)
            String.format(Locale.getDefault(), "%02d:%02d", diffSec / 60L, diffSec % 60L)
        } else {
            val elapsedSec = ((System.currentTimeMillis() - shownAtMillis) / 1000L).coerceAtLeast(0L)
            String.format(Locale.getDefault(), "%02d:%02d", elapsedSec / 60L, elapsedSec % 60L)
        }
        timerView?.text = formatted
        expandedTimerView?.text = formatted
    }

    private fun createOpticalIslandSurface(radiusPx: Float, isExpanded: Boolean): LayerDrawable {
        val core = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(Color.parseColor(if (isExpanded) "#F0090D16" else "#FA090D16"))
        }
        val glassRim = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#66F8FAFC"),
                Color.parseColor("#14F8FAFC"),
                Color.parseColor("#3860A5FA")
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setStroke(dp(1), Color.parseColor(if (isExpanded) "#80F8FAFC" else "#59F8FAFC"))
        }
        return LayerDrawable(arrayOf(core, glassRim))
    }

    private fun getCutoutRect(): Rect? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.windowInsets.displayCutout?.boundingRects?.firstOrNull()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.cutout?.boundingRects?.firstOrNull()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun spaceW(widthDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
    }

    private fun spaceH(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (rootFrame != null) {
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE && isExpanded) {
                setExpandedState(false, animate = false)
            } else {
                rebuildIslandContent(animateEntrance = false)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        removeOverlayNow()
        super.onDestroy()
    }
}
