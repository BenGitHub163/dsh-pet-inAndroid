package com.dshpet.android.voice

import android.content.Context
import com.dshpet.android.R

/**
 * 豆包音色表（`res/raw/doubao_voices.tsv`，`voice_id \t 名字 \t 分类`）。
 *
 * 统一放这里，避免每加一个页面就复制一份解析代码
 *（之前设置页和测试台各有一份拷贝，改一处忘另一处）。
 */
object DoubaoVoices {

    @Volatile
    private var cache: List<Triple<String, String, String>>? = null

    fun load(ctx: Context): List<Triple<String, String, String>> {
        cache?.let { return it }
        val list = runCatching {
            ctx.resources.openRawResource(R.raw.doubao_voices).bufferedReader().useLines { seq ->
                seq.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size >= 2) Triple(p[0].trim(), p[1].trim(), p.getOrElse(2) { "" }.trim()) else null
                }.toList()
            }
        }.getOrElse { emptyList() }
        cache = list
        return list
    }

    /** 名字（用于 UI 展示）；找不到就回退 id */
    fun nameOf(ctx: Context, voiceId: String): String =
        load(ctx).firstOrNull { it.first == voiceId }?.second ?: voiceId
}
