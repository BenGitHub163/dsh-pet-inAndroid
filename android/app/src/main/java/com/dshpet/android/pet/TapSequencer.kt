package com.dshpet.android.pet

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs

/**
 * 串联式多击识别：1 / 2 / 3 击互斥，永远不会"先提交单击再提交双击"。
 *
 * 两条铁律：
 *  1) 每一下抬起只做计数与即时反馈，语义动作一律延迟到 settle；
 *  2) 位移、时长、多指任一越界，整串作废（不是只丢这一下）。
 */
class TapSequencer(
    private val listener: Listener,
    private val slop: Float,
    private val now: () -> Long = SystemClock::uptimeMillis,
    schedule: ((Runnable, Long) -> Unit)? = null,
    unschedule: ((Runnable) -> Unit)? = null,
) {

    interface Listener {
        /**
     * 点击「反馈」钩子：默认空实现。
     * 动画 / 台词 / 音效由业务侧 onTap() 负责，这里再触发一次就会响两次。
     */
    fun onTapFeedback(count: Int) {}
        fun onTapCommitted(count: Int)
    }

    data class Timing(
        val gapMs: Long = 260L,
        val maxTapMs: Long = 240L,
        val tailGuardMs: Long = 400L,
        val dedupeMs: Long = 600L,
        val maxCount: Int = 3,
    )

    var timing: Timing = Timing()

    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val postAt: (Runnable, Long) -> Unit = schedule ?: { r, d -> main.postDelayed(r, d) }
    private val unpost: (Runnable) -> Unit = unschedule ?: { r -> main.removeCallbacks(r) }

    private var count = 0
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dirty = false
    private var guardUntil = 0L
    private var lastCommitAt = 0L
    private var lastCommitCount = 0

    private val settle = Runnable { settleNow() }

    private fun settleNow() {
        val n = count
        count = 0
        if (n == 0) return
        val t = now()
        val capped = n.coerceAtMost(timing.maxCount)
        if (capped >= timing.maxCount) guardUntil = t + timing.tailGuardMs
        val dup = capped >= 2 && capped == lastCommitCount && t - lastCommitAt < timing.dedupeMs
        if (dup) return
        lastCommitAt = t
        lastCommitCount = capped
        listener.onTapCommitted(capped)
    }

    fun onDown(x: Float, y: Float) {
        val t = now()
        if (t < guardUntil) { cancelAll(); return }
        unpost(settle)
        downX = x
        downY = y
        downAt = t
        dirty = false
    }

    fun onMove(x: Float, y: Float) {
        if (dirty) return
        if (abs(x - downX) > slop || abs(y - downY) > slop) cancelAll()
    }

    fun onPointerDown() = cancelAll()

    fun onUp(x: Float, y: Float, pointerCount: Int) {
        val t = now()
        if (dirty || pointerCount != 1 || t - downAt > timing.maxTapMs) { cancelAll(); return }
        onMove(x, y)
        if (dirty) return
        count++
        listener.onTapFeedback(count)
        unpost(settle)
        if (count >= timing.maxCount) settle.run() else postAt(settle, timing.gapMs)
    }

    fun cancelAll() {
        unpost(settle)
        count = 0
        dirty = true
    }

    fun release() {
        unpost(settle)
        count = 0
    }
}
