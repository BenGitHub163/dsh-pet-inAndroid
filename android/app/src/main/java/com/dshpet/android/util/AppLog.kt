package com.dshpet.android.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内置日志系统：按日期落盘（filesDir/logs/yyyy-MM-dd.log）。
 * 应用内「关于 → 查看日志」可浏览/复制/分享；未捕获异常自动写入。
 */
object AppLog {

    private var dir: File? = null          // 调试日志（开发者）
    private var userDir: File? = null      // 用户日志（普通用户能看懂）

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "logs").apply { mkdirs() }
        userDir = File(ctx.filesDir, "logs_user").apply { mkdirs() }
    }

    /** 调试日志：开发者看，进 logcat + logs/ 目录 */
    fun d(tag: String, msg: String) = log(tag, msg)

    /** 用户日志：只记"用户能理解的事件"，进 logs_user/ 目录，界面上可直接查看 */
    @Synchronized
    fun u(tag: String, msg: String) {
        android.util.Log.i("User_$tag", msg)
        val d = userDir ?: return
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        try {
            File(d, "$date.log").appendText("$time [$tag] $msg\n")
        } catch (e: Exception) {
            Log.w("AppLog", "写入用户日志失败", e)
        }
    }

    /** 读取最近 N 行用户日志（给界面展示） */
    fun readUserLog(maxLines: Int = 50): String {
        val d = userDir ?: return "日志尚未初始化"
        val files = d.listFiles()?.sortedByDescending { it.name }?.take(2) ?: return "暂无日志"
        val lines = ArrayList<String>()
        files.reversed().forEach { f ->
            runCatching { lines.addAll(f.readLines().takeLast(maxLines)) }
        }
        return if (lines.isEmpty()) "暂无日志" else lines.takeLast(maxLines).joinToString("\n")
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        // 先打 logcat：即使文件日志尚未 init，也不会静默丢日志
        android.util.Log.i("Dsh_$tag", msg)
        val d = dir ?: return
        val now = Date()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(now)
        val f = File(d, "$date.log")
        try {
            f.appendText("$time [$tag] $msg\n")
        } catch (e: Exception) {
            Log.w("AppLog", "写入日志失败", e)
        }
        // 日志保留最近 14 天，防膨胀
        try {
            val cutoff = System.currentTimeMillis() - 14L * 24 * 3600 * 1000
            d.listFiles(FileFilter { it.isFile && it.extension == "log" })?.forEach {
                if (it.lastModified() < cutoff) it.delete()
            }
        } catch (e: Exception) {
        }
    }

    /** 按日期倒序的日志文件列表 */
    fun files(): List<File> =
        dir?.listFiles(FileFilter { it.isFile && it.extension == "log" })
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun read(f: File): String = runCatching { f.readText() }.getOrDefault("(读取失败)")

    fun crashLog(): String = files().joinToString("\n\n==== ${'='} ${'='} ====\n\n") { f ->
        "== ${f.name} ==\n${read(f)}"
    }
}
