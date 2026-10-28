package com.dshpet.android.voice.context

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备上下文感知（**Provider**）——让桌宠"知道"用户在什么环境里。
 *
 * 采集项：时间 / 位置 / 电量 / 网络 / **前台应用** / **内存** / **亮度** / 存储。
 *
 * 三个原则：
 *  1. **按需采集 + 30s 缓存**：不后台常驻、不持续定位；
 *  2. **双路降级**：前台应用优先系统 UsageStats（需授权），拿不到就不给这一项，
 *     其余项照常；位置无权限同样降级。功能永不因缺权限而崩；
 *  3. **反地理编码放 IO 线程**，失败静默。
 */
class DeviceContextProvider(private val ctx: Context) {

    private var cachedAt = 0L
    private var cachedLevel = -1
    private var cached: String? = null
    private val appResolver = ForegroundAppResolver(ctx)
    private val mediaProvider = MediaDevicesProvider(ctx)
    private val usageProvider = AppUsageProvider(ctx)

    /** 结构化快照：写记忆时把"在哪、在用什么应用"一并存下来 */
    data class EnvFacts(val place: String?, val lat: Double?, val lng: Double?, val app: String?)

    private var lastFacts: EnvFacts? = null

    fun facts(): EnvFacts = lastFacts ?: EnvFacts(
        place = null, lat = null, lng = null,
        app = runCatching { appResolver.current()?.label }.getOrNull(),
    )

    fun hasLocationPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 前台应用那一路用的是哪种实现（"usage-stats" 表示原生可用，"none" 表示降级） */
    fun appSourceId(): String = runCatching { appResolver.sourceId() }.getOrDefault("none")

    fun openUsageAccessSettings() = appResolver.openSettings()

    suspend fun promptBlock(force: Boolean = false, level: Int = 1): String {
        val now = System.currentTimeMillis()
        val hit = cached
        if (!force && hit != null && cachedLevel == level && now - cachedAt < TTL_MS) return hit
        val block = withContext(Dispatchers.IO) { build(now, level) }
        cached = block
        cachedAt = now
        cachedLevel = level
        return block
    }

    private fun build(now: Long, level: Int): String {
        val sb = StringBuilder()
        sb.append("【当前环境（系统自动提供，供参考；不要逐条复述）】\n")
        sb.append("时间：").append(timeLine(now)).append('\n')

        val placeText = if (level >= 1) locationLine(now) else null
        val app = if (level >= 1) runCatching { appResolver.current() }.getOrNull() else null

        placeText?.let { sb.append("位置：").append(it).append('\n') }
        sb.append("电量：").append(batteryLine()).append('\n')
        sb.append("网络：").append(networkLine()).append('\n')
        app?.let { sb.append("用户正在用的应用：").append(it.label).append('\n') }

        // 隐私档位=详细时，才补充"硬件通路 / 正在播放 / 今日用量"
        if (level >= 2) {
            mediaProvider.audioRoute().let { sb.append("音频通路：").append(it).append('\n') }
            mediaProvider.mediaNow()?.let { m ->
                val what = listOfNotNull(m.title, m.artist).joinToString(" - ")
                if (what.isNotBlank()) sb.append("正在播放：").append(what).append("（").append(m.app).append("）\n")
            }
            val top = usageProvider.todayTopApps(3)
            if (top.isNotEmpty()) {
                sb.append("今天用得最多的应用：")
                    .append(top.joinToString("，") { "${it.first} ${it.second} 分钟" })
                    .append('\n')
            }
        }

        sb.append("手机状态：").append(memoryLine()).append("；").append(brightnessLine()).append('\n')
        sb.append("存储：").append(storageLine()).append('\n')

        lastFacts = EnvFacts(
            place = placeText?.substringBefore(" 附近")?.takeIf { it.isNotBlank() && !it.startsWith("未知") && !it.startsWith("约") },
            lat = null,
            lng = null,
            app = app?.label,
        )

        sb.append("使用要求：\n")
        when (level) {
            0 -> sb.append("当前隐私档位：**关闭**。不要提及用户正在用什么应用、也不要提问位置；只能使用时间/电量/网络这类设备状态。\n")
            1 -> sb.append("当前隐私档位：**基础**。可以知道正在用哪个应用、大致城市，但**不要主动展开**，用户问到再答。\n")
            else -> sb.append("当前隐私档位：**详细**。可以结合应用和大致情况自然地关心一句（\"刷了挺久啦，歇会儿\"），但不要每轮都提。\n")
        }
        sb.append(
            "1. 用户问时间、地点、电量这类问题时，直接用上面的值回答，不要说\"我查不到\"。\n" +
                "2. **绝对不能说\"我检测到你正在用微信\"**，也不要念包名。\n" +
                "3. **你看得到应用里的内容**（视频标题、聊天消息、浏览记录）。" +
                "4. 如果用户问\"我在看什么视频\"，就说自己知道的；**不要编造、不要猜测**。\n"
        )
        return sb.toString()
    }

