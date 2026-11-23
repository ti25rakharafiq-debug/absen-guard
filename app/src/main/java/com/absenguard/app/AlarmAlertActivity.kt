package com.absenguard.app

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.OvershootInterpolator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class AlarmAlertActivity : AppCompatActivity() {

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
    private lateinit var clockView: TextView
    private var expectedAnswer = 0
    private var mathRequired = false
    private var mathInput: EditText? = null

    private val clockRunnable = object : Runnable {
        override fun run() {
            clockView.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopActiveAlarm()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)
        val db = ScheduleDbHelper(this)
        val schedule = if (scheduleId == AlarmScheduler.TEST_SCHEDULE_ID) {
            CourseSchedule(
                id = AlarmScheduler.TEST_SCHEDULE_ID,
                dayOfWeek = Calendar.MONDAY,
                startHour = 7,
                startMinute = 0,
                endHour = 8,
                endMinute = 40,
                courseName = "Simulasi Tes Absen BOC",
                room = "Online",
                classMode = "Online",
                ringEnabled = true,
                vibrateEnabled = true,
                notifEnabled = true,
                active = true
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
                active = true
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F8F9FA"))
            }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(26), dp(22), dp(26))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EEF1F6"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#D5DCE6"))
            }
        }

        val badge = TextView(this).apply {
            text = "WAKTUNYA ABSEN KULIAH"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#DC2626"))
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FEE2E2"))
                cornerRadius = dp(16).toFloat()
            }
        }
        val badgePulse = ObjectAnimator.ofFloat(badge, "scaleX", 1f, 1.04f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val badgePulseY = ObjectAnimator.ofFloat(badge, "scaleY", 1f, 1.04f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        badgePulse.start()
        badgePulseY.start()

        clockView = TextView(this).apply {
            text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            textSize = 34f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#5B6AD0"))
            gravity = Gravity.CENTER
        }

        val titleView = TextView(this).apply {
            text = schedule.courseName
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E2430"))
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
            textSize = 14f
            setTextColor(Color.parseColor("#5A6577"))
            gravity = Gravity.CENTER
        }

        card.addView(badge)
        card.addView(space(14))
        card.addView(clockView)
        card.addView(space(10))
        card.addView(titleView)
        card.addView(space(6))
        card.addView(subtitleView)

        if (mathRequired) {
            val a = Random.nextInt(4, 16)
            val b = Random.nextInt(4, 16)
            expectedAnswer = a + b

            val mathLabel = TextView(this).apply {
                text = "Tantangan Pagi: $a + $b = ?"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1E2430"))
                gravity = Gravity.CENTER
            }

            val edit = EditText(this).apply {
                hint = "Ketik hasil hitungan"
                inputType = InputType.TYPE_CLASS_NUMBER
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#1E2430"))
                setHintTextColor(Color.parseColor("#5A6577"))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F8F9FA"))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.parseColor("#D5DCE6"))
                }
            }
            mathInput = edit
            card.addView(space(16))
            card.addView(mathLabel)
            card.addView(space(8))
            card.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val btnPortal = makeActionButton("Buka Portal Absen (SIPT2)", "#5B6AD0") {
            if (!validateMath()) return@makeActionButton
            stopActiveAlarm()
            cancelNotif(schedule.id)
            val portalIntent = Intent(this, SiptPortalActivity::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, schedule.id)
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
            stopActiveAlarm()
            cancelNotif(schedule.id)
            AlarmScheduler.scheduleSnooze(this, schedule.id, 5)
            Toast.makeText(this, "Alarm ditunda 5 menit", Toast.LENGTH_SHORT).show()
            finish()
        }

        card.addView(space(22))
        card.addView(btnPortal, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
        card.addView(space(10))
        card.addView(btnAttend, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
        card.addView(space(10))
        card.addView(btnSnooze, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))

        root.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        handler.post(clockRunnable)
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
            setTextColor(Color.parseColor("#F8F9FA"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = dp(25).toFloat()
            }
            setOnClickListener { onClick() }
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
        stopActiveAlarm()
        super.onDestroy()
    }
}
