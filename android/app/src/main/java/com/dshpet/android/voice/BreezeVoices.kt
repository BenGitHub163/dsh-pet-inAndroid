package com.dshpet.android.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Breeze Blue 音色**名字缓存**。
 *
 * 用户只认名字，`voc_xxx` 这种 ID 不该出现在 UI 上 —— 所以：
 *  - 列表拉到之后把 `id → name` 缓存进 SharedPreferences（离线也能显示名字）；
 *  - UI 只展示 [nameOf]；真正调接口时才用 id；
 *  - 没缓存过、又没联网时，[nameOf] 回退成"未命名音色"，绝不把 id 甩给用户。
 */
object BreezeVoices {

    private const val PREF = "breeze_voices"
    private const val KEY_LIST = "list_json"

private val client = com.dshpet.android.api.ApiClient.read(30)

    /** 缓存里的音色（id 在前，名字在后） */
    fun cached(ctx: Context): List<Pair<String, String>> {
        val raw = ctx.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) null else id to o.optString("name").ifBlank { "未命名音色" }
            }
        }.getOrDefault(emptyList())
    }

    /** 名字（UI 只显示这个） */
    fun nameOf(ctx: Context, voiceId: String): String =
        cached(ctx).firstOrNull { it.first == voiceId }?.second ?: if (voiceId.isBlank()) "未选音色" else "未命名音色"

    /**
     * 从服务端拉列表并写缓存。返回 `id → name`。
     * 失败时抛出，调用方决定是否用缓存兜底。
     */
    /** 从服务端拉列表并写缓存（网络在 api/BreezeApi，本类只做缓存） */
    suspend fun fetch(ctx: Context, apiKey: String, language: String = "zh"): List<Pair<String, String>> {
        val list = com.dshpet.android.api.BreezeApi.listVoices(apiKey, language)
        saveCache(ctx, list)
        return list
    }
    private fun saveCache(ctx: Context, list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.forEach { (id, name) ->
            arr.put(JSONObject().put("id", id).put("name", name))
        }
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
