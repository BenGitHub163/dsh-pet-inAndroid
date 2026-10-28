package com.dshpet.android.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * 桌宠头顶的"光辉"：一团呼吸的光晕 + 三圈向外扩散的光环，纯 Canvas 绘制（不需要素材）。
 *
 * 绘制锚点**只看窗口**，不看具体动画：
 *  圆心 = (窗口水平中心, 人物包围盒顶边 [headTopPx])。
 * 因为窗口现在就是"人物包围盒"（见 [PetWindowFactory]），包围盒顶边≈人物头顶，
 * 所以任何一段动画下光晕都落在头顶附近，不需要为每段动画单独标定。
 *
 * 历史坑（保留说明，避免再踩）：
 *  1. 相位曾用"每帧 += 0.06"，在 120Hz 屏上等于每秒 7.2 圈 → 现在按真实时间算，[PERIOD_MS] 一圈。
 *  2. 圆心曾用 0.66（那是"16:9 画布固定裁中间 42%"时代的头顶位置），几何改成包围盒后它变成了胸口，
 *     看起来就是"光辉不见了/跑到身上"。现在改成贴包围盒顶边，和裁剪逻辑彻底解耦。
 *  3. 窗口当时是纯包围盒，头顶没有多余空间，所以光环上半圈画到窗口外被裁 → 只剩下半截。
 *     现在语音运行时由 [PetWindowFactory.geometryFor] 的 `haloPx` 在头顶预留一块透明区，
 *     圆心也随之从"窗口比例"改成"包围盒顶边的绝对像素"（[setHeadAnchor]）。
 */
class PetVoiceWaveView(ctx: Context) : View(ctx) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var glowShader: RadialGradient? = null
    private var glowRadius = 0f

    /**
     * 人物包围盒顶边在窗口坐标系里的 y（像素）。
     * `-1` = 还没设置过（退回按窗口高度比例估一个，兼容旧调用点）。
     */
    private var headTopPx = -1f

    private var active = false
    private var speaking = false
    private var startedAt = 0L

    init {
        // 装饰层：不能吃触摸，否则会把桌宠的手势全挡住
        isClickable = false
        isFocusable = false
    }

    /** 由服务在 applyGeometry() 里同步：包围盒顶边 = 光环圆心 */
    fun setHeadAnchor(px: Int) {
        val v = px.coerceAtLeast(0).toFloat()
        if (v == headTopPx) return
        headTopPx = v
        buildGlow(width, height)
        invalidate()
    }

    fun setActive(on: Boolean, speakingNow: Boolean = false) {
        if (active == on && speaking == speakingNow) return
        if (on && !active) startedAt = SystemClock.uptimeMillis()   // 每次重新开口都从 0 相位起
        active = on
        speaking = speakingNow
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildGlow(w, h)
    }

    /** 圆心 y：有锚点就用锚点（包围盒顶边），否则退回窗口高度比例 */
    private fun centerY(h: Int): Float {
        val anchor = headTopPx
        if (anchor >= 0f) return anchor.coerceIn(0f, h * 0.6f)
        return h * HEAD_Y
    }

    /**
     * 光晕（径向渐变）按窗口尺寸预生成，避免每帧 new。
     * 半径同时受"圆心到窗口各边的余量"限制——光环可以顶到窗口边缘，
     * 但不能画到窗口外面去（那部分会被 WindowManager 直接裁掉，看着就是半个光晕）。
     */
    private fun buildGlow(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val cy = centerY(h)
        val room = minOf(cy, h - cy, cx, w - cx).coerceAtLeast(4f)
        glowRadius = min(min(w, h) * GLOW_SCALE, room).coerceAtLeast(6f)
        glowShader = RadialGradient(
            cx, cy, glowRadius.coerceAtLeast(1f),
            intArrayOf(
                Color.argb(170, 120, 205, 255),
                Color.argb(60, 120, 205, 255),
                Color.argb(0, 120, 205, 255),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active || width <= 0 || height <= 0) return

        val now = SystemClock.uptimeMillis()
        val t = ((now - startedAt) % PERIOD_MS).toFloat() / PERIOD_MS
        val cx = width / 2f
        val cy = centerY(height)
        val unit = min(width, height)

        // ---- 1. 呼吸光晕 ----
        val shader = glowShader ?: run { buildGlow(width, height); glowShader }
        if (shader != null) {
            val pulse = 0.88f + 0.12f * sin(t * 2f * Math.PI.toFloat())
            glowPaint.shader = shader
            glowPaint.alpha = (255 * (if (speaking) 1f else 0.72f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, glowRadius * pulse, glowPaint)
        }

        // ---- 2. 三圈扩散光环 ----
        ringPaint.strokeWidth = (unit * 0.030f).coerceAtLeast(2.5f)
        val baseR = unit * 0.10f
        // 不能画出窗口：圆心的可用半径按"到顶边/左右边/底边"的最小值算。
        // 头顶预留（haloPx）越大，这里能画出的整圈就越大。
        val maxR = minOf(
            min(cx, width - cx) - ringPaint.strokeWidth,
            cy - ringPaint.strokeWidth,
            height - cy - ringPaint.strokeWidth,
            unit * 0.75f,
        ).coerceAtLeast(baseR + 6f)

        for (i in 0 until 3) {
            val p = (t + i / 3f) % 1f
            val r = baseR + p * (maxR - baseR)
            val alpha = ((1f - p) * 230f).toInt().coerceIn(0, 255)
            ringPaint.color = if (speaking) Color.argb(alpha, 110, 240, 220)
            else Color.argb(alpha, 130, 200, 255)
            canvas.drawCircle(cx, cy, r, ringPaint)
        }

        postInvalidateOnAnimation()
    }

    private companion object {
        /** 一圈扩散用时（毫秒）：1700 ≈ 每秒 0.6 圈 */
        const val PERIOD_MS = 1700L

        /** 兜底圆心比例：只有在没调过 [setHeadAnchor] 时才会用到 */
        const val HEAD_Y = 0.16f

        /** 光晕半径相对窗口短边的比例 */
        const val GLOW_SCALE = 0.55f
    }
}
