package com.dshpet.android.pet

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import com.dshpet.android.data.PetConfig
import com.dshpet.android.util.ProvideComposeHost
import com.dshpet.android.util.attachComposeHost
import com.dshpet.android.util.mdBlur
import kotlin.math.roundToInt

/**
 * 桌宠气语气泡：独立的**非触控**悬浮窗口（`FLAG_NOT_TOUCHABLE`），锚定在桌宠正上方，
 * 放不下时自动翻到下方。
 *
 * 谁在用：`PetOverlayService.showBubble()`（余额/更新/错误提示）、自言自语定时器、
 * Agent 联动状态播报、语音页的"先给我麦克风权限吧"。
 * 支持多种气泡样式与可选的毛玻璃背景（默认关，Android 12+ 才有真模糊）。
 */
class SpeechBubble(
    private val ctx: Context,
    private val engine: PetEngine,
    private val config: PetConfig,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var hideRunnable: Runnable? = null
    /** 由服务侧更新的配置缓存（服务在协程里读取 DataStore 后写入） */
    @Volatile
    var bubbleStyle: String = "classic_top"
    /** 自定义气泡底色（"#AARRGGBB"；空串 = 跟随 [bubbleStyle] 自带颜色） */
    @Volatile
    var bubbleColor: String = ""
    @Volatile
    var blurOn: Boolean = false

    fun showText(text: String, durationMs: Long = 6000) {
        hideRunnable?.let { handler.removeCallbacks(it) }
        if (view == null) {
            val composeView = ComposeView(ctx).apply {
                attachComposeHost()
                setContent {
                    ProvideComposeHost {
                    BubbleContent(
                        text = text,
                        style = bubbleStyle,
                        blur = blurOn,
                        customColor = bubbleColor,
                    )
                    }
                }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            view = composeView
            params = lp
            runCatching { wm.addView(composeView, lp) }
        } else {
            (view as ComposeView).setContent {
                ProvideComposeHost {
                    BubbleContent(
                        text = text,
                        style = bubbleStyle,
                        blur = blurOn,
                        customColor = bubbleColor,
                    )
                }
            }
        }
        view?.visibility = View.VISIBLE
        // 等布局完成后再定位（首次 measure 前尺寸为 0）
        view?.post { reposition() }
        hideRunnable = Runnable { hide() }
        handler.postDelayed(hideRunnable!!, durationMs.coerceAtLeast(1000))
    }

    fun hide() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        view?.visibility = View.GONE
    }

    fun dismiss() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    /** 锚定：气泡底边中心 = 桌宠顶边中心（全物理像素） */
    private fun reposition() {
        val v = view ?: return
        val lp = params ?: return
        v.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val bw = v.measuredWidth
        val bh = v.measuredHeight
        val (sw, sh) = screenPx()
        val petCenterX = engine.winX + engine.winW / 2
        var x = petCenterX - bw / 2
        x = x.coerceIn(8, maxOf(8, sw - bw - 8))
        var y = engine.winY - bh - 8
        if (y < 0) y = engine.winY + engine.winH + 8 // 顶部放不下则放下方
        y = y.coerceIn(0, maxOf(0, sh - bh))
        lp.x = x
        lp.y = y
        runCatching { wm.updateViewLayout(v, lp) }
    }

    /** 屏幕尺寸：统一走 [com.dshpet.android.util.ScreenMetrics]，别再抄一份实现 */
    private fun screenPx(): Pair<Int, Int> = com.dshpet.android.util.ScreenMetrics.screenPx(ctx)

    /**
     * 气泡本体。
     *
     * @param customColor 用户自定义底色（"#AARRGGBB"）。非空时**覆盖**样式的背景色，
     *   文字色按底色亮度自动取深/浅（见 [readableTextOn]），样式仍然决定圆角与尾巴形状。
     */
    @Composable
    private fun BubbleContent(text: String, style: String, blur: Boolean, customColor: String = "") {
        var visible by androidx.compose.runtime.remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { visible = true }
        val base = when (style) {
            "soft_blue_top" -> BubbleStyle(Color(0xFFD6E6FF), Color(0xFF1B3A6B), 14f, true)
            "glass_right" -> BubbleStyle(Color(0xCCFFFFFF), Color(0xFF22272E), 16f, true)
            "breath_bubble" -> BubbleStyle(Color(0xFFE8F5FF), Color(0xFF12355B), 22f, true)
            "paper_left" -> BubbleStyle(Color(0xFFFFF8E1), Color(0xFF5D4A1E), 12f, false)
            else -> BubbleStyle(Color(0xFFFFFFFF), Color(0xFF2B2F36), 14f, true) // classic_top
        }
        val customArgb = parseArgb(customColor)
        val (bg, fg, radius, tail) = if (customArgb != null) {
            BubbleStyle(Color(customArgb), readableTextOn(customArgb), base.radius, base.tail)
        } else {
            base
        }
        Box(
            modifier = Modifier
                .width(230.dp)
                .padding(8.dp)
                .shadow(6.dp, RoundedCornerShape(radius.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(radius.dp))
                    .background(
                        // 自定义颜色优先：此时不再用"毛玻璃的固定渐变"（否则用户选的颜色看不出来）
                        if (blur && customArgb == null) Brush.linearGradient(listOf(Color(0xCCFFFFFF), Color(0xCCEAF2FF)))
                        else androidx.compose.ui.graphics.SolidColor(bg)
                    )
                    .mdBlur(blur, radius = 18)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = text,
                    color = fg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 气泡尾巴
            if (tail) {
                Canvas(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp)
                ) {
                    val w = size.width
                    val p = Path().apply {
                        moveTo(w / 2 - 10.dp.toPx(), 0f)
                        lineTo(w / 2 + 10.dp.toPx(), 0f)
                        lineTo(w / 2, 10.dp.toPx())
                        close()
                    }
                    drawPath(p, bg)
                }
            }
        }
    }

    private data class BubbleStyle(
        val bg: Color,
        val fg: Color,
        val radius: Float,
        val tail: Boolean,
    )
}

/** "#AARRGGBB" / "#RRGGBB" → 0xAARRGGBB（不合法返回 null） */
internal fun parseArgb(hex: String): Int? {
    val s = hex.trim().removePrefix("#")
    if (s.length != 6 && s.length != 8) return null
    val v = s.toLongOrNull(16) ?: return null
    return if (s.length == 6) (0xFF000000L or v).toInt() else v.toInt()
}

/** 按底色亮度自动选深/浅文字色，保证自定义颜色之后字还看得清 */
internal fun readableTextOn(argb: Int): Color {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return if (lum > 0.6) Color(0xFF2B2F36) else Color(0xFFF7FAFF)
}
