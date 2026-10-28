package com.dshpet.android.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.dshpet.android.data.PetConfig
import com.dshpet.android.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机自启：`BOOT_COMPLETED` 后若用户开启了"开机自启"且已授予悬浮窗权限，
 * 自动启动桌宠服务（对应桌面端开机自启功能）。
 *
 * 注意两点（都是踩过的坑）：
 *  1. **组件默认是 disabled 的**（见 AndroidManifest 的 `android:enabled="false"`）：
 *     只有用户在设置里打开"开机自启"时才会被 [com.dshpet.android.MainActivity] / [com.dshpet.android.PetApp]
 *     通过 `setComponentEnabledSetting` 打开。这样没开开关的用户连进程都不会被拉起，也就不会多耗电。
 *  2. **国产 ROM 还会发 `QUICKBOOT_POWERON`**（快速开机/假关机再起），只监听 BOOT_COMPLETED 会漏。
 *     另外广播回调里不能做长任务，要用 [goAsync] 把进程留住，否则刚 launch 就被回收。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!isBootAction(intent.action)) return
        val app = context.applicationContext
        // 保住进程直到协程跑完（DataStore 读取 + startForegroundService 都是异步的）
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cfg = PetConfig.get(app)
                val auto = cfg.autoStart()
                val canOverlay = Settings.canDrawOverlays(app)
                AppLog.log("BOOT", "收到 ${intent.action}：开机自启=$auto 悬浮窗权限=$canOverlay")
                if (auto && canOverlay) {
                    val ok = PetOverlayService.ensureRunning(app, persist = true)
                    val what = when (intent.action) {
                        Intent.ACTION_BOOT_COMPLETED -> "开机"
                        Intent.ACTION_MY_PACKAGE_REPLACED -> "更新后"
                        else -> "快速开机"
                    }
                    if (ok) AppLog.u("开机自启", "已${what}自动显示桌宠")
                    else AppLog.u("开机自启", "${what}自启失败：系统拦截了自启动（去自启动管理里允许）")
                } else if (auto) {
                    AppLog.u("开机自启", "缺少悬浮窗权限，未能自启")
                }
            } catch (e: Throwable) {
                AppLog.log("BOOT", "自启异常：${e.javaClass.simpleName} ${e.message}")
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    private fun isBootAction(action: String?): Boolean = when (action) {
        Intent.ACTION_BOOT_COMPLETED,
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON",
        Intent.ACTION_MY_PACKAGE_REPLACED,
        -> true
        else -> false
    }
}
