package com.dshpet.android.pet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.dshpet.android.util.AppLog

/**
 * 国产 ROM「自启动白名单」的查询 + 引导。
 *
 * 为什么需要它：`BootReceiver` 收到 `BOOT_COMPLETED` 才会起服务，但小米 HyperOS / MIUI
 * **默认把第三方应用的自启动权限关掉**——广播照样发，但系统根本不会拉起本应用的进程，
 * 于是"开机自启"这个开关看起来像坏了（日志里连一行都不会有）。
 *
 * 两件事：
 *  1. [queryAutoStart]：读系统里"自启动"这个 AppOp 的真实状态（目前只有 MIUI 系有可读接口）；
 *  2. [guide]/[openAutostart]：跳系统「自启动管理」页，让用户去授权（跳不过去退应用详情）。
 */
object AutoStartHelper {

    /** MIUI / HyperOS 的「自启动」AppOp 编号（AUTO_START） */
    private const val MIUI_OP_AUTO_START = 10021

    /** 当前机型是否属于"默认限自启"的 ROM（决定设置页是否显示引导） */
    fun needsGuide(): Boolean {
        val brand = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()
        return listOf(
            "xiaomi", "redmi", "poco",      // HyperOS / MIUI
            "huawei", "honor",              // EMUI / MagicOS
            "oppo", "realme", "oneplus",    // ColorOS
            "vivo", "iqoo",                 // OriginOS
            "meizu", "samsung", "lenovo",
        ).any { brand.contains(it) }
    }

    /** 机型名（显示给用户看） */
    fun romName(): String {
        val b = Build.BRAND.ifBlank { Build.MANUFACTURER }
        return "$b ${Build.MODEL}"
    }

    private fun isMiuiFamily(): Boolean {
        val s = (Build.BRAND + " " + Build.MANUFACTURER + " " + Build.DISPLAY).lowercase()
        return s.contains("xiaomi") || s.contains("redmi") || s.contains("poco") || s.contains("miui")
    }

    /**
     * 系统「自启动」白名单是否已经允许本应用。
     *
     * 两条路（都要，因为不同 ROM 版本不一样）：
     *  1. **公开 API**：`AppOpsManager.unsafeCheckOpNoThrow("android:auto_start", …)`——
     *     有的 MIUI/HyperOS 把自启动注册成 app-op，这条能读到（也顺带试几个命名变体，见 [OP_NAMES]）；
     *  2. **老 MIUI 的隐藏 API**：`android.miui.AppOpsUtils#getApplicationEnabledSetting(ctx, pkg, 10021)`
     *     （0 = 允许）。实测 HyperOS 2（本机）已经没有这个方法了，只剩 adb 通知相关的几个，
     *     所以这条路在新机上必然失败——只是留给老机型的。
     *
     * 都读不到就返回 `null` = "无法读取"，UI 退回到"用户开关 + 手动去系统确认"，不瞎猜。
     */
    fun queryAutoStart(ctx: Context): Boolean? {
        if (!isMiuiFamily()) return null
        queryByMiuiOpCode(ctx)?.let { return it }
        queryByAppOps(ctx)?.let { return it }
        return queryByMiuiAppOpsUtils(ctx)
    }

    /**
     * **主力方案**：MIUI/HyperOS 把「自启动」注册成自己的 AppOp（编号 10021）。
     *
     * 实测本机 `dumpsys appops --package com.dshpet.android` 里就有这一行：
     *   `MIUIOP(10021) (ignore):`
     * 所以虽然 `AppOpsUtils` 的旧方法没了，**按 op 码查状态这条路仍然通**：
     * `AppOpsManager.checkOpNoThrow(10021, uid, pkg)` → MODE_ALLOWED(0) / MODE_IGNORED(1)。
     * 这个方法被 AOSP 标了 @Deprecated，新版 SDK 里可能拿不到，所以走反射。
     */
    private fun queryByMiuiOpCode(ctx: Context): Boolean? = runCatching {
        val aom = ctx.getSystemService(android.app.AppOpsManager::class.java) ?: return null
        val uid = android.os.Process.myUid()
        val m = android.app.AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        val mode = m.invoke(aom, MIUI_OP_AUTO_START, uid, ctx.packageName) as? Int ?: return null
        AppLog.log("BOOT", "MIUIOP($MIUI_OP_AUTO_START) 探测 → mode=$mode")
        when (mode) {
            android.app.AppOpsManager.MODE_ALLOWED -> true
            android.app.AppOpsManager.MODE_IGNORED,
            android.app.AppOpsManager.MODE_ERRORED,
            -> false
            else -> null
        }
    }.getOrElse { e ->
        AppLog.log("BOOT", "MIUIOP 探测失败：${e.javaClass.simpleName} ${e.message}")
        null
    }

