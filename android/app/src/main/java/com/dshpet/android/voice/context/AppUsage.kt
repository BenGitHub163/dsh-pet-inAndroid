package com.dshpet.android.voice.context

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.util.Calendar

/**
 * 应用画像（**Provider**）：装了多少应用、今天用得最多的是哪几个。
 *
 * 权限说明（这是"桌面宠物"这类应用唯一能拿到的正当路径）：
 *  - 应用列表：Android 11+ 需要 manifest 里 `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"/>`，
 *    否则 `getInstalledApplications` 只返回"与本应用有交互"的少数应用；
 *  - 使用时长：`PACKAGE_USAGE_STATS`（特殊权限，用户在「使用情况访问」里授权）。
 *
 * 隐私处理：**不把完整应用列表塞进提示词**。只给"数量 + 今天用得最多的 3 个 + 时长"，
 * 这样既能说出"你今天在抖音刷了一个多小时"，又不会把用户装了什么全抖给第三方模型。
 */
class AppUsageProvider(private val ctx: Context) {

    fun installedAppCount(): Int = try {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(PackageManager.GET_META_DATA).size
    } catch (e: Throwable) {
        Log.w(TAG, "installedAppCount failed: ${e.message}")
        -1
    }

    /** 已安装的"知名应用"（用于闲聊："你还装了 B 站啊"），最多 6 个 */
    fun installedKnown(): List<String> = try {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val pkgs = pm.getInstalledApplications(PackageManager.GET_META_DATA).map { it.packageName }.toSet()
        UsageStatsAppSource.KNOWN.filterKeys { it in pkgs }.values.take(6)
    } catch (e: Throwable) {
        emptyList()
    }

    fun hasUsageAccess(): Boolean {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm == null) {
                false
            } else {
                val now = System.currentTimeMillis()
                !usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86_400_000L, now).isNullOrEmpty()
            }
        } catch (e: Throwable) {
            false
        }
    }

    /** 今天前台使用时长 Top N（分钟），过滤掉自己的包和不足 3 分钟的 */
    fun todayTopApps(limit: Int = 3): List<Pair<String, Int>> {
        if (!hasUsageAccess()) return emptyList()
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val start = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now) ?: return emptyList()

            val perPkg = HashMap<String, Long>()
            stats.forEach { s ->
                if (s.packageName == ctx.packageName) return@forEach
                perPkg[s.packageName] = (perPkg[s.packageName] ?: 0L) + s.totalTimeInForeground
            }
            perPkg.entries
                .filter { it.value >= 3 * 60_000L }
                .sortedByDescending { it.value }
                .take(limit)
                .map { (pkg, ms) ->
                    val label = UsageStatsAppSource.KNOWN[pkg] ?: labelOf(pkg)
                    label to (ms / 60_000L).toInt()
                }
        } catch (e: Throwable) {
            Log.w(TAG, "todayTopApps failed: ${e.message}")
            emptyList()
        }
    }

    private fun labelOf(pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    private companion object {
        const val TAG = "DshVoiceUsage"
    }
}
