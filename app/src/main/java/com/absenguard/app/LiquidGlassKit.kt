package com.absenguard.app

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.sin

object LiquidGlassKit {

    fun glassSurface(
        context: Context,
        isDark: Boolean,
        radiusDp: Float = 22f,
        tintHex: String? = null,
        strongRim: Boolean = true
    ): Drawable {
        val radiusPx = dp(context, radiusDp)
        val baseColor = when {
            tintHex != null -> Color.parseColor(tintHex)
            isDark -> Color.parseColor("#B8192236")
            else -> Color.parseColor("#CCF8FAFC")
        }
        val sheenTop = if (isDark) Color.parseColor("#33F8FAFC") else Color.parseColor("#73F8FAFC")
        val sheenMid = if (isDark) Color.parseColor("#08F8FAFC") else Color.parseColor("#12F8FAFC")
        val sheenBottom = if (isDark) Color.parseColor("#1A60A5FA") else Color.parseColor("#26CBD5E1")
        val rimColor = when {
            isDark && strongRim -> Color.parseColor("#52F8FAFC")
            isDark -> Color.parseColor("#33F8FAFC")
            strongRim -> Color.parseColor("#D9F8FAFC")
            else -> Color.parseColor("#99E2E8F0")
        }
        val rippleColor = if (isDark) Color.parseColor("#3893C5FD") else Color.parseColor("#2E4F46E5")

        val baseLayer = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(baseColor)
        }