    /** 自启动 op 在不同 ROM 上的命名变体（读不到会抛异常，被 runCatching 吃掉） */
    private val OP_NAMES = listOf(
        "android:auto_start",
        "auto_start",
        "miui:auto_start",
        "android:miui_auto_start",
    )

    private fun queryByAppOps(ctx: Context): Boolean? {
        val aom = runCatching {
            ctx.getSystemService(android.app.AppOpsManager::class.java)
        }.getOrNull() ?: return null
        val uid = android.os.Process.myUid()
        for (name in OP_NAMES) {
            val mode = runCatching { aom.unsafeCheckOpNoThrow(name, uid, ctx.packageName) }
                .getOrNull() ?: continue
            AppLog.log("BOOT", "自启动 op 探测 $name → mode=$mode")
            when (mode) {
                android.app.AppOpsManager.MODE_ALLOWED -> return true
                android.app.AppOpsManager.MODE_IGNORED,
                android.app.AppOpsManager.MODE_ERRORED,
                -> return false
                // DEFAULT / FOREGROUND 这类"没明确说法"的就不下结论，继续试下一个
            }
        }
        return null
    }

    private fun queryByMiuiAppOpsUtils(ctx: Context): Boolean? = runCatching {
        val clz = Class.forName("android.miui.AppOpsUtils")
        val m = clz.getDeclaredMethod(
            "getApplicationEnabledSetting",
            Context::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        m.isAccessible = true
        val v = m.invoke(null, ctx, ctx.packageName, MIUI_OP_AUTO_START) as? Int ?: return null
        v == 0
    }.getOrElse { e ->
        AppLog.log("BOOT", "查询自启动权限失败：${e.javaClass.simpleName} ${e.message}")
        null
    }

    /** 给 UI 用的一句话状态 */
    fun grantedText(granted: Boolean?): String = when (granted) {
        true -> "已允许"
        false -> "未允许"
        null -> "无法读取（请到系统里确认）"
    }

    /** 开机自启广播接收器的启停（只有"用户想开 + 系统允许"时才需要它） */
    fun setBootReceiverEnabled(ctx: Context, enabled: Boolean) {
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, "${ctx.packageName}.pet.BootReceiver"),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            AppLog.log("BOOT", "BootReceiver enabled=$enabled")
        }
    }

    /**
     * 直接跳到系统的「自启动管理」页。
     * @return true = 已跳转；false = 本 ROM 没有这个页面（调用方可以退回应用详情）
     */
    fun openAutostart(ctx: Context): Boolean {
        val candidates = listOf(
            // HyperOS / MIUI 14+：安全中心 → 授权管理 → 自启动管理
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.permcenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 华为 EMUI
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            // 荣耀
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            // OPPO / ColorOS
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            // vivo
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            // 魅族
            "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
        )
        for ((pkg, cls) in candidates) {
            val ok = runCatching {
                val i = Intent().apply {
                    component = ComponentName(pkg, cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(i)
            }.isSuccess
            if (ok) {
                AppLog.u("桌宠", "已打开自启动管理：$pkg/$cls")
                return true
            }
        }
        AppLog.log("BOOT", "本机没有可直接打开的自启动页，退回应用详情")
        return false
    }

    /** 应用详情页（自启动页跳不过去时的兜底：用户在这里能找到"自启动/后台"开关） */
    fun openAppDetails(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { AppLog.log("BOOT", "打开应用详情失败：${it.message}") }
    }

    /** 一键引导：先试自启动页，跳不过去退应用详情。返回是否跳到了自启动页 */
    fun guide(ctx: Context): Boolean = openAutostart(ctx).also { if (!it) openAppDetails(ctx) }
}
