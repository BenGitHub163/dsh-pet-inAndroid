package com.dshpet.android.pet

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.dshpet.android.data.PetConfig
import com.dshpet.android.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 「桌宠跑着跑着就没了」的自愈看门狗。
 *
 * 为什么需要：HyperOS / MIUI 这类 ROM 即使前台服务也照样会被清掉
 * （内存回收、后台清理、息屏省电策略）。`START_STICKY` 只在系统"主动杀"时有用，
 * 用户从最近任务划掉、或者厂商的省电策略直接 stop 掉时，服务就再也不回来了，
 * 表现就是"桌宠一会儿自己消失"。
 *
 * 做法：用一个**持久化**的周期任务（15 分钟，系统允许的最小周期）检查一次：
 * `pet_running` 标记是 true（用户想要桌宠）而服务不在 → 重新拉起来。
 * 这是兜底，不是主路径：正常情况靠前台服务 + START_STICKY。
 */
class PetWatchdogJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            val cfg = PetConfig.get(applicationContext)
            val wanted = runCatching { cfg.petRunning() }.getOrDefault(false)
            if (wanted && PetOverlayService.runningInstances().isEmpty()) {
                AppLog.log("SVC", "看门狗：桌宠不在了，重新拉起")
                PetOverlayService.ensureRunning(applicationContext, 0, persist = true)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 0x0D54

        /** 15 分钟（`JobInfo.Builder.setPeriodic` 允许的最小值） */
        private const val PERIOD_MS = 15 * 60 * 1000L

        /** 排上周期任务（幂等：已在排队就跳过） */
        fun schedule(ctx: Context) {
            runCatching {
                val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                if (js.getPendingJob(JOB_ID) != null) return
                val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, PetWatchdogJob::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)                       // 重启后继续（需要 RECEIVE_BOOT_COMPLETED 权限）
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build()
                js.schedule(info)
                AppLog.log("SVC", "看门狗已排期（15 分钟）")
            }.onFailure { AppLog.log("SVC", "看门狗排期失败：${it.javaClass.simpleName}") }
        }

        /** 用户明确退出桌宠时取消，免得刚关掉又被拉起来 */
        fun cancel(ctx: Context) {
            runCatching {
                (ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler)?.cancel(JOB_ID)
                AppLog.log("SVC", "看门狗已取消")
            }
        }
    }
}
