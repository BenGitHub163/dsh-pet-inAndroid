package com.dshpet.android.voice.context

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * 「当前前台应用」的**双路实现**（Strategy + Fallback Chain）。
 *
 * 路线 A（手机支持）：UsageStatsManager —— 需要用户在设置里授予「使用情况访问权限」。
 * 路线 B（手机不支持 / 没授权）：返回 null，上层自动降级为"不提供应用信息"，
 *                              其余感知（时间/电量/亮度/内存）照常，功能不崩。
 *
 * 这是本项目统一的"两块"范式：
 *   接口 = 能力；实现 = 原生 + 兜底；选择 = 运行时探测，而不是 try/catch 猜。
 */
interface ForegroundAppSource {
    val id: String

    /** 当前是否可用（权限/系统版本/厂商裁剪） */
    fun isAvailable(): Boolean

    /** 前台应用（包名 + 展示名），拿不到返回 null */
    fun current(): AppSnapshot?
}

data class AppSnapshot(val packageName: String, val label: String)

/** 路线 A：系统使用情况统计 */
class UsageStatsAppSource(private val ctx: Context) : ForegroundAppSource {

    override val id: String = "usage-stats"

    override fun isAvailable(): Boolean {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm == null) {
                false
            } else {
                val now = System.currentTimeMillis()
                // 没授权时 queryUsageStats 返回空列表（不抛异常），只能这样探测
                !usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86_400_000L, now).isNullOrEmpty()
            }
        } catch (e: Throwable) {
            false
        }
    }

    override fun current(): AppSnapshot? {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm == null) {
                null
            } else {
                val now = System.currentTimeMillis()
                val events = usm.queryEvents(now - 5 * 60_000L, now)
                if (events == null) {
                    null
                } else {
                    val e = UsageEvents.Event()
                    var pkg: String? = null
                    while (events.hasNextEvent()) {
                        events.getNextEvent(e)
                        if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                            e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                        ) {
                            pkg = e.packageName
                        }
                    }
                    val p = pkg
                    if (p == null || p == ctx.packageName) null else AppSnapshot(p, labelOf(p))
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "usage stats failed: ${e.message}")
            null
        }
    }

    /** 常见应用给中文名（不查 PackageManager，省时间且更口语） */
    private fun labelOf(pkg: String): String {
        KNOWN[pkg]?.let { return it }
        return runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    companion object {
        private const val TAG = "DshVoiceApp"
        val KNOWN = mapOf(
            "com.tencent.mm" to "微信",
            "com.tencent.mobileqq" to "QQ",
            "com.tencent.wework" to "企业微信",
            "com.taobao.taobao" to "淘宝",
            "com.tmall.wireless" to "天猫",
            "com.jingdong.app.mall" to "京东",
            "com.xunmeng.pinduoduo" to "拼多多",
            "com.eg.android.AlipayGphone" to "支付宝",
            "com.ss.android.ugc.aweme" to "抖音",
            "com.smile.gifmaker" to "快手",
            "com.xingin.xhs" to "小红书",
            "tv.danmaku.bili" to "哔哩哔哩",
            "com.zhihu.android" to "知乎",
            "com.sina.weibo" to "微博",
            "com.netease.cloudmusic" to "网易云音乐",
            "com.tencent.qqmusic" to "QQ音乐",
            "com.tencent.tmgp.sgame" to "王者荣耀",
            "com.tencent.mf.uam" to "和平精英",
            "com.miHoYo.Yuanshen" to "原神",
            "com.google.android.youtube" to "YouTube",
        )
    }
}

/** 路线 B：兜底实现（永远返回 null，语义清晰，上层不用判断权限） */
class NullAppSource : ForegroundAppSource {
    override val id: String = "none"
    override fun isAvailable(): Boolean = false
    override fun current(): AppSnapshot? = null
}

/** 把"双路"封装起来：优先原生，不可用则兜底 */
class ForegroundAppResolver(private val ctx: Context) {
    private val sources: List<ForegroundAppSource> = listOf(UsageStatsAppSource(ctx), NullAppSource())
    private val active: ForegroundAppSource by lazy { sources.firstOrNull { it.isAvailable() } ?: sources.last() }

    fun sourceId(): String = active.id
    fun current(): AppSnapshot? = active.current()

    /** 引导用户去开「使用情况访问权限」（系统设置页，无法用运行时弹窗申请） */
    fun openSettings() {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
