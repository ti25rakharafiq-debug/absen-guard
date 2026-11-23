package com.absenguard.app

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatActivity

class SiptPortalActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = ScheduleDbHelper(this)
        val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)
        val activeId = if (scheduleId > 0) scheduleId else findNearestActiveSchedule(db)
        if (activeId > 0) {
            db.getScheduleById(activeId)?.let {
                db.recordAttendance(activeId, it.courseName, "HADIR")
            }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C212C"))
            }
        }
        val back = Button(this).apply {
            text = "Kembali"
            setTextColor(Color.parseColor("#F1F5F9"))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "Portal Absen SIPT2"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#F1F5F9"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        }
        val fill = Button(this).apply {
            text = "Isi Akun"
            setTextColor(Color.parseColor("#F1F5F9"))
            setOnClickListener { fillCredentials() }
        }
        val confirm = Button(this).apply {
            text = "Konfirmasi Hadir ✓"
            setTextColor(Color.parseColor("#F1F5F9"))
            setOnClickListener {
                val targetId = if (scheduleId > 0) scheduleId else findNearestActiveSchedule(db)
                if (targetId > 0) {
                    db.getScheduleById(targetId)?.let {
                        db.recordAttendance(targetId, it.courseName, "HADIR")
                    }
                }
                NextClassWidgetProvider.updateAllWidgets(this@SiptPortalActivity)
                finish()
            }
        }
        topBar.addView(back)
        topBar.addView(title)
        topBar.addView(fill)
        topBar.addView(confirm)

        webView = WebView(this).apply {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    CookieManager.getInstance().flush()
                    fillCredentials()
                }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar)
            addView(webView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        }
        setContentView(root)
        webView.loadUrl(db.getSetting("portal_url", "https://sipt2.ubpkarawang.ac.id/apps"))
    }

    private fun fillCredentials() {
        val db = ScheduleDbHelper(this)
        val username = db.getSetting("sipt_username", "ti25.rakharafiq@mhs.ubpkarawang.ac.id")
            .replace("\\", "\\\\").replace("'", "\\'")
        val password = db.getSetting("sipt_password", "ubpk2024")
            .replace("\\", "\\\\").replace("'", "\\'")
        val script = """
            (function(){
                var user=document.querySelector('input[type="email"], input[name*="email"], input[name*="user"], input[name*="nim"], input[type="text"]');
                var pass=document.querySelector('input[type="password"]');
                function setField(el,value){
                    if(el && (el.value===null || el.value.trim()==='')){
                        el.value=value;
                        el.dispatchEvent(new Event('input',{bubbles:true}));
                        el.dispatchEvent(new Event('change',{bubbles:true}));
                    }
                }
                setField(user,'$username');
                setField(pass,'$password');
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun findNearestActiveSchedule(db: ScheduleDbHelper): Long {
        val now = java.util.Calendar.getInstance()
        val today = now.get(java.util.Calendar.DAY_OF_WEEK)
        val todaySchedules = db.getSchedulesForDay(today).filter { it.active }
        val totalMinute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val active = todaySchedules.firstOrNull {
            totalMinute in it.startMinute + it.startHour * 60..it.endMinute + it.endHour * 60
        } ?: todaySchedules.firstOrNull { it.startMinute + it.startHour * 60 >= totalMinute }
        return active?.id ?: db.getAllSchedules().firstOrNull { it.active }?.id ?: -1L
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
