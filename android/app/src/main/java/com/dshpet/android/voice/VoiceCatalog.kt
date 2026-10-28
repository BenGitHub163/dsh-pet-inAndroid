package com.dshpet.android.voice

/**
 * 可选音色表（`音色 id → 显示名`）。
 *
 * 原来这份列表在 `VoiceLoopActivity` 和 `ui/PetSettingsActivity` 里各抄了一份，
 * 加一个音色要改两处，改漏就会"语音设置里有、语音循环里没有"。
 * 现在只有这一份，两个页面都引用它。
 */
object VoiceCatalog {

    val VOICES: List<Pair<String, String>> = listOf(
        "zh_female_sajiaoxuemei_uranus_bigtts" to "撒娇学妹 2.0",
        "zh_female_tianmeixiaoyuan_uranus_bigtts" to "甜美小源 2.0",
        "zh_female_mengyatou_uranus_bigtts" to "萌丫头 2.0",
        "zh_female_chunribu_uranus_bigtts" to "春日部姐姐 2.0",
        "ICL_uranus_zh_female_bingjiaomengmei_tob" to "病娇萌妹 2.0",
        "ICL_uranus_zh_female_jiaoruoluoli_tob" to "娇弱萝莉 2.0",
    )

    /** 默认音色（列表第一个） */
    val DEFAULT_ID: String get() = VOICES.first().first
}
