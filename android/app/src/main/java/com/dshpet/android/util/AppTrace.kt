package com.dshpet.android.util

import android.util.Log

/**
 * AOP 插桩的**运行时接收端**。
 *
 * `LogAopPlugin` 会在编译期把带 [TraceLog] 的方法改写成调用这里的方法，
 * 所以这里必须：
 *  - **不依赖 Context**（插桩发生在任意线程、任意生命周期）；
 *  - **不抛异常**（日志坏了不能拖垮业务）；
 *  - **不做字符串拼接以外的重活**（参数摘要由插桩代码生成）。
 *
 * 日志格式统一为：
 * ```
 * Dsh_Voice  → speak | text=你好呀… | main
 * Dsh_Voice  ← speak | 12ms | main
 * ```
 * 等级用 [level] 控制，最终仍汇聚到现有 `Dsh_*` 体系，`AppLog` 的文件日志不受影响。
 */
object AppTrace {

    /** 日志等级（与 android.util.Log 对齐） */
    enum class Level { V, D, I, W }

    fun enter(module: String, method: String, args: String) {
        log(Level.D, module, "→ $method" + if (args.isBlank()) "" else " | $args")
    }

    fun exit(module: String, method: String, elapsedMs: Long) {
        log(Level.D, module, "← $method" + if (elapsedMs >= 0) " | ${elapsedMs}ms" else "")
    }

    /** 插桩代码按注解里的 level 调用这个重载 */
    fun enter(level: String, module: String, method: String, args: String) {
        val lv = runCatching { Level.valueOf(level.uppercase()) }.getOrDefault(Level.D)
        log(lv, module, "→ $method" + if (args.isBlank()) "" else " | $args")
    }

    fun exit(level: String, module: String, method: String, elapsedMs: Long) {
        val lv = runCatching { Level.valueOf(level.uppercase()) }.getOrDefault(Level.D)
        log(lv, module, "← $method" + if (elapsedMs >= 0) " | ${elapsedMs}ms" else "")
    }

    private fun log(level: Level, module: String, msg: String) {
        val tag = "Dsh_$module"
        val thread = Thread.currentThread().name
        val line = "$msg | $thread"
        runCatching {
            when (level) {
                Level.V -> Log.v(tag, line)
                Level.D -> Log.d(tag, line)
                Level.I -> Log.i(tag, line)
                Level.W -> Log.w(tag, line)
            }
        }
    }
}
