package com.dshpet.android.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View

/**
 * 悬浮窗描边（调试用）：把窗口的**真实矩形**画出来。
 *
 * 存在的意义：桌宠窗口比人物大一圈（左右各约 20% 是透明边距），平时完全看不出来
 * 这块矩形有多大、在哪——这正是"调不了大小"、"点不到桌宠"、"出生在角落"这些
 * 问题的共同根源（Android 悬浮窗整块矩形都吃触摸，透明边距也算）。
 * 打开这个开关就能直接看到边界，调参数时不用再猜。
 *
 * 绘制内容：
 *  - 虚线边框 = 窗口边界
 *  - 中心十字 = 窗口中心
 *  - 左上角文字 = 窗口尺寸 + 缩放档位
 *
 * 层级：在 [PetVoiceWaveView] 之后加入容器，所以画在最上层；
 * 它本身不接收触摸（`isClickable = false`），不影响手势。
 */
class WindowBorderView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    /** 左上角标签，由服务在尺寸变化时更新 */
    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF00E5FF.toInt()          // 青色，跟桌宠配色区分度高
        pathEffect = DashPathEffect(floatArrayOf(9f * density, 6f * density), 0f)
    }

    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC000000.toInt()
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        textSize = 11f * density
        isFakeBoldText = true
    }

    init {
        isClickable = false
        isFocusable = false
        // 纯装饰层：不参与命中测试
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return

        val inset = stroke.strokeWidth / 2f
        canvas.drawRect(inset, inset, width - inset, height - inset, stroke)

        // 中心十字：用来判断"人物是不是真的在窗口中间"
        val cx = width / 2f
        val cy = height / 2f
        val arm = 8f * density
        canvas.drawLine(cx - arm, cy, cx + arm, cy, stroke)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, stroke)

        if (label.isNotEmpty()) {
            val pad = 5f * density
            val textW = labelPaint.measureText(label)
            val textH = labelPaint.textSize
            val left = 4f * density
            val top = 4f * density
            canvas.drawRect(left, top, left + textW + pad * 2, top + textH + pad * 1.4f, labelBg)
            canvas.drawText(label, left + pad, top + textH * 1.1f, labelPaint)
        }
    }
}
