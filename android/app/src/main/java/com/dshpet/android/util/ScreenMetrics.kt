package com.dshpet.android.util

import android.content.Context
import android.os.Build
import android.view.WindowManager

/**
 * 屏幕尺寸（物理像素）的唯一实现。
 *
 * 以前这段代码在 5 个文件里各抄了一份（`DynamicIsland`、`PetChatWindow`、`PetMenu`、
 * `QuickChat`、`SpeechBubble`，外加 `PetOverlayService` 一份变体）——
 * 修 API 版本差异时得改 6 处，漏一处就是"某些机型窗口位置不对"。
 *
 * 语义：**物理屏幕**，不是应用可用区（不含状态栏/导航条的判断，那些在调用方自己扣）。
 */
object ScreenMetrics {

    fun screenPx(ctx: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            wm?.currentWindowMetrics?.bounds?.let { return it.width() to it.height() }
        }
        @Suppress("DEPRECATION")
        val dm = ctx.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }
}
