package com.dshpet.android.pet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 动画目录：解析 assets/pet/manifest.json（动画名 -> 文件+时长）与
 * videos/ 下的分类子目录（idle/turn/move/click/drag/random）。
 * 行为与原桌面端 catalog.build_categories 的目录优先策略一致：
 * 分类由目录决定，其余动画归入随机动作池。
 */
class PetCatalog private constructor(
    val names: List<String>,
    /** 动画名 -> 相对 videos/ 的路径 */
    val files: Map<String, String>,
    /** 动画名 -> 时长（秒） */
    val durations: Map<String, Double>,
    val folderMap: Map<String, String>,
    val folderFiles: Map<String, List<String>>,
    val noMirror: Set<String>,
    /**
     * 动画名 → 非透明像素包围盒（归一化 `[l, t, r, b]`，相对整帧 16:9 画布）。
     * 来自 `assets/pet/frames.json`（构建时离线统计所有帧的并集），用于按动画调整窗口。
     */
    val frames: Map<String, FloatArray> = emptyMap(),
    /** 所有动画的并集包围盒（「满屏」档位用它算最大画幅） */
    val framesGlobal: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
) {
    val idle: String? get() = folderFiles["idle"]?.firstOrNull()
    val turn: String? get() = folderFiles["turn"]?.firstOrNull()
    val idles: List<String> get() = folderFiles["idle"] ?: emptyList()
    val turns: List<String> get() = folderFiles["turn"] ?: emptyList()
    val moves: List<String> get() = folderFiles["move"] ?: emptyList()
    val clicks: List<String> get() = folderFiles["click"] ?: emptyList()
    val drag: String? get() = folderFiles["drag"]?.firstOrNull()
    /** 随机动作池 = random 目录 + 未进入核心分类的动画 */
    val acts: List<String>
        get() {
            val core = folderFiles["idle"].orEmpty() + folderFiles["turn"].orEmpty() +
                    folderFiles["move"].orEmpty() + folderFiles["click"].orEmpty() +
                    folderFiles["drag"].orEmpty() + folderFiles["random"].orEmpty()
            val listed = core.distinct()
            val extra = names.filter { it !in listed }
            return (folderFiles["random"] ?: emptyList()) + extra
        }

    /** 目录中实际存在的中文动画名（覆盖 ANIM_FILES 文档映射，以文件为准） */
    fun displayName(name: String): String = name

    companion object {
        suspend fun load(ctx: Context): PetCatalog = withContext(Dispatchers.IO) {
            val am = ctx.assets
            val manifestText = am.open("pet/manifest.json").bufferedReader().use { it.readText() }
            val root = JSONObject(manifestText)
            val names = mutableListOf<String>()
            val files = mutableMapOf<String, String>()
            val durations = mutableMapOf<String, Double>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val o = root.getJSONObject(name)
                names.add(name)
                files[name] = o.getString("file")
                durations[name] = o.optDouble("duration", 0.0)
            }
            names.sort()

            // 目录分类：videos 下第一层子目录
            val folderMap = mutableMapOf<String, String>()
            val folderFiles = mutableMapOf<String, MutableList<String>>()
            for (name in names) {
                val rel = files[name] ?: continue
                val folder = rel.substringBefore('/')
                if (folder != rel && folder.isNotEmpty()) {
                    folderMap[name] = folder
                    folderFiles.getOrPut(folder) { mutableListOf() }.add(name)
                }
            }
            folderFiles.values.forEach { it.sort() }

            // 含文字动画：转向时不镜像
            val noMirror = try {
                val t = JSONObject(am.open("pet/text_clips.json").bufferedReader().use { it.readText() })
                val arr = t.optJSONArray("no_mirror")
                if (arr != null) {
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                } else emptySet()
            } catch (e: Exception) {
                emptySet()
            }

            // 帧包围盒（可选文件：没有就退回"整帧"，窗口会保守地按老逻辑走）
            val framesMap = HashMap<String, FloatArray>()
            var globalBox = floatArrayOf(0.30f, 0.08f, 0.72f, 0.92f)
            runCatching {
                val fj = JSONObject(am.open("pet/frames.json").bufferedReader().use { it.readText() })
                val boxes = fj.optJSONObject("boxes") ?: return@runCatching
                val keys2 = boxes.keys()
                while (keys2.hasNext()) {
                    val k = keys2.next()
                    val a = boxes.optJSONArray(k) ?: continue
                    if (a.length() < 4) continue
                    framesMap[k] = floatArrayOf(
                        a.getDouble(0).toFloat(), a.getDouble(1).toFloat(),
                        a.getDouble(2).toFloat(), a.getDouble(3).toFloat(),
                    )
                }
                val g = fj.optJSONObject("_meta")?.optJSONArray("global")
                if (g != null && g.length() >= 4) {
                    globalBox = floatArrayOf(
                        g.getDouble(0).toFloat(), g.getDouble(1).toFloat(),
                        g.getDouble(2).toFloat(), g.getDouble(3).toFloat(),
                    )
                }
            }

            PetCatalog(names, files, durations, folderMap, folderFiles, noMirror, framesMap, globalBox)
        }
    }
}
