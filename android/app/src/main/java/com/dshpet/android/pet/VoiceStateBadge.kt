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

/**
 * 语音状态提示小胶囊：**听 / 思考 / 说话** 三态。
 *
 * 为什么要单独一个窗口，而不是塞进桌宠窗口里：
 *  - 桌宠窗口尺寸 = 动画包围盒（满屏 1080x617，中档 463x264，小档 325x187），
 *    小档下窗口里根本没有放胶囊的地方；
 *  - 塞进去会被窗口裁掉（SurfaceView 不裁，但普通 View 会裁），而且会挡住人物头部。
 *  所以做成独立的小悬浮窗，贴在桌宠窗口上方（上方放不下就放下方）。
 *
 * 这个窗口是 `FLAG_NOT_TOUCHABLE` 的：点击穿透，且它很小、只显示几秒，被系统压到 0.8 也没关系。
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

    private val dotTick = object : Runnable {
        override fun run() {
            dotPhase = (dotPhase + 1) % 4
            render()
            if (isTickerState(state)) handler.postDelayed(this, 320)
        }
    }

    /** 是否处于需要"跳点"动画的状态 */
    private fun isTickerState(s: VoiceState?): Boolean =
        s is VoiceState.Listening || s is VoiceState.Thinking

    fun update(s: VoiceState?) {
        state = s
        if (s == null || s is VoiceState.Idle) {
            hide()
            return
        }
        show()
        render()
        handler.removeCallbacks(dotTick)
        if (isTickerState(s)) handler.postDelayed(dotTick, 320)
    }

    /** 桌宠窗口动了：跟着挪（只是 updateViewLayout，很便宜） */
    fun followWindow(petWinX: Int, petWinY: Int, petWinW: Int, petWinH: Int) {
        val lp = params ?: return
        val v = view ?: return
        val (sw, sh) = screenSize()
        val bw = v.measuredWidth.takeIf { it > 0 } ?: (v.layoutParams?.width ?: 0)
        val bh = v.measuredHeight.takeIf { it > 0 } ?: (v.layoutParams?.height ?: 0)
        if (bw <= 0 || bh <= 0) return
        val gap = dp(8)
        var x = petWinX + (petWinW - bw) / 2
        // 优先放上方；上方放不下就放下方；都放不下就贴屏幕上边
        var y = petWinY - bh - gap
        if (y < dp(48)) {
            val below = petWinY + petWinH + gap
            y = if (below + bh <= sh - dp(24)) below else dp(48)
        }
        x = x.coerceIn(0, (sw - bw).coerceAtLeast(0))
        y = y.coerceIn(0, (sh - bh).coerceAtLeast(0))
        if (lp.x == x && lp.y == y) return
        lp.x = x
        lp.y = y
        runCatching { wm.updateViewLayout(v, lp) }
    }

    fun hide() {
        handler.removeCallbacks(dotTick)
        val v = view ?: return
        runCatching { wm.removeViewImmediate(v) }
        view = null
        params = null
    }

    // ---------------------------------------------------------------- 内部

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
        }
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
            x = 0
            y = dp(48)
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

    private fun screenSize(): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private val COLOR_LISTENING = Color.parseColor("#3B82F6")
        private val COLOR_THINKING = Color.parseColor("#8B5CF6")
        private val COLOR_SPEAKING = Color.parseColor("#10B981")
        private val COLOR_FAILED = Color.parseColor("#6B7280")
    }
}
