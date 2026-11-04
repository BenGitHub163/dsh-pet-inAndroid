package com.dshpet.android

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.dshpet.android.data.PetConfig
import com.dshpet.android.pet.PetOverlayService
import com.dshpet.android.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：初始化日志/通知渠道；未捕获异常写入内置日志；
 * 若用户开启了"开机自启/自动启动"且授权过，拉起时确保桌宠服务在运行。
 */
class PetApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        /** 进程级 Application 上下文（DataStore 全局单例用） */
        @Volatile
        internal var injectedAppContext: android.content.Context? = null
    }

    override fun onCreate() {
        injectedAppContext = this
        // 提示词仓库：assets/prompts/*.txt（可在 files/prompts/ 覆盖，不用重新编译）
        runCatching { com.dshpet.android.prompt.PromptStore.init(this) }
        // 后台预热配置快照：此后所有请求读内存，不再阻塞主线程
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.dshpet.android.data.PetConfig.refreshSnapshot(this@PetApp) }
        }
        // 统一数据源：以 PetConfig(DataStore) 为准，同步到语音层缓存
        runCatching {
            kotlinx.coroutines.runBlocking {
                val c = com.dshpet.android.data.PetConfig.get(this@PetApp)
                val vs = com.dshpet.android.voice.core.VoiceSettingsStore.get(this@PetApp)
                val cur = vs.load()
                com.dshpet.android.data.KeyFactory.reload(c, cur)
                val keys = com.dshpet.android.data.KeyFactory.current()
                val rid = c.doubaoResourceId(); val vid = c.doubaoVoiceId()
                vs.save(cur.copy(
                    deepseekKey = keys.deepseek,
                    doubaoKey = keys.doubao,
                    breezeKey = keys.breeze,
                    resourceId = if (rid.isNotBlank()) rid else cur.resourceId,
                    voiceId = if (vid.isNotBlank()) vid else cur.voiceId,
                ))
            }
        }.onFailure { android.util.Log.w("PetApp", "syncKeys failed: " + it.message) }

        super.onCreate()
        AppLog.init(this)
        installCrashHandler()
        AppLog.log("APP", "启动 v${BuildConfig.VERSION_NAME}")
        logMemory("进程创建")
        createChannels()
        appScope.launch {
            val cfg = PetConfig.get(this@PetApp)
            // 同步 launcher 入口（隐藏后台开关）与开机自启接收器状态
            PetConfig.hideRecentsCached = cfg.hideFromRecents()
            com.dshpet.android.pet.PetOverlayService.maxInstances = cfg.maxInstances()
            // 恢复 Agent 事件目录（SAF 持久授权）
            runCatching {
                val tree = cfg.agentEventsTreeUri()
                if (tree.isNotBlank()) {
                    com.dshpet.android.plugin.AgentEventBus.treeUri = android.net.Uri.parse(tree)
                    // 校验持久授权仍然有效（用户可能在系统里撤销）
                    val perms = contentResolver.persistedUriPermissions
                    if (perms.none { it.uri.toString() == tree && it.isReadPermission }) {
                        AppLog.log("PLUGIN", "SAF 授权已失效: $tree")
                        com.dshpet.android.plugin.AgentEventBus.treeUri = null
                    }
                }
            }
            MainActivity.applyRecentsAlias(this@PetApp, cfg.hideFromRecents())
            // 开机自启：接收器只跟"用户开关"走（白名单没给允许时系统本来就不会拉起我们，
            // 留着接收器也无害）；白名单只用于 UI 显示真实状态，**绝不能**拿它决定"现在要不要启动桌宠"。
            val autoStartWant = cfg.autoStart()
            val autoStartGranted = com.dshpet.android.compat.autostart.AutoStartHelper.queryAutoStart(this@PetApp)
            com.dshpet.android.compat.autostart.AutoStartHelper.setBootReceiverEnabled(this@PetApp, autoStartWant)
            AppLog.log(
                "BOOT",
                "启动对账：开机自启=$autoStartWant 系统白名单=${
                    com.dshpet.android.compat.autostart.AutoStartHelper.grantedText(autoStartGranted)
                }",
            )
            if (cfg.petRunning() && cfg.overlayPermissionGranted()) {
                PetOverlayService.ensureRunning(this@PetApp, persist = false)
            }
        }
    }

    /** 内存诊断：当前进程可用堆 + 系统低内存状态 */
    private fun logMemory(tag: String) {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val maxMb = rt.maxMemory() / 1048576
        val am = getSystemService(ActivityManager::class.java)
        val lowRam = am?.isLowRamDevice ?: false
        val mi = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val sysLow = mi.lowMemory
        val availMb = mi.availMem / 1048576
        AppLog.log(
            "MEM",
            "$tag: 堆已用 ${usedMb}MB / 上限 ${maxMb}MB, lowRamDevice=$lowRam, 系统低内存=$sysLow, 系统可用内存≈${availMb}MB"
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // level>=TRIM_MEMORY_COMPLETE(80)/UI_HIDDEN(20) 前的各级都记录
        AppLog.log("MEM", "onTrimMemory level=$level " +
                when {
                    level >= 80 -> "(COMPLETE: 进程随时可能被杀)"
                    level >= 60 -> "(MODERATE)"
                    level >= 40 -> "(BACKGROUND)"
                    level >= 20 -> "(UI_HIDDEN)"
                    level >= 15 -> "(TRIM_MEMORY_RUNNING_CRITICAL: 后台内存紧张)"
                    else -> "(RUNNING_LOW/LOW)"
                })
        logMemory("onTrimMemory")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppLog.log("MEM", "onLowMemory: 系统整体内存耗尽，进程濒临被杀")
        logMemory("onLowMemory")
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val rt = Runtime.getRuntime()
            AppLog.log(
                "CRASH",
                "线程=${thread.name}\n${Log.getStackTraceString(throwable)}\n" +
                        "[内存快照] 堆已用${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB/上限${rt.maxMemory() / 1048576}MB",
            )
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val pet = NotificationChannel(
            PetOverlayService.CHANNEL_ID, getString(R.string.notification_channel_pet),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.fgs_description)
            setShowBadge(false)
        }
        val chat = NotificationChannel(
            "chat", getString(R.string.notification_chat_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(pet)
            nm.createNotificationChannel(chat)
        }
    }
}
