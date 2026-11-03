package com.dshpet.android.prompt

import android.content.Context
import com.dshpet.android.util.AppLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 提示词仓库（**外置**）：所有 system prompt 都放在 `assets/prompts/<key>.txt`，
 * 不再写死在 Kotlin 字符串里。
 *
 * 为什么要这样：
 *  - 改一句语气不用重新编译、不用发版；
 *  - 同一套代码可以做多个人格包（换 assets 即可）；
 *  - 排查"模型为什么这么答"时，能直接把当前生效的提示词导出来看。
 *
 * 查找顺序：
 *  1. `filesDir/prompts/<key>.txt` —— 用户/调试覆盖（可热改，重启 App 生效）；
 *  2. `assets/prompts/<key>.txt` —— 随包内置，默认值；
 *  3. 调用方传进来的 `fallback` —— 连资源都缺时的兜底（保证功能不哑）。
 *
 * 说明：读取结果按 key 缓存，[reload] 可强制重读。文件内容为空时按"没配"处理。
 */
object PromptStore {

    private const val ASSET_DIR = "prompts"
    private const val TAG = "Dsh_PROMPT"

    private val cache = ConcurrentHashMap<String, String>()

    @Volatile
    private var appCtx: Context? = null

    /** 由 [com.dshpet.android.PetApp.onCreate] 调用；重复调用会清缓存。 */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        cache.clear()
        val n = runCatching {
            ctx.assets.list(ASSET_DIR)?.count { it.endsWith(".txt") } ?: 0
        }.getOrDefault(0)
        AppLog.log(TAG, "提示词目录已就绪：assets/$ASSET_DIR 共 $n 个（用户覆盖目录 files/$ASSET_DIR）")
    }

    /**
     * 取提示词。
     * @param key 文件名（不含 `.txt`），如 `nudge_clingy`
     * @param fallback 资源缺失时的兜底文本（**保持短**，别把完整提示词再抄一遍）
     */
    fun text(key: String, fallback: String = ""): String {
        cache[key]?.let { return it }
        val ctx = appCtx ?: return fallback
        val loaded = readOverride(ctx, key) ?: readAsset(ctx, key)
        val value = loaded?.takeIf { it.isNotBlank() } ?: fallback
        cache[key] = value
        return value
    }

    /** 丢掉缓存（改完覆盖文件后调用） */
    fun reload() = cache.clear()

    private fun readOverride(ctx: Context, key: String): String? = runCatching {
        val f = File(ctx.filesDir, "$ASSET_DIR/$key.txt")
        if (f.exists()) f.readText() else null
    }.getOrNull()

    private fun readAsset(ctx: Context, key: String): String? = runCatching {
        ctx.assets.open("$ASSET_DIR/$key.txt").bufferedReader().use { it.readText() }
    }.getOrNull()
}
