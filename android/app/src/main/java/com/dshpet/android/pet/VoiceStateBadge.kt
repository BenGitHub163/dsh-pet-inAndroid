package com.dshpet.android.pet

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.dshpet.android.voice.core.VoiceState
import kotlin.math.max

/**
 * 语音状态提示小胶囊：**听 / 思考 / 说话** 三态。
 *
 * 为什么要单独一个窗口，而不是塞进桌宠窗口里：
 *  - 桌宠窗口尺寸 = 动画包围盒（满屏 1080x617，中档 463x264，小档 325x187），
 *    小档下窗口里根本没有放胶囊的地方；
 *  - 塞进去会被窗口裁掉，而且会挡住人物头部。
 *  所以做成独立的小悬浮窗，贴在桌宠窗口上方（上方放不下就放下方）。
 *
 * **首帧定位**：窗口必须在 `addView` 之前就把 x/y 算好并写进 LayoutParams，
 * 否则会先在 (0, dp(48)) 闪一下再跳到桌宠头顶（用户实测过这个跳变）。
 * 所以这里先 `measure()` 出真实尺寸 → 算位置 → 再 addView。
 */
class VoiceStateBadge(context: Context) {

    private val ctx = context
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 当前状态（null = 隐藏） */
    private var state: VoiceState? = null
    private var dotPhase = 0

    /** 最近一次知道的桌宠窗口矩形（"首帧定位"要靠它，否则不知道往哪贴） */
    private var petX = Int.MIN_VALUE
    private var petY = 0
    private var petW = 0
    private var petH = 0

    private val dotTick = object : Runnable {
        override fun run() {
            dotPhase = (dotPhase + 1) % 4
            render()
            if (isTickerState(state)) handler.postDelayed(this, 320)
        }
    }

    private fun isTickerState(s: VoiceState?): Boolean =
        s is VoiceState.Listening || s is VoiceState.Thinking

    /**
     * 更新状态。[x]/[y]/[w]/[h] 是**桌宠窗口**的矩形：首帧要用它在 addView 前把位置算好。
     */
    fun update(s: VoiceState?, x: Int = Int.MIN_VALUE, y: Int = 0, w: Int = 0, h: Int = 0) {
        if (x != Int.MIN_VALUE) {
            petX = x; petY = y; petW = w; petH = h
        }
        state = s
        if (s == null || s is VoiceState.Idle) {
            hide()
            return
        }
        show()          // 内部会用已经算好的 pet* 做首帧定位
        render()
        handler.removeCallbacks(dotTick)
        if (isTickerState(s)) handler.postDelayed(dotTick, 320)
    }

    /** 桌宠窗口动了：跟着挪（只是 updateViewLayout，很便宜；没显示时直接返回） */
    fun followWindow(x: Int, y: Int, w: Int, h: Int) {
        petX = x; petY = y; petW = w; petH = h
        val v = view ?: return
        recenter(v.measuredWidth.takeIf { it > 0 } ?: v.width, v.measuredHeight.takeIf { it > 0 } ?: v.height)
    }

    fun hide() {
        handler.removeCallbacks(dotTick)
        val v = view ?: return
        runCatching { wm.removeViewImmediate(v) }
        view = null
        params = null
    }

    // ---------------------------------------------------------------- 内部

    /** 计算胶囊左上角：优先桌宠上方，其次下方，最后贴屏幕上沿 */
    private fun computePos(bw: Int, bh: Int): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        val sw = m.widthPixels
        val sh = m.heightPixels
        val gap = dp(8)
        var x = if (petX != Int.MIN_VALUE) petX + (petW - bw) / 2 else (sw - bw) / 2
        var y = if (petX != Int.MIN_VALUE) petY - bh - gap else dp(48)
        if (y < dp(48)) {
            val below = petY + petH + gap
            y = if (below + bh <= sh - dp(24)) below else dp(48)
        }
        x = x.coerceIn(0, max(0, sw - bw))
        y = y.coerceIn(0, max(0, sh - bh))
        return x to y
    }

    private fun recenter(bw: Int, bh: Int) {
        val lp = params ?: return
        val v = view ?: return
        if (bw <= 0 || bh <= 0) return
        val (x, y) = computePos(bw, bh)
        if (lp.x == x && lp.y == y) return
        lp.x = x
        lp.y = y
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun show() {
        if (view != null) return
        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(COLOR_LISTENING)
            }
            elevation = dp(6).toFloat()
            text = "我在听"
        }
        // 文字长度会变（"我在听" ↔ "让我想想"），窗口是 WRAP_CONTENT：
        // 尺寸一变就重新居中，否则胶囊会往右"长"出去。
        tv.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            val w = r - l
            val h = b - t
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            if (w == or - ol && h == ob - ot) return@addOnLayoutChangeListener
            recenter(w, h)
        }
        // **先量再放**：这样 addView 的第一帧就在正确位置上，不会在左上角闪一下
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val bw = tv.measuredWidth.coerceAtLeast(dp(80))
        val bh = tv.measuredHeight.coerceAtLeast(dp(30))
        val (x0, y0) = computePos(bw, bh)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = x0
            y = y0
        }
        view = tv
        params = lp
        runCatching { wm.addView(tv, lp) }
    }

    private fun render() {
        val tv = view ?: return
        val s = state ?: return
        val (text, color) = when (s) {
            is VoiceState.Listening -> {
                val partial = s.partial.trim()
                val dots = ".".repeat(dotPhase)
                if (partial.isEmpty()) "我在听$dots" to COLOR_LISTENING
                else "${partial.take(12)}$dots" to COLOR_LISTENING
            }
            is VoiceState.Thinking -> "让我想想${".".repeat(dotPhase)}" to COLOR_THINKING
            is VoiceState.Speaking -> "说话中" to COLOR_SPEAKING
            is VoiceState.Failed -> (s.reason.take(14).ifBlank { "出了点问题" }) to COLOR_FAILED
            VoiceState.Idle -> return
        }
        tv.text = text
        (tv.background as? GradientDrawable)?.setColor(color)
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private val COLOR_LISTENING = Color.parseColor("#3B82F6")
        private val COLOR_THINKING = Color.parseColor("#8B5CF6")
        private val COLOR_SPEAKING = Color.parseColor("#10B981")
        private val COLOR_FAILED = Color.parseColor("#6B7280")
    }
}