        val sheenLayer = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(sheenTop, sheenMid, sheenBottom)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setStroke(dpInt(context, 1), rimColor)
        }

        val layered = LayerDrawable(arrayOf(baseLayer, sheenLayer))
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(Color.parseColor("#F8FAFC"))
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), layered, mask)
    }

    fun heroGlassSurface(context: Context, radiusDp: Float = 26f): Drawable {
        val radiusPx = dp(context, radiusDp)
        val body = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#E64338CA"),
                Color.parseColor("#DC2563EB"),
                Color.parseColor("#E00284C7")
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
        }
        val specular = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#59F8FAFC"),
                Color.parseColor("#0DF8FAFC"),
                Color.parseColor("#2638BDF8")
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setStroke(dpInt(context, 1), Color.parseColor("#8CF8FAFC"))
        }
        val layered = LayerDrawable(arrayOf(body, specular))
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(Color.parseColor("#F8FAFC"))
        }
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#40F8FAFC")), layered, mask)
    }

    fun pillGlassSurface(
        context: Context,
        fillHex: String,
        rimHex: String,
        radiusDp: Float = 18f,
        rippleHex: String = "#38F8FAFC"
    ): Drawable {
        val radiusPx = dp(context, radiusDp)
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(Color.parseColor(fillHex))
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#3DF8FAFC"), Color.parseColor("#05F8FAFC"))
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setStroke(dpInt(context, 1), Color.parseColor(rimHex))
        }
        val layered = LayerDrawable(arrayOf(fill, sheen))
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(Color.parseColor("#F8FAFC"))
        }
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor(rippleHex)), layered, mask)
    }

    fun applyGlassElevation(view: View, elevationDp: Float, isDark: Boolean) {
        view.elevation = dp(view.context, elevationDp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val shadowColor = if (isDark) Color.parseColor("#73060A12") else Color.parseColor("#244338CA")
            view.outlineSpotShadowColor = shadowColor
            view.outlineAmbientShadowColor = shadowColor
        }
    }

    fun attachLiquidTouch(view: View, pressScale: Float = 0.96f) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(pressScale)
                        .scaleY(pressScale)
                        .alpha(0.92f)
                        .setDuration(95L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(260L)
                        .setInterpolator(OvershootInterpolator(2.3f))
                        .start()
                }
            }
            false
        }
    }

    fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    fun dpInt(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

class AuroraMeshView(
    context: Context,
    private val isDarkProvider: () -> Boolean
) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
        duration = 16000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun startMotion() {
        if (!animator.isStarted) {
            animator.start()
        } else if (animator.isPaused) {
            animator.resume()
        }
    }

    fun stopMotion() {
        if (animator.isRunning) {
            animator.pause()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val dark = isDarkProvider()
        val baseColors = if (dark) {
            intArrayOf(
                Color.parseColor("#080C16"),
                Color.parseColor("#0F172A"),
                Color.parseColor("#0B1324")
            )
        } else {
            intArrayOf(
                Color.parseColor("#E8EEFF"),
                Color.parseColor("#F1F5F9"),
                Color.parseColor("#E6F7FA")
            )
        }
        bgPaint.shader = LinearGradient(0f, 0f, w, h, baseColors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val orb1X = w * (0.22f + 0.14f * cos(phase))
        val orb1Y = h * (0.16f + 0.08f * sin(phase))
        val orb1R = w * 0.65f
        val orb1Color = if (dark) Color.parseColor("#384F46E5") else Color.parseColor("#426366F1")
        drawOrb(canvas, orb1X, orb1Y, orb1R, orb1Color)

        val orb2X = w * (0.82f + 0.12f * sin(phase * 0.8f))
        val orb2Y = h * (0.42f + 0.10f * cos(phase * 0.8f))
        val orb2R = w * 0.60f
        val orb2Color = if (dark) Color.parseColor("#300EA5E9") else Color.parseColor("#3838BDF8")
        drawOrb(canvas, orb2X, orb2Y, orb2R, orb2Color)

        val orb3X = w * (0.35f + 0.15f * sin(phase * 1.2f))
        val orb3Y = h * (0.78f + 0.08f * cos(phase * 1.2f))
        val orb3R = w * 0.58f
        val orb3Color = if (dark) Color.parseColor("#2910B981") else Color.parseColor("#3034D399")
        drawOrb(canvas, orb3X, orb3Y, orb3R, orb3Color)
    }

    private fun drawOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float, centerColor: Int) {
        orbPaint.shader = RadialGradient(
            cx,
            cy,
            radius.coerceAtLeast(1f),
            intArrayOf(centerColor, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, orbPaint)
    }
}

class GlassShimmerOverlay(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var progress = -1f

    private val shimmerAnimator = ValueAnimator.ofFloat(-0.6f, 1.8f).apply {
        duration = 4200L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!shimmerAnimator.isStarted) shimmerAnimator.start()
    }

    override fun onDetachedFromWindow() {
        shimmerAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val shader = LinearGradient(
            0f,
            0f,
            w * 0.45f,
            h * 0.25f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.parseColor("#26F8FAFC"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        matrix.reset()
        matrix.setTranslate(w * progress, 0f)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        canvas.drawRoundRect(
            0f,
            0f,
            w,
            h,
            LiquidGlassKit.dp(context, 26f),
            LiquidGlassKit.dp(context, 26f),
            paint
        )
    }
}

class LiquidToggleSwitch(
    context: Context,
    initialChecked: Boolean,
    private val isDark: Boolean,
    private val onChanged: (Boolean) -> Unit
) : FrameLayout(context) {

    private var checked = initialChecked
    private val trackDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = LiquidGlassKit.dp(context, 15f)
    }
    private val thumbView: View
    private val trackWidthPx = LiquidGlassKit.dpInt(context, 50)
    private val trackHeightPx = LiquidGlassKit.dpInt(context, 30)
    private val thumbSizePx = LiquidGlassKit.dpInt(context, 24)
    private val padPx = LiquidGlassKit.dpInt(context, 3)

    init {
        layoutParams = LayoutParams(trackWidthPx, trackHeightPx)
        background = trackDrawable

        thumbView = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#F8FAFC"), Color.parseColor("#E2E8F0"))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(LiquidGlassKit.dpInt(context, 1), Color.parseColor("#CCF8FAFC"))
            }
            elevation = LiquidGlassKit.dp(context, 3f)
        }
        val thumbParams = LayoutParams(thumbSizePx, thumbSizePx).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            marginStart = padPx
        }
        addView(thumbView, thumbParams)
        applyVisualState(animate = false)

        setOnClickListener {
            checked = !checked
            applyVisualState(animate = true)
            onChanged(checked)
        }
    }

    private fun applyVisualState(animate: Boolean) {
        val offColor = if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1")
        val onColor = Color.parseColor("#2E8B57")
        val rimOff = if (isDark) Color.parseColor("#475569") else Color.parseColor("#94A3B8")
        val rimOn = Color.parseColor("#6EE7B7")
        val targetX = if (checked) (trackWidthPx - thumbSizePx - padPx * 2).toFloat() else 0f

        if (!animate) {
            trackDrawable.setColor(if (checked) onColor else offColor)
            trackDrawable.setStroke(LiquidGlassKit.dpInt(context, 1), if (checked) rimOn else rimOff)
            thumbView.translationX = targetX
            return
        }

        val startColor = if (checked) offColor else onColor
        val endColor = if (checked) onColor else offColor
        val evaluator = ArgbEvaluator()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            addUpdateListener {
                val frac = it.animatedValue as Float
                trackDrawable.setColor(evaluator.evaluate(frac, startColor, endColor) as Int)
            }
            start()
        }
        trackDrawable.setStroke(LiquidGlassKit.dpInt(context, 1), if (checked) rimOn else rimOff)

        thumbView.animate()
            .translationX(targetX)
            .scaleX(1.14f)
            .scaleY(0.92f)
            .setDuration(130L)
            .withEndAction {
                thumbView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150L)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .start()
            }
            .start()
    }
}