    private fun timeLine(now: Long): String {
        val fmt = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA)
        val clock = SimpleDateFormat("HH", Locale.CHINA).format(Date(now)).toIntOrNull() ?: 12
        val period = when (clock) {
            in 0..4 -> "凌晨"
            in 5..8 -> "早上"
            in 9..11 -> "上午"
            12 -> "中午"
            in 13..16 -> "下午"
            in 17..18 -> "傍晚"
            in 19..22 -> "晚上"
            else -> "深夜"
        }
        return "${fmt.format(Date(now))}（$period）"
    }

    private fun batteryLine(): String = try {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        "$pct%，${if (charging) "正在充电" else "未充电"}"
    } catch (e: Throwable) {
        "未知"
    }

    private fun networkLine(): String = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "无网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
            else -> "其他"
        }
    } catch (e: Throwable) {
        "未知"
    }

    /** 前台应用：双路解析，拿不到就整行省略 */
    private fun appLine(): String? {
        val app = runCatching { appResolver.current() }.getOrNull() ?: return null
        return "${app.label}（包名 ${app.packageName}）"
    }

    private fun memoryLine(): String = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalGb = mi.totalMem / 1073741824.0
        val usedPct = if (mi.totalMem > 0) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem) else 0
        val low = if (mi.lowMemory) "，系统内存紧张" else ""
        "内存已用 $usedPct%%（共 %.1f GB）%s".format(totalGb, low)
    } catch (e: Throwable) {
        "内存未知"
    }

    private fun brightnessLine(): String = try {
        val v = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        val auto = Settings.System.getInt(
            ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = pm.isInteractive
        val pct = if (v >= 0) v * 100 / 255 else -1
        "屏幕${if (screenOn) "亮着" else "已熄屏"}，亮度${if (pct >= 0) "约 $pct%" else "未知"}${if (auto) "（自动调节）" else ""}"
    } catch (e: Throwable) {
        "屏幕状态未知"
    }

    private fun storageLine(): String = try {
        val sf = StatFs(ctx.filesDir.path)
        val freeGb = sf.availableBytes / 1073741824.0
        "剩余 %.1f GB".format(freeGb)
    } catch (e: Throwable) {
        "未知"
    }

    /** 位置：优先城市/区域名；拿不到就退化成"大致坐标"（并提醒模型别念数字） */
    private fun locationLine(now: Long): String? {
        if (!hasLocationPermission()) return null
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
                ?: return "未知（尚未取得定位）"

            if (now - loc.time > LOCATION_MAX_AGE_MS) return "未知（定位数据较旧）"

            val city = runCatching {
                @Suppress("DEPRECATION")
                Geocoder(ctx, Locale.CHINA).getFromLocation(loc.latitude, loc.longitude, 1)
                    ?.firstOrNull()
                    ?.let { a -> listOfNotNull(a.locality ?: a.adminArea, a.subLocality ?: a.subAdminArea).distinct().joinToString("") }
            }.getOrNull()

            if (!city.isNullOrBlank()) "$city 附近"
            else "约北纬 %.1f 度、东经 %.1f 度（只作参考，不要念出来）".format(loc.latitude, loc.longitude)
        } catch (e: Throwable) {
            Log.w(TAG, "location failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "DshVoiceCtx"
        private const val TTL_MS = 30_000L
        private const val LOCATION_MAX_AGE_MS = 10 * 60_000L
    }
}
