package com.dshpet.android.compat.autostart

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.dshpet.android.util.AppLog

/**
 * 国产 ROM「自启动白名单」：查询真实权限 + 跳到系统页让用户改。
 *
 * 业务层只应调用 [queryAutoStart]、[switchChecked]、[statusLine]、[applyUserChoice]。
 * BootReceiver 跟用户意愿走；开关显示跟系统权限走（读不到时才退回用户意愿）。
 */
object AutoStartHelper {

    private const val MIUI_OP_AUTO_START = 10021

    fun needsGuide(): Boolean {
        val brand = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()
        return listOf(
            "xiaomi", "redmi", "poco",
            "huawei", "honor",
            "oppo", "realme", "oneplus",
            "vivo", "iqoo",
            "meizu", "samsung", "lenovo",
        ).any { brand.contains(it) }
    }

    fun romName(): String {
        val b = Build.BRAND.ifBlank { Build.MANUFACTURER }
        return "$b ${Build.MODEL}"
    }

    /**
     * 系统是否已允许本应用自启动。
     * true / false = 读到了；null = 这台机读不到，UI 只能按用户开关显示。
     */
    fun queryAutoStart(ctx: Context): Boolean? {
        queryByMiuiOpCode(ctx)?.let { return it }
        queryByAppOps(ctx)?.let { return it }
        queryByMiuiAppOpsUtils(ctx)?.let { return it }
        return null
    }

    private fun queryByMiuiOpCode(ctx: Context): Boolean? = runCatching {
        val aom = ctx.getSystemService(android.app.AppOpsManager::class.java) ?: return null
        val m = android.app.AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        val mode = m.invoke(aom, MIUI_OP_AUTO_START, Process.myUid(), ctx.packageName) as? Int ?: return null
        AppLog.log("BOOT", "MIUIOP($MIUI_OP_AUTO_START) 探测 → mode=$mode")
        modeToBool(mode)
    }.getOrElse { e ->
        AppLog.log("BOOT", "MIUIOP 探测失败：${e.javaClass.simpleName} ${e.message}")
        null
    }

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
        val uid = Process.myUid()
        for (name in OP_NAMES) {
            val mode = runCatching { aom.unsafeCheckOpNoThrow(name, uid, ctx.packageName) }
                .getOrNull() ?: continue
            AppLog.log("BOOT", "自启动 op 探测 $name → mode=$mode")
            modeToBool(mode)?.let { return it }
        }
        return null
    }

    private fun modeToBool(mode: Int): Boolean? = when (mode) {
        android.app.AppOpsManager.MODE_ALLOWED -> true
        android.app.AppOpsManager.MODE_IGNORED,
        android.app.AppOpsManager.MODE_ERRORED,
        -> false
        else -> null
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
        AppLog.log("BOOT", "AppOpsUtils 查询失败：${e.javaClass.simpleName} ${e.message}")
        null
    }

    fun grantedText(granted: Boolean?): String = when (granted) {
        true -> "已允许"
        false -> "未允许"
        null -> "无法读取（请到系统里确认）"
    }

    /** 开关是否点亮：能读系统权限就跟系统走，读不到才跟用户意愿。 */
    fun switchChecked(wanted: Boolean, granted: Boolean?): Boolean = granted ?: wanted

    fun statusLine(wanted: Boolean, granted: Boolean?): String = when (granted) {
        true -> "系统已允许自启动，开机后会自动显示桌宠"
        false -> "系统未允许自启动，打开开关将跳到系统设置"
        null -> if (wanted) {
            if (needsGuide()) "已开启接收器｜请在${romName()}的自启动管理里允许本应用"
            else "开机后自动显示桌宠"
        } else {
            if (needsGuide()) "关闭后重启不会自动出现｜点开将跳到系统自启动页"
            else "关闭后重启手机不会自动出现桌宠"
        }
    }

    /**
     * 用户拨动开关：
     *  - 立刻启停 [BootReceiver]（按意愿，避免授权后没回到本页就重启时接收器还是关的）；
     *  - 只要系统状态还没对齐用户意愿，就跳到系统自启动页。
     * @return 是否已经打开了系统页
     */
    fun applyUserChoice(ctx: Context, wanted: Boolean, granted: Boolean? = queryAutoStart(ctx)): Boolean {
        setBootReceiverEnabled(ctx, wanted)
        val needSettings = when (granted) {
            true -> !wanted
            false -> wanted
            null -> wanted && needsGuide()
        }
        if (!needSettings) return false
        val opened = guide(ctx)
        AppLog.u("桌宠", if (opened) "已打开系统自启动页，请允许「小肥鱼」" else "未能打开自启动页，已退到应用详情")
        return true
    }

    fun setBootReceiverEnabled(ctx: Context, enabled: Boolean) {
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, com.dshpet.android.pet.BootReceiver::class.java),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            AppLog.log("BOOT", "BootReceiver enabled=$enabled")
        }
    }

    fun openAutostart(ctx: Context): Boolean {
        val pkg = ctx.packageName
        val extras = listOf(
            "package_name" to pkg,
            "packageName" to pkg,
            "extra_pkgname" to pkg,
            "extra_package" to pkg,
        )

        val components = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.permcenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupAwakedAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.optimize.process.ProtectActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.sm" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        )
        for ((p, cls) in components) {
            val intent = Intent().apply {
                component = ComponentName(p, cls)
                extras.forEach { (k, v) -> putExtra(k, v) }
            }
            if (launch(ctx, intent)) {
                AppLog.u("桌宠", "已打开自启动管理：$p/$cls")
                return true
            }
        }

        val implicit = listOf(
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST"),
            Intent("huawei.intent.action.HSM_BOOTAPP_MANAGER"),
            Intent("huawei.intent.action.HSM_PROTECTED_APPS"),
            Intent("oppo.intent.action.STARTUP_MANAGER"),
            Intent("com.coloros.safecenter.permission.startup"),
            Intent("iqoo.permissionmanager.action.POWER_SAVE"),
        )
        for (raw in implicit) {
            extras.forEach { (k, v) -> raw.putExtra(k, v) }
            if (launch(ctx, raw)) {
                AppLog.u("桌宠", "已打开自启动管理：${raw.action}")
                return true
            }
        }
        AppLog.log("BOOT", "本机没有可直接打开的自启动页，退回应用详情")
        return false
    }

    fun openAppDetails(ctx: Context) {
        val ok = launch(
            ctx,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
        )
        if (!ok) AppLog.log("BOOT", "打开应用详情失败")
    }

    /** 先试自启动页，跳不过去退应用详情。 */
    fun guide(ctx: Context): Boolean = openAutostart(ctx).also { if (!it) openAppDetails(ctx) }

    private fun launch(ctx: Context, intent: Intent): Boolean {
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            ctx.startActivity(intent)
            true
        }.getOrElse { false }
    }
}
