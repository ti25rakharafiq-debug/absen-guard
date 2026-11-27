package com.absenguard.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class AlarmAlertActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        var activeRingtone: Ringtone? = null
        var activeVibrator: Vibrator? = null

        fun stopActiveAlarm() {
            try {
                activeRingtone?.stop()
            } catch (_: Exception) {
            }
            activeRingtone = null
            try {
                activeVibrator?.cancel()
            } catch (_: Exception) {
            }
            activeVibrator = null
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var auroraView: AuroraMeshView
    private lateinit var clockView: TextView
    private lateinit var sensorHintView: TextView
    private var expectedAnswer = 0
    private var mathRequired = false
    private var mathInput: EditText? = null
    private var activeScheduleId = -1L

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var alarmStartedAt = 0L
    private var sawNonFaceDown = false
    private var flippedMuted = false
    private var shakeCount = 0
    private var lastShakeTimestamp = 0L
    private var gestureHandled = false

    private val clockRunnable = object : Runnable {
        override fun run() {
            clockView.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopActiveAlarm()
        alarmStartedAt = System.currentTimeMillis()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)
        activeScheduleId = scheduleId
        val db = ScheduleDbHelper(this)
        val isDark = db.getSetting("dark_mode", "0") == "1"

        window.statusBarColor = Color.parseColor(if (isDark) "#080C16" else "#E8EEFF")
        window.navigationBarColor = Color.parseColor(if (isDark) "#0B1324" else "#E6F7FA")
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }

        val schedule = if (scheduleId == AlarmScheduler.TEST_SCHEDULE_ID) {
            CourseSchedule(
                id = AlarmScheduler.TEST_SCHEDULE_ID,
                dayOfWeek = Calendar.MONDAY,
                startHour = 7,
                startMinute = 0,
                endHour = 8,
                endMinute = 40,
                courseName = "Simulasi Tes Absen E-Learning",
                room = "Online",
                classMode = "Online",
                ringEnabled = true,
                vibrateEnabled = true,
                notifEnabled = true,
                active = true,
                courseUrl = "https://elearning.ubpkarawang.ac.id/login/index.php"
            )
        } else {
            db.getScheduleById(scheduleId) ?: CourseSchedule(
                id = scheduleId,
                dayOfWeek = Calendar.MONDAY,
                startHour = 7,
                startMinute = 0,
                endHour = 8,
                endMinute = 40,
                courseName = "Jadwal Kuliah",
                room = "-",
                classMode = "Online",
                ringEnabled = true,
                vibrateEnabled = true,
                notifEnabled = true,
                active = true,
                courseUrl = ""
            )
        }

        val shouldRing = (db.getSetting("global_ring", "1") == "1") && schedule.ringEnabled
        val shouldVibrate = (db.getSetting("global_vibrate", "1") == "1") && schedule.vibrateEnabled
        mathRequired = db.getSetting("math_dismiss", "0") == "1"

        if (shouldRing) {
            try {
                val customUri = db.getSetting("custom_ringtone_uri", "")
                val uri = if (customUri.isNotEmpty()) {
                    Uri.parse(customUri)
                } else {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                }
                val ringtone = RingtoneManager.getRingtone(this, uri)
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.isLooping = true
                }
                activeRingtone = ringtone
                ringtone.play()
            } catch (_: Exception) {
                try {
                    val ringtone = RingtoneManager.getRingtone(
                        this,
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    )
                    ringtone.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ringtone.isLooping = true
                    }
                    activeRingtone = ringtone
                    ringtone.play()
                } catch (_: Exception) {
                }
            }
        }

        if (shouldVibrate) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                activeVibrator = vibrator
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 400, 700), 0)
                vibrator.vibrate(effect)
            } catch (_: Exception) {
            }
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val rootFrame = FrameLayout(this)
        auroraView = AuroraMeshView(this) { isDark }
        rootFrame.addView(
            auroraView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val centerWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(26), dp(22), dp(26))
            background = LiquidGlassKit.glassSurface(this@AlarmAlertActivity, isDark, 28f)
            LiquidGlassKit.applyGlassElevation(this, 12f, isDark)
        }

        val badge = TextView(this).apply {
            text = "WAKTUNYA ABSEN KULIAH"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (isDark) "#FCA5A5" else "#DC2626"))
            setPadding(dp(15), dp(7), dp(15), dp(7))
            background = LiquidGlassKit.pillGlassSurface(
                this@AlarmAlertActivity,
                if (isDark) "#4D991B1B" else "#33DC2626",
                "#80DC2626",
                16f
            )
        }
        val badgePulse = ObjectAnimator.ofFloat(badge, "scaleX", 1f, 1.05f).apply {
            duration = 850L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val badgePulseY = ObjectAnimator.ofFloat(badge, "scaleY", 1f, 1.05f).apply {
            duration = 850L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        badgePulse.start()
        badgePulseY.start()

        clockView = TextView(this).apply {
            text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            textSize = 36f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (isDark) "#93C5FD" else "#4338CA"))
            gravity = Gravity.CENTER
        }

        val titleView = TextView(this).apply {
            text = schedule.courseName
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (isDark) "#F1F5F9" else "#0F172A"))
            gravity = Gravity.CENTER
        }

        val timeText = String.format(
            Locale.getDefault(),
            "%02d:%02d - %02d:%02d • Ruang %s (%s)",
            schedule.startHour,
            schedule.startMinute,
            schedule.endHour,
            schedule.endMinute,
            schedule.room,
            schedule.classMode
        )

        val subtitleView = TextView(this).apply {
            text = timeText
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (isDark) "#CBD5E1" else "#475569"))
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = LiquidGlassKit.glassSurface(this@AlarmAlertActivity, isDark, 14f, strongRim = false)
            gravity = Gravity.CENTER
        }

        sensorHintView = TextView(this).apply {
            text = "Goyang HP: Tunda 5m  •  Balik Layar: Bisukan"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (isDark) "#6EE7B7" else "#2E8B57"))
            gravity = Gravity.CENTER
        }

        card.addView(badge)
        card.addView(space(14))
        card.addView(clockView)
        card.addView(space(10))
        card.addView(titleView)
        card.addView(space(8))
        card.addView(subtitleView)
        card.addView(space(8))
        card.addView(sensorHintView)

        if (mathRequired) {
            val a = Random.nextInt(4, 16)
            val b = Random.nextInt(4, 16)
            expectedAnswer = a + b

            val mathLabel = TextView(this).apply {
                text = "Tantangan Pagi: $a + $b = ?"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (isDark) "#F1F5F9" else "#0F172A"))
                gravity = Gravity.CENTER
            }

            val edit = EditText(this).apply {
                hint = "Ketik hasil hitungan"
                inputType = InputType.TYPE_CLASS_NUMBER
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(if (isDark) "#F1F5F9" else "#0F172A"))
                setHintTextColor(Color.parseColor("#64748B"))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = LiquidGlassKit.glassSurface(this@AlarmAlertActivity, isDark, 16f)
            }
            mathInput = edit
            card.addView(space(16))
            card.addView(mathLabel)
            card.addView(space(8))
            card.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val btnPortal = makeActionButton("Buka E-Learning Matkul", "#4F46E5") {
            if (!validateMath()) return@makeActionButton
            stopActiveAlarm()
            cancelNotif(schedule.id)
            val portalIntent = Intent(this, SiptPortalActivity::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, schedule.id)
                putExtra(SiptPortalActivity.EXTRA_COURSE_URL, schedule.courseUrl)
                putExtra(SiptPortalActivity.EXTRA_COURSE_NAME, schedule.courseName)
            }
            startActivity(portalIntent)
            finish()
        }

        val btnAttend = makeActionButton("Sudah Absen", "#2E8B57") {
            if (!validateMath()) return@makeActionButton
            stopActiveAlarm()
            cancelNotif(schedule.id)
            if (schedule.id > 0) {
                db.recordAttendance(schedule.id, schedule.courseName, "HADIR")
                NextClassWidgetProvider.updateAllWidgets(this)
            }
            Toast.makeText(this, "Absen tercatat hadir!", Toast.LENGTH_SHORT).show()
            finish()
        }

        val btnSnooze = makeActionButton("Tunda 5 Menit", "#D97706") {
            triggerSnooze(schedule.id, "Alarm ditunda 5 menit")
        }

        card.addView(space(20))
        card.addView(btnPortal, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        card.addView(space(10))
        card.addView(btnAttend, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        card.addView(space(10))
        card.addView(btnSnooze, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))

        centerWrap.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        rootFrame.addView(centerWrap)
        setContentView(rootFrame)
        handler.post(clockRunnable)
    }

    override fun onResume() {
        super.onResume()
        auroraView.startMotion()
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        auroraView.stopMotion()
        sensorManager?.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER || gestureHandled) return
        val now = System.currentTimeMillis()
        if (now - alarmStartedAt < 1500L) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // 1. Flip Face-Down to Mute Ringtone & Vibration
        if (z > -2.0f) {
            sawNonFaceDown = true
        }
        if (!flippedMuted && sawNonFaceDown && z < -7.5f && abs(x) < 4.5f && abs(y) < 4.5f) {
            flippedMuted = true
            stopActiveAlarm()
            pulseConfirmationBuzz()
            sensorHintView.text = "Suara Dibisukan (Layar Tengkurap)"
            sensorHintView.setTextColor(Color.parseColor("#F59E0B"))
            return
        }

        // 2. Shake Gesture to Snooze 5 Minutes
        val gX = x / SensorManager.GRAVITY_EARTH
        val gY = y / SensorManager.GRAVITY_EARTH
        val gZ = z / SensorManager.GRAVITY_EARTH
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)

        if (gForce > 2.35f) {
            if (now - lastShakeTimestamp > 650L) {
                shakeCount = 0
            }
            lastShakeTimestamp = now
            shakeCount++
            if (shakeCount >= 2) {
                gestureHandled = true
                pulseConfirmationBuzz()
                triggerSnooze(activeScheduleId, "Digoyang: Alarm ditunda 5 menit")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun pulseConfirmationBuzz() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(75L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun triggerSnooze(scheduleId: Long, message: String) {
        stopActiveAlarm()
        cancelNotif(scheduleId)
        AlarmScheduler.scheduleSnooze(this, scheduleId, 5)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun validateMath(): Boolean {
        if (!mathRequired) return true
        val typed = mathInput?.text?.toString()?.trim()?.toIntOrNull()
        if (typed != expectedAnswer) {
            Toast.makeText(this, "Jawaban hitungan belum tepat!", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun cancelNotif(scheduleId: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = if (scheduleId == AlarmScheduler.TEST_SCHEDULE_ID) 999 else (scheduleId * 10 + 1).toInt()
        nm.cancel(id)
    }

    private fun makeActionButton(label: String, hexColor: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            background = LiquidGlassKit.pillGlassSurface(this@AlarmAlertActivity, hexColor, "#A6F8FAFC", 26f)
            LiquidGlassKit.applyGlassElevation(this, 6f, false)
            setOnClickListener { onClick() }
            LiquidGlassKit.attachLiquidTouch(this, 0.95f)
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

    override fun onDestroy() {
        handler.removeCallbacks(clockRunnable)
        sensorManager?.unregisterListener(this)
        stopActiveAlarm()
        super.onDestroy()
    }
}
