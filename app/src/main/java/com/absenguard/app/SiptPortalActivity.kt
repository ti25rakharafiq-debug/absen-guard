package com.absenguard.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Calendar

class SiptPortalActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COURSE_URL = "extra_course_url"
        const val EXTRA_COURSE_NAME = "extra_course_name"
    }

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var targetCourseUrl = ""
    private var wasRedirectedToLogin = false
    private var autoLoginAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = ScheduleDbHelper(this)
        val scheduleId = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, -1L)
        val passedUrl = intent.getStringExtra(EXTRA_COURSE_URL)?.trim().orEmpty()
        val passedName = intent.getStringExtra(EXTRA_COURSE_NAME)?.trim().orEmpty()

        window.statusBarColor = Color.parseColor("#0F172A")
        window.navigationBarColor = Color.parseColor("#0F172A")
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val activeId = if (scheduleId > 0) scheduleId else findNearestActiveSchedule(db)
        val schedule = if (activeId > 0) db.getScheduleById(activeId) else null

        targetCourseUrl = when {
            passedUrl.isNotEmpty() -> passedUrl
            schedule != null && schedule.courseUrl.isNotBlank() -> schedule.courseUrl
            else -> db.getSetting("portal_url", "https://elearning.ubpkarawang.ac.id/login/index.php")
        }

        val headerTitle = when {
            passedName.isNotEmpty() -> passedName
            schedule != null -> schedule.courseName
            else -> "E-Learning UBP"
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#0F172A"),
                    Color.parseColor("#1E293B")
                )
            )
        }

        val back = Button(this).apply {
            text = "Kembali"
            isAllCaps = false
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F1F5F9"))
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = LiquidGlassKit.pillGlassSurface(this@SiptPortalActivity, "#334155", "#66F8FAFC", 16f)
            setOnClickListener { finish() }
            LiquidGlassKit.attachLiquidTouch(this)
        }

        val title = TextView(this).apply {
            text = headerTitle
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            gravity = Gravity.CENTER
        }

        val fill = Button(this).apply {
            text = "Isi Akun"
            isAllCaps = false
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = LiquidGlassKit.pillGlassSurface(this@SiptPortalActivity, "#4F46E5", "#99F8FAFC", 16f)
            setOnClickListener { fillCredentials(false) }
            LiquidGlassKit.attachLiquidTouch(this)
        }

        val confirm = Button(this).apply {
            text = "Hadir ✓"
            isAllCaps = false
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = LiquidGlassKit.pillGlassSurface(this@SiptPortalActivity, "#2E8B57", "#99F8FAFC", 16f)
            setOnClickListener {
                if (activeId > 0) {
                    db.getScheduleById(activeId)?.let {
                        db.recordAttendance(activeId, it.courseName, "HADIR")
                        Toast.makeText(this@SiptPortalActivity, "Hadir tercatat: ${it.courseName}", Toast.LENGTH_SHORT).show()
                    }
                }
                NextClassWidgetProvider.updateAllWidgets(this@SiptPortalActivity)
                finish()
            }
            LiquidGlassKit.attachLiquidTouch(this)
        }

        topBar.addView(back)
        topBar.addView(title)
        topBar.addView(fill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(6) })
        topBar.addView(confirm, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))

        webView = WebView(this).apply {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    CookieManager.getInstance().flush()
                    if (url.contains("/login")) {
                        wasRedirectedToLogin = true
                        val shouldSubmit = !autoLoginAttempted
                        autoLoginAttempted = true
                        fillCredentials(shouldSubmit)
                    } else {
                        if (wasRedirectedToLogin &&
                            targetCourseUrl.contains("elearning.ubpkarawang.ac.id") &&
                            !targetCourseUrl.contains("/login") &&
                            !isSameTargetUrl(url, targetCourseUrl)
                        ) {
                            wasRedirectedToLogin = false
                            view.loadUrl(targetCourseUrl)
                            return
                        }
                        highlightAttendanceButton()
                    }
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

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        setContentView(root)
        webView.loadUrl(targetCourseUrl)
    }

    private fun isSameTargetUrl(current: String, target: String): Boolean {
        val cleanCurrent = current.substringBefore("#").trimEnd('/')
        val cleanTarget = target.substringBefore("#").trimEnd('/')
        return cleanCurrent.equals(cleanTarget, ignoreCase = true)
    }

    private fun fillCredentials(autoSubmit: Boolean) {
        val db = ScheduleDbHelper(this)
        val username = db.getSetting("sipt_username", "ti25.rakharafiq@mhs.ubpkarawang.ac.id")
            .replace("\\", "\\\\").replace("'", "\\'")
        val password = db.getSetting("sipt_password", "Buana0000!")
            .replace("\\", "\\\\").replace("'", "\\'")
        val script = """
            (function(){
                var user=document.querySelector('#username, input[name="username"], input[name*="email"], input[type="email"], input[type="text"]');
                var pass=document.querySelector('#password, input[name="password"], input[type="password"]');
                function setField(el,value){
                    if(el){
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
        if (autoSubmit) {
            handler.postDelayed({
                val submitScript = """
                    (function(){
                        var btn=document.querySelector('#loginbtn, button[type="submit"], input[type="submit"]');
                        if(btn){ btn.click(); }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(submitScript, null)
            }, 450L)
        }
    }

    private fun highlightAttendanceButton() {
        val script = """
            (function(){
                var items=document.querySelectorAll('a, button, input[type="submit"]');
                for(var i=0;i<items.length;i++){
                    var el=items[i];
                    var txt=(el.innerText||el.value||'').toLowerCase();
                    var href=(el.getAttribute('href')||'').toLowerCase();
                    if(txt.indexOf('submit attendance')!==-1 || txt.indexOf('ajukan kehadiran')!==-1 || txt.indexOf('presensi')!==-1 || href.indexOf('attendance.php')!==-1){
                        el.style.outline='3px solid #2E8B57';
                        el.style.backgroundColor='#E6F4EA';
                        el.style.color='#0F172A';
                        el.style.padding='8px 14px';
                        el.style.borderRadius='12px';
                        el.style.fontWeight='bold';
                        el.scrollIntoView({behavior:'smooth', block:'center'});
                        break;
                    }
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun findNearestActiveSchedule(db: ScheduleDbHelper): Long {
        val now = Calendar.getInstance()
        val today = now.get(Calendar.DAY_OF_WEEK)
        val todaySchedules = db.getSchedulesForDay(today).filter { it.active }
        val totalMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val active = todaySchedules.firstOrNull {
            totalMinute in (it.startHour * 60 + it.startMinute)..(it.endHour * 60 + it.endMinute)
        } ?: todaySchedules.firstOrNull { (it.startHour * 60 + it.startMinute) >= totalMinute }
        return active?.id ?: db.getAllSchedules().firstOrNull { it.active }?.id ?: -1L
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }
}
