package com.dshpet.android.pet

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewConfiguration

/**
 * 单击 / 双击 / 三击识别器（进程级单例，多桌宠实例共用一套状态）。
 *
 * 时间参数取自 Android 平台（ViewConfiguration）：
 *   - DOUBLE_TAP_TIMEOUT = 300ms  ：两击之间允许的最大间隔
 *   - DOUBLE_TAP_MIN_TIME = 40ms  ：小于此值视为抖动
 *   - MULTI_TAP_TIMEOUT  = 300ms  ：无障碍"三击放大"用的多击间隔
 *   - TAP_TIMEOUT        = 100ms  ：单击判定
 *
 * 三击判定采用"节奏相似"规则（tap tempo 思路）：
 *   已有两击、间隔 d1；第三击间隔 d2
 *   若 d2 <= d1 + DEVIATION(250ms) 且 d2 >= MIN(40ms) → 判为三击
 * 任意一击后等待 (当前间隔 + 偏离) 仍无下一击 → 按已有击数结算。
 */
object TapRecognizer {

    private const val DEVIATION_MS = 250L
    private const val MIN_GAP = 40L   // ViewConfiguration.getDoubleTapMinTime() 是隐藏 API
    private val DOUBLE_MAX = ViewConfiguration.getDoubleTapTimeout().toLong()   // 300ms
    private const val TRIPLE_TOTAL_MAX = 1200L                                   // 三击总跨度上限

    private val handler = Handler(Looper.getMainLooper())
    private var count = 0
    private var firstAt = 0L
    private var lastAt = 0L
    private var lastGap = 0L
    private var pending: Runnable? = null

    fun onTap(onSingle: () -> Unit, onDouble: () -> Unit, onTriple: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        val gap = if (count == 0) 0L else now - lastAt

        if (count > 0 && (gap < MIN_GAP || gap > DOUBLE_MAX + DEVIATION_MS)) {
            // 间隔太短（抖动）→ 忽略；间隔太长（超时）→ 先把上一组结算掉
            if (gap < MIN_GAP) return
            settle(onSingle, onDouble, onTriple)
        }

        if (count == 0) firstAt = now
        count++
        lastGap = gap
        lastAt = now
        android.util.Log.i("DshTap", "tap#$count gap=${gap}ms")

        pending?.let { handler.removeCallbacks(it) }

        val total = now - firstAt
        if (count >= 3 && total <= TRIPLE_TOTAL_MAX) {
            count = 0; pending = null
            android.util.Log.i("DshTap", "=> TRIPLE (total=${total}ms, gap=${gap}ms)")
            onTriple()
            return
        }

        // 等待下一击的超时：间隔相似上限；第一击后用双击上限
        val wait = when (count) {
            1 -> DOUBLE_MAX + DEVIATION_MS      // 550ms 等第二击
            else -> (lastGap + DEVIATION_MS).coerceAtLeast(MIN_GAP)
        }
        val r = Runnable {
            val n = count
            count = 0; pending = null
            android.util.Log.i("DshTap", "=> settle n=$n")
            when (n) {
                1 -> onSingle()
                2 -> onDouble()
                else -> onTriple()
            }
        }
        pending = r
        handler.postDelayed(r, wait)
    }

    private fun settle(onSingle: () -> Unit, onDouble: () -> Unit, onTriple: () -> Unit) {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        val n = count
        count = 0
        if (n <= 0) return
        android.util.Log.i("DshTap", "=> settle n=$n (pre)")
        when (n) {
            1 -> onSingle()
            2 -> onDouble()
            else -> onTriple()
        }
    }
}
