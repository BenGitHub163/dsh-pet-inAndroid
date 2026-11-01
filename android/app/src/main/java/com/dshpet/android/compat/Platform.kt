package com.dshpet.android.compat

import android.os.Build

/**
 * 给**界面层**（设置页 / 菜单 / 聊天窗）用的平台能力快照。
 *
 * 存在的意义：业务代码里不该再出现 `Build.VERSION.SDK_INT >= 31` 这种字面量。
 * 判断"某能力有没有"时调用这里；真正的机型/窗口策略见 [WindowPolicy]。
 *
 * 注意：这里只放**能力**判断（有/没有），不放**策略**（有的话该怎么做）。
 */
object Platform {

    val sdk: Int get() = Build.VERSION.SDK_INT

    /** 真毛玻璃（`RenderEffect` / `Modifier.blur`）：Android 12（API 31）起 */
    val blurSupported: Boolean get() = sdk >= Build.VERSION_CODES.S

    /** `fitInsetsTypes = 0`（窗口坐标 = 整屏坐标）：Android 11（API 30）起 */
    val fitInsetsSupported: Boolean get() = sdk >= Build.VERSION_CODES.R

    /** 能读写 `WindowManager.LayoutParams#setBackgroundBlurRadius`：Android 12 起 */
    val windowBlurSupported: Boolean get() = sdk >= Build.VERSION_CODES.S

    /** 通知渠道：Android 8 起 */
    val notificationChannelSupported: Boolean get() = sdk >= Build.VERSION_CODES.O
}
