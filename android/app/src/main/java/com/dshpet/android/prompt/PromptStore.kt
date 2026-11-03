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
    private const val TAG = "PROMPT"

    private val cache = ConcurrentHashMap<String, String>()

    /** YAML 总表解析结果（null = 还没解析过） */
    @Volatile
    private var yamlCache: Map<String, String>? = null

    @Volatile
    private var appCtx: Context? = null

    /** 由 [com.dshpet.android.PetApp.onCreate] 调用；重复调用会清缓存。 */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        cache.clear()
        yamlCache = null
        val n = runCatching {
            ctx.assets.list(ASSET_DIR)?.count { it.endsWith(".txt") } ?: 0
        }.getOrDefault(0)
        // 启动时就把 YAML 解析掉：一是尽早暴露语法错误，二是让"当前生效多少条"可查
        val yaml = runCatching { yamlTable(ctx.applicationContext) }.getOrDefault(emptyMap())
        AppLog.log(
            TAG,
            "提示词就绪：prompts.yaml ${yaml.size} 条，单文件兜底 $n 个（覆盖目录 files/$ASSET_DIR）",
        )
    }

    /**
     * 取提示词。
     *
     * 查找顺序（先命中先用）：
     *  1. `files/prompts/prompts.yaml`      —— 用户整表覆盖（改完重启生效）
     *  2. `assets/prompts/prompts.yaml`     —— 随包内置的**总表**（推荐维护方式）
     *  3. `files/prompts/<key>.txt`         —— 单条覆盖（临时热改最方便）
     *  4. `assets/prompts/<key>.txt`        —— 单条内置（早期方式，仍兼容）
     *  5. `fallback`                        —— 兜底，保证功能不哑
     *
     * @param key YAML 里的键 / txt 的文件名，如 `nudge_clingy`
     * @param fallback 资源缺失时的兜底文本（**保持短**，别把完整提示词再抄一遍）
     */
    fun text(key: String, fallback: String = ""): String {
        cache[key]?.let { return it }
        val ctx = appCtx ?: return fallback
        val loaded = readYaml(ctx, key)
            ?: readOverride(ctx, key)
            ?: readAsset(ctx, key)
        val value = loaded?.takeIf { it.isNotBlank() } ?: fallback
        cache[key] = value
        return value
    }

    /** 解析后的 YAML 总表（懒加载，失败返回空表） */
    private fun yamlTable(ctx: Context): Map<String, String> {
        yamlCache?.let { return it }
        val text = readOverrideYaml(ctx) ?: readAssetYaml(ctx)
        val table = runCatching {
            if (text.isNullOrBlank()) emptyMap() else parseYaml(text)
        }.getOrElse { e ->
            AppLog.log(TAG, "prompts.yaml 解析失败，回退到单文件：${e.message}")
            emptyMap()
        }
        yamlCache = table
        AppLog.log(TAG, "prompts.yaml 载入 ${table.size} 条")
        return table
    }

    private fun readYaml(ctx: Context, key: String): String? = yamlTable(ctx)[key]

    @Suppress("UNCHECKED_CAST")
    private fun parseYaml(text: String): Map<String, String> {
        val root = org.yaml.snakeyaml.Yaml().load<Map<String, Any?>>(text) ?: return emptyMap()
        val prompts = root["prompts"] as? Map<String, Any?> ?: return emptyMap()
        return prompts.mapNotNull { (k, v) ->
            val s = (v as? String)?.trimEnd()
            if (s.isNullOrBlank()) null else k to s
        }.toMap()
    }

    private fun readOverrideYaml(ctx: Context): String? = runCatching {
        val f = File(ctx.filesDir, "$ASSET_DIR/prompts.yaml")
        if (f.exists()) f.readText() else null
    }.getOrNull()

    private fun readAssetYaml(ctx: Context): String? = runCatching {
        ctx.assets.open("$ASSET_DIR/prompts.yaml").bufferedReader().use { it.readText() }
    }.getOrNull()

    /** 丢掉缓存（改完覆盖文件后调用） */
    fun reload() {
        cache.clear()
        yamlCache = null
    }

    private fun readOverride(ctx: Context, key: String): String? = runCatching {
        val f = File(ctx.filesDir, "$ASSET_DIR/$key.txt")
        if (f.exists()) f.readText() else null
    }.getOrNull()

    private fun readAsset(ctx: Context, key: String): String? = runCatching {
        ctx.assets.open("$ASSET_DIR/$key.txt").bufferedReader().use { it.readText() }
    }.getOrNull()
}
